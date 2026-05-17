package org.example.ky;

/**
 * 合成性能档位。
 *
 * <p>档位主要影响彩框模板的安全缩放扫描次数、裁剪检测采样密度和输出编码质量。
 * FAST 更适合批量处理，QUALITY 更保守。</p>
 */
public enum PerformanceProfile {
    FAST(16, 2, true, 0.90f, 0.10f),
    BALANCED(24, 2, true, 0.92f, 0.35f),
    QUALITY(40, 1, true, 0.95f, -1f);

    final int safetySteps;
    final int clipSampleStride;
    final boolean preferJpegOutput;
    final float jpegQuality;
    final float pngCompressionQuality;

    PerformanceProfile(
            int safetySteps,
            int clipSampleStride,
            boolean preferJpegOutput,
            float jpegQuality,
            float pngCompressionQuality
    ) {
        this.safetySteps = safetySteps;
        this.clipSampleStride = clipSampleStride;
        this.preferJpegOutput = preferJpegOutput;
        this.jpegQuality = jpegQuality;
        this.pngCompressionQuality = pngCompressionQuality;
    }
}
