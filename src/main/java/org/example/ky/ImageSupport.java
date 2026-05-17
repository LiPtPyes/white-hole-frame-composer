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
 * 图像处理通用工具方法。
 */
abstract class ImageSupport {

/**
 * 通过行列扫描估计矩形白洞。
 *
 * <p>这是兜底方案，适合白洞边界接近矩形且白色区域稳定的模板。</p>
 */
static Rect detectWhiteHoleByScan(BufferedImage frame, int whiteThr, double rowWhiteRatioThr, int stride) {
    int w = frame.getWidth(), h = frame.getHeight();

    // 统计某一行的白色像素占比。
    java.util.function.IntToDoubleFunction rowWhiteRatio = (yy) -> {
        int white = 0, total = 0;
        for (int x = 0; x < w; x += stride) {
            int rgb = frame.getRGB(x, yy);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            if (r >= whiteThr && g >= whiteThr && b >= whiteThr) white++;
            total++;
        }
        return total == 0 ? 0 : (white * 1.0 / total);
    };

    // 统计某一列的白色像素占比。
    java.util.function.IntToDoubleFunction colWhiteRatio = (xx) -> {
        int white = 0, total = 0;
        for (int y = 0; y < h; y += stride) {
            int rgb = frame.getRGB(xx, y);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            if (r >= whiteThr && g >= whiteThr && b >= whiteThr) white++;
            total++;
        }
        return total == 0 ? 0 : (white * 1.0 / total);
    };

    int top = 0;
    while (top < h && rowWhiteRatio.applyAsDouble(top) < rowWhiteRatioThr) top++;

    int bottom = h - 1;
    while (bottom >= 0 && rowWhiteRatio.applyAsDouble(bottom) < rowWhiteRatioThr) bottom--;

    int left = 0;
    while (left < w && colWhiteRatio.applyAsDouble(left) < rowWhiteRatioThr) left++;

    int right = w - 1;
    while (right >= 0 && colWhiteRatio.applyAsDouble(right) < rowWhiteRatioThr) right--;

    if (top >= bottom || left >= right) {
        throw new IllegalStateException("detectWhiteHoleByScan failed: no stable white rectangle found.");
    }

    return new Rect(left, top, right - left + 1, bottom - top + 1);
}

/**
 * 将矩形区域转换为白洞 mask。
 */
static HoleResult buildHoleFromRect(int w, int h, Rect r) {
    boolean[] mask = new boolean[w * h];
    for (int y = r.y; y < r.y + r.h; y++) {
        for (int x = r.x; x < r.x + r.w; x++) {
            mask[y * w + x] = true;
        }
    }
    return new HoleResult(mask, r, w, h);
}

/**
 * 将白洞结果转换为 alpha mask 图像。
 */
public static BufferedImage buildMaskImage(HoleResult hole, int w, int h) {
    BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y=0; y<h; y++) {
        for (int x=0; x<w; x++) {
            int idx = y*w + x;
            int a = hole.holeMask[idx] ? 0xFF : 0x00;
            mask.setRGB(x, y, (a << 24));
        }
    }
    return mask;
}

/**
 * 判断颜色是否是明亮且接近灰阶的中性色。
 */
protected static boolean isBrightNeutral(int rgb, int minLuma, int chromaTol) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    int luma = (299 * r + 587 * g + 114 * b) / 1000;
    int maxC = Math.max(r, Math.max(g, b));
    int minC = Math.min(r, Math.min(g, b));
    return luma >= minLuma && (maxC - minC) <= chromaTol;
}

/**
 * 计算布尔 mask 的最小外接矩形。
 */
protected static Rect bboxOfMask(boolean[] mask, int w, int h) {
    int minX = w, minY = h, maxX = -1, maxY = -1;
    for (int y = 0; y < h; y++) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            if (!mask[row + x]) continue;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
    }
    if (maxX < minX || maxY < minY) return null;
    return new Rect(minX, minY, maxX - minX + 1, maxY - minY + 1);
}

/**
 * 构造二维前缀和，用于快速查询局部区域是否包含 true。
 */
protected static int[][] buildTruePrefix(boolean[] arr, int w, int h) {
    int[][] prefix = new int[h + 1][w + 1];
    for (int y = 1; y <= h; y++) {
        int rowSum = 0;
        int row = (y - 1) * w;
        for (int x = 1; x <= w; x++) {
            if (arr[row + (x - 1)]) rowSum++;
            prefix[y][x] = prefix[y - 1][x] + rowSum;
        }
    }
    return prefix;
}

/**
 * 用前缀和判断指定像素周围方形区域内是否存在 true。
 */
protected static boolean hasTrueInSquare(int[][] prefix, int x, int y, int radius, int w, int h) {
    int x1 = Math.max(0, x - radius);
    int y1 = Math.max(0, y - radius);
    int x2 = Math.min(w - 1, x + radius);
    int y2 = Math.min(h - 1, y + radius);
    int sum = prefix[y2 + 1][x2 + 1] - prefix[y1][x2 + 1] - prefix[y2 + 1][x1] + prefix[y1][x1];
    return sum > 0;
}

/**
 * 获取文件扩展名。
 */
protected static String extensionOf(String fileName) {
    if (fileName == null || fileName.isEmpty()) return "";
    int idx = fileName.lastIndexOf('.');
    if (idx < 0 || idx == fileName.length() - 1) return "";
    return fileName.substring(idx + 1).toLowerCase(java.util.Locale.ROOT);
}

/**
 * 解析性能档位。
 */
protected static PerformanceProfile parseProfile(String raw) {
    if (raw == null || raw.trim().isEmpty()) return PerformanceProfile.QUALITY;
    String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
    for (PerformanceProfile p : PerformanceProfile.values()) {
        if (p.name().equals(s)) return p;
    }
    return PerformanceProfile.QUALITY;
}

/**
 * 设置绘制时使用的高质量插值参数。
 */
protected static void setHQ(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
}

/**
 * 计算模板图的稳定缓存 key。
 */
protected static String buildFrameKey(BufferedImage frame) {
    int w = frame.getWidth();
    int h = frame.getHeight();
    int[] argb = frame.getRGB(0, 0, w, h, null, 0, w);
    MessageDigest md;
    try {
        md = MessageDigest.getInstance("SHA-256");
    } catch (Exception e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
    md.update((byte) (w >>> 24));
    md.update((byte) (w >>> 16));
    md.update((byte) (w >>> 8));
    md.update((byte) w);
    md.update((byte) (h >>> 24));
    md.update((byte) (h >>> 16));
    md.update((byte) (h >>> 8));
    md.update((byte) h);
    for (int c : argb) {
        md.update((byte) (c >>> 24));
        md.update((byte) (c >>> 16));
        md.update((byte) (c >>> 8));
        md.update((byte) c);
    }
    return toHex(md.digest());
}

protected static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    char[] hex = "0123456789abcdef".toCharArray();
    for (int i = 0, j = 0; i < bytes.length; i++) {
        int v = bytes[i] & 0xFF;
        out[j++] = hex[v >>> 4];
        out[j++] = hex[v & 0x0F];
    }
    return new String(out);
}

protected static int clamp(int v) {
    return v < 0 ? 0 : Math.min(v, 255);
}

protected static String format3(double v) {
    return String.format(java.util.Locale.ROOT, "%.3f", v);
}

protected static double toMs(long nanos) {
    return nanos / 1_000_000.0;
}
}
