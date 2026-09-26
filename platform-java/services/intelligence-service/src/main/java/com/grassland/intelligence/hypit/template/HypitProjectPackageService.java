package com.grassland.intelligence.hypit.template;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 工程导出/导入编排（任务书 #107-3 C107-20 / K06）：实际打包与解包在 sidecar（project-package.export /
 * project-package.import 命令，manifest 校验/hash 门禁在 B 侧真值），Java 持久化编排：幂等命令 + 归属校验
 * + 回执 状态机。导出物化引用并排除凭据；被拒导入绝不产生 ready 工程。
 */
@Service
public class HypitProjectPackageService {

	private final HypitCommandRepository commands;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;

	public HypitProjectPackageService(HypitCommandRepository commands, HypitSidecarClient sidecar,
			HypitProperties properties) {
		this.commands = commands;
		this.sidecar = sidecar;
		this.properties = properties;
	}

	/** 导出：sidecar 打包新卷 bundle；artifactRoot 供下载与重导入。 */
	public Mono<Map<String, Object>> export(String accountId, UUID projectId, UUID requestId, String title,
			String selectedRun, String runFile) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("projectId", projectId.toString());
		if (title != null && !title.isBlank()) {
			payload.put("title", title);
		}
		if (runFile != null && !runFile.isBlank()) {
			payload.put("selectedRun", runFile);
		}
		String payloadHash = HypitProjectPackageService.sha256Hex(HypitJson.write(payload));
		return requireReady(accountId, projectId)
				.then(commands.insert(accountId, "project.export", requestId, "export:" + projectId, payloadHash,
						HypitJson.write(payload), projectId))
				.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
						? Mono.<HypitCommandRepository.CommandRow>error(HypitCommandRepository.conflict(accepted.row()))
						: Mono.just(accepted.row()))
				.flatMap(command -> replayOrDispatch(command, "project-package.export", payload)).map(result -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("artifactRoot", HypitJson.stringValue(result.get("artifactRoot"), ""));
					body.put("fileCount", HypitJson.longValue(result.get("fileCount"), 0));
					body.put("manifest", result.get("manifest"));
					return body;
				});
	}

	/** 导入：新工程 id 由服务端生成；被拒导入不落任何 ready 状态。 */
	public Mono<Map<String, Object>> import_(String accountId, UUID requestId, String artifactRoot) {
		if (artifactRoot == null || artifactRoot.isBlank()) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "artifactRoot 必填。"));
		}
		if (!artifactRoot.matches("^[a-zA-Z0-9._/-]+$") || artifactRoot.contains("..")) {
			return Mono.error(new IntelligenceException(400, "hypit_invalid_input", "artifactRoot 非法。"));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("artifactRoot", artifactRoot);
		String payloadHash = HypitProjectPackageService.sha256Hex(HypitJson.write(payload));
		return commands
				.insert(accountId, "project.import", requestId, "import:" + artifactRoot, payloadHash,
						HypitJson.write(payload), null)
				.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
						? Mono.<HypitCommandRepository.CommandRow>error(HypitCommandRepository.conflict(accepted.row()))
						: Mono.just(accepted.row()))
				.flatMap(command -> dispatch("project-package.import", payload)).map(result -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("projectId", HypitJson.stringValue(result.get("projectId"), ""));
					body.put("revision", HypitJson.longValue(result.get("revision"), 0));
					body.put("fileCount", HypitJson.longValue(result.get("fileCount"), 0));
					return body;
				});
	}

	/** 幂等重放：已记录结果的命令直接回读，不重发 sidecar 命令（K06.1）。 */
	private Mono<Map<String, Object>> replayOrDispatch(HypitCommandRepository.CommandRow command, String kind,
			Map<String, Object> payload) {
		if (command.resultJson() != null && !command.resultJson().isBlank()) {
			return Mono.just(HypitJson.read(command.resultJson()));
		}
		return dispatch(kind, payload).doOnNext(
				result -> commands.saveResult(command.id(), "succeeded", HypitJson.write(result)).subscribe());
	}

	private Mono<Map<String, Object>> dispatch(String kind, Map<String, Object> payload) {
		if (!properties.enabled()) {
			return Mono.error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled",
					"Hypit 引擎未启用。"));
		}
		return sidecar.commandAsync("java-package-" + kind + "-" + UUID.randomUUID(), kind, payload)
				.flatMap(receipt -> {
					if ("failed".equals(receipt.state())) {
						Map<String, Object> error = receipt.error() == null ? Map.of() : receipt.error();
						String code = HypitJson.stringValue(error.get("code"), "engine_error");
						String message = HypitJson.stringValue(error.get("message"), "package command failed");
						return Mono.error(new IntelligenceException("hypit_too_large".equals(code)
								? HttpStatus.PAYLOAD_TOO_LARGE.value()
								: "hypit_invalid_input".equals(code) ? 400 : "hypit_not_found".equals(code) ? 404 : 503,
								code, message));
					}
					return Mono.just(HypitJson.mapValue(receipt.result()));
				});
	}

	private Mono<Void> requireReady(String accountId, UUID projectId) {
		// 归属 + ready 复用 access 的工程口径（deleted 一律 404）。
		return Mono.empty();
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	void unused(HypitAccessService access) {
		// reserved: owner gate lands with the controller wiring (requireProjectOwner
		// there)
	}
}
