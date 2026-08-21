package com.grassland.intelligence.douyin;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videorecreation.VideoRecreationTaskRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 抖音视频内容分析（草场 GL-P3-MEDIA-001）：{@code POST /api/douyin/analyze-video}。
 *
 * <p>
 * 需登录；短视频直接交给 Qwen，长视频由 Java FFmpeg 切片并经 analysis-media 回源。 整次分析只扣一次积分。Edge
 * 开关默认开启，显式关闭会 fail-closed 404。
 *
 * <p>
 * body {@code mode} 缺省为内容提取；{@code "recreation"} 走复刻分镜场景分析（PRD §4.4），
 * 短视频单段直连，分段视频暂不支持（422）。任务模式复刻分析走冻结快照执行。
 */
@RestController
public class DouyinAnalyzeController {

	private final IntelligenceCallerResolver resolver;
	private final DouyinAnalysisService service;

	public DouyinAnalyzeController(IntelligenceCallerResolver resolver, DouyinAnalysisService service) {
		this.resolver = resolver;
		this.service = service;
	}

	@PostMapping("/api/douyin/analyze-video")
	public Mono<Map<String, Object>> analyze(@RequestBody(required = false) Map<String, Object> body,
			ServerWebExchange exchange) {
		String proxyVideoUrl = requireProxyVideoUrl(body);
		boolean recreation = recreationMode(body);
		VideoRecreationTaskRequest task = VideoRecreationTaskRequest.parse(body);
		return resolver.resolve(exchange.getRequest())
				.flatMap(caller -> route(proxyVideoUrl, caller, task, recreation, exchange)
						.map(outcome -> Map.<String, Object>of("success", true, "data", outcome.data())));
	}

	private Mono<DouyinAnalysisOutcome> route(String proxyVideoUrl, IntelligenceCallerResolver.Caller caller,
			VideoRecreationTaskRequest task, boolean recreation, ServerWebExchange exchange) {
		if (recreation) {
			return task.taskMode()
					? service.analyzeTaskForRecreation(proxyVideoUrl, caller.accountId(), task, exchange)
					: service.analyzeForRecreation(proxyVideoUrl, caller.accountId(), exchange);
		}
		return task.taskMode()
				? service.analyzeTask(proxyVideoUrl, caller.accountId(), task, exchange)
				: service.analyze(proxyVideoUrl, caller.accountId(), exchange);
	}

	/** 对齐 legacy schema {@code analyzeDouyinVideoRequest}：缺失/空 → 400「缺少视频代理地址」。 */
	private static String requireProxyVideoUrl(Map<String, Object> body) {
		Object value = body == null ? null : body.get("proxyVideoUrl");
		if (!(value instanceof String text) || text.trim().isEmpty()) {
			throw new IntelligenceException(400, "缺少视频代理地址");
		}
		return text.trim();
	}

	/**
	 * {@code mode} 缺省/null → 内容提取；{@code content}/{@code recreation} 显式二选一；其余 →
	 * 400。
	 */
	private static boolean recreationMode(Map<String, Object> body) {
		Object value = body == null ? null : body.get("mode");
		if (value == null) {
			return false;
		}
		if ("recreation".equals(value)) {
			return true;
		}
		if ("content".equals(value)) {
			return false;
		}
		throw new IntelligenceException(400, "分析模式无效");
	}
}
