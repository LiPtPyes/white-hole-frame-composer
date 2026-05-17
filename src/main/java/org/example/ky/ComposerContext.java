package org.example.ky;

import java.awt.image.BufferedImage;

/**
 * 模板分析结果缓存。
 *
 * <p>同一个模板可能会被批量套用到多张商品图，缓存可以避免重复做白洞检测和 mask 构建。</p>
 */
class FrameCache {
    final FrameTypeDebug typeDebug;
    final FrameType frameType;
    final boolean transparentWatermark;
    final String holeBranch;
    final HoleResult hole;
    final HoleResult productHole;
    final BufferedImage holeMask;
    final BufferedImage productMask;

    FrameCache(
            FrameTypeDebug typeDebug,
            FrameType frameType,
            boolean transparentWatermark,
            String holeBranch,
            HoleResult hole,
            HoleResult productHole,
            BufferedImage holeMask,
            BufferedImage productMask
    ) {
        this.typeDebug = typeDebug;
        this.frameType = frameType;
        this.transparentWatermark = transparentWatermark;
        this.holeBranch = holeBranch;
        this.hole = hole;
        this.productHole = productHole;
        this.holeMask = holeMask;
        this.productMask = productMask;
    }
}

/**
 * 单次合成使用的模板上下文。
 *
 * <p>这里同时保存“完整白洞”和“商品安全放置区域”。两者可能不同：完整白洞用于补白，
 * 商品安全区域会避开模板上的 Logo、文字或装饰。</p>
 */
class TemplateContext {
    final FrameTypeDebug typeDebug;
    final FrameType frameType;
    final boolean transparentWatermark;
    final boolean water3Like;
    final String holeBranch;
    final HoleResult hole;
    final HoleResult productHole;
    final BufferedImage holeMask;
    final BufferedImage productMask;

    TemplateContext(
            FrameTypeDebug typeDebug,
            FrameType frameType,
            boolean transparentWatermark,
            boolean water3Like,
            String holeBranch,
            HoleResult hole,
            HoleResult productHole,
            BufferedImage holeMask,
            BufferedImage productMask
    ) {
        this.typeDebug = typeDebug;
        this.frameType = frameType;
        this.transparentWatermark = transparentWatermark;
        this.water3Like = water3Like;
        this.holeBranch = holeBranch;
        this.hole = hole;
        this.productHole = productHole;
        this.holeMask = holeMask;
        this.productMask = productMask;
    }
}

/**
 * 商品图预处理结果。
 */
class PreparedProduct {
    final BufferedImage cut;
    final BufferedImage tight;

    PreparedProduct(BufferedImage cut, BufferedImage tight) {
        this.cut = cut;
        this.tight = tight;
    }
}

/**
 * 商品放置决策。
 */
class PlacementDecision {
    final Rect rect;
    final double padRatio;
    final double shrink;

    PlacementDecision(Rect rect, double padRatio, double shrink) {
        this.rect = rect;
        this.padRatio = padRatio;
        this.shrink = shrink;
    }
}

/**
 * 商品层与安全 mask 的裁剪统计。
 */
class ClipStats {
    final long totalOpaque;
    final long keepOpaque;
    final long clippedOpaque;
    final double keepRatio;

    ClipStats(long totalOpaque, long keepOpaque, long clippedOpaque) {
        this.totalOpaque = totalOpaque;
        this.keepOpaque = keepOpaque;
        this.clippedOpaque = clippedOpaque;
        this.keepRatio = totalOpaque <= 0 ? 1.0 : (keepOpaque * 1.0 / totalOpaque);
    }
}

/**
 * 商品图中心区域与估计背景色的距离统计。
 */
class CenterBgStats {
    final double avgDist;
    final double lowDistRatio;

    CenterBgStats(double avgDist, double lowDistRatio) {
        this.avgDist = avgDist;
        this.lowDistRatio = lowDistRatio;
    }
}

/**
 * 透明度统计，用于判断抠图是否过度或不足。
 */
class AlphaStats {
    final double visibleRatio;
    final double avgVisibleAlpha;

    AlphaStats(double visibleRatio, double avgVisibleAlpha) {
        this.visibleRatio = visibleRatio;
        this.avgVisibleAlpha = avgVisibleAlpha;
    }
}
