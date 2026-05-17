# White Hole Frame Composer

电商主图白洞套框算法，一个纯 Java 图片合成项目。

把商品图自动放进带有“中间白色留空区域”的水印框、主图框、边框模板中，支持白洞检测、商品图去底、自动缩放、安全避让 Logo/文字/装饰元素，适合批量生成电商主图。

> 我做这个项目的原因很直接：在实现“商品图自动套水印框 / 主图框 / 白洞模板”的需求时，网上很难找到一个能直接复用的开源 Java 算法。这个项目把当时踩过的坑和启发式规则整理成一个可运行、可二次开发的工程。

## Keywords

中文关键词：

- 电商主图套框
- 商品图自动套框
- 商品图水印框合成
- 白洞模板合成
- 白洞检测算法
- 主图框自动合成
- Java 图片合成
- Java 商品图去底
- Java 图片抠图
- 水印模板合成
- 图片模板自动填充
- 电商图片批量处理

English keywords:

- white hole frame composer
- ecommerce product image frame
- product image frame generator
- watermark frame composer
- Java image compositing
- Java background removal
- product image template filling
- image mask placement
- automatic product image framing
- OpenCV alternative in Java

GitHub / Gitee Topics 建议：

```text
java
image-processing
image-composition
ecommerce
product-image
watermark
background-removal
template-matching
mask
imageio
```

## 这个项目解决什么问题

很多电商图片模板是这样的结构：

```text
+----------------------------------+
|         Logo / text / decor       |
|                                  |
|        white hole area            |
|      product should be here       |
|                                  |
|          bottom banner            |
+----------------------------------+
```

需求看起来只是“把商品图放到模板中间”，但实际会遇到很多细节：

- 模板中间不一定是透明区域，可能只是白色或接近白色。
- 模板边缘也可能是白色，容易被误判成白洞。
- 模板上方可能有 Logo、文字、角标，商品不能压上去。
- JPEG 压缩会让白色边缘产生噪声。
- 商品图可能是白底图，需要先去底再放入模板。
- 商品图不能简单拉伸，要保持宽高比。
- 商品放太大容易被 mask 裁剪，放太小又浪费空间。

White Hole Frame Composer 就是为这些问题写的启发式合成算法。

示例：
![1.jpg](src/main/resources/%E5%9B%BE%E7%89%87%E6%B5%8B%E8%AF%95/1.jpg)

![水印1.png](src/main/resources/%E6%B0%B4%E5%8D%B0/%E6%B0%B4%E5%8D%B01.png)

![result.jpg](src/main/resources/results/result.jpg)

## 功能特性

- 纯 Java 实现，不依赖 OpenCV。
- 基于 `BufferedImage` 和 `ImageIO`，容易集成到普通 Java 项目。
- 支持 JPG、PNG 输入和输出。
- 支持透明模板和非透明白底模板。
- 支持彩色边框模板和外圈浅色混合模板。
- 支持中心白洞检测、透明洞检测、白色连通块检测、矩形扫描兜底。
- 支持商品图自适应去底和透明边裁剪。
- 支持模板 Logo、文字、角标、底部条幅避让。
- 支持 mask 安全扫描，尽量避免商品被边框裁切。
- 支持 FAST、BALANCED、QUALITY 三种性能档位。
- 对相同模板做缓存，适合批量处理多张商品图。

## 效果目标

输入：

- 一张商品图，例如白底商品图。
- 一张带白洞区域的模板图，例如主图框、水印框、促销边框。

输出：

- 商品主体自动去底。
- 商品主体保持比例缩放。
- 商品主体放入模板可用区域。
- 模板文字、Logo、装饰元素尽量不被商品覆盖。
- 得到最终合成图。

## 项目结构

```text
white-hole-frame-composer/
  pom.xml
  README.md
  .gitignore
  examples/
  src/main/resources/
  src/main/java/org/example/ky/
    WhiteHoleFrameComposer.java
    PerformanceProfile.java
    FrameType.java
    FrameTypeDebug.java
    Rect.java
    HoleResult.java
    ComposerContext.java
    ImageSupport.java
    FrameTemplateSupport.java
    ProductImageSupport.java
    PlacementSupport.java
    CompositeOutputSupport.java
    package-info.java
```

## 核心类

| 类 | 说明 |
| --- | --- |
| `WhiteHoleFrameComposer` | 对外入口，提供 `compose(...)` 和命令行入口。 |
| `PerformanceProfile` | 性能档位，控制扫描次数、采样密度和输出质量。 |
| `FrameTemplateSupport` | 模板识别、白洞检测、装饰保护、模板缓存。 |
| `ProductImageSupport` | 商品图去底、透明边裁剪、背景色估计。 |
| `PlacementSupport` | 商品放置矩形计算、contain 缩放、mask 防裁剪。 |
| `CompositeOutputSupport` | 白洞补底、最终叠图、JPG/PNG 输出。 |
| `Rect` | 矩形模型。 |
| `HoleResult` | 白洞 mask 和外接矩形。 |

## 快速开始

### 环境要求

- JDK 8 或更高版本。
- Maven 3.6 或更高版本。

### 编译

```bash
mvn clean package
```

如果没有 Maven，也可以直接用 `javac` 编译：

```bash
mkdir -p target/classes
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")
```

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force -Path target/classes | Out-Null
javac -encoding UTF-8 -d target/classes (Get-ChildItem -Path src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

## 命令行使用

```bash
java -cp target/classes org.example.ky.WhiteHoleFrameComposer <product> <frame> <output>
```

参数说明：

| 参数 | 说明 |
| --- | --- |
| `product` | 商品原图路径，例如 `examples/product.jpg`。 |
| `frame` | 模板图路径，例如 `examples/frame.png`。 |
| `output` | 输出路径，例如 `result.jpg` 或 `result.png`。 |

示例：

```bash
java -cp target/classes org.example.ky.WhiteHoleFrameComposer examples/product.jpg examples/frame.png result.jpg
```

使用 Maven 打包后的 jar：

```bash
java -jar target/white-hole-frame-composer-1.0.0-SNAPSHOT.jar examples/product.jpg examples/frame.png result.jpg
```

## Java API 使用

```java
import org.example.ky.PerformanceProfile;
import org.example.ky.WhiteHoleFrameComposer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class Demo {
    public static void main(String[] args) throws Exception {
        BufferedImage product = ImageIO.read(new File("examples/product.jpg"));
        BufferedImage frame = ImageIO.read(new File("examples/frame.png"));

        BufferedImage output = WhiteHoleFrameComposer.compose(
                product,
                frame,
                PerformanceProfile.QUALITY
        );

        ImageIO.write(output, "png", new File("result.png"));
    }
}
```

## 性能档位

| 档位 | 适用场景 | 特点 |
| --- | --- | --- |
| `FAST` | 批量处理、对速度敏感 | 安全扫描步数少，采样更稀疏，速度较快。 |
| `BALANCED` | 常规线上处理 | 在速度和边缘安全性之间折中。 |
| `QUALITY` | 对边缘质量更敏感 | 扫描更细，裁剪检测更保守。 |

## 算法流程

### 1. 模板类型识别

算法会分析模板四周边缘的明亮中性色比例，识别模板类型。

| 类型 | 说明 |
| --- | --- |
| `COLORED_BORDER` | 彩色边框围住中间白洞，常见于主图框、水印框、促销框。 |
| `WHITE_MIXED` | 外圈也有大量白色或浅灰色，不能简单把白色区域都当成洞。 |

### 2. 白洞检测

白洞检测不是只靠一个阈值，而是按模板情况逐级尝试：

1. 如果模板有透明区域，优先使用 alpha 通道定位洞。
2. 从画布中心附近找白色种子点，通过 BFS 扩展连通白洞。
3. 对外圈偏白的模板，先排除外圈，再找内部白洞。
4. 使用白色连通块检测作为补充。
5. 最后用行列扫描矩形白洞作为兜底。

### 3. 装饰保护

模板中的 Logo、文字、角标、底部条幅可能靠近白洞边缘。算法会从完整白洞中再生成一个商品安全区域：

- 检测白洞中非纯白、偏暗或疑似文字的像素。
- 给这些装饰像素周围加保护半径。
- 从商品可放置区域中剔除这些位置。
- 最终商品层会被安全 mask 限制。

### 4. 商品图预处理

商品图会先做自适应去底：

- 从四角估计背景色。
- 根据颜色距离移除背景。
- 如果背景和主体太接近，回退到边缘 flood-fill。
- 如果抠图比例异常，回退到纯白背景规则。
- 最后裁剪透明外边，让主体尽量填满模板。

### 5. 商品放置

商品按 contain 方式缩放，保持原始宽高比。

彩框模板会额外做安全扫描：

- 从较大的缩放比例开始尝试。
- 统计商品可见像素落在安全 mask 外的数量。
- 选择满足保留比例和裁剪像素上限的最大尺寸。
- 如果没有完全满足条件的方案，选择裁剪最少的方案兜底。

### 6. 最终叠图

渲染顺序固定为：

```text
white hole background -> frame template -> product layer
```

也就是：

```text
白洞纯白底层 -> 模板图 -> 商品图层
```

这样可以兼容透明洞、白底洞、带 JPEG 噪声的白色洞。

## 与简单图片叠加的区别

普通叠图通常只是：

```text
resize product -> draw on frame
```

这个项目额外处理：

- 自动找模板中的可用白洞区域。
- 自动避开模板上的文字和 Logo。
- 自动去除商品白底。
- 自动防止商品被 mask 裁切。
- 自动处理透明模板和非透明模板。
- 自动缓存模板检测结果，适合批量套图。

## 输入图片建议

- 模板图尽量清晰，白洞边缘不要过度压缩。
- 如果能提供透明 PNG 模板，白洞检测会更稳定。
- 商品图背景最好稳定，四角尽量是同一种背景色。
- 如果商品主体和背景颜色非常接近，启发式抠图可能需要调参数。
- 建议在 `examples/` 放入可公开授权的测试图，方便别人快速运行。

## 当前限制

- 主要面向“中间有明显白洞”的模板。
- 对非常复杂的非规则洞形状，可能需要调整阈值。
- 商品图去底是启发式算法，不等同于深度学习分割模型。
- 当前包名是 `org.example.ky`，正式发布前可以改成自己的域名包名。
- 当前没有附带样例图片，上传前建议补充 `examples/product.jpg` 和 `examples/frame.png`。

## 常见搜索问题

如果你是通过搜索引擎找到这里，可能在找这些问题的解决方案：

- Java 如何把商品图自动套入主图框？
- Java 如何检测模板中的白色空白区域？
- Java 如何自动合成电商主图水印框？
- Java 商品图白底怎么自动去掉？
- 不用 OpenCV 如何做图片模板合成？
- 如何避免商品图覆盖模板 Logo？
- 如何批量生成电商主图？
- 如何把商品图片放进 PNG 水印模板？

## 上传到 GitHub 或 Gitee

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin <your-repository-url>
git push -u origin main
```

上传后建议做这些事情：

- 在仓库描述里写：`Java ecommerce product image frame composer / 电商主图白洞套框算法`。
- 在 Topics 中加入上面的关键词。
- 在 `examples/` 中放入输入图和输出图。
- 在 README 顶部增加效果图对比。
- 补充 `LICENSE` 文件。

## 许可证建议

开源前建议补充 `LICENSE` 文件。

| License | 说明 |
| --- | --- |
| MIT | 宽松，允许商业使用和二次分发。 |
| Apache-2.0 | 宽松，包含专利授权条款。 |
| GPL-3.0 | 强 copyleft，衍生项目也需要开源。 |

如果希望别人可以自由商用和二次开发，通常选择 MIT 或 Apache-2.0。

## 后续优化方向

- 增加单元测试和像素级回归测试。
- 增加示例图片和合成前后效果图。
- 把调试日志替换为可配置 logger。
- 把检测阈值抽成配置对象。
- 增加批量处理 CLI。
- 增加 Maven Central 或 GitHub Packages 发布配置。

