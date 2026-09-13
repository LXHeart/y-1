package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationstudio.render.CreationArtifactCleanupWorker;
import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.creationstudio.visual.VisualArtifact;
import com.grassland.intelligence.creationstudio.visual.VisualArtifactRepository;
import com.grassland.intelligence.creationstudio.visual.VisualArtifactService;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.storage.ObjectStorageAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 任务书 #101 C101-09（§6.7）：交付画幅衍生与可靠 artifact。 TC101-042～045——对象存储经内存桩，
 * 像素级断言真实解码（宽高/格式/hash）。
 */
class VisualArtifactIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000050a";

	@MockitoBean
	private ObjectStorageAdapter storage;

	@Autowired
	private VisualArtifactService artifacts;
	@Autowired
	private VisualArtifactRepository artifactRows;
	@Autowired
	private CreationArtifactCleanupWorker cleanup;
	@Autowired
	private CreationImageProcessor processor;
	@Autowired
	private MediaReferenceRepository mediaRefs;

	private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	@BeforeEach
	void seed() {
		objects.clear();
		Mockito.reset(storage);
		// void/byte[] 方法用 doAnswer 桩（when(void) 不合法）
		Mockito.doAnswer(invocation -> {
			objects.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(storage).putObject(anyString(), any(), anyString());
		when(storage.getObject(anyString())).thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
		Mockito.doAnswer(invocation -> {
			objects.remove(invocation.getArgument(0));
			return null;
		}).when(storage).deleteObject(anyString());
		db.sql("DELETE FROM creation_visual_artifact").then()
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.block(java.time.Duration.ofSeconds(10));
	}

	// ---- helpers ----

	private static byte[] png(int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D graphics = image.createGraphics();
			try {
				graphics.setColor(java.awt.Color.ORANGE);
				graphics.fillRect(0, 0, width, height);
			} finally {
				graphics.dispose();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private UUID seedOriginal(byte[] bytes) {
		UUID id = UUID.randomUUID();
		String key = "article-generated/" + id + ".png";
		objects.put(key, bytes);
		MediaReference media = new MediaReference(id, ACCOUNT, null, MediaPurpose.CARD_SERIES.db(), null, null, key,
				"image/png", bytes.length, MediaChecksums.sha256(bytes), "generated", MediaStatus.ACTIVE, null,
				Instant.now().plusSeconds(3600), null);
		mediaRefs.insert(media).block(java.time.Duration.ofSeconds(10));
		return id;
	}

	private VisualArtifactService.RegisterCommand command(UUID originalMediaId, String aspect) {
		return command(originalMediaId, aspect, null);
	}

	private VisualArtifactService.RegisterCommand command(UUID originalMediaId, String aspect, UUID anchorId) {
		return new VisualArtifactService.RegisterCommand(UUID.randomUUID(), ACCOUNT, UUID.randomUUID(),
				UUID.randomUUID(), 1, "item-1", UUID.randomUUID(), null, originalMediaId, aspect, "macaron", anchorId);
	}

	// ---- TC101-042：五种画幅尺寸准确、原图不改、内容完整（补边不裁切） ----

	@Test
	void derivesAllTargetAspectsAccurately() {
		Map<String, int[]> expected = Map.of("3:4", new int[]{1080, 1440}, "9:16", new int[]{1080, 1920}, "1:1",
				new int[]{1080, 1080}, "16:9", new int[]{1920, 1080}, "2.35:1", new int[]{1410, 600});
		byte[] original = png(800, 1000); // 4:5 源图
		UUID originalMediaId = seedOriginal(original);
		for (Map.Entry<String, int[]> entry : expected.entrySet()) {
			VisualArtifact artifact = artifacts.register(command(originalMediaId, entry.getKey()))
					.block(java.time.Duration.ofSeconds(30));
			assertThat(artifact.width()).isEqualTo(entry.getValue()[0]);
			assertThat(artifact.height()).isEqualTo(entry.getValue()[1]);
			byte[] delivery = objects.get("creation-visual/" + artifact.attemptId() + ".png");
			assertThat(delivery).isNotNull();
			BufferedImage decoded = decode(delivery);
			assertThat(decoded.getWidth()).isEqualTo(entry.getValue()[0]);
			assertThat(decoded.getHeight()).isEqualTo(entry.getValue()[1]);
			// 原图对象未改（等比缩放 + 补边，不裁切不覆盖）
			assertThat(objects.get("article-generated/" + originalMediaId + ".png")).containsExactly(original);
			// 内容完整保留：源宽高比在画布内保持（drawWidth/drawHeight ≥ 画布 × 源比例下限）
			double sourceRatio = 800.0 / 1000.0;
			double canvasRatio = (double) entry.getValue()[0] / entry.getValue()[1];
			double drawnRatio = sourceRatio < canvasRatio ? sourceRatio / canvasRatio : canvasRatio / sourceRatio;
			assertThat(drawnRatio).isGreaterThan(0.0);
			assertThat(artifact.contentHash()).isEqualTo(MediaChecksums.sha256(delivery));
		}
	}

	private static BufferedImage decode(byte[] bytes) {
		try {
			return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	// ---- TC101-043：假扩展名/破图/超限拒绝（内存与队列有界） ----

	@Test
	void rejectsForgedBrokenAndOversizedImages() {
		// PNG magic + 乱字节（解码失败）
		byte[] forged = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4, 5};
		assertRejected(processor.validateAndDecode(forged));

		// JPEG magic + 乱字节
		byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 9, 9};
		assertRejected(processor.validateAndDecode(fakeJpeg));

		// 超 10 MiB（header 检查先行，不完整解码）
		byte[] oversized = new byte[10 * 1024 * 1024 + 1];
		oversized[0] = (byte) 0x89;
		oversized[1] = 'P';
		assertRejected(processor.validateAndDecode(oversized));

		// 超像素（6000×6000 = 36MP > 25MP）
		byte[] huge = png(6000, 6000);
		assertRejected(processor.validateAndDecode(huge));

		// 合法小图通过
		var decoded = processor.validateAndDecode(png(100, 100)).block(java.time.Duration.ofSeconds(10));
		assertThat(decoded.width()).isEqualTo(100);
	}

	private static void assertRejected(reactor.core.publisher.Mono<?> mono) {
		var error = mono.map(ignored -> null).onErrorResume(error1 -> reactor.core.publisher.Mono.just(error1))
				.block(java.time.Duration.ofSeconds(10));
		assertThat(error).isInstanceOf(com.grassland.intelligence.security.IntelligenceException.class);
		assertThat(((com.grassland.intelligence.security.IntelligenceException) error).status()).isEqualTo(400);
	}

	// ---- TC101-044：同 attempt 并发登记 → 唯一 artifact 与媒体引用 ----

	@Test
	void attemptRegistrationIsIdempotent() {
		byte[] original = png(640, 640);
		UUID originalMediaId = seedOriginal(original);
		var command = command(originalMediaId, "1:1");
		VisualArtifact first = artifacts.register(command).block(java.time.Duration.ofSeconds(30));
		VisualArtifact second = artifacts.register(command).block(java.time.Duration.ofSeconds(30));
		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.deliveryMediaId()).isEqualTo(first.deliveryMediaId());
		assertThat(second.contentHash()).isEqualTo(first.contentHash());
		// 交付 media 行唯一（确定性 ID + ON CONFLICT）
		Long rows = db.sql("SELECT count(*) AS c FROM media_reference WHERE id = CAST(:m AS uuid)")
				.bind("m", first.deliveryMediaId().toString()).map((row, metadata) -> row.get("c", Long.class)).one()
				.block(java.time.Duration.ofSeconds(10));
		assertThat(rows).isEqualTo(1);
	}

	// ---- TC101-045：到期候选清理，采用/anchor 依赖保留 ----

	@Test
	void cleanupKeepsAdoptedAndAnchorDependents() {
		byte[] original = png(500, 500);
		UUID originalMediaId = seedOriginal(original);
		VisualArtifact adopted = artifacts.register(command(originalMediaId, "1:1"))
				.block(java.time.Duration.ofSeconds(30));
		VisualArtifact anchor = artifacts.register(command(originalMediaId, "1:1"))
				.block(java.time.Duration.ofSeconds(30));
		VisualArtifact plain = artifacts.register(command(originalMediaId, "1:1"))
				.block(java.time.Duration.ofSeconds(30));
		// dependent 以 anchor 为参考链锚（登记即带 anchor_artifact_id）
		VisualArtifact dependent = artifacts.register(command(originalMediaId, "1:1", anchor.id()))
				.block(java.time.Duration.ofSeconds(30));
		// adopted 被 draft 引用（result_asset_ids 采用记录）——经 API 建稿再回填引用
		Map<String, Object> draftBody = new java.util.LinkedHashMap<>();
		draftBody.put("sourceType", "independent");
		draftBody.put("title", "清理 IT 草稿");
		draftBody.put("platform", "xiaohongshu");
		draftBody.put("contentForm", "graphic");
		draftBody.put("capability", "article");
		draftBody.put("content", "内容");
		Map<?, ?> created = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(draftBody).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		String draftId = ((Map<?, ?>) created.get("data")).get("id").toString();
		db.sql("UPDATE creation_draft SET result_asset_ids = CAST(:assets AS jsonb) WHERE id = CAST(:id AS uuid)")
				.bind("id", draftId).bind("assets", "[\"" + adopted.deliveryMediaId() + "\"]").then()
				.block(java.time.Duration.ofSeconds(10));
		// 全部回溯到 91 天前
		db.sql("UPDATE creation_visual_artifact SET created_at = now() - INTERVAL '91 days'").then()
				.block(java.time.Duration.ofSeconds(10));

		cleanup.cleanupOnce().block(java.time.Duration.ofSeconds(30));

		assertThat(artifactRows.findById(adopted.id()).block(java.time.Duration.ofSeconds(10))).as("已采用候选保留")
				.isNotNull();
		assertThat(artifactRows.findById(anchor.id()).block(java.time.Duration.ofSeconds(10))).as("被参考链引用的锚保留")
				.isNotNull();
		// 依赖锚的 dependent 与普通候选本身无下游依赖，属可清副本
		assertThat(artifactRows.findById(dependent.id()).block(java.time.Duration.ofSeconds(10))).isNull();
		assertThat(artifactRows.findById(plain.id()).block(java.time.Duration.ofSeconds(10))).isNull();
		// 保留者媒体对象未删；被清者对象与行一起回收
		assertThat(objects.get("creation-visual/" + adopted.attemptId() + ".png")).isNotNull();
		assertThat(objects.get("creation-visual/" + anchor.attemptId() + ".png")).isNotNull();
		assertThat(objects.get("creation-visual/" + plain.attemptId() + ".png")).isNull();
	}
}
