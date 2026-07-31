package com.grassland.marketplace.taskcatalog;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/**
 * 链接可达性核验（Verification v1）：对 content_url 发 SSRF 安全的 HEAD 请求。
 *
 * <p><b>SSRF 硬防护</b>（{@link LinkUrlGuard}）：拒绝私有/环回/链路本地/多播 IP 字面量与解析到内网的域名；
 * WebClient <b>不跟随重定向</b>（防 redirect-SSRF 绕过）；短超时（默认 3s，{@code marketplace.verification.link-timeout-ms}）。
 *
 * <p>判定：2xx/3xx → passed；404/410 → failed（明确不存在）；其余 4xx/5xx/超时/DNS/SSRF 拒绝 → inconclusive。
 * inconclusive 是保守态——只把「明确 404」判 failed、「干净 2xx/3xx」判 passed，其余留给商家手动决策。
 *
 * <p>{@link LinkUrlGuard#validate} 内含 {@code InetAddress.getAllByName}（阻塞 DNS），故校验跑在 boundedElastic；
 * HTTP 探测本身非阻塞（WebClient）。核验不读附件字节（Stage 4 AI 视觉才读），故无 IDOR 面。
 */
@Component
public class LinkReachabilityChecker {

    /** 单项核验结果：status ∈ passed/failed/inconclusive，detail 为人类可读原因。 */
    public record CheckResult(String status, String detail) {}

    private final WebClient client;

    public LinkReachabilityChecker(@Value("${marketplace.verification.link-timeout-ms:3000}") long timeoutMs) {
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .followRedirect(false);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    public Mono<CheckResult> check(String contentUrl) {
        return Mono.fromCallable(() -> LinkUrlGuard.validate(contentUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::probe)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(new CheckResult("inconclusive", e.getMessage())))
                .onErrorResume(e -> Mono.just(new CheckResult("inconclusive", "不可达或超时")));
    }

    private Mono<CheckResult> probe(URI url) {
        return client.head().uri(url).exchangeToMono(resp -> {
            int s = resp.statusCode().value();
            String status = (s >= 200 && s < 400) ? "passed"
                    : (s == 404 || s == 410) ? "failed"
                    : "inconclusive";
            return resp.releaseBody().then(Mono.just(new CheckResult(status, "HTTP " + s)));
        });
    }
}
