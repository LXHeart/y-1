package com.grassland.intelligence.hypit.studio;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Studio 会话服务（任务书 #107-2 C107-12 声明；107-fix-2 C107F2-19 重写）。
 *
 * <p>
 * F16 修复：不再只建内存票据——①先在 PG（V92 hypit_session）登记 starting 行， ②经 sidecar
 * {@code studio.session} 以该 id 启动真实 Studio 子进程（broker ready
 * 探测通过才回执），③markActive 后签 60 秒一次性票据（nonce 单槽，HMAC 用途 {@code ticket|} 与断言
 * {@code assertion|} 分开）。失败标 failed 不留 active 假会话。 断言签发/核销在
 * {@link HypitSessionAccessService}；复用键与撤销矩阵由 C107F2-20 收口。
 */
@Service
public class HypitStudioSessionService {

	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);
	/** §6.9：会话绝对 TTL 3600s；票据 60s 一次性。 */
	static final Duration SESSION_TTL = Duration.ofSeconds(3600);
	static final Duration TICKET_TTL = Duration.ofSeconds(60);

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitProjectRepository projects;
	private final HypitSessionRepository sessions;

	public HypitStudioSessionService(HypitProperties properties, HypitSidecarClient sidecar,
			HypitProjectRepository projects, HypitSessionRepository sessions) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.projects = projects;
		this.sessions = sessions;
	}

	public Mono<StudioSessionView> openSession(String accountId, UUID projectId, UUID requestId, String runFile,
			Long revision, boolean readOnly) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(project -> {
					if (!properties.enabled() || !sidecar.configured()) {
						return Mono.error(HypitAccessService.unavailable("Studio 会话"));
					}
					if (properties.sessionTicketSecret().length() < 32
							|| properties.sessionAssertionSecret().length() < 32) {
						return Mono.error(HypitAccessService.unavailable("Studio 会话密钥未配置"));
					}
					long boundRevision = revision == null ? project.revision() : revision;
					if (boundRevision <= 0) {
						return Mono.error(new IntelligenceException(409, "hypit_state_conflict", "工程尚无可用修订，先完成初始化。"));
					}
					String effectiveRun = runFile == null || runFile.isBlank() ? "main.svrun" : runFile;
					// C107F2-20 复用键 owner/project/kind/run/revision/readOnly 全等才复用；
					// 任何权限或版本变化都是新会话（不复用旧可写实例）。
					return sessions.findReusable(accountId, projectId, "studio", effectiveRun, boundRevision, readOnly)
							.flatMap(reusable -> reissueTicket(reusable, true))
							.switchIfEmpty(Mono.defer(() -> createSession(accountId, projectId, requestId, effectiveRun,
									boundRevision, readOnly)));
				});
	}

	/** 复用：换发新票（nonce 单槽替换，旧票立即失效），reused=true。 */
	private Mono<StudioSessionView> reissueTicket(HypitSessionRepository.SessionRow reusable, boolean reused) {
		return issueTicket(reusable)
				.map(ticket -> new StudioSessionView(reusable.id(), ticket.ticketUrl(), ticket.expiresAt().toString(),
						reusable.revision(), reusable.readOnly(), reused, ticket.messageNonce()));
	}

	/** 新建：登记 PG（starting）→ broker 启动 → markActive → 签票。 */
	private Mono<StudioSessionView> createSession(String accountId, UUID projectId, UUID requestId, String runFile,
			long revision, boolean readOnly) {
		String sessionId = "sess-" + UUID.randomUUID().toString().replace("-", "");
		Instant expiresAt = Instant.now().plus(SESSION_TTL);
		return sessions
				.insertStarting(sessionId, projectId, accountId, "studio", runFile, revision, readOnly, expiresAt)
				.flatMap(row -> launchAndActivate(accountId, row, requestId)
						.onErrorResume(error -> sessions.markFailed(sessionId)
								.then(Mono.error(error instanceof IntelligenceException intelligenceError
										? intelligenceError
										: new IntelligenceException(HttpStatus.BAD_GATEWAY.value(),
												"hypit_engine_error", "studio.session 失败")))));
	}

	/**
	 * C107F2-20：关闭会话（幂等）——PG 置 closed 并让 broker 终止子进程/WS。 未知或已终态会话同样返回
	 * closed=true（幂等 200 语义，§6.9）。
	 */
	public Mono<StudioSessionView> closeSession(String accountId, UUID projectId, String sessionId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.then(sessions.findById(sessionId)
						.filter(row -> row.projectId().equals(projectId) && row.accountId().equals(accountId))
						.switchIfEmpty(Mono.empty()))
				.flatMap(row -> sessions.markClosed(sessionId)
						.then(sidecar.commandAsync("studio-close-" + UUID.randomUUID(), "studio.session.close",
								Map.of("sessionId", sessionId)).onErrorResume(error -> Mono.empty()))
						.then(Mono.just(
								new StudioSessionView(sessionId, "", "", row.revision(), row.readOnly(), false, ""))))
				// 未知/非本会话：幂等空 → 200 closed:true（controller 层组装）。
				.switchIfEmpty(Mono.defer(() -> sessions.findById(sessionId)
						.map(row -> new StudioSessionView(sessionId, "", "", row.revision(), row.readOnly(), false, ""))
						.defaultIfEmpty(new StudioSessionView(sessionId, "", "", 0, false, false, ""))));
	}

	/** C107F2-20：撤销属主在工程内全部活跃会话（工程删除/注销入口），返回撤销行数。 */
	public Mono<Long> revokeForOwner(String accountId, UUID projectId) {
		return sessions.revokeAllForOwner(accountId, projectId)
				.flatMap(revoked -> sidecar
						.commandAsync("studio-revoke-" + UUID.randomUUID(), "studio.session.revoke",
								Map.of("projectId", projectId.toString(), "ownerAccountId", accountId))
						.onErrorResume(error -> Mono.empty()).then(Mono.just(revoked)));
	}

	/** ②③ broker 启动（ready 探测）→ markActive → 签一次性票据（nonce 单槽入 PG）。 */
	private Mono<StudioSessionView> launchAndActivate(String accountId, HypitSessionRepository.SessionRow row,
			UUID requestId) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("sessionId", row.id());
		payload.put("projectId", row.projectId().toString());
		payload.put("ownerAccountId", accountId);
		payload.put("runFile", row.runFile());
		payload.put("revision", row.revision());
		payload.put("readOnly", row.readOnly());
		String commandId = "studio-session-" + (requestId == null ? UUID.randomUUID() : requestId);
		return sidecar.commandAsync(commandId, "studio.session", payload).timeout(SESSION_TIMEOUT).flatMap(command -> {
			if (command.result() == null) {
				throw new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"studio.session 失败");
			}
			return sessions.markActive(row.id());
		}).flatMap(active -> issueTicket(active).map(ticket -> new StudioSessionView(active.id(), ticket.ticketUrl(),
				ticket.expiresAt().toString(), active.revision(), active.readOnly(), false, ticket.messageNonce())));
	}

	/** 60 秒一次性票据：sid/exp/nonce + 用途限定 HMAC（ticket|）；nonceHash 单槽入 PG。 */
	private Mono<StudioTicket> issueTicket(HypitSessionRepository.SessionRow row) {
		String nonce = UUID.randomUUID().toString().replace("-", "");
		Instant ticketExpiresAt = Instant.now().plus(TICKET_TTL);
		String payload = row.id() + "." + ticketExpiresAt.toEpochMilli() + "." + nonce;
		String ticket = payload + "." + hmac("ticket|" + payload);
		String nonceHash = sha256Hex(nonce);
		String ticketUrl = "/studio/" + row.id() + "/?ticket=" + ticket;
		return sessions.replaceTicket(row.id(), nonceHash, ticketExpiresAt).map(updated -> new StudioTicket(ticket,
				ticketUrl, ticketExpiresAt, UUID.randomUUID().toString().replace("-", "")));
	}

	private String hmac(String payloadWithPurpose) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(
					new SecretKeySpec(properties.sessionTicketSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return hex(mac.doFinal(payloadWithPurpose.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("studio ticket signing failed", error);
		}
	}

	/** 票据 nonce 哈希（access 核销侧共用，C107F2-19）。 */
	public static String sha256Hex(String value) {
		try {
			return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("sha-256 unavailable", error);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			out.append(String.format("%02x", b));
		return out.toString();
	}

	public record StudioTicket(String ticket, String ticketUrl, Instant expiresAt, String messageNonce) {
	}

	public record StudioSessionView(String sessionId, String ticketUrl, String expiresAt, long revision,
			boolean readOnly, boolean reused, String messageNonce) {
	}
}
