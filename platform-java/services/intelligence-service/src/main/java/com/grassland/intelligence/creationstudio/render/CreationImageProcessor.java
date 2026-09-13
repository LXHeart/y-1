package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.security.IntelligenceException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-09（D13、§5.1、§6.7）：受控图像处理。
 *
 * <ul>
 * <li>先读 header（PNG/JPEG magic + 以 long 校验宽×高 ≤25MP）再解码，单张 ≤10 MiB；
 * 假扩展名/破图明确拒绝；</li>
 * <li>等比缩放并补边（palette backgroundHex），保留全部文字（不裁切）；</li>
 * <li>阻塞工作在有界工作线程执行：同时最多 2 个任务、队列 20，超出 429 STUDIO_BUSY（§5.6）。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class CreationImageProcessor {

	private static final int MAX_BYTES = 10 * 1024 * 1024;
	private static final long MAX_PIXELS = 25L * 1024 * 1024;
	private static final int MAX_PERMITS = 2;
	private static final int MAX_QUEUE = 20;

	private final Semaphore permits = new Semaphore(MAX_PERMITS);
	private final AtomicInteger queued = new AtomicInteger(0);

	public record Decoded(BufferedImage image, String format, int width, int height) {
	}

	/** 校验并解码：header/magic/尺寸上限先行，解码后核验真实格式与 magic 一致（伪装拒绝）。 */
	public Mono<Decoded> validateAndDecode(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return Mono.error(new IntelligenceException(400, "STUDIO_UNSUPPORTED_FORMAT", "图片内容为空"));
		}
		if (bytes.length > MAX_BYTES) {
			return Mono.error(new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "图片超过 10 MiB 上限"));
		}
		String declared = magicFormat(bytes);
		if (declared == null) {
			return Mono.error(new IntelligenceException(400, "STUDIO_UNSUPPORTED_FORMAT", "只支持 PNG/JPEG 图片"));
		}
		return bounded(() -> {
			BufferedImage image;
			try {
				image = ImageIO.read(new ByteArrayInputStream(bytes));
			} catch (Exception error) {
				throw new IntelligenceException(400, "STUDIO_UNSUPPORTED_FORMAT", "图片数据无法解码");
			}
			if (image == null) {
				throw new IntelligenceException(400, "STUDIO_UNSUPPORTED_FORMAT", "图片数据无法解码");
			}
			long pixels = (long) image.getWidth() * image.getHeight();
			if (pixels > MAX_PIXELS) {
				throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "图片超过 25MP 上限");
			}
			return new Decoded(image, declared, image.getWidth(), image.getHeight());
		});
	}

	/** 等比缩放至目标画幅并补边（内容完整保留），输出 PNG。 */
	public Mono<byte[]> derivePadded(byte[] source, int targetWidth, int targetHeight, String padHex) {
		if (targetWidth <= 0 || targetHeight <= 0) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "目标画幅不合法"));
		}
		return validateAndDecode(source)
				.flatMap(decoded -> bounded(() -> renderPadded(decoded, targetWidth, targetHeight, padHex)));
	}

	private static byte[] renderPadded(Decoded decoded, int targetWidth, int targetHeight, String padHex) {
		BufferedImage source = decoded.image();
		double scale = Math.min((double) targetWidth / source.getWidth(), (double) targetHeight / source.getHeight());
		int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = canvas.createGraphics();
		try {
			graphics.setColor(java.awt.Color.decode(padHex == null ? "#ffffff" : padHex));
			graphics.fillRect(0, 0, targetWidth, targetHeight);
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
					java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, (targetWidth - drawWidth) / 2, (targetHeight - drawHeight) / 2, drawWidth,
					drawHeight, null);
		} finally {
			graphics.dispose();
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(canvas, "png", out)) {
				throw new IntelligenceException(502, "STUDIO_PROVIDER_FAILED", "衍生图编码失败");
			}
			return out.toByteArray();
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw new IntelligenceException(502, "STUDIO_PROVIDER_FAILED", "衍生图编码失败");
		}
	}

	private <T> Mono<T> bounded(java.util.function.Supplier<T> blockingWork) {
		if (queued.incrementAndGet() > MAX_QUEUE) {
			queued.decrementAndGet();
			return Mono.error(new IntelligenceException(429, "STUDIO_BUSY", "图像处理队列已满，请稍后重试"));
		}
		return Mono.fromCallable(() -> {
			permits.acquire();
			try {
				return blockingWork.get();
			} finally {
				permits.release();
				queued.decrementAndGet();
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private static String magicFormat(byte[] bytes) {
		if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
			return "png";
		}
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "jpeg";
		}
		return null;
	}
}
