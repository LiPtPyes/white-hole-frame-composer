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
 * 负责最终图层合成和文件输出。
 */
abstract class CompositeOutputSupport extends ProductImageSupport {

/**
 * 根据白洞 mask 构造白色底层。
 *
 * <p>模板白洞区域可能是透明的，也可能是带压缩噪声的白色。先铺一层纯白底，
 * 可以保证洞内背景稳定，再叠加模板和商品。</p>
 */
protected static BufferedImage buildHoleWhiteLayer(BufferedImage holeMask, int width, int height) {
    BufferedImage holeWhite = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D gwhite = holeWhite.createGraphics();
    setHQ(gwhite);
    gwhite.setColor(Color.WHITE);
    gwhite.fillRect(0, 0, width, height);
    gwhite.dispose();

    Graphics2D gwi = holeWhite.createGraphics();
    gwi.setComposite(AlphaComposite.DstIn);
    gwi.drawImage(holeMask, 0, 0, null);
    gwi.dispose();
    return holeWhite;
}

/**
 * 按固定顺序渲染最终结果：白洞底色 -> 模板 -> 商品层。
 */
protected static BufferedImage renderComposite(
        BufferedImage frame,
        BufferedImage holeWhite,
        BufferedImage productLayer,
        int width,
        int height
) {
    BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D go = out.createGraphics();
    setHQ(go);
    go.drawImage(holeWhite, 0, 0, null);
    go.drawImage(frame, 0, 0, null);
    go.drawImage(productLayer, 0, 0, null);
    go.dispose();
    return out;
}

/**
 * 根据输出文件扩展名和性能档位写出图片。
 */
public static void writeImageWithProfile(BufferedImage image, File outputFile, PerformanceProfile profile) throws Exception {
    PerformanceProfile pf = (profile == null) ? PerformanceProfile.QUALITY : profile;
    String ext = extensionOf(outputFile.getName());
    if (ext.isEmpty()) ext = pf.preferJpegOutput ? "jpg" : "png";

    if ("jpg".equals(ext) || "jpeg".equals(ext)) {
        writeJpeg(image, outputFile, pf.jpegQuality);
        return;
    }
    if ("png".equals(ext)) {
        if (pf == PerformanceProfile.QUALITY || pf.pngCompressionQuality < 0f) {
            ImageIO.write(image, "png", outputFile);
        } else {
            writePngWithCompression(image, outputFile, pf.pngCompressionQuality);
        }
        return;
    }
    ImageIO.write(image, ext, outputFile);
}

/**
 * 写出 JPEG。
 *
 * <p>JPEG 不支持透明通道，因此这里先用白底把 ARGB 图像转换为 RGB。</p>
 */
protected static void writeJpeg(BufferedImage image, File outputFile, float quality) throws Exception {
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = rgb.createGraphics();
    setHQ(g);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
    g.drawImage(image, 0, 0, null);
    g.dispose();

    Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
    if (!it.hasNext()) {
        ImageIO.write(rgb, "jpg", outputFile);
        return;
    }
    ImageWriter writer = it.next();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
        writer.setOutput(ios);
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
        }
        writer.write(null, new IIOImage(rgb, null, null), param);
    } finally {
        writer.dispose();
    }
}

/**
 * 使用指定压缩质量写出 PNG。
 */
protected static void writePngWithCompression(BufferedImage image, File outputFile, float compressionQuality) throws Exception {
    Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("png");
    if (!it.hasNext()) {
        ImageIO.write(image, "png", outputFile);
        return;
    }
    ImageWriter writer = it.next();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
        writer.setOutput(ios);
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0f, Math.min(1f, compressionQuality)));
        }
        writer.write(null, new IIOImage(image, null, null), param);
    } finally {
        writer.dispose();
    }
}
}
