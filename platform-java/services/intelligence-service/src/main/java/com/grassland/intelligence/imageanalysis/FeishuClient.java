package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 飞书开放平台 HTTP 客户端（草场 intelligence Slice 6）。移植 legacy
 * {@code feishu-export.service.ts} 的 5 个 API 调用。 base URL 硬编码
 * {@code https://open.feishu.cn}（与 legacy 一致，不可配）；超时
 * {@code feishu.export.api-timeout-ms}（默认 30s）。 错误按 legacy
 * {@code validateFeishuResponse} 映射：上传图片遇 code 99991672 → 固定权限消息；其余 code!=0
 * →「飞书{op}失败」。 飞书以 body 中 {@code code} 判定成败（非 HTTP 状态），与 legacy 一致——故不检查 HTTP
 * 状态，只解析 body code。
 */
@Component
public class FeishuClient {

	static final String BASE_URL = "https://open.feishu.cn";
	static final String DOCUMENT_URL_PREFIX = "https://bytedance.feishu.cn/docx/";
	static final int IMAGE_UPLOAD_PERMISSION_CODE = 99991672;
	static final String IMAGE_UPLOAD_PERMISSION_MESSAGE = "飞书图片上传失败：当前飞书应用缺少图片上传权限，请在飞书开放平台为该应用开通 docs:document.media:upload 权限后重试";

	private final WebClient client;
	private final ObjectMapper mapper = new ObjectMapper();

	public FeishuClient(@Value("${feishu.export.api-timeout-ms:30000}") long timeoutMs,
			com.grassland.intelligence.ai.DnsPinningResolver dnsPinning) {
		// GL-P3-AI-001 尾巴（覆盖扩展）：固定运营域名钉扎——创建时解析一次，连接期不走系统 DNS
		this.client = com.grassland.intelligence.ai.PinnedOutboundClients.forFixedHost(FeishuClient.class, BASE_URL,
				dnsPinning, Duration.ofMillis(Math.max(1, timeoutMs)), 4 * 1024 * 1024);
	}

	public Mono<String> tenantAccessToken(String appId, String appSecret) {
		return client.post().uri("/open-apis/auth/v3/tenant_access_token/internal")
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("app_id", appId, "app_secret", appSecret))
				.exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
				.map(body -> parseToken(body, "获取访问凭证")).onErrorMap(FeishuClient::wrap);
	}

	public Mono<String> createDocument(String token, String title, String folderToken) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		if (folderToken != null && !folderToken.isBlank()) {
			body.put("folder_token", folderToken);
		}
		return postJson("/open-apis/docx/v1/documents", token, body, "创建文档")
				.map(node -> nonEmpty(node.path("data").path("document").path("document_id").asText(), "创建文档",
						"document_id"));
	}

	/** 返回首个子块的 block_id（图片占位块用）。 */
	public Mono<String> appendBlocks(String token, String documentId, List<Map<String, Object>> blocks) {
		return postJson("/open-apis/docx/v1/documents/" + documentId + "/blocks/" + documentId + "/children", token,
				Map.of("children", blocks), "写入文档内容")
				.map(node -> nonEmpty(node.path("data").path("children").path(0).path("block_id").asText(), "写入文档内容",
						"block_id"));
	}

	public Mono<String> uploadMedia(String token, FeishuUploadImage image, String parentBlockId) {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("file_name", image.fileName());
		form.add("parent_type", "docx_image");
		form.add("parent_node", parentBlockId);
		form.add("size", String.valueOf(image.bytes().length));
		form.add("file", namedResource(image));

		return client.post().uri("/open-apis/drive/v1/medias/upload_all").header("Authorization", "Bearer " + token)
				.contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(form))
				.exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
				.map(body -> parseUpload(body, "上传图片")).onErrorMap(FeishuClient::wrap);
	}

	public Mono<Void> replaceImageBlock(String token, String documentId, String blockId, String fileToken) {
		return patchJson("/open-apis/docx/v1/documents/" + documentId + "/blocks/" + blockId, token,
				Map.of("replace_image", Map.of("token", fileToken)), "设置图片内容").then();
	}

	private Mono<JsonNode> postJson(String uri, String token, Object body, String operation) {
		return client.post().uri(uri).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
				.map(raw -> validate(raw, operation)).onErrorMap(FeishuClient::wrap);
	}

	private Mono<JsonNode> patchJson(String uri, String token, Object body, String operation) {
		return client.patch().uri(uri).header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
				.map(raw -> validate(raw, operation)).onErrorMap(FeishuClient::wrap);
	}

	private String parseToken(String body, String operation) {
		JsonNode node = validate(body, operation);
		return nonEmpty(node.path("tenant_access_token").asText(), operation, "tenant_access_token");
	}

	private String parseUpload(String body, String operation) {
		JsonNode node = validate(body, operation);
		return nonEmpty(node.path("data").path("file_token").asText(), operation, "file_token");
	}

	private static String nonEmpty(String value, String operation, String field) {
		if (value == null || value.isEmpty()) {
			throw new IntelligenceException(502, "飞书" + operation + "失败: 缺少 " + field);
		}
		return value;
	}

	private JsonNode validate(String body, String operation) {
		if (body == null || body.isBlank()) {
			throw new IntelligenceException(502, "飞书" + operation + "失败: 空响应");
		}
		try {
			JsonNode node = mapper.readTree(body);
			int code = node.path("code").asInt(-1);
			if (code == 0) {
				return node;
			}
			if ("上传图片".equals(operation) && code == IMAGE_UPLOAD_PERMISSION_CODE) {
				throw new IntelligenceException(502, IMAGE_UPLOAD_PERMISSION_MESSAGE);
			}
			String msg = node.path("msg").asText("");
			throw new IntelligenceException(502, "飞书" + operation + "失败: " + msg + " (code: " + code + ")");
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw new IntelligenceException(502, "飞书" + operation + "失败: 响应解析失败");
		}
	}

	/** 非 {@link IntelligenceException} 的上游异常（超时/连接等）统一包装为 502。 */
	private static Throwable wrap(Throwable error) {
		return error instanceof IntelligenceException ? error : new IntelligenceException(502, "飞书请求失败");
	}

	/** multipart file part：ByteArrayResource 携带文件名（BodyInserters 据此识别为文件 part）。 */
	private static ByteArrayResource namedResource(FeishuUploadImage image) {
		return new ByteArrayResource(image.bytes(), image.fileName()) {
			@Override
			public String getFilename() {
				return image.fileName();
			}
		};
	}

	/** 飞书媒体上传图片（携带字节数据、MIME、文件名）。 */
	public record FeishuUploadImage(byte[] bytes, String mimeType, String fileName) {
	}
}
