package org.example.ky;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品放置和防裁剪逻辑。
 */
abstract class PlacementSupport extends FrameTemplateSupport {

/**
 * 根据模板类型和商品尺寸决定商品放置矩形、内边距和缩放系数。
 */
protected static PlacementDecision resolvePlacementDecision(TemplateContext template, BufferedImage productTight) {
    Rect rect;
    double padRatio;
    double shrink;
    if (template.frameType == FrameType.WHITE_MIXED) {
        int[][] blocked = buildBlockedIntegral(template.productHole);
        rect = findBestRectForProductInHole(
                template.productHole, productTight.getWidth(), productTight.getHeight(), 2, true, blocked
        );
        if (rect == null) {
            rect = findCenteredRectFallback(
                    template.productHole, productTight.getWidth(), productTight.getHeight(), 2, blocked
            );
        }
        padRatio = 0.0;
        shrink = 0.998;
    } else if (template.transparentWatermark) {
        Rect holeBox = (template.hole != null && template.hole.bbox != null) ? template.hole.bbox : template.productHole.bbox;
        rect = holeBox != null ? holeBox : template.productHole.bbox;
        padRatio = 0.0;
        shrink = 1.000;
    } else {
        rect = template.productHole.bbox;
        if (template.water3Like) {
            padRatio = 0.060;
            shrink = 0.995;
        } else {
            padRatio = 0.034;
            shrink = 0.989;
        }
    }
    return new PlacementDecision(rect, padRatio, shrink);
}

protected static void logPlacementDecision(TemplateContext template, PlacementDecision placement) {
    System.out.println(
            "[compose] layoutBranch=" + template.frameType +
                    ", placeRect=" + placement.rect +
                    ", padRatio=" + placement.padRatio +
                    ", shrink=" + placement.shrink +
                    ", holeBbox=" + template.hole.bbox
    );
}

/**
 * 生成已经放入画布坐标系的商品图层。
 *
 * <p>彩框模板会额外检查安全 mask，必要时缩小商品，避免明显越界或遮挡模板装饰。</p>
 */
protected static BufferedImage buildPlacedProductLayer(
        BufferedImage productTight,
        TemplateContext template,
        PlacementDecision placement,
        int width,
        int height,
        PerformanceProfile profile
) {
    boolean capScaleAt1 = false;
    int pad = (int) Math.round(Math.min(placement.rect.w, placement.rect.h) * placement.padRatio);
    int bx = placement.rect.x + pad;
    int by = placement.rect.y + pad;
    int bw = placement.rect.w - 2 * pad;
    int bh = placement.rect.h - 2 * pad;

    BufferedImage productLayer;
    if (template.frameType == FrameType.COLORED_BORDER && template.transparentWatermark) {
        productLayer = placeProductContainWithMaskSafety(
                productTight, width, height,
                bx, by, bw, bh,
                placement.shrink, capScaleAt1,
                template.productMask, 0.995, 0.70,
                2000, 16,
                profile.safetySteps, profile.clipSampleStride
        );
    } else if (template.frameType == FrameType.COLORED_BORDER) {
        double minKeepRatio = template.water3Like ? 0.9994 : 0.995;
        long maxClipPixels = template.water3Like ? 40 : 1500;
        int clipAlphaThr = template.water3Like ? 64 : 16;
        double minShrink = template.water3Like ? 0.90 : 0.70;
        productLayer = placeProductContainWithMaskSafety(
                productTight, width, height,
                bx, by, bw, bh,
                placement.shrink, capScaleAt1,
                template.productMask, minKeepRatio, minShrink,
                maxClipPixels, clipAlphaThr,
                profile.safetySteps, profile.clipSampleStride
        );
    } else {
        productLayer = placeProductContainOnce(
                productTight, width, height,
                bx, by, bw, bh,
                placement.shrink, capScaleAt1
        );
    }

    Graphics2D gmask = productLayer.createGraphics();
    gmask.setComposite(AlphaComposite.DstIn);
    gmask.drawImage(template.productMask, 0, 0, null);
    gmask.dispose();
    return productLayer;
}

/**
 * 在白洞内寻找保持商品宽高比的最大可放置矩形。
 */
public static Rect findBestRectForProductInHole(HoleResult hole, int productW, int productH, int inset) {
    return findBestRectForProductInHole(hole, productW, productH, inset, true);
}

public static Rect findBestRectForProductInHole(HoleResult hole, int productW, int productH, int inset, boolean forceCenter) {
    return findBestRectForProductInHole(hole, productW, productH, inset, forceCenter, buildBlockedIntegral(hole));
}

protected static Rect findBestRectForProductInHole(
        HoleResult hole, int productW, int productH, int inset, boolean forceCenter, int[][] blocked
) {
    int w = hole.width, h = hole.height;
    if (productW <= 0 || productH <= 0 || w <= 0 || h <= 0) return null;

    Rect b = hole.bbox;
    // 以画布中心作为主对齐目标，避免局部装饰导致 bbox 偏移。
    int cx = w / 2;
    int cy = h / 2;
    int maxShiftX = forceCenter ? Math.max(24, w / 40) : Integer.MAX_VALUE;
    int maxShiftY = forceCenter ? Math.max(24, h / 30) : Integer.MAX_VALUE;

    double low = 0.0;
    double high = Math.max(
            b.w * 1.0 / Math.max(1, productW),
            b.h * 1.0 / Math.max(1, productH)
    ) * 2.0;

    int bestX = -1, bestY = -1, bestW = 0, bestH = 0;
    for (int it = 0; it < 28; it++) {
        double mid = (low + high) * 0.5;
        int rw = Math.max(1, (int) Math.floor(productW * mid));
        int rh = Math.max(1, (int) Math.floor(productH * mid));

        if (rw > w || rh > h) {
            high = mid;
            continue;
        }

        int[] pos = findCenterClosestValidTopLeft(blocked, w, h, rw, rh, cx, cy, maxShiftX, maxShiftY);
        if (pos != null) {
            low = mid;
            bestX = pos[0];
            bestY = pos[1];
            bestW = rw;
            bestH = rh;
        } else {
            high = mid;
        }
    }

    // 在强约束下找不到时，回退到宽松中心策略，避免极端模板无法放置
    if (bestX < 0 || bestY < 0 || bestW <= 0 || bestH <= 0) {
        low = 0.0;
        high = Math.max(
                b.w * 1.0 / Math.max(1, productW),
                b.h * 1.0 / Math.max(1, productH)
        ) * 2.0;
        for (int it = 0; it < 28; it++) {
            double mid = (low + high) * 0.5;
            int rw = Math.max(1, (int) Math.floor(productW * mid));
            int rh = Math.max(1, (int) Math.floor(productH * mid));
            if (rw > w || rh > h) {
                high = mid;
                continue;
            }
            int[] pos = findCenterClosestValidTopLeft(blocked, w, h, rw, rh, cx, cy, Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (pos != null) {
                low = mid;
                bestX = pos[0];
                bestY = pos[1];
                bestW = rw;
                bestH = rh;
            } else {
                high = mid;
            }
        }
    }

    if (bestX < 0 || bestY < 0 || bestW <= 0 || bestH <= 0) return null;

    int p = Math.max(0, inset);
    if (!forceCenter) {
        int nx = bestX + p;
        int ny = bestY + p;
        int nw = bestW - 2 * p;
        int nh = bestH - 2 * p;
        if (nw < 20 || nh < 20) return new Rect(bestX, bestY, bestW, bestH);
        return new Rect(nx, ny, nw, nh);
    }

    // 最终强制居中：若当前最大解偏离中心，则在保持居中的前提下轻微缩小到可放置尺寸。
    int centeredW = bestW;
    int centeredH = bestH;
    int centeredX = 0, centeredY = 0;
    boolean centeredOk = false;
    for (int i = 0; i < 160; i++) {
        centeredX = cx - centeredW / 2;
        centeredY = cy - centeredH / 2;
        boolean inside = centeredX >= 0 && centeredY >= 0 && centeredX + centeredW <= w && centeredY + centeredH <= h;
        if (inside && rectBlockedCount(blocked, centeredX, centeredY, centeredW, centeredH) == 0) {
            centeredOk = true;
            break;
        }
        int nw2 = Math.max(1, (int) Math.floor(centeredW * 0.99));
        int nh2 = Math.max(1, (int) Math.floor(centeredH * 0.99));
        if (nw2 == centeredW && nh2 == centeredH) break;
        centeredW = nw2;
        centeredH = nh2;
    }
    if (!centeredOk) return null;

    int nx = centeredX + p;
    int ny = centeredY + p;
    int nw = centeredW - 2 * p;
    int nh = centeredH - 2 * p;
    if (nw < 20 || nh < 20) return new Rect(centeredX, centeredY, centeredW, centeredH);
    return new Rect(nx, ny, nw, nh);
}

protected static Rect findCenteredRectFallback(HoleResult hole, int productW, int productH, int inset) {
    return findCenteredRectFallback(hole, productW, productH, inset, buildBlockedIntegral(hole));
}

protected static Rect findCenteredRectFallback(
        HoleResult hole, int productW, int productH, int inset, int[][] blocked
) {
    int w = hole.width, h = hole.height;
    if (productW <= 0 || productH <= 0 || w <= 0 || h <= 0) return null;
    int cx = w / 2, cy = h / 2;
    if (rectBlockedCount(blocked, cx, cy, 1, 1) != 0) return null;

    Rect b = hole.bbox;
    double low = 0.0;
    double high = Math.max(
            b.w * 1.0 / Math.max(1, productW),
            b.h * 1.0 / Math.max(1, productH)
    ) * 2.0;

    int bestW = 0, bestH = 0;
    for (int it = 0; it < 30; it++) {
        double mid = (low + high) * 0.5;
        int rw = Math.max(1, (int) Math.floor(productW * mid));
        int rh = Math.max(1, (int) Math.floor(productH * mid));
        int x = cx - rw / 2;
        int y = cy - rh / 2;
        if (x < 0 || y < 0 || x + rw > w || y + rh > h) {
            high = mid;
            continue;
        }
        if (rectBlockedCount(blocked, x, y, rw, rh) == 0) {
            low = mid;
            bestW = rw;
            bestH = rh;
        } else {
            high = mid;
        }
    }
    if (bestW <= 0 || bestH <= 0) return null;
    int x = cx - bestW / 2;
    int y = cy - bestH / 2;
    int p = Math.max(0, inset);
    int nx = x + p, ny = y + p, nw = bestW - 2 * p, nh = bestH - 2 * p;
    if (nw < 20 || nh < 20) return new Rect(x, y, bestW, bestH);
    return new Rect(nx, ny, nw, nh);
}

protected static Rect centerRectInside(Rect outer, int innerW, int innerH) {
    if (outer == null) return null;
    if (innerW <= 0 || innerH <= 0) return null;
    int rw = Math.min(innerW, outer.w);
    int rh = Math.min(innerH, outer.h);
    if (rw <= 0 || rh <= 0) return null;
    int cx = outer.x + outer.w / 2;
    int cy = outer.y + outer.h / 2;
    int x = cx - rw / 2;
    int y = cy - rh / 2;
    int maxX = outer.x + outer.w - rw;
    int maxY = outer.y + outer.h - rh;
    if (x < outer.x) x = outer.x;
    if (y < outer.y) y = outer.y;
    if (x > maxX) x = maxX;
    if (y > maxY) y = maxY;
    return new Rect(x, y, rw, rh);
}

protected static int[][] buildBlockedIntegral(HoleResult hole) {
    int w = hole.width, h = hole.height;
    boolean[] mask = hole.holeMask;
    int[][] prefix = new int[h + 1][w + 1];
    for (int y = 1; y <= h; y++) {
        int rowSum = 0;
        int rowBase = (y - 1) * w;
        for (int x = 1; x <= w; x++) {
            int blocked = mask[rowBase + (x - 1)] ? 0 : 1;
            rowSum += blocked;
            prefix[y][x] = prefix[y - 1][x] + rowSum;
        }
    }
    return prefix;
}

protected static int rectBlockedCount(int[][] prefix, int x, int y, int rw, int rh) {
    int x2 = x + rw;
    int y2 = y + rh;
    return prefix[y2][x2] - prefix[y][x2] - prefix[y2][x] + prefix[y][x];
}

protected static int[] findCenterClosestValidTopLeft(
        int[][] blockedPrefix,
        int w, int h,
        int rw, int rh,
        int centerX, int centerY,
        int maxShiftX,
        int maxShiftY
) {
    int maxX = w - rw;
    int maxY = h - rh;
    if (maxX < 0 || maxY < 0) return null;

    long bestDist = Long.MAX_VALUE;
    int bestX = -1, bestY = -1;

    for (int y = 0; y <= maxY; y++) {
        for (int x = 0; x <= maxX; x++) {
            if (rectBlockedCount(blockedPrefix, x, y, rw, rh) != 0) continue;
            int rcx = x + rw / 2;
            int rcy = y + rh / 2;
            if (Math.abs(rcx - centerX) > maxShiftX) continue;
            if (Math.abs(rcy - centerY) > maxShiftY) continue;
            long dx = rcx - centerX;
            long dy = rcy - centerY;
            long d2 = dx * dx + dy * dy;
            if (d2 < bestDist) {
                bestDist = d2;
                bestX = x;
                bestY = y;
                if (d2 == 0) return new int[]{bestX, bestY};
            }
        }
    }
    return bestX < 0 ? null : new int[]{bestX, bestY};
}

/**
 * 按 contain 方式把商品缩放到目标矩形中，只执行一次放置。
 */
public static BufferedImage placeProductContainOnce(
        BufferedImage src,
        int canvasW, int canvasH,
        int bx, int by, int bw, int bh,
        double shrink,
        boolean capScaleAt1
) {
    double sx = bw * 1.0 / src.getWidth();
    double sy = bh * 1.0 / src.getHeight();
    double scale = Math.min(sx, sy) * shrink; // contain

    if (capScaleAt1) scale = Math.min(scale, 1.0);

    int drawW = (int) Math.round(src.getWidth() * scale);
    int drawH = (int) Math.round(src.getHeight() * scale);

    int dx = bx + (bw - drawW) / 2;
    int dy = by + (bh - drawH) / 2;

    BufferedImage layer = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = layer.createGraphics();
    setHQ(g);
    g.drawImage(src, dx, dy, drawW, drawH, null);
    g.dispose();
    return layer;
}

/**
 * 在 mask 安全约束下放置商品。
 *
 * <p>方法会从较大的缩放比例开始向下扫描，选择满足保留比例和裁剪像素上限的最大结果。</p>
 */
public static BufferedImage placeProductContainWithMaskSafety(
        BufferedImage src,
        int canvasW, int canvasH,
        int bx, int by, int bw, int bh,
        double initialShrink,
        boolean capScaleAt1,
        BufferedImage mask,
        double minKeepRatio,
        double minShrink,
        long maxClipPixels,
        int clipAlphaThr,
        int safetySteps,
        int clipSampleStride
) {
    double hi = Math.max(0.1, initialShrink);
    double lo = Math.max(0.1, Math.min(hi, minShrink));

    int steps = Math.max(4, safetySteps);
    BufferedImage bestLayer = null;
    double bestShrink = lo;
    ClipStats bestStats = null;

    BufferedImage fallbackLayer = null;
    double fallbackShrink = lo;
    ClipStats fallbackStats = null;

    // 从大到小扫描，先命中的就是“满足约束下尽可能大”的 shrink
    for (int i = 0; i <= steps; i++) {
        double t = (steps == 0) ? 0.0 : (i * 1.0 / steps);
        double curShrink = hi - (hi - lo) * t;
        BufferedImage layer = placeProductContainOnce(src, canvasW, canvasH, bx, by, bw, bh, curShrink, capScaleAt1);
        ClipStats stats = computeMaskClipStats(layer, mask, clipAlphaThr, clipSampleStride);

        if (fallbackStats == null || isBetterFallback(stats, curShrink, fallbackStats, fallbackShrink)) {
            fallbackLayer = layer;
            fallbackShrink = curShrink;
            fallbackStats = stats;
        }

        if (stats.keepRatio >= minKeepRatio && stats.clippedOpaque <= maxClipPixels) {
            bestLayer = layer;
            bestShrink = curShrink;
            bestStats = stats;
            break;
        }
    }

    if (bestLayer != null) {
        System.out.println("[compose] coloredSafety keepRatio=" + format3(bestStats.keepRatio) +
                ", clipPx=" + bestStats.clippedOpaque +
                ", chosenShrink=" + format3(bestShrink));
        return bestLayer;
    }

    // 找不到满足阈值的解时，选择“像素裁切最小”的候选，避免明显遮挡
    System.out.println("[compose] coloredSafety keepRatio=" + format3(fallbackStats.keepRatio) +
            ", clipPx=" + fallbackStats.clippedOpaque +
            ", chosenShrink=" + format3(fallbackShrink) + " (fallback-minClip)");
    return fallbackLayer;
}

/**
 * 统计商品层中有多少可见像素落在安全 mask 内外。
 */
protected static ClipStats computeMaskClipStats(BufferedImage layer, BufferedImage mask, int alphaThr, int sampleStride) {
    int w = layer.getWidth(), h = layer.getHeight();
    int step = Math.max(1, sampleStride);
    long weight = 1L * step * step;
    long total = 0, keep = 0, clipped = 0;
    for (int y = 0; y < h; y += step) {
        for (int x = 0; x < w; x += step) {
            int la = (layer.getRGB(x, y) >>> 24) & 0xFF;
            if (la < alphaThr) continue;
            total += weight;
            int ma = (mask.getRGB(x, y) >>> 24) & 0xFF;
            if (ma >= alphaThr) {
                keep += weight;
            } else {
                clipped += weight;
            }
        }
    }
    return new ClipStats(total, keep, clipped);
}

protected static boolean isBetterFallback(ClipStats cur, double curShrink, ClipStats best, double bestShrink) {
    if (best == null) return true;
    if (cur.clippedOpaque != best.clippedOpaque) return cur.clippedOpaque < best.clippedOpaque;
    if (Math.abs(cur.keepRatio - best.keepRatio) > 1e-12) return cur.keepRatio > best.keepRatio;
    return curShrink > bestShrink;
}
}
