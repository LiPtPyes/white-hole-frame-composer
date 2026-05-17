package org.example.ky;

/**
 * 模板类型识别的调试信息。
 *
 * <p>这些字段会输出到日志中，用于解释模板为什么被归类为某一种类型。</p>
 */
public class FrameTypeDebug {
    /**
     * 最终识别出的模板类型。
     */
    public final FrameType type;

    /**
     * 边缘采样带宽，单位为像素。
     */
    public final int sampleBand;

    /**
     * 四周边缘中“明亮中性色”像素的总体占比。
     */
    public final double edgeNeutralRatio;

    /**
     * 上、右、下、左四条边分别的明亮中性色占比。
     */
    public final double[] sideNeutralRatio;

    /**
     * 达到白边阈值的边数。
     */
    public final int whiteSideCount;

    /**
     * 命中的识别规则说明。
     */
    public final String rule;

    FrameTypeDebug(
            FrameType type,
            int sampleBand,
            double edgeNeutralRatio,
            double[] sideNeutralRatio,
            int whiteSideCount,
            String rule
    ) {
        this.type = type;
        this.sampleBand = sampleBand;
        this.edgeNeutralRatio = edgeNeutralRatio;
        this.sideNeutralRatio = sideNeutralRatio;
        this.whiteSideCount = whiteSideCount;
        this.rule = rule;
    }
}
