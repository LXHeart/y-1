package com.grassland.intelligence.creationstudio.wechat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-19/21：微信公众号 API 客户端（固定端点，§6.8）。 出站仅固定路径＋query access_token；响应超时
 * 30s（候选分页 5s）；错误脱敏——只回 code 与受控 message，凭据/令牌不进异常文本。 token 失效类
 * errcode（40001/42001/40014）经 {@link WechatApiException#tokenInvalid()} 透出，
 * 由调用方执行 「有界刷新一次后重试一次」。
 */
@Component
public class WechatApiClient {

	private final WebClient client;

	public WechatApiClient(WechatProperties properties) {
		this.client = WebClient.builder().baseUrl(properties.apiBaseUrl()).build();
	}

	public record AccessToken(String accessToken, int expiresInSeconds) {
	}

	public record UploadedImage(String mediaId, String url) {
	}

	public record WechatArticle(String title, String content, String thumbMediaId, String digest, String author,
			String contentSourceUrl, int needOpenComment, int onlyFansCanComment) {
	}

	public record DraftArticle(String title, String content, String digest, String thumbMediaId, String author,
			String contentSourceUrl) {
	}

	public record DraftSummary(String mediaId, String title, String updatedAt, String content) {
	}

	public record BatchPage(List<DraftSummary> items, long totalItem) {
	}

	static final List<String> TOKEN_INVALID_CODES = List.of("40001", "42001", "40014");

	/** GET /cgi-bin/token（verify/草稿写入共用；40125 invalid appsecret 等按 code 透出）。 */
	public Mono<AccessToken> fetchAccessToken(String appId, String appSecret) {
		return client.get()
				.uri(uriBuilder -> uriBuilder.path("/cgi-bin/token").queryParam("grant_type", "client_credential")
						.queryParam("appid", appId).queryParam("secret", appSecret).build())
				.accept(MediaType.APPLICATION_JSON).retrieve()
				.onStatus(HttpStatusCode::isError,
						response -> response.bodyToMono(String.class).defaultIfEmpty("")
								.map(body -> new WechatApiException(safeCode(body), "微信接口调用失败")))
				.bodyToMono(Map.class).timeout(Duration.ofSeconds(15)).flatMap(this::parseToken);
	}

	/** POST /cgi-bin/media/uploadimg（正文图——返回 URL 语义，不是 media_id）。 */
	public Mono<UploadedImage> uploadContentImage(String token, byte[] bytes, String filename) {
		return postMultipart("/cgi-bin/media/uploadimg", token, bytes, filename).bodyToMono(Map.class)
				.timeout(Duration.ofSeconds(30)).flatMap(body -> {
					Object url = body.get("url");
					if (url instanceof String value && !value.isBlank()) {
						return Mono.just(new UploadedImage(null, value));
					}
					return Mono.error(new WechatApiException(errcodeOf(body), "正文图片上传未返回 URL"));
				});
	}

	/** POST /cgi-bin/material/add_material?type=image（封面——返回 media_id 语义）。 */
	public Mono<UploadedImage> uploadCoverMaterial(String token, byte[] bytes, String filename) {
		MultipartBodyBuilder form = formOf(bytes, filename);
		form.part("description", "cover");
		return client.post()
				.uri(uriBuilder -> uriBuilder.path("/cgi-bin/material/add_material").queryParam("access_token", token)
						.queryParam("type", "image").build())
				.contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(form.build()))
				.retrieve()
				.onStatus(HttpStatusCode::isError,
						response -> response.bodyToMono(String.class).defaultIfEmpty("")
								.map(body -> new WechatApiException(safeCode(body), "封面上传失败")))
				.bodyToMono(Map.class).timeout(Duration.ofSeconds(30)).flatMap(body -> {
					Object mediaId = body.get("media_id");
					if (mediaId instanceof String value && !value.isBlank()) {
						Object url = body.get("url");
						return Mono.just(new UploadedImage(value, url instanceof String u ? u : null));
					}
					return Mono.error(new WechatApiException(errcodeOf(body), "封面上传未返回 media_id"));
				});
	}

	/** POST /cgi-bin/draft/add（唯一派发点——调用方负责持久 submitting 标记先行，且不自动重试）。 */
	public Mono<String> addDraft(String token, WechatArticle article) {
		Map<String, Object> articleBody = new java.util.LinkedHashMap<>();
		articleBody.put("title", article.title());
		articleBody.put("content", article.content());
		articleBody.put("thumb_media_id", article.thumbMediaId());
		articleBody.put("need_open_comment", article.needOpenComment() == 1 ? 1 : 0);
		articleBody.put("only_fans_can_comment", article.onlyFansCanComment() == 1 ? 1 : 0);
		if (article.digest() != null && !article.digest().isBlank()) {
			articleBody.put("digest", article.digest());
		}
		if (article.author() != null && !article.author().isBlank()) {
			articleBody.put("author", article.author());
		}
		if (article.contentSourceUrl() != null && !article.contentSourceUrl().isBlank()) {
			articleBody.put("content_source_url", article.contentSourceUrl());
		}
		return postJson("/cgi-bin/draft/add", token, Map.of("articles", List.of(articleBody))).bodyToMono(Map.class)
				.timeout(Duration.ofSeconds(30)).flatMap(body -> {
					Object mediaId = body.get("media_id");
					if (mediaId instanceof String value && !value.isBlank()) {
						return Mono.just(value);
					}
					return Mono.error(new WechatApiException(errcodeOf(body), "草稿写入未返回 media_id"));
				});
	}

	/** POST /cgi-bin/draft/get（只读回读；news 单篇取第一项）。 */
	@SuppressWarnings("unchecked")
	public Mono<DraftArticle> getDraft(String token, String mediaId) {
		return postJson("/cgi-bin/draft/get", token, Map.of("media_id", mediaId)).bodyToMono(Map.class)
				.timeout(Duration.ofSeconds(30)).flatMap(body -> {
					if (body.get("news_item") instanceof List<?> items && !items.isEmpty()
							&& items.get(0) instanceof Map<?, ?> first) {
						Map<String, Object> item = (Map<String, Object>) first;
						return Mono.just(new DraftArticle(text(item.get("title")), text(item.get("content")),
								text(item.get("digest")), text(item.get("thumb_media_id")), text(item.get("author")),
								text(item.get("content_source_url"))));
					}
					return Mono.error(new WechatApiException(errcodeOf(body), "草稿读取内容为空"));
				});
	}

	/** POST /cgi-bin/draft/batchget（候选搜索；一页 count 条，offset 翻页；响应上限 5s）。 */
	@SuppressWarnings("unchecked")
	public Mono<BatchPage> batchGetDrafts(String token, int offset, int count) {
		return postJson("/cgi-bin/draft/batchget", token, Map.of("offset", offset, "count", count, "no_content", 0))
				.bodyToMono(Map.class).timeout(Duration.ofSeconds(5)).flatMap(body -> {
					List<DraftSummary> items = new ArrayList<>();
					if (body.get("item") instanceof List<?> raw) {
						for (Object entry : raw) {
							if (entry instanceof Map<?, ?> map) {
								Map<String, Object> item = (Map<String, Object>) map;
								Map<String, Object> article = summaryArticle(item);
								items.add(new DraftSummary(text(item.get("media_id")), text(article.get("title")),
										text(item.get("update_time")), text(article.get("content"))));
							}
						}
					}
					long total = body.get("total_item") instanceof Number number ? number.longValue() : items.size();
					return Mono.just(new BatchPage(items, total));
				});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> summaryArticle(Map<String, Object> item) {
		if (item.get("content") instanceof Map<?, ?> content && content.get("news_item") instanceof List<?> news
				&& !news.isEmpty() && news.get(0) instanceof Map<?, ?> first) {
			return (Map<String, Object>) first;
		}
		return Map.of();
	}

	private org.springframework.web.reactive.function.client.WebClient.ResponseSpec postMultipart(String path,
			String token, byte[] bytes, String filename) {
		MultipartBodyBuilder form = formOf(bytes, filename);
		return client.post().uri(uriBuilder -> uriBuilder.path(path).queryParam("access_token", token).build())
				.contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(form.build()))
				.retrieve().onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("").map(body -> new WechatApiException(safeCode(body), "图片上传失败")));
	}

	private static MultipartBodyBuilder formOf(byte[] bytes, String filename) {
		MultipartBodyBuilder form = new MultipartBodyBuilder();
		form.part("media", new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		return form;
	}

	private org.springframework.web.reactive.function.client.WebClient.ResponseSpec postJson(String path, String token,
			Object body) {
		return client.post().uri(uriBuilder -> uriBuilder.path(path).queryParam("access_token", token).build())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()
				.onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class).defaultIfEmpty("")
						.map(raw -> new WechatApiException(safeCode(raw), "微信接口调用失败")));
	}

	private Mono<AccessToken> parseToken(Map<?, ?> body) {
		Object token = body.get("access_token");
		Object errcode = body.get("errcode");
		if (token instanceof String value && !value.isBlank()) {
			int expiresIn = body.get("expires_in") instanceof Number number ? number.intValue() : 7200;
			return Mono.just(new AccessToken(value, expiresIn));
		}
		return Mono.error(new WechatApiException(String.valueOf(errcode == null ? "unknown" : errcode), "微信凭据校验未通过"));
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static String errcodeOf(Map<?, ?> body) {
		Object errcode = body.get("errcode");
		return String.valueOf(errcode == null ? "unknown" : errcode);
	}

	static String safeCode(String body) {
		if (body == null) {
			return "network";
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"errcode\"\\s*:\\s*(\\d+)").matcher(body);
		return matcher.find() ? matcher.group(1) : "network";
	}

	static class WechatApiException extends RuntimeException {

		private final String code;

		WechatApiException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}

		public boolean tokenInvalid() {
			return TOKEN_INVALID_CODES.contains(code);
		}
	}
}
