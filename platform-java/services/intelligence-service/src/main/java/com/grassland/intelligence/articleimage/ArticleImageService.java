package com.grassland.intelligence.articleimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 文章配图推荐、搜图、参考图描述与生图编排。图片端点保持 legacy 免费语义。 */
@Service
public class ArticleImageService {

	private static final Logger log = LoggerFactory.getLogger(ArticleImageService.class);
	private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(30);
	private static final String GENERATED_PREFIX = "/api/article-generation/generated-images/";

	private final RoutedTextCompletionService routed;
	private final BingImageSearchClient search;
	private final ImageGenerationClient generation;
	private final GeneratedImageStore store;
	private final MediaReferenceRepository mediaRefs;
	private final com.grassland.intelligence.media.StoreMediaModerationService moderation;
	private final Duration generatedTtl;
	private final ObjectMapper mapper = new ObjectMapper();

	// 任务书 #61：去AI味 skill 注入（免费 Routed 通道显式接入；计费流在执行环内统一注入）
	private final com.grassland.intelligence.humanize.HumanizeInjectionService humanize;

	public ArticleImageService(RoutedTextCompletionService routed, BingImageSearchClient search,
			ImageGenerationClient generation, GeneratedImageStore store, MediaReferenceRepository mediaRefs,
			com.grassland.intelligence.media.StoreMediaModerationService moderation,
			com.grassland.intelligence.humanize.HumanizeInjectionService humanize,
			@Value("${article-images.generated.ttl-seconds:1800}") long generatedTtlSeconds) {
		this.routed = routed;
		this.search = search;
		this.generation = generation;
		this.store = store;
		this.mediaRefs = mediaRefs;
		this.moderation = moderation;
		this.humanize = humanize;
		this.generatedTtl = Duration.ofSeconds(generatedTtlSeconds);
	}

	public Mono<ImageRecommendation> recommend(String accountId, String organizationId, RecommendCommand command) {
		return humanize
				.injectCreative(List.of(ChatMessage.user(
						ArticleImagePrompts.recommendation(command.content(), command.outline(), command.platform()))))
				.flatMap(
						msgs -> routed.completeFor(accountId, organizationId, msgs, 2048, CHAT_TIMEOUT, "配图推荐失败，请稍后重试"))
				.map(result -> parseRecommendation(result.content()));
	}

	public Mono<List<ImageSearchResult>> search(String keywords, int count) {
		return search.search(keywords, count);
	}

	/**
	 * 生图并登记为 media 资产。{@code purpose} 区分归属用途：文章配图用
	 * {@link MediaPurpose#ARTICLE_GENERATED}， 视频改编出图（Slice 9）用
	 * {@link MediaPurpose#VIDEO_ASSET}；存储/读路径与外部 URL 契约不变。 任务书 #58 决策 G：endpoint
	 * 必填（BYOK 或平台凭据解析），静态 env 端点已删。
	 */
	public Mono<GeneratedImageResponse> generate(GenerateCommand command, MediaOwner owner, MediaPurpose purpose,
			ImageGenerationClient.Endpoint endpoint) {
		Mono<String> prompt = command.images().isEmpty()
				? Mono.just(command.prompt())
				: Flux.fromIterable(command.images()).concatMap(image -> describe(owner, image)).collectList()
						.map(descriptions -> ArticleImagePrompts.enhance(command.prompt(), descriptions));
		// 参考图双层增强并存：文本描述（describe→enhance）承载场景/物件信息进 prompt；
		// MiniMax 方言再直传 subject_reference 保人物一致性（ArticleImagePrompts 描述不了长相）。
		return prompt.flatMap(value -> generation.generate(value, command.size(), endpoint, command.images()))
				.flatMap(generated -> toResponse(generated, owner, purpose));
	}

	public Mono<GeneratedImageStore.StoredImage> findGenerated(String id) {
		return store.find(id);
	}

	private Mono<String> describe(MediaOwner owner, ReferenceImage image) {
		String dataUri = "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
		return humanize
				.injectCreative(List.of(ChatMessage.user(List
						.of(ContentPart.text(ArticleImagePrompts.referenceDescription()), ContentPart.image(dataUri)))))
				.flatMap(msgs -> routed.completeFor(owner.accountId(), owner.organizationId(), msgs, 2048, CHAT_TIMEOUT,
						"参考图分析失败，请稍后重试"))
				.map(result -> result.content());
	}

	private Mono<GeneratedImageResponse> toResponse(GeneratedImage generated, MediaOwner owner, MediaPurpose purpose) {
		if (generated.imageUrl() != null) {
			return Mono.error(new IntelligenceException(502, "图片生成服务未返回可托管的图片数据"));
		}
		return store.store(generated.base64())
				.flatMap(ref -> registerGeneratedMedia(ref, generated.base64(), owner, purpose).thenReturn(
						new GeneratedImageResponse(GENERATED_PREFIX + ref.id(), generated.revisedPrompt())));
	}

	/**
	 * 把生成的图片登记为 media 资产（草场 Slice 8 第二步）：S3 受管对象必须登记成功后才向调用方返回；
	 * 本地临时兜底（managed=false）跳过持久登记，避免文件 TTL 与 DB 行生命周期脱节。 公开读路径
	 * {@code /generated-images/{id}} 不查此行，字节级契约不变。
	 */
	private Mono<Void> registerGeneratedMedia(GeneratedImageStore.StoredRef ref, String base64, MediaOwner owner,
			MediaPurpose purpose) {
		if (!ref.managed()) {
			return Mono.empty();
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64);
		} catch (Exception ignored) {
			return Mono.error(new IntelligenceException(502, "图片生成服务返回了无效图片数据"));
		}
		MediaReference media = new MediaReference(UUID.fromString(ref.id()), owner.accountId(), owner.organizationId(),
				purpose.db(), null, null, ref.objectKey(), "image/png", bytes.length, MediaChecksums.sha256(bytes),
				"generated", MediaStatus.ACTIVE, null, Instant.now().plus(generatedTtl), null);
		return Mono.defer(() -> mediaRefs.insert(media)
				// AI 生成结果多模态审核（任务书 #45 登记）：登记成功后异步 advisory 送审，
				// 失败静默不影响生成响应（受审 purpose 由审核服务自身 gate）。
				.doOnNext(active -> moderation.moderateGeneratedAsync(active, bytes))).retry(2)
				.doOnError(error -> log.error("generated image media registration failed: imageId={}, objectKey={}",
						ref.id(), ref.objectKey(), error))
				.then();
	}

	private ImageRecommendation parseRecommendation(String raw) {
		try {
			JsonNode root = mapper.readTree(stripCodeFence(raw));
			JsonNode values = root.path("placements");
			if (!values.isArray()) {
				throw invalidRecommendation();
			}
			List<ImagePlacement> placements = new ArrayList<>();
			for (JsonNode value : values) {
				String position = required(value, "position");
				String description = required(value, "description");
				String keywords = required(value, "searchKeywords");
				String prompt = required(value, "prompt");
				if (position != null && description != null && keywords != null && prompt != null) {
					placements.add(new ImagePlacement(position, description, keywords, prompt));
				}
			}
			if (placements.isEmpty()) {
				throw new IntelligenceException(502, "配图推荐服务返回了空结果");
			}
			int requested = root.path("recommendedCount").canConvertToInt()
					? root.path("recommendedCount").intValue()
					: placements.size();
			int count = Math.max(placements.size(), Math.min(10, requested));
			return new ImageRecommendation(count, placements);
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw new IntelligenceException(502, "配图服务返回了无法解析的内容");
		}
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		return value.isEmpty() ? null : value;
	}

	private static String stripCodeFence(String raw) {
		String value = raw.trim();
		if (!value.startsWith("```")) {
			return value;
		}
		int newline = value.indexOf('\n');
		if (newline >= 0) {
			value = value.substring(newline + 1);
		}
		int closing = value.lastIndexOf("```");
		return (closing >= 0 ? value.substring(0, closing) : value).trim();
	}

	private static IntelligenceException invalidRecommendation() {
		return new IntelligenceException(502, "配图推荐服务返回了无效数据");
	}

	public record RecommendCommand(String content, String outline, String platform) {
	}

	public record GenerateCommand(String prompt, String size, List<ReferenceImage> images) {
		public GenerateCommand {
			images = images == null ? List.of() : List.copyOf(images);
		}
	}
}
