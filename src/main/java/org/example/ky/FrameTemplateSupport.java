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
 * 模板识别和白洞检测逻辑。
 *
 * <p>这一层负责把一张模板图解析成可复用的 {@link TemplateContext}：模板类型、白洞 mask、
 * 商品安全区域 mask，以及用于日志排查的识别信息。</p>
 */
abstract class FrameTemplateSupport extends ImageSupport {

    private static final int FRAME_CACHE_MAX = 32;
    private static final Map<String, FrameCache> FRAME_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, FrameCache>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FrameCache> eldest) {
                    return size() > FRAME_CACHE_MAX;
                }
            }
    );

/**
 * 解析模板上下文，并按模板内容做缓存。
 */
protected static TemplateContext resolveTemplateContext(BufferedImage frame) {
    String frameKey = buildFrameKey(frame);
    FrameCache cached = FRAME_CACHE.get(frameKey);
    if (cached != null) {
        boolean water3Like = cached.frameType == FrameType.COLORED_BORDER &&
                isWater3LikeBottomBanner(cached.typeDebug.edgeNeutralRatio, cached.typeDebug.sideNeutralRatio);
        return new TemplateContext(
                cached.typeDebug,
                cached.frameType,
                cached.transparentWatermark,
                water3Like,
                cached.holeBranch,
                cached.hole,
                cached.productHole,
                cached.holeMask,
                cached.productMask
        );
    }

    FrameTypeDebug typeDebug = analyzeFrameType(frame);
    FrameType frameType = typeDebug.type;
    HoleResult hole;
    String holeBranch;
    if (frameType == FrameType.WHITE_MIXED) {
        hole = detectHoleAdaptive(frame);
        holeBranch = "adaptive-white-mixed";
    } else {
        hole = detectHoleForColoredBorder(frame);
        holeBranch = "colored-border-center-seed";
    }

    boolean transparentWatermark = isTransparentWatermark(frame);
    HoleResult refinedHole = refineHoleMaskAvoidFrameDecor(frame, hole, frameType, transparentWatermark);
    if (refinedHole != hole) {
        hole = refinedHole;
        holeBranch = holeBranch + "+decor-guard";
    }
    logDecorLeak(frame, hole, frameType);

    int width = frame.getWidth();
    int height = frame.getHeight();
    BufferedImage holeMask = buildMaskImage(hole, width, height);
    HoleResult productHole;
    BufferedImage productMask;
    if (frameType == FrameType.WHITE_MIXED && !transparentWatermark) {
        productHole = hole;
        productMask = holeMask;
    } else {
        productHole = buildProductSafeHole(frame, hole, frameType, transparentWatermark);
        productMask = buildMaskImage(productHole, width, height);
    }

    FrameCache cacheValue = new FrameCache(
            typeDebug, frameType, transparentWatermark, holeBranch, hole, productHole, holeMask, productMask
    );
    FRAME_CACHE.put(frameKey, cacheValue);

    boolean water3Like = frameType == FrameType.COLORED_BORDER &&
            isWater3LikeBottomBanner(typeDebug.edgeNeutralRatio, typeDebug.sideNeutralRatio);
    return new TemplateContext(
            typeDebug, frameType, transparentWatermark, water3Like, holeBranch, hole, productHole, holeMask, productMask
    );
}

/**
 * 输出模板识别日志，方便定位模板分类或白洞检测问题。
 */
protected static void logTemplateContext(TemplateContext template) {
    System.out.println(
            "[compose] frameType=" + template.frameType +
                    ", sampleBand=" + template.typeDebug.sampleBand +
                    ", edgeNeutralRatio=" + format3(template.typeDebug.edgeNeutralRatio) +
                    ", sideNeutral(top,right,bottom,left)=(" +
                    format3(template.typeDebug.sideNeutralRatio[0]) + "," +
                    format3(template.typeDebug.sideNeutralRatio[1]) + "," +
                    format3(template.typeDebug.sideNeutralRatio[2]) + "," +
                    format3(template.typeDebug.sideNeutralRatio[3]) + ")" +
                    ", whiteSideCount=" + template.typeDebug.whiteSideCount +
                    ", rule=\"" + template.typeDebug.rule + "\"" +
                    ", water3Like=" + template.water3Like +
                    ", holeBranch=" + template.holeBranch +
                    ", holeBbox=" + template.hole.bbox
    );
}

/**
 * 自适应白洞检测。
 *
 * <p>适用于外圈也可能存在浅色区域的模板。检测顺序是：透明通道、中心种子、
 * 排除外圈后的白色连通块、普通白色连通块、矩形扫描兜底。</p>
 */
public static HoleResult detectHoleAdaptive(BufferedImage frame) {
    int w = frame.getWidth(), h = frame.getHeight();
    int minArea = Math.max(12000, (w * h) / 30);
    int edgeInset = chooseEdgeInset(frame, 185, 24);

    HoleResult alphaHole = detectHoleByAlphaIfUseful(frame, 12, minArea);
    if (alphaHole != null) return alphaHole;
    if (isCenterTransparent(frame, 12)) {
        HoleResult alphaHoleAny = detectHoleByAlphaIfUseful(frame, 12, minArea, false);
        if (alphaHoleAny != null) return alphaHoleAny;
    }

    try {
        HoleResult hole = detectHoleByCenterSeed(frame, 190, 30, minArea);
        Rect r = hole.bbox;
        boolean tooBig = (r.w > w * 0.995 && r.h > h * 0.995);
        boolean touchEdgesTooMuch =
                r.x <= 2 || r.y <= 2 || (r.x + r.w) >= w - 3 || (r.y + r.h) >= h - 3;
        boolean plausible = r.w > w * 0.35 && r.h > h * 0.35;
        if (plausible && !tooBig && !touchEdgesTooMuch) return hole;
    } catch (Exception ignore) {
        // 中心种子法失败时继续尝试其他检测策略。
    }

    if (edgeInset > 0) {
        try {
            HoleResult hole = detectInnerWhiteHoleExcludingOuter(frame, 185, 24, edgeInset, minArea);
            Rect r = hole.bbox;
            boolean plausible = r.w > w * 0.35 && r.h > h * 0.35;
            if (plausible) return hole;
        } catch (Exception ignore) {
            // 外圈排除策略失败时继续尝试其他检测策略。
        }
    }

    try {
        HoleResult hole = detectWhiteHoleRobust(frame, 235, minArea);
        Rect r = hole.bbox;
        boolean tooBig = (r.w > w * 0.99 && r.h > h * 0.99);
        if (!tooBig) return hole;
    } catch (Exception ignore) {
        // 普通白色连通块失败后使用矩形扫描兜底。
    }

    Rect rr = detectWhiteHoleByScan(frame, 230, 0.90, 4);
    return buildHoleFromRect(w, h, rr);
}

/**
 * 彩色边框模板专用白洞检测。
 *
 * <p>彩框模板中间通常有明显白洞，但边框、Logo、底部条幅可能贴近洞边。
 * 因此检测结果会做轻微腐蚀，减少 JPEG 噪声和边框像素混入。</p>
 */
public static HoleResult detectHoleForColoredBorder(BufferedImage frame) {
    int w = frame.getWidth(), h = frame.getHeight();
    int minArea = Math.max(16000, (w * h) / 45);

    HoleResult alphaHole = detectHoleByAlphaIfUseful(frame, 12, minArea);
    if (alphaHole != null) return erodeHole(alphaHole, 2);
    if (isCenterTransparent(frame, 12)) {
        HoleResult alphaHoleAny = detectHoleByAlphaIfUseful(frame, 12, minArea, false);
        if (alphaHoleAny != null) return erodeHole(alphaHoleAny, 2);
    }

    try {
        HoleResult hole = detectHoleByCenterSeed(frame, 175, 20, minArea);
        Rect r = hole.bbox;
        boolean plausible = r.w > w * 0.40 && r.h > h * 0.40;
        boolean touchEdgesTooMuch =
                r.x <= 2 || r.y <= 2 || (r.x + r.w) >= w - 3 || (r.y + r.h) >= h - 3;
        if (plausible && !touchEdgesTooMuch) {
            return erodeHole(hole, 3);
        }
    } catch (Exception ignore) {
        // 继续尝试更保守的白色连通块检测。
    }

    try {
        HoleResult hole = detectWhiteHoleRobust(frame, 240, minArea);
        Rect r = hole.bbox;
        boolean plausible = r.w > w * 0.45 && r.h > h * 0.45;
        if (plausible) return erodeHole(hole, 3);
    } catch (Exception ignore) {
        // 继续尝试矩形扫描。
    }

    try {
        Rect rr = detectWhiteHoleByScan(frame, 235, 0.95, 4);
        return erodeHole(buildHoleFromRect(w, h, rr), 3);
    } catch (Exception ignore) {
            // 彩框专用策略全部失败时回退到自适应检测。
    }
    return detectHoleAdaptive(frame);
}

/**
 * 如果模板存在有效透明区域，优先使用 alpha 通道定位白洞。
 */
protected static HoleResult detectHoleByAlphaIfUseful(BufferedImage frame, int alphaThr, int minArea) {
    return detectHoleByAlphaIfUseful(frame, alphaThr, minArea, true);
}

protected static HoleResult detectHoleByAlphaIfUseful(
        BufferedImage frame, int alphaThr, int minArea, boolean requireWhite
) {
    int w = frame.getWidth(), h = frame.getHeight();
    long total = (long) w * h;
    long transparent = 0;
    int whiteLuma = 230;
    int whiteTol = 18;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int rgb = frame.getRGB(x, y);
            int a = (rgb >>> 24) & 0xFF;
            if (a <= alphaThr && (!requireWhite || isBrightNeutral(rgb, whiteLuma, whiteTol))) {
                transparent++;
            }
        }
    }
    double ratio = transparent * 1.0 / total;
    if (ratio < 0.04) return null; // 透明像素占比太低，不走 alpha 路径

    int[] seed = findNearestTransparentSeed(frame, w / 2, h / 2, alphaThr);
    if (seed == null) return null;

    boolean[] mask = new boolean[w * h];
    boolean[] visited = new boolean[w * h];
    int[] qx = new int[w * h];
    int[] qy = new int[w * h];
    int area = 0;
    int minX = seed[0], minY = seed[1], maxX = seed[0], maxY = seed[1];
    int[] dx = {1, -1, 0, 0};
    int[] dy = {0, 0, 1, -1};
    int head = 0, tail = 0;
    qx[tail] = seed[0];
    qy[tail] = seed[1];
    tail++;
    visited[seed[1] * w + seed[0]] = true;

    while (head < tail) {
        int x = qx[head];
        int y = qy[head];
        head++;
        int idx = y * w + x;
        int rgb = frame.getRGB(x, y);
        int a = (rgb >>> 24) & 0xFF;
        if (a > alphaThr) continue;
        if (requireWhite && !isBrightNeutral(rgb, whiteLuma, whiteTol)) continue;
        mask[idx] = true;
        area++;
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        for (int k = 0; k < 4; k++) {
            int nx = x + dx[k], ny = y + dy[k];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            int nidx = ny * w + nx;
            if (visited[nidx]) continue;
            visited[nidx] = true;
            qx[tail] = nx;
            qy[tail] = ny;
            tail++;
        }
    }
    if (area < minArea) return null;
    System.out.println("[compose] alphaHole used ratio=" + format3(ratio) +
            ", alphaThr=" + alphaThr +
            ", whiteLuma=" + whiteLuma +
            ", whiteTol=" + whiteTol +
            ", requireWhite=" + requireWhite);
    Rect bbox = new Rect(minX, minY, maxX - minX + 1, maxY - minY + 1);
    return new HoleResult(mask, bbox, w, h);
}

protected static boolean isCenterTransparent(BufferedImage frame, int alphaThr) {
    int cx = frame.getWidth() / 2;
    int cy = frame.getHeight() / 2;
    int a = (frame.getRGB(cx, cy) >>> 24) & 0xFF;
    return a <= alphaThr;
}

protected static int[] findNearestTransparentSeed(BufferedImage frame, int cx, int cy, int alphaThr) {
    int w = frame.getWidth(), h = frame.getHeight();
    int maxR = Math.max(w, h);
    for (int r = 0; r <= maxR; r++) {
        int x1 = cx - r;
        int x2 = cx + r;
        int y1 = cy - r;
        int y2 = cy + r;

        for (int x = x1; x <= x2; x++) {
            int y = y1;
            if (x >= 0 && x < w && y >= 0 && y < h) {
                int a = (frame.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= alphaThr) return new int[]{x, y};
            }
            y = y2;
            if (x >= 0 && x < w && y >= 0 && y < h) {
                int a = (frame.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= alphaThr) return new int[]{x, y};
            }
        }

        for (int y = y1 + 1; y <= y2 - 1; y++) {
            int x = x1;
            if (x >= 0 && x < w && y >= 0 && y < h) {
                int a = (frame.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= alphaThr) return new int[]{x, y};
            }
            x = x2;
            if (x >= 0 && x < w && y >= 0 && y < h) {
                int a = (frame.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= alphaThr) return new int[]{x, y};
            }
        }
    }
    return null;
}

/**
 * 在排除外圈浅色区域后，从内部寻找白洞。
 *
 * <p>用于处理外圈也偏白的模板，避免把模板外边框误判成内部可用区域。</p>
 */
public static HoleResult detectInnerWhiteHoleExcludingOuter(
        BufferedImage frame,
        int minLuma,
        int chromaTol,
        int edgeInset,
        int minArea
) {
    int w = frame.getWidth(), h = frame.getHeight();
    int inset = Math.max(0, Math.min(edgeInset, Math.min(w, h) / 4));

    boolean[] white = new boolean[w * h];
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            int rgb = frame.getRGB(x, y);
            white[idx] = isBrightNeutral(rgb, minLuma, chromaTol);
        }
    }

    // 直接切掉最外圈，避免外圈白色参与内部白洞识别。
    if (inset > 0) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x < inset || y < inset || x >= w - inset || y >= h - inset) {
                    white[y * w + x] = false;
                }
            }
        }
    }

    boolean[] visited = new boolean[w * h];
    int[] qx = new int[w * h];
    int[] qy = new int[w * h];
    int[] dx = {1, -1, 0, 0};
    int[] dy = {0, 0, 1, -1};
    int cx = w / 2, cy = h / 2;

    double bestScore = -1;
    boolean[] bestMask = null;
    int bestMinX = 0, bestMinY = 0, bestMaxX = -1, bestMaxY = -1;

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            if (visited[idx]) continue;
            if (!white[idx]) {
                visited[idx] = true;
                continue;
            }

            int area = 0;
            int minX = x, minY = y, maxX = x, maxY = y;
            boolean hasCenter = false;
            boolean[] tmp = new boolean[w * h];
            int head = 0, tail = 0;
            qx[tail] = x;
            qy[tail] = y;
            tail++;
            visited[idx] = true;

            while (head < tail) {
                int px = qx[head];
                int py = qy[head];
                head++;
                int pidx = py * w + px;
                tmp[pidx] = true;
                area++;

                if (px == cx && py == cy) hasCenter = true;
                if (px < minX) minX = px;
                if (py < minY) minY = py;
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;

                for (int k = 0; k < 4; k++) {
                    int nx = px + dx[k], ny = py + dy[k];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    int nidx = ny * w + nx;
                    if (visited[nidx]) continue;
                    visited[nidx] = true;
                    if (white[nidx]) {
                        qx[tail] = nx;
                        qy[tail] = ny;
                        tail++;
                    }
                }
            }

            if (area < minArea) continue;
            if (!hasCenter) continue;

            int marginLeft = minX;
            int marginTop = minY;
            int marginRight = (w - 1) - maxX;
            int marginBottom = (h - 1) - maxY;
            int minMargin = Math.min(Math.min(marginLeft, marginRight), Math.min(marginTop, marginBottom));

            double score = area + minMargin * 5000.0; // 离边界越远越像洞
            if (score > bestScore) {
                bestScore = score;
                bestMask = tmp;
                bestMinX = minX; bestMinY = minY; bestMaxX = maxX; bestMaxY = maxY;
            }
        }
    }

    if (bestMask == null) {
        throw new IllegalStateException("No inner white hole found. Try minLuma=180~210 or minArea smaller.");
    }

    Rect bbox = new Rect(bestMinX, bestMinY, bestMaxX - bestMinX + 1, bestMaxY - bestMinY + 1);
    return new HoleResult(bestMask, bbox, w, h);
}

/**
 * 对白洞 mask 做形态学腐蚀，收紧边界。
 */
protected static HoleResult erodeHole(HoleResult hole, int iterations) {
    int w = hole.width, h = hole.height;
    boolean[] cur = hole.holeMask.clone();
    int iters = Math.max(0, iterations);
    for (int it = 0; it < iters; it++) {
        boolean[] nxt = new boolean[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                if (!cur[idx]) continue;
                if (cur[idx - 1] && cur[idx + 1] && cur[idx - w] && cur[idx + w]) {
                    nxt[idx] = true;
                }
            }
        }
        cur = nxt;
    }

    Rect bbox = bboxOfMask(cur, w, h);
    if (bbox == null || bbox.w < 20 || bbox.h < 20) return hole;
    return new HoleResult(cur, bbox, w, h);
}

/**
 * 判断模板是否包含可观比例的透明像素。
 */
protected static boolean isTransparentWatermark(BufferedImage frame) {
    int w = frame.getWidth(), h = frame.getHeight();
    int step = 2;
    long total = 0;
    long transparent = 0;
    int alphaThr = 8;
    for (int y = 0; y < h; y += step) {
        for (int x = 0; x < w; x += step) {
            int a = (frame.getRGB(x, y) >>> 24) & 0xFF;
            if (a <= alphaThr) transparent++;
            total++;
        }
    }
    double ratio = total == 0 ? 0.0 : (transparent * 1.0 / total);
    return ratio >= 0.02;
}

protected static boolean isDarkVisible(int argb, int alphaThr, int lumaThr) {
    int a = (argb >>> 24) & 0xFF;
    if (a < alphaThr) return false;
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    int luma = (299 * r + 587 * g + 114 * b) / 1000;
    return luma <= lumaThr;
}

protected static boolean isSparseDarkPixel(BufferedImage frameRgb, int x, int y, int w, int h, int radius, double maxRatio) {
    int x1 = Math.max(0, x - radius);
    int x2 = Math.min(w - 1, x + radius);
    int y1 = Math.max(0, y - radius);
    int y2 = Math.min(h - 1, y + radius);

    int dark = 0, total = 0;
    for (int yy = y1; yy <= y2; yy++) {
        for (int xx = x1; xx <= x2; xx++) {
            int argb = frameRgb.getRGB(xx, yy);
            if (isDarkVisible(argb, 32, 200)) dark++;
            total++;
        }
    }
    if (total <= 0) return false;
    double ratio = dark * 1.0 / total;
    return ratio <= maxRatio;
}

/**
 * 统计白洞区域内疑似装饰像素的数量，用于调试误判。
 */
protected static void logDecorLeak(BufferedImage frame, HoleResult hole, FrameType frameType) {
    int w = hole.width, h = hole.height;
    boolean[] mask = hole.holeMask;
    int topBand = Math.max(80, (int) Math.round(h * 0.35));
    int rightBand = Math.max(160, (int) Math.round(w * 0.35));
    long leakAll = 0, leakTopRight = 0;
    int strictWhite = 245;
    int alphaCut = 12;

    for (int y = 0; y < h; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int idx = row + x;
            if (!mask[idx]) continue;
            int rgb = frame.getRGB(x, y);
            int a = (rgb >>> 24) & 0xFF;
            if (a <= alphaCut) continue;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            if (r >= strictWhite && g >= strictWhite && b >= strictWhite) continue;
            leakAll++;
            if (y <= topBand && x >= w - 1 - rightBand) leakTopRight++;
        }
    }
    if (leakAll > 0) {
        System.out.println("[compose] decorLeakPx=" + leakAll +
                ", topRightLeak=" + leakTopRight +
                ", frameType=" + frameType);
    }
}

/**
 * 对白洞 mask 做二次修正，避免模板文字、Logo、角标等装饰区被商品覆盖。
 */
protected static HoleResult refineHoleMaskAvoidFrameDecor(BufferedImage frame, HoleResult hole, FrameType frameType) {
    return refineHoleMaskAvoidFrameDecor(frame, hole, frameType, false);
}

protected static HoleResult refineHoleMaskAvoidFrameDecor(BufferedImage frame, HoleResult hole, FrameType frameType, boolean transparentWatermark) {
    int w = hole.width, h = hole.height;
    boolean[] base = hole.holeMask;

    int neutralLuma = (frameType == FrameType.COLORED_BORDER) ? 206 : 212;
    int neutralTol = (frameType == FrameType.COLORED_BORDER) ? 24 : 20;
    int guardRadius = (frameType == FrameType.COLORED_BORDER) ? 12 : 10;
    int topBand = (frameType == FrameType.COLORED_BORDER)
            ? Math.max(120, (int) Math.round(h * 0.32))
            : Math.max(80, (int) Math.round(h * 0.22));
    int cornerBand = (frameType == FrameType.COLORED_BORDER)
            ? Math.max(180, (int) Math.round(w * 0.34))
            : Math.max(120, (int) Math.round(w * 0.26));
    int textBand = (frameType == FrameType.COLORED_BORDER && transparentWatermark)
            ? Math.max(90, (int) Math.round(h * 0.20))
            : 0;

    boolean[] decorSeed = new boolean[w * h];
    int seedCount = 0;
    for (int y = 0; y < h; y++) {
            if (y > topBand) continue; // 仅保护上方装饰区，避免误削底部条幅附近的可用空间。
        int row = y * w;
        for (int x = 0; x < w; x++) {
            boolean inCorner = (x <= cornerBand) || (x >= w - 1 - cornerBand);
            int idx = row + x;
            int rgb = frame.getRGB(x, y);
            if (inCorner && !isBrightNeutral(rgb, neutralLuma, neutralTol)) {
                decorSeed[idx] = true;
                seedCount++;
            }
            if (transparentWatermark && frameType == FrameType.COLORED_BORDER && y <= textBand && isDarkVisible(rgb, 32, 200)) {
                if (isSparseDarkPixel(frame, x, y, w, h, 4, 0.45)) {
                    if (!decorSeed[idx]) {
                        decorSeed[idx] = true;
                        seedCount++;
                    }
                }
            }
        }
    }
    if (seedCount == 0) return hole;

    int[][] decorPrefix = buildTruePrefix(decorSeed, w, h);
    boolean[] refined = base.clone();
    int baseArea = 0, refinedArea = 0, removed = 0;
    for (int y = 0; y < h; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int idx = row + x;
            if (!base[idx]) continue;
            baseArea++;
            if (hasTrueInSquare(decorPrefix, x, y, guardRadius, w, h)) {
                refined[idx] = false;
                removed++;
            } else {
                refinedArea++;
            }
        }
    }

    // 彩框模板：强制洞内为“近乎纯白”的区域，防止 logo/文字被当成可放置区域。
    int strictRemoved = 0;
    if (frameType == FrameType.COLORED_BORDER) {
        int strictWhite = 245;
        int alphaCut = 8;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                if (!refined[idx]) continue;
                int rgb = frame.getRGB(x, y);
                int a = (rgb >>> 24) & 0xFF;
                if (a <= alphaCut) continue;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < strictWhite || g < strictWhite || b < strictWhite) {
                    refined[idx] = false;
                    strictRemoved++;
                }
            }
        }
        refinedArea -= strictRemoved;
    }

    double keepRatio = refinedArea * 1.0 / Math.max(1, baseArea);

    // 保护逻辑：避免误杀导致可用洞面积过小。
    if (refinedArea < Math.max(2500, (int) Math.round(baseArea * 0.35))) {
        return hole;
    }

    // 若装饰剔除过猛（比如透明 PNG 背景导致的大块误剔除），回退到“仅严格白”的过滤策略。
    if (transparentWatermark && frameType == FrameType.COLORED_BORDER && keepRatio < 0.90) {
        boolean[] soft = base.clone();
        int softRemoved = 0;
        int strictWhite = 245;
        int alphaCut = 8;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                if (!soft[idx]) continue;
                int rgb = frame.getRGB(x, y);
                int a = (rgb >>> 24) & 0xFF;
                if (a <= alphaCut) continue;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < strictWhite || g < strictWhite || b < strictWhite) {
                    soft[idx] = false;
                    softRemoved++;
                }
            }
        }
        Rect softBbox = bboxOfMask(soft, w, h);
        if (softBbox != null && softBbox.w >= 20 && softBbox.h >= 20) {
            System.out.println("[compose] decorGuard fallback softKeepRatio=" +
                    format3((baseArea - softRemoved) * 1.0 / Math.max(1, baseArea)));
            return new HoleResult(soft, softBbox, w, h);
        }
    }

    Rect bbox = bboxOfMask(refined, w, h);
    if (bbox == null || bbox.w < 20 || bbox.h < 20) return hole;

    if (removed > 0) {
        System.out.println("[compose] decorGuard removedPx=" + removed +
                ", baseArea=" + baseArea +
                ", keepRatio=" + format3(keepRatio) +
                ", guardRadius=" + guardRadius +
                ", topBand=" + topBand +
                ", cornerBand=" + cornerBand);
        if (strictRemoved > 0) {
            System.out.println("[compose] decorGuard strictRemoved=" + strictRemoved);
        }
    }
    return new HoleResult(refined, bbox, w, h);
}

/**
 * 根据边缘浅色比例识别模板类型。
 */
public static FrameTypeDebug analyzeFrameType(BufferedImage frame) {
    int w = frame.getWidth(), h = frame.getHeight();
    // 只看很窄的边缘带，避免把内部白洞算进“边缘白色”。
    int band = Math.max(4, Math.min(w, h) / 200);
    if (band * 2 >= w || band * 2 >= h) {
        return new FrameTypeDebug(
                FrameType.COLORED_BORDER,
                band,
                0.0,
                new double[]{0.0, 0.0, 0.0, 0.0},
                0,
                "band too large -> default COLORED_BORDER"
        );
    }

    long edgeTotal = 0, edgeNeutral = 0;
    long[] sideTotal = new long[4];   // top,right,bottom,left
    long[] sideNeutral = new long[4];

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            boolean inTop = y < band;
            boolean inBottom = y >= h - band;
            boolean inLeft = x < band;
            boolean inRight = x >= w - band;
            boolean inEdgeBand = inTop || inBottom || inLeft || inRight;
            if (!inEdgeBand) continue;

            int rgb = frame.getRGB(x, y);
            boolean neutral = isBrightNeutral(rgb, 185, 24);
            edgeTotal++;
            if (neutral) edgeNeutral++;

            if (inTop) {
                sideTotal[0]++;
                if (neutral) sideNeutral[0]++;
            }
            if (inRight) {
                sideTotal[1]++;
                if (neutral) sideNeutral[1]++;
            }
            if (inBottom) {
                sideTotal[2]++;
                if (neutral) sideNeutral[2]++;
            }
            if (inLeft) {
                sideTotal[3]++;
                if (neutral) sideNeutral[3]++;
            }
        }
    }

    if (edgeTotal == 0) {
        return new FrameTypeDebug(
                FrameType.COLORED_BORDER,
                band,
                0.0,
                new double[]{0.0, 0.0, 0.0, 0.0},
                0,
                "edgeTotal=0 -> default COLORED_BORDER"
        );
    }
    double edgeRatio = edgeNeutral * 1.0 / edgeTotal;

    int whiteSideCount = 0;
    double[] sideRatio = new double[4];
    double sideWhiteThr = 0.55;
    for (int i = 0; i < 4; i++) {
        if (sideTotal[i] == 0) continue;
        double r = sideNeutral[i] * 1.0 / sideTotal[i];
        sideRatio[i] = r;
        if (r >= sideWhiteThr) whiteSideCount++;
    }

    // water3 这类模板：上、左、右边缘偏白，但底边是明显彩条，不应进入 white-mixed 分支。
    // 仅在非常典型形态下覆盖，避免影响其他模板。
    boolean water3LikeBottomBanner = isWater3LikeBottomBanner(edgeRatio, sideRatio);
    if (water3LikeBottomBanner) {
        return new FrameTypeDebug(
                FrameType.COLORED_BORDER,
                band,
                edgeRatio,
                sideRatio,
                whiteSideCount,
                "override COLORED_BORDER: top/left/right neutral high + bottom colored banner"
        );
    }

    // 外圈浅色明显且至少两边成立时，判定为 white-mixed。
    if (edgeRatio >= 0.30 && whiteSideCount >= 2) {
        return new FrameTypeDebug(
                FrameType.WHITE_MIXED,
                band,
                edgeRatio,
                sideRatio,
                whiteSideCount,
                "edgeNeutralRatio>=0.30 && sideNeutral>=0.55 at >=2 sides"
        );
    }
    return new FrameTypeDebug(
            FrameType.COLORED_BORDER,
            band,
            edgeRatio,
            sideRatio,
            whiteSideCount,
            "fallback COLORED_BORDER (strict edge rule not met)"
    );
}

protected static boolean isWater3LikeBottomBanner(double edgeRatio, double[] sideRatio) {
    if (sideRatio == null || sideRatio.length < 4) return false;
    double topR = sideRatio[0];
    double rightR = sideRatio[1];
    double bottomR = sideRatio[2];
    double leftR = sideRatio[3];

    int strongTopSides = 0;
    if (topR >= 0.74) strongTopSides++;
    if (rightR >= 0.74) strongTopSides++;
    if (leftR >= 0.74) strongTopSides++;

    // 额外限制 topR < 0.95：避免把“顶边几乎全白”的模板误判到 colored 分支。
    return edgeRatio >= 0.55 && strongTopSides >= 2 && bottomR <= 0.22 && topR < 0.95;
}

protected static int chooseEdgeInset(BufferedImage frame, int minLuma, int chromaTol) {
    int w = frame.getWidth(), h = frame.getHeight();
    int band = Math.max(8, Math.min(w, h) / 40);
    long white = 0, total = 0;

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            if (x >= band && x < w - band && y >= band && y < h - band) continue;
            total++;
            int rgb = frame.getRGB(x, y);
            if (isBrightNeutral(rgb, minLuma, chromaTol)) white++;
        }
    }
    if (total == 0) return 0;

    double ratio = white * 1.0 / total;
    if (ratio >= 0.30) return Math.max(40, Math.min(w, h) / 20);
    if (ratio >= 0.15) return Math.max(16, Math.min(w, h) / 60);
    return 0;
}

/**
 * 从画布中心附近寻找种子点，并通过 BFS 扩展出连通白洞。
 */
public static HoleResult detectHoleByCenterSeed(BufferedImage frame, int minLuma, int colorTol, int minArea) {
    int w = frame.getWidth(), h = frame.getHeight();
    int cx = w / 2, cy = h / 2;

    int half = Math.max(12, Math.min(w, h) / 12);
    long sumR = 0, sumG = 0, sumB = 0, cnt = 0;
    for (int y = Math.max(0, cy - half); y <= Math.min(h - 1, cy + half); y++) {
        for (int x = Math.max(0, cx - half); x <= Math.min(w - 1, cx + half); x++) {
            int rgb = frame.getRGB(x, y);
            sumR += (rgb >> 16) & 0xFF;
            sumG += (rgb >> 8) & 0xFF;
            sumB += rgb & 0xFF;
            cnt++;
        }
    }
    if (cnt == 0) throw new IllegalStateException("center sample failed");

    int bgR = (int) Math.round(sumR * 1.0 / cnt);
    int bgG = (int) Math.round(sumG * 1.0 / cnt);
    int bgB = (int) Math.round(sumB * 1.0 / cnt);

    int[] seed = findSeedNearCenter(frame, cx, cy, bgR, bgG, bgB, minLuma, colorTol);
    if (seed == null) {
        throw new IllegalStateException("center seed not found");
    }

    boolean[] visited = new boolean[w * h];
    boolean[] mask = new boolean[w * h];
    int[] qx = new int[w * h];
    int[] qy = new int[w * h];
    int[] dx = {1, -1, 0, 0};
    int[] dy = {0, 0, 1, -1};

    int area = 0;
    int minX = seed[0], minY = seed[1], maxX = seed[0], maxY = seed[1];

    int head = 0, tail = 0;
    qx[tail] = seed[0];
    qy[tail] = seed[1];
    tail++;
    visited[seed[1] * w + seed[0]] = true;

    while (head < tail) {
        int x = qx[head];
        int y = qy[head];
        head++;
        int idx = y * w + x;
        int rgb = frame.getRGB(x, y);

        if (!isNearBgAndBright(rgb, bgR, bgG, bgB, minLuma, colorTol)) {
            continue;
        }

        mask[idx] = true;
        area++;
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;

        for (int k = 0; k < 4; k++) {
            int nx = x + dx[k], ny = y + dy[k];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            int nidx = ny * w + nx;
            if (visited[nidx]) continue;
            visited[nidx] = true;
            qx[tail] = nx;
            qy[tail] = ny;
            tail++;
        }
    }

    if (area < minArea) {
        throw new IllegalStateException("center-seed area too small: " + area);
    }

    Rect bbox = new Rect(minX, minY, maxX - minX + 1, maxY - minY + 1);
    return new HoleResult(mask, bbox, w, h);
}

protected static int[] findSeedNearCenter(
        BufferedImage frame, int cx, int cy,
        int bgR, int bgG, int bgB,
        int minLuma, int colorTol
) {
    int w = frame.getWidth(), h = frame.getHeight();
    int cRgb = frame.getRGB(cx, cy);
    if (isNearBgAndBright(cRgb, bgR, bgG, bgB, minLuma, colorTol)) {
        return new int[]{cx, cy};
    }

    int maxR = Math.min(w, h) / 4;
    for (int r = 1; r <= maxR; r++) {
        int left = Math.max(0, cx - r), right = Math.min(w - 1, cx + r);
        int top = Math.max(0, cy - r), bottom = Math.min(h - 1, cy + r);

        for (int x = left; x <= right; x++) {
            int rgbTop = frame.getRGB(x, top);
            if (isNearBgAndBright(rgbTop, bgR, bgG, bgB, minLuma, colorTol)) return new int[]{x, top};
            int rgbBottom = frame.getRGB(x, bottom);
            if (isNearBgAndBright(rgbBottom, bgR, bgG, bgB, minLuma, colorTol)) return new int[]{x, bottom};
        }
        for (int y = top + 1; y < bottom; y++) {
            int rgbLeft = frame.getRGB(left, y);
            if (isNearBgAndBright(rgbLeft, bgR, bgG, bgB, minLuma, colorTol)) return new int[]{left, y};
            int rgbRight = frame.getRGB(right, y);
            if (isNearBgAndBright(rgbRight, bgR, bgG, bgB, minLuma, colorTol)) return new int[]{right, y};
        }
    }
    return null;
}

protected static boolean isNearBgAndBright(int rgb, int bgR, int bgG, int bgB, int minLuma, int colorTol) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;

    int luma = (299 * r + 587 * g + 114 * b) / 1000;
    if (luma < minLuma) return false;

    int dr = Math.abs(r - bgR);
    int dg = Math.abs(g - bgG);
    int db = Math.abs(b - bgB);
    int maxD = Math.max(dr, Math.max(dg, db));
    int manhattan = dr + dg + db;
    return (maxD <= colorTol) || (manhattan <= colorTol * 2);
}

/**
 * 构造商品安全放置区域。
 *
 * <p>完整白洞用于补白，但商品不能覆盖模板上的可见文字或装饰。该方法会从白洞中剔除这些像素。</p>
 */
protected static HoleResult buildProductSafeHole(BufferedImage frame, HoleResult hole, FrameType frameType, boolean transparentWatermark) {
    int w = hole.width, h = hole.height;
    boolean[] safe = hole.holeMask.clone();
    int baseArea = 0;
    int removed = 0;
    int alphaCut = 8;
    int whiteLuma = (frameType == FrameType.COLORED_BORDER) ? 245 : 242;
    int whiteTol = (frameType == FrameType.COLORED_BORDER) ? 18 : 16;
    int transparentGuardRadius = (frameType == FrameType.COLORED_BORDER) ? 4 : 3;

    int[][] visiblePrefix = null;
    if (transparentWatermark) {
        boolean[] visible = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                int argb = frame.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                visible[idx] = a > alphaCut;
            }
        }
        visiblePrefix = buildTruePrefix(visible, w, h);
    }

    for (int y = 0; y < h; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int idx = row + x;
            if (!safe[idx]) continue;
            baseArea++;
            int argb = frame.getRGB(x, y);
            int a = (argb >>> 24) & 0xFF;
            boolean reserve;
            if (transparentWatermark) {
                reserve = hasTrueInSquare(visiblePrefix, x, y, transparentGuardRadius, w, h);
            } else {
                reserve = !isBrightNeutral(argb, whiteLuma, whiteTol);
            }
            if (reserve) {
                safe[idx] = false;
                removed++;
            }
        }
    }

    if (removed <= 0) return hole;
    Rect bbox = bboxOfMask(safe, w, h);
    if (bbox == null || bbox.w < 20 || bbox.h < 20) return hole;

    int safeArea = baseArea - removed;
    if (safeArea < Math.max(2500, (int) Math.round(baseArea * 0.35))) return hole;

    System.out.println("[compose] productSafeArea keepRatio=" + format3(safeArea * 1.0 / Math.max(1, baseArea)) +
            ", removedPx=" + removed +
            ", transparentWatermark=" + transparentWatermark +
            ", guardRadius=" + (transparentWatermark ? transparentGuardRadius : 0) +
            ", safeBbox=" + bbox);
    return new HoleResult(safe, bbox, w, h);
}

/**
 * 基于白色连通块的通用白洞检测。
 */
public static HoleResult detectWhiteHoleRobust(BufferedImage frame, int whiteThr, int minArea) {
    int w = frame.getWidth(), h = frame.getHeight();

    boolean[] white = new boolean[w*h];
    for (int y=0; y<h; y++) {
        for (int x=0; x<w; x++) {
            int rgb = frame.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            white[y*w + x] = (r >= whiteThr && g >= whiteThr && b >= whiteThr);
        }
    }

    boolean[] visited = new boolean[w*h];
    int[] qx = new int[w * h];
    int[] qy = new int[w * h];
    int[] dx = {1,-1,0,0};
    int[] dy = {0,0,1,-1};

    double bestScore = -1;
    boolean[] bestMask = null;
    int bestMinX=0,bestMinY=0,bestMaxX=-1,bestMaxY=-1;

    for (int y=0; y<h; y++) {
        for (int x=0; x<w; x++) {
            int idx = y*w + x;
            if (visited[idx]) continue;
            if (!white[idx]) {
                visited[idx] = true;
                continue;
            }

            int area = 0;
            int minX=x, minY=y, maxX=x, maxY=y;
            boolean[] tmp = new boolean[w*h];

            int head = 0, tail = 0;
            qx[tail] = x;
            qy[tail] = y;
            tail++;
            visited[idx] = true;

            while(head < tail) {
                int cx = qx[head];
                int cy = qy[head];
                head++;
                int cidx = cy*w + cx;
                tmp[cidx] = true;
                area++;

                if (cx<minX) minX=cx;
                if (cy<minY) minY=cy;
                if (cx>maxX) maxX=cx;
                if (cy>maxY) maxY=cy;

                for (int k=0; k<4; k++) {
                    int nx=cx+dx[k], ny=cy+dy[k];
                    if (nx<0||nx>=w||ny<0||ny>=h) continue;
                    int nidx = ny*w + nx;
                    if (visited[nidx]) continue;

                    if (white[nidx]) {
                        visited[nidx] = true;
                        qx[tail] = nx;
                        qy[tail] = ny;
                        tail++;
                    } else {
                        visited[nidx] = true;
                    }
                }
            }

            if (area < minArea) continue;

            int marginLeft = minX;
            int marginTop = minY;
            int marginRight = (w - 1) - maxX;
            int marginBottom = (h - 1) - maxY;
            int minMargin = Math.min(Math.min(marginLeft, marginRight), Math.min(marginTop, marginBottom));

            double score = area + minMargin * 5000.0; // 离边界越远越像内部白洞。
            if (score > bestScore) {
                bestScore = score;
                bestMask = tmp;
                bestMinX=minX; bestMinY=minY; bestMaxX=maxX; bestMaxY=maxY;
            }
        }
    }

    if (bestMask == null) {
        throw new IllegalStateException("No white hole found. Try whiteThr=240~253 or minArea smaller.");
    }

    Rect bbox = new Rect(bestMinX, bestMinY, bestMaxX-bestMinX+1, bestMaxY-bestMinY+1);
    return new HoleResult(bestMask, bbox, w, h);
}

/**
 * 生成只保留洞外区域的模板覆盖层。
 */
public static BufferedImage makeFrameOverlay(BufferedImage frameRgb, boolean[] holeMask, int w, int h) {
    BufferedImage overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y=0; y<h; y++) {
        for (int x=0; x<w; x++) {
            int idx = y*w + x;
            int argb = frameRgb.getRGB(x, y);
            int rgb = argb & 0x00FFFFFF;
            int a = holeMask[idx] ? 0x00 : ((argb >>> 24) & 0xFF); // 洞内透明，洞外保留模板。
            overlay.setRGB(x, y, (a << 24) | rgb);
        }
    }
    return overlay;
}
}
