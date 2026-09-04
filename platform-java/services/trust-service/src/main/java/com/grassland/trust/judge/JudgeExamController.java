package com.grassland.trust.judge;

import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 准入考试用户端入口（任务书 #74 卡 E，拍板 D4）。
 *
 * <ul>
 *   <li>GET /api/trust/judges/exam — 出题：active 题库随机 N=10（响应<b>不含</b>答案）。</li>
 *   <li>POST /api/trust/judges/exam — 交卷 {@code {"answers": {"<questionId>": <choiceIndex>}}}：
 *       ≥80 分及格 → exam_passed_at + 见习标记（Lv4）+ audit；不及格冷却 24h，attempt 留痕。</li>
 * </ul>
 *
 * <p>仅推荐官可考（审判官只从推荐官池产生）；Lv5 报名直入 full 不需要考试。
 */
@RestController
public class JudgeExamController {

    private final TrustCallerResolver callers;
    private final JudgeExamService examService;

    public JudgeExamController(TrustCallerResolver callers, JudgeExamService examService) {
        this.callers = callers;
        this.examService = examService;
    }

    @GetMapping("/api/trust/judges/exam")
    public Mono<ResponseEntity<Map<String, Object>>> draw(ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(TrustCallerResolver.Caller::isRecommender)
                .switchIfEmpty(fail(403, "仅推荐官可参加审判官准入考试"))
                .then(examService.draw())
                .map(questions -> ResponseEntity.ok(Map.of("success", true,
                        "data", Map.of("questions", questions))));
    }

    @PostMapping("/api/trust/judges/exam")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@RequestBody(required = false) SubmitAnswersRequest body,
                                                            ServerHttpRequest request) {
        String answers = body == null ? null : body.answers();
        return callers.requireMerchantOrRecommender(request)
                .filter(TrustCallerResolver.Caller::isRecommender)
                .switchIfEmpty(fail(403, "仅推荐官可参加审判官准入考试"))
                .flatMap(caller -> examService.grade(caller.accountId(), answers))
                .map(result -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("score", result.score());
                    data.put("passed", result.passed());
                    data.put("admissionLevel", result.admissionLevel());
                    data.put("cooldownUntil", result.cooldownUntil() == null ? null : result.cooldownUntil().toString());
                    return ResponseEntity.ok(Map.of("success", true, "data", data));
                });
    }

    /** 交卷请求体：answers = {questionId: choiceIndex}。 */
    public record SubmitAnswersRequest(String answers) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
