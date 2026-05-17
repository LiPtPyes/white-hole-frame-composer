package org.example.ky;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 白洞模板合成算法的公开入口。
 *
 * <p>调用方通常只需要使用 {@link #compose(BufferedImage, BufferedImage)} 或
 * {@link #compose(BufferedImage, BufferedImage, PerformanceProfile)}。内部会完成模板识别、
 * 白洞区域检测、商品图抠图、放置区域计算和最终叠图。</p>
 */
public class WhiteHoleFrameComposer extends CompositeOutputSupport {

    /**
     * 命令行调试入口。
     *
     * <p>参数顺序：商品图路径、模板图路径、输出路径。未传参数时使用项目资源目录中的样例图。</p>
     */
    public static void main(String[] args) throws Exception {
        System.out.println("work dir = " + System.getProperty("user.dir"));
        long tMainStart = System.nanoTime();

        String productPath = args.length > 0 ? args[0] : "src/main/resources/图片测试/1.jpg";
        String framePath = args.length > 1 ? args[1] : "src/main/resources/水印/水印1.png";
        String outPath = args.length > 2 ? args[2] : "src/main/resources/results/result.jpg";

        PerformanceProfile profile = PerformanceProfile.FAST;

        long tReadStart = System.nanoTime();
        BufferedImage product = ImageIO.read(new File(productPath));
        BufferedImage frame = ImageIO.read(new File(framePath));
        long tReadEnd = System.nanoTime();

        long tComposeStart = System.nanoTime();
        BufferedImage out = compose(product, frame, profile);
        long tComposeEnd = System.nanoTime();

        long tWriteStart = System.nanoTime();
        writeImageWithProfile(out, new File(outPath), profile);
        long tWriteEnd = System.nanoTime();

        System.out.println("Done -> " + new File(outPath).getAbsolutePath());
        System.out.println(
                "[timing] oneImageMs total=" + format3(toMs(tWriteEnd - tMainStart)) +
                        ", profile=" + profile.name() +
                        ", read=" + format3(toMs(tReadEnd - tReadStart)) +
                        ", compose=" + format3(toMs(tComposeEnd - tComposeStart)) +
                        ", write=" + format3(toMs(tWriteEnd - tWriteStart))
        );
    }

    /**
     * 使用最高质量策略合成商品图和模板图。
     *
     * @param product 商品原图
     * @param frame 带白洞区域的模板图
     * @return 合成后的 ARGB 图像
     */
    public static BufferedImage compose(BufferedImage product, BufferedImage frame) {
        return compose(product, frame, PerformanceProfile.QUALITY);
    }

    /**
     * 合成商品图和模板图。
     *
     * <p>算法流程为：解析模板上下文 -> 商品图抠图与裁边 -> 计算放置区域 ->
     * 生成商品层 -> 按“白洞底色、模板、商品层”的顺序叠图。</p>
     *
     * @param product 商品原图
     * @param frame 带白洞区域的模板图
     * @param profile 性能档位；传 {@code null} 时使用 {@link PerformanceProfile#QUALITY}
     * @return 合成后的 ARGB 图像
     */
    public static BufferedImage compose(BufferedImage product, BufferedImage frame, PerformanceProfile profile) {
        PerformanceProfile pf = (profile == null) ? PerformanceProfile.QUALITY : profile;
        long t0 = System.nanoTime();
        int width = frame.getWidth();
        int height = frame.getHeight();

        TemplateContext template = resolveTemplateContext(frame);
        long tDetectEnd = System.nanoTime();
        logTemplateContext(template);

        PreparedProduct preparedProduct = prepareProduct(product, template.frameType);
        BufferedImage holeWhite = buildHoleWhiteLayer(template.holeMask, width, height);
        long tPrepEnd = System.nanoTime();

        PlacementDecision placement = resolvePlacementDecision(template, preparedProduct.tight);
        logPlacementDecision(template, placement);
        BufferedImage productLayer = buildPlacedProductLayer(
                preparedProduct.tight,
                template,
                placement,
                width,
                height,
                pf
        );
        long tPlaceEnd = System.nanoTime();

        BufferedImage out = renderComposite(frame, holeWhite, productLayer, width, height);
        long tBlendEnd = System.nanoTime();

        System.out.println(
                "[timing] composeMs total=" + format3(toMs(tBlendEnd - t0)) +
                        ", profile=" + pf.name() +
                        ", detect=" + format3(toMs(tDetectEnd - t0)) +
                        ", prep=" + format3(toMs(tPrepEnd - tDetectEnd)) +
                        ", place=" + format3(toMs(tPlaceEnd - tPrepEnd)) +
                        ", blend=" + format3(toMs(tBlendEnd - tPlaceEnd))
        );

        return out;
    }
}
