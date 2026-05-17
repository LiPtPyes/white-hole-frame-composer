package org.example.ky;

/**
 * 模板类型。
 */
public enum FrameType {
    /**
     * 彩色边框围住中间白洞的模板，例如常见水印框。
     */
    COLORED_BORDER,

    /**
     * 外圈也包含大量白色或浅灰色元素的模板，需要更严格地区分外圈和内部白洞。
     */
    WHITE_MIXED
}
