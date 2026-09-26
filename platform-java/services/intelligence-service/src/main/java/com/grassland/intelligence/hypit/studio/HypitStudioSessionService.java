package com.grassland.intelligence.hypit.studio;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Studio 会话服务（任务书 #107-2 C107-12 / 契约 §6.2 POST P/studio-sessions）。
 *
 * <p>
 * owner 校验后经 sidecar {@code studio.session} 域绑定会话：一次性 ticket URL、 同 Run
 * 默认复用活跃会话、readOnly 会话拒绝写回。URL 策略（base path、Origin、 WS 同源）由 B 侧
 * url-policy/proxy 强制，Java 只持有会话事实，绝不回显 token。
 */
@Service
public class HypitStudioSessionService {

	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitProjectRepository projects;

	public HypitStudioSessionService(HypitProperties properties, HypitSidecarClient sidecar,
			HypitProjectRepository projects) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.projects = projects;
	}

	public Mono<StudioSessionView> openSession(String accountId, UUID projectId, UUID requestId, String runFile,
			Long revision, boolean readOnly) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(project -> {
					if (!properties.enabled() || !sidecar.configured()) {
						return Mono.error(HypitAccessService.unavailable("Studio 会话"));
					}
					Map<String, Object> payload = new HashMap<>();
					payload.put("projectId", projectId.toString());
					payload.put("runFile", runFile == null || runFile.isBlank() ? "main.svrun" : runFile);
					if (revision != null) {
						payload.put("revision", revision);
					}
					payload.put("readOnly", readOnly);
					return sidecar.commandAsync("studio-session-" + UUID.randomUUID(), "studio.session", payload)
							.timeout(SESSION_TIMEOUT).map(command -> {
								if (command.result() == null) {
									throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
											"hypit_engine_error", "studio.session 失败");
								}
								@SuppressWarnings("unchecked")
								Map<String, Object> result = (Map<String, Object>) command.result();
								return new StudioSessionView(String.valueOf(result.get("sessionId")),
										String.valueOf(result.get("ticketUrl")),
										String.valueOf(result.get("expiresAt")),
										revision == null ? project.revision() : revision, readOnly,
										Boolean.TRUE.equals(result.get("reused")));
							});
				});
	}

	public record StudioSessionView(String sessionId, String ticketUrl, String expiresAt, long revision,
			boolean readOnly, boolean reused) {
	}
}
