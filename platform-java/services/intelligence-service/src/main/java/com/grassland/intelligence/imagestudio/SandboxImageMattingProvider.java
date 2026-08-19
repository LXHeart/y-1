package com.grassland.intelligence.imagestudio;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sandbox 抠图提供者（任务书 #43 Stage 1）。
 *
 * <p>确定性像素操作：原图中央 80% 区域保持不透明，边缘 10% 线性渐变至全透明。
 * 保证 IT 可断言：输出尺寸同源、含 alpha 通道（RGBA）、纯 PNG 编码。
 */
@Component
public class SandboxImageMattingProvider implements ImageMattingProvider {

    @Override
    public Mono<MattingResult> matting(MattingCommand command) {
        return Mono.fromCallable(() -> applySandboxMatting(command))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static MattingResult applySandboxMatting(MattingCommand command) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(command.image()));
            if (source == null) {
                throw new IllegalArgumentException("无法解析图片文件");
            }
            int width = source.getWidth();
            int height = source.getHeight();

            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // 边缘比例：10% 线性渐变
            int edgeX = Math.max(1, (int) (width * 0.10));
            int edgeY = Math.max(1, (int) (height * 0.10));

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = source.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    double fx = edgeFade(x, edgeX, width);
                    double fy = edgeFade(y, edgeY, height);
                    int alpha = (int) (Math.max(0.0, Math.min(1.0, fx * fy)) * 255);

                    result.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(result, "png", out);
            return new MattingResult(out.toByteArray(), "sandbox", true);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Sandbox 抠图处理失败", e);
        }
    }

    /**
     * 单维度边缘衰减因子。中央 80% = 1.0；左/上边缘 10% 从 0 线性升至 1；右/下边缘 10% 从 1 线性降至 0。
     */
    private static double edgeFade(int pos, int edge, int total) {
        if (pos < edge) {
            return (double) pos / edge;
        }
        int fadeStart = total - edge;
        if (pos >= fadeStart) {
            return (double) (total - 1 - pos) / edge;
        }
        return 1.0;
    }
}
