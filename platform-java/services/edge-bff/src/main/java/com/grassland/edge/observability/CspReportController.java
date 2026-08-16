package com.grassland.edge.observability;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * CSP 违规报告收集端点（CSP report-only → 强制的观察通道）。
 *
 * <p>浏览器对 {@code report-uri /csp-report} 的上报是无登录态 POST（无 Cookie、可能无 Origin），
 * 经 nginx 公网入口反代到本端点；只做采样日志（document-uri / violated-directive / blocked-uri /
 * 行列号），不做任何回显或持久化——报告内容本身不可信，绝不解析执行。始终 204，让浏览器静默。
 *
 * <p>采集口径：report-only 阶段看违规量收敛后，经 {@code CSP_MODE=enforce} 切强制头；切换后同一端点
 * 继续收强制模式的违规报告。
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);
    private static final int MAX_REPORT_BYTES = 64 * 1024;

    @PostMapping(path = "/api/csp-report",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/csp-report"})
    public Mono<Void> report(ServerWebExchange exchange) {
        return exchange.getRequest().getBody()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .reduce(new StringBuilder(), (builder, chunk) -> {
                    if (builder.length() < MAX_REPORT_BYTES) {
                        builder.append(chunk, 0, Math.min(chunk.length(), MAX_REPORT_BYTES - builder.length()));
                    }
                    return builder;
                })
                .doOnNext(body -> log.warn("[csp-report] body={}",
                        truncate(compactWhitespace(body.toString()), 1024)))
                .then(Mono.fromRunnable(() -> exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT)));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String compactWhitespace(String value) {
        return value.replaceAll("\\s+", " ");
    }
}
