package com.grassland.finance.credits;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 内部积分端点鉴权 WebFilter（GL-P3-AI-001 下属切片）。移植 legacy {@code server/src/lib/internal-auth.ts}：
 * 仅匹配 {@code /internal/credits/**}，对其他路径透传。
 *
 * <ul>
 *   <li>{@code rejectForwardedRequest}：带任一 {@code X-Forwarded-*}/{@code Forwarded} 头 → 404（纵深防御，
 *       内部端点只应由容器网络内服务直连，穿公网代理到达即拒）；</li>
 *   <li>{@code requireInternalKey}：{@code credits.internal-key}（env {@code INTERNAL_API_KEY}）未配 → 503 fail-closed；
 *       {@code X-Internal-Key} 不匹配 → 401。</li>
 * </ul>
 *
 * <p>intelligence {@code FinanceCreditsClient} 与 legacy Express 代理均以此共享密钥鉴权（服务间断言留待
 * Express tail 退役后切换）。调用方按 HTTP 状态码判结果，错误体仅作排障。
 */
@Component
public class CreditsInternalAuthFilter implements WebFilter {

    private static final String[] FORWARDED_HEADERS = {"x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "forwarded"};

    private final String internalKey;

    public CreditsInternalAuthFilter(@Value("${credits.internal-key:}") String internalKey) {
        this.internalKey = internalKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/internal/credits/")) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();
        for (String name : FORWARDED_HEADERS) {
            if (headers.getFirst(name) != null) {
                return writeError(exchange.getResponse(), HttpStatus.NOT_FOUND, "内部接口不可经代理访问");
            }
        }
        if (internalKey == null || internalKey.isBlank()) {
            return writeError(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, "内部接口未配置密钥");
        }
        String provided = headers.getFirst("X-Internal-Key");
        if (provided == null || !provided.equals(internalKey)) {
            return writeError(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "内部接口鉴权失败");
        }
        return chain.filter(exchange);
    }

    private static Mono<Void> writeError(ServerHttpResponse response, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"success\":false,\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
