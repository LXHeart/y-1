package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/** ArticleImageService 生成图登记 media 资产的单元测试（草场 Slice 8 第二步，Part B）。 */
@ExtendWith(MockitoExtension.class)
class ArticleImageServiceTest {

	private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};

	@Mock
	private RoutedTextCompletionService routed;
	@Mock
	private BingImageSearchClient search;
	@Mock
	private ImageGenerationClient generation;
	@Mock
	private GeneratedImageStore store;
	@Mock
	private MediaReferenceRepository mediaRefs;
	@Mock
	private com.grassland.intelligence.media.StoreMediaModerationService moderation;
	@Mock
	private com.grassland.intelligence.humanize.HumanizeInjectionService humanize;

	@Captor
	private ArgumentCaptor<MediaReference> mediaCaptor;

	private ArticleImageService service;

	private static final ImageGenerationClient.Endpoint ENDPOINT = new ImageGenerationClient.Endpoint(
			"https://img.example/v1", "sk-key", "wanx-v1", "qwen");

	@BeforeEach
	void setUp() {
		// 任务书 #61：注入服务透传桩（本类断言 media 登记，不关心注入内容）
		lenient().when(humanize.injectCreative(anyList()))
				.thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		service = new ArticleImageService(routed, search, generation, store, mediaRefs, moderation, humanize, 1800);
	}

	@Test
	void generateRegistersMediaAssetWithOwnerAndMetadata() {
		String b64 = Base64.getEncoder().encodeToString(PNG);
		String objectKey = "article-generated/abc.png";
		String id = UUID.randomUUID().toString();
		when(generation.generate(any(), any(), any(), any()))
				.thenReturn(Mono.just(new GeneratedImage(null, b64, "优化后")));
		when(store.store(b64)).thenReturn(Mono.just(new GeneratedImageStore.StoredRef(id, objectKey)));
		when(mediaRefs.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0, MediaReference.class)));

		GeneratedImageResponse response = service
				.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
						new MediaOwner("acct-1", "org-1"), MediaPurpose.ARTICLE_GENERATED,
						new ImageGenerationClient.Endpoint("https://img.example/v1", "sk-key", "wanx-v1", "qwen"))
				.block();

		assertThat(response).isNotNull();
		assertThat(response.imageUrl()).isEqualTo("/api/article-generation/generated-images/" + id);
		verify(mediaRefs).insert(mediaCaptor.capture());
		MediaReference media = mediaCaptor.getValue();
		assertThat(media.id()).isEqualTo(UUID.fromString(id));
		assertThat(media.ownerAccountId()).isEqualTo("acct-1");
		assertThat(media.organizationId()).isEqualTo("org-1");
		assertThat(media.objectKey()).isEqualTo(objectKey);
		assertThat(media.mimeType()).isEqualTo("image/png");
		assertThat(media.sizeBytes()).isEqualTo(PNG.length);
		assertThat(media.source()).isEqualTo("generated");
		assertThat(media.status()).isEqualTo(MediaStatus.ACTIVE);
		assertThat(media.checksum()).hasSize(64); // sha256 hex
		assertThat(media.expiresAt()).isNotNull();
	}

	@Test
	void generateRejectsProviderUrlThatCannotEnterManagedStorage() {
		when(generation.generate(any(), any(), any(), any()))
				.thenReturn(Mono.just(new GeneratedImage("https://cdn.example/x.png", null, "p")));

		assertThatThrownBy(() -> service.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
				new MediaOwner("acct-1", null), MediaPurpose.ARTICLE_GENERATED, ENDPOINT).block())
				.isInstanceOfSatisfying(com.grassland.intelligence.security.IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(502));

		verify(store, never()).store(any());
		verify(mediaRefs, never()).insert(any());
	}

	@Test
	void generateFailsAfterRegistrationRetriesInsteadOfReturningBareObject() {
		String b64 = Base64.getEncoder().encodeToString(PNG);
		String id = UUID.randomUUID().toString();
		when(generation.generate(any(), any(), any(), any())).thenReturn(Mono.just(new GeneratedImage(null, b64, "p")));
		when(store.store(b64)).thenReturn(Mono.just(new GeneratedImageStore.StoredRef(id, "k")));
		when(mediaRefs.insert(any())).thenReturn(Mono.error(new RuntimeException("db down")));

		assertThatThrownBy(() -> service.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
				new MediaOwner("acct-1", null), MediaPurpose.ARTICLE_GENERATED, ENDPOINT).block())
				.hasMessage("db down");
		verify(mediaRefs, org.mockito.Mockito.times(3)).insert(any());
	}

	@Test
	void localFallbackSkipsPersistentMediaRegistration() {
		String b64 = Base64.getEncoder().encodeToString(PNG);
		String id = UUID.randomUUID().toString();
		when(generation.generate(any(), any(), any(), any())).thenReturn(Mono.just(new GeneratedImage(null, b64, "p")));
		when(store.store(b64)).thenReturn(Mono.just(new GeneratedImageStore.StoredRef(id, id + ".png", false)));

		GeneratedImageResponse response = service
				.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
						new MediaOwner("acct-1", null), MediaPurpose.ARTICLE_GENERATED, ENDPOINT)
				.block();

		assertThat(response).isNotNull();
		assertThat(response.imageUrl()).endsWith(id);
		verify(mediaRefs, never()).insert(any());
	}

	/** AI 生成结果多模态审核钩子（任务书 #45）：登记成功即异步送审；本地兜底不送。 */
	@Test
	void registeredGeneratedImageFiresModerationHookWithOriginalBytes() {
		String b64 = Base64.getEncoder().encodeToString(PNG);
		String id = UUID.randomUUID().toString();
		when(generation.generate(any(), any(), any(), any())).thenReturn(Mono.just(new GeneratedImage(null, b64, "p")));
		when(store.store(b64)).thenReturn(Mono.just(new GeneratedImageStore.StoredRef(id, "k")));
		when(mediaRefs.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0, MediaReference.class)));

		service.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
				new MediaOwner("acct-1", null), MediaPurpose.ARTICLE_GENERATED, ENDPOINT).block();

		org.mockito.ArgumentCaptor<byte[]> bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
		verify(moderation).moderateGeneratedAsync(mediaCaptor.capture(), bytes.capture());
		assertThat(mediaCaptor.getValue().purpose()).isEqualTo("article_generated");
		assertThat(mediaCaptor.getValue().id()).isEqualTo(UUID.fromString(id));
		assertThat(bytes.getValue()).isEqualTo(PNG);

		// 本地兜底（managed=false）无 media 行 → 不送审
		org.mockito.Mockito.clearInvocations(moderation);
		when(store.store(b64)).thenReturn(Mono.just(new GeneratedImageStore.StoredRef(id, id + ".png", false)));
		service.generate(new ArticleImageService.GenerateCommand("提示", "1024x1024", List.of()),
				new MediaOwner("acct-1", null), MediaPurpose.ARTICLE_GENERATED, ENDPOINT).block();
		verify(moderation, never()).moderateGeneratedAsync(any(), any());
	}
}
