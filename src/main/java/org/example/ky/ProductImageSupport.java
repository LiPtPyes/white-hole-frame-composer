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
 * 商品图抠图和裁剪逻辑。
 */
abstract class ProductImageSupport extends PlacementSupport {

/**
 * 对商品图做背景去除，并裁掉透明外边。
 */
protected static PreparedProduct prepareProduct(BufferedImage product, FrameType frameType) {
    boolean allowBgFallback = frameType == FrameType.COLORED_BORDER;
    BufferedImage productCut = removeProductBgAdaptive(product, allowBgFallback);
    BufferedImage productTight = cropToOpaqueBounds(productCut, 10, 2);
    return new PreparedProduct(productCut, productTight);
}

/**
 * 裁剪透明外边，让商品主体尽量填满可放置区域。
 */
public static BufferedImage cropToOpaqueBounds(BufferedImage src, int alphaThr, int pad) {
    int w = src.getWidth(), h = src.getHeight();
    int minX = w, minY = h, maxX = -1, maxY = -1;

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int a = (src.getRGB(x, y) >>> 24) & 0xFF;
            if (a < alphaThr) continue;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
    }

    // 没有可见像素，直接返回原图。
    if (maxX < minX || maxY < minY) {
        return src;
    }

    int p = Math.max(0, pad);
    minX = Math.max(0, minX - p);
    minY = Math.max(0, minY - p);
    maxX = Math.min(w - 1, maxX + p);
    maxY = Math.min(h - 1, maxY + p);

    int nw = maxX - minX + 1;
    int nh = maxY - minY + 1;
    BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    setHQ(g);
    g.drawImage(src, -minX, -minY, null);
    g.dispose();
    return out;
}

/**
 * 自适应去除商品图背景。
 *
 * <p>优先根据四角估计背景色做颜色距离抠图；如果判断抠图风险较高，
 * 则回退到边缘 flood-fill 或纯白背景规则。</p>
 */
public static BufferedImage removeProductBgAdaptive(BufferedImage src, boolean allowFallback) {
    try {
        int minSide = Math.min(src.getWidth(), src.getHeight());
        int patch = Math.max(16, minSide / 10);
        int[] bg = estimateBgFromCorners(src, patch);
        CenterBgStats centerStats = calcCenterBgStats(src, bg[0], bg[1], bg[2]);
        System.out.println("[compose] bgStats avgDist=" + format3(centerStats.avgDist) +
                ", lowDistRatio=" + format3(centerStats.lowDistRatio) +
                ", bg=(" + bg[0] + "," + bg[1] + "," + bg[2] + ")");
        if (allowFallback && !src.getColorModel().hasAlpha()
                && centerStats.avgDist < 18
                && centerStats.lowDistRatio > 0.55) {
            System.out.println("[compose] bgTooClose avgDist=" + format3(centerStats.avgDist) +
                    ", lowDistRatio=" + format3(centerStats.lowDistRatio) + " -> weak edge cut");
            return removeBgByEdgeFlood(src, bg[0], bg[1], bg[2], 10, 24);
        }
        BufferedImage cut = removeBgByColorDistance(src, bg[0], bg[1], bg[2], 12, 34);
        AlphaStats cutStats = calcVisibleAlphaStats(cut, 8);
        if (allowFallback && !src.getColorModel().hasAlpha() && cutStats.avgVisibleAlpha < 245) {
            System.out.println("[compose] bgCut avgVisibleAlpha=" + format3(cutStats.avgVisibleAlpha) +
                    ", visibleRatio=" + format3(cutStats.visibleRatio) + " -> fallback original");
            return src;
        }
        double opaqueRatio = calcOpaqueRatio(cut, 16);

        // 比例异常说明抠图过度或不足，回退到纯白背景规则。
        if (allowFallback && (opaqueRatio < 0.01 || opaqueRatio > 0.92)) {
            BufferedImage pure = removePureWhiteBg(src, 253, 247);
            AlphaStats pureStats = calcVisibleAlphaStats(pure, 8);
            if (!src.getColorModel().hasAlpha() && pureStats.avgVisibleAlpha < 245) {
                System.out.println("[compose] pureCut avgVisibleAlpha=" + format3(pureStats.avgVisibleAlpha) +
                        ", visibleRatio=" + format3(pureStats.visibleRatio) + " -> fallback original");
                return src;
            }
            return pure;
        }
        return cut;
    } catch (Exception e) {
        return removePureWhiteBg(src, 253, 247);
    }
}

/**
 * 统计商品图中心区域与估计背景色的距离，用于判断背景是否贴近主体。
 */
protected static CenterBgStats calcCenterBgStats(BufferedImage src, int bgR, int bgG, int bgB) {
    int w = src.getWidth(), h = src.getHeight();
    int x0 = w * 2 / 5;
    int y0 = h * 2 / 5;
    int x1 = w * 3 / 5;
    int y1 = h * 3 / 5;
    long sum = 0;
    long cnt = 0;
    long low = 0;
    int thr = 18;
    for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
            int rgb = src.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int dist = Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
            sum += dist;
            cnt++;
            if (dist <= thr) {
                low++;
            }
        }
    }
    double avg = cnt == 0 ? 0 : (sum * 1.0 / cnt);
    double lowRatio = cnt == 0 ? 0 : (low * 1.0 / cnt);
    return new CenterBgStats(avg, lowRatio);
}

/**
 * 从四角采样估计商品图背景色。
 */
protected static int[] estimateBgFromCorners(BufferedImage src, int patch) {
    int w = src.getWidth(), h = src.getHeight();
    int p = Math.max(4, Math.min(patch, Math.min(w, h) / 4));

    int[][] means = new int[4][3];
    means[0] = avgPatch(src, 0, 0, p, p);
    means[1] = avgPatch(src, w - p, 0, p, p);
    means[2] = avgPatch(src, 0, h - p, p, p);
    means[3] = avgPatch(src, w - p, h - p, p, p);

    int avgR = 0, avgG = 0, avgB = 0;
    for (int i = 0; i < 4; i++) {
        avgR += means[i][0];
        avgG += means[i][1];
        avgB += means[i][2];
    }
    avgR /= 4;
    avgG /= 4;
    avgB /= 4;

    int spread = 0;
    for (int i = 0; i < 4; i++) {
        int d = Math.max(
                Math.abs(means[i][0] - avgR),
                Math.max(Math.abs(means[i][1] - avgG), Math.abs(means[i][2] - avgB))
        );
        if (d > spread) spread = d;
    }
    if (spread > 36) {
        throw new IllegalStateException("corner background is not stable, spread=" + spread);
    }
    return new int[]{avgR, avgG, avgB};
}

protected static int[] avgPatch(BufferedImage img, int sx, int sy, int pw, int ph) {
    long sumR = 0, sumG = 0, sumB = 0, cnt = 0;
    for (int y = sy; y < sy + ph; y++) {
        for (int x = sx; x < sx + pw; x++) {
            int rgb = img.getRGB(x, y);
            sumR += (rgb >> 16) & 0xFF;
            sumG += (rgb >> 8) & 0xFF;
            sumB += rgb & 0xFF;
            cnt++;
        }
    }
    if (cnt == 0) return new int[]{255, 255, 255};
    return new int[]{
            (int) Math.round(sumR * 1.0 / cnt),
            (int) Math.round(sumG * 1.0 / cnt),
            (int) Math.round(sumB * 1.0 / cnt)
    };
}

/**
 * 从图片边缘开始 flood-fill 背景区域，只移除与边缘连通且接近背景色的像素。
 */
protected static BufferedImage removeBgByEdgeFlood(
        BufferedImage src, int bgR, int bgG, int bgB, int edgeThr, int featherThr
) {
    int w = src.getWidth(), h = src.getHeight();
    boolean[] bgMask = new boolean[w * h];
    int[] qx = new int[w * h];
    int[] qy = new int[w * h];
    int head = 0, tail = 0;

    // 从边缘入队
    for (int x = 0; x < w; x++) {
        if (isBgNear(src.getRGB(x, 0), bgR, bgG, bgB, edgeThr)) {
            int idx = x;
            bgMask[idx] = true;
            qx[tail] = x; qy[tail] = 0; tail++;
        }
        if (isBgNear(src.getRGB(x, h - 1), bgR, bgG, bgB, edgeThr)) {
            int idx = (h - 1) * w + x;
            if (!bgMask[idx]) {
                bgMask[idx] = true;
                qx[tail] = x; qy[tail] = h - 1; tail++;
            }
        }
    }
    for (int y = 0; y < h; y++) {
        if (isBgNear(src.getRGB(0, y), bgR, bgG, bgB, edgeThr)) {
            int idx = y * w;
            if (!bgMask[idx]) {
                bgMask[idx] = true;
                qx[tail] = 0; qy[tail] = y; tail++;
            }
        }
        if (isBgNear(src.getRGB(w - 1, y), bgR, bgG, bgB, edgeThr)) {
            int idx = y * w + (w - 1);
            if (!bgMask[idx]) {
                bgMask[idx] = true;
                qx[tail] = w - 1; qy[tail] = y; tail++;
            }
        }
    }

    // BFS 扩展
    while (head < tail) {
        int x = qx[head];
        int y = qy[head];
        head++;
        int nx, ny, nidx;
        if (x > 0) {
            nx = x - 1; ny = y; nidx = ny * w + nx;
            if (!bgMask[nidx] && isBgNear(src.getRGB(nx, ny), bgR, bgG, bgB, edgeThr)) {
                bgMask[nidx] = true; qx[tail] = nx; qy[tail] = ny; tail++;
            }
        }
        if (x + 1 < w) {
            nx = x + 1; ny = y; nidx = ny * w + nx;
            if (!bgMask[nidx] && isBgNear(src.getRGB(nx, ny), bgR, bgG, bgB, edgeThr)) {
                bgMask[nidx] = true; qx[tail] = nx; qy[tail] = ny; tail++;
            }
        }
        if (y > 0) {
            nx = x; ny = y - 1; nidx = ny * w + nx;
            if (!bgMask[nidx] && isBgNear(src.getRGB(nx, ny), bgR, bgG, bgB, edgeThr)) {
                bgMask[nidx] = true; qx[tail] = nx; qy[tail] = ny; tail++;
            }
        }
        if (y + 1 < h) {
            nx = x; ny = y + 1; nidx = ny * w + nx;
            if (!bgMask[nidx] && isBgNear(src.getRGB(nx, ny), bgR, bgG, bgB, edgeThr)) {
                bgMask[nidx] = true; qx[tail] = nx; qy[tail] = ny; tail++;
            }
        }
    }

    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int denom = Math.max(1, featherThr - edgeThr);
    for (int y = 0; y < h; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int idx = row + x;
            int rgb = src.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int a;
            if (bgMask[idx]) {
                a = 0;
            } else if (featherThr > edgeThr && hasBgNeighbor(bgMask, x, y, w, h)) {
                int dist = maxColorDist(r, g, b, bgR, bgG, bgB);
                if (dist <= edgeThr) {
                    a = 0;
                } else if (dist >= featherThr) {
                    a = 255;
                } else {
                    a = clamp((int) Math.round(255.0 * (dist - edgeThr) / denom));
                }
            } else {
                a = 255;
            }
            out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
        }
    }
    return out;
}

protected static boolean isBgNear(int rgb, int bgR, int bgG, int bgB, int thr) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    return maxColorDist(r, g, b, bgR, bgG, bgB) <= thr;
}

protected static int maxColorDist(int r, int g, int b, int bgR, int bgG, int bgB) {
    return Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
}

protected static boolean hasBgNeighbor(boolean[] mask, int x, int y, int w, int h) {
    int idx = y * w + x;
    if (x > 0 && mask[idx - 1]) return true;
    if (x + 1 < w && mask[idx + 1]) return true;
    if (y > 0 && mask[idx - w]) return true;
    if (y + 1 < h && mask[idx + w]) return true;
    return false;
}

protected static BufferedImage removeBgByColorDistance(
        BufferedImage src,
        int bgR, int bgG, int bgB,
        int cutThr, int featherThr
) {
    int w = src.getWidth(), h = src.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int denom = Math.max(1, featherThr - cutThr);

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int rgb = src.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            int dist = Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
            int a;
            if (dist <= cutThr) {
                a = 0;
            } else if (dist >= featherThr) {
                a = 255;
            } else {
                a = clamp((int) Math.round(255.0 * (dist - cutThr) / denom));
            }

            out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
        }
    }
    return out;
}

protected static double calcOpaqueRatio(BufferedImage img, int alphaThr) {
    int w = img.getWidth(), h = img.getHeight();
    long opaque = 0, total = (long) w * h;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int a = (img.getRGB(x, y) >>> 24) & 0xFF;
            if (a >= alphaThr) opaque++;
        }
    }
    return total == 0 ? 0 : (opaque * 1.0 / total);
}

protected static AlphaStats calcVisibleAlphaStats(BufferedImage img, int alphaThr) {
    int w = img.getWidth(), h = img.getHeight();
    long visible = 0, total = (long) w * h;
    long sumAlpha = 0;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int a = (img.getRGB(x, y) >>> 24) & 0xFF;
            if (a > alphaThr) {
                visible++;
                sumAlpha += a;
            }
        }
    }
    double visibleRatio = total == 0 ? 0 : (visible * 1.0 / total);
    double avgAlpha = visible == 0 ? 0 : (sumAlpha * 1.0 / visible);
    return new AlphaStats(visibleRatio, avgAlpha);
}

/**
 * 移除纯白背景，并对接近白色的边缘做羽化。
 */
public static BufferedImage removePureWhiteBg(BufferedImage src, int pureThr, int featherThr) {
    int w = src.getWidth(), h = src.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

    for (int y=0; y<h; y++) {
        for (int x=0; x<w; x++) {
            int rgb = src.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            int a;
            if (r >= pureThr && g >= pureThr && b >= pureThr) {
                a = 0;
            } else if (r >= featherThr && g >= featherThr && b >= featherThr) {
                int d = Math.max(255 - r, Math.max(255 - g, 255 - b));
                int maxD = Math.max(1, 255 - featherThr);
                a = clamp((int) Math.round(255.0 * d / maxD));
            } else {
                a = 255;
            }

            out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
        }
    }
    return out;
}
}
