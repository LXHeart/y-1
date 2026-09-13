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

	/**
	 * 任务书 #101 C101-21（§6.8 步骤 3）：按微信用途压缩——正文 ≤1 MiB、封面 ≤2 MiB。 PNG 达标直用； 否则固定阶梯（宽度
	 * 2048/1600/1280/1024 × JPEG q90/80/70，宽度优先保大，质量尽阶再缩宽），透明图 转 JPEG
	 * 以白色排版背景铺底；产物字节原样返回供落库核对；全阶不达标明确拒绝（不删图强行成功）。
	 */
	public record WechatDerived(byte[] bytes, String contentType) {
	}

	private static final int[] WECHAT_WIDTHS = {2048, 1600, 1280, 1024};
	private static final float[] WECHAT_QUALITIES = {0.90f, 0.80f, 0.70f};

	public Mono<WechatDerived> deriveForWechat(byte[] source, int maxBytes, String backgroundHex) {
		return validateAndDecode(source).flatMap(decoded -> bounded(() -> ladder(decoded, maxBytes, backgroundHex)));
	}

	private WechatDerived ladder(Decoded decoded, int maxBytes, String backgroundHex) {
		if ("png".equals(decoded.format()) && decoded.image().getColorModel().getNumComponents() <= 4) {
			try {
				byte[] png = encodePng(decoded.image());
				if (png.length <= maxBytes) {
					return new WechatDerived(png, "image/png");
				}
			} catch (Exception ignored) {
				// 编码失败继续走 JPEG 阶梯
			}
		}
		for (int width : WECHAT_WIDTHS) {
			if (width >= decoded.width()) {
				continue; // 只缩不放
			}
			for (float quality : WECHAT_QUALITIES) {
				byte[] candidate = encodeJpegScaled(decoded.image(), width, quality, backgroundHex);
				if (candidate.length <= maxBytes) {
					return new WechatDerived(candidate, "image/jpeg");
				}
			}
		}
		// 原尺寸也试一轮纯质量压缩（图本身 ≤1024 宽时上面循环被跳过）
		for (float quality : WECHAT_QUALITIES) {
			byte[] candidate = encodeJpegScaled(decoded.image(), decoded.width(), quality, backgroundHex);
			if (candidate.length <= maxBytes) {
				return new WechatDerived(candidate, "image/jpeg");
			}
		}
		throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
				"图片压缩后仍超过微信上限（" + (maxBytes / 1024 / 1024) + " MiB），请更换更小的图片");
	}

	private static byte[] encodePng(java.awt.image.BufferedImage image) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) {
			throw new IllegalStateException("png encode failed");
		}
		return out.toByteArray();
	}

	private static byte[] encodeJpegScaled(java.awt.image.BufferedImage source, int targetWidth, float quality,
			String backgroundHex) {
		int targetHeight = Math.max(1, Math.round((float) targetWidth / source.getWidth() * source.getHeight()));
		// 透明 PNG → JPEG 必须铺底（选定排版背景，默认白）
		BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = canvas.createGraphics();
		try {
			graphics.setColor(java.awt.Color.decode(backgroundHex == null ? "#ffffff" : backgroundHex));
			graphics.fillRect(0, 0, targetWidth, targetHeight);
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
					java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
		} finally {
			graphics.dispose();
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			javax.imageio.ImageWriteParam param = null;
			javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
			param = writer.getDefaultWriteParam();
			param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			try (var stream = ImageIO.createImageOutputStream(out)) {
				writer.setOutput(stream);
				writer.write(null, new javax.imageio.IIOImage(canvas, null, null), param);
			} finally {
				writer.dispose();
			}
			return out.toByteArray();
		} catch (Exception error) {
			throw new IntelligenceException(502, "STUDIO_PROVIDER_FAILED", "图片压缩编码失败");
		}
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
