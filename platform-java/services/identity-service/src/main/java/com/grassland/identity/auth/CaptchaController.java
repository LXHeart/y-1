package com.grassland.identity.auth;

import com.grassland.identity.security.CaptchaGenerator;
import com.grassland.identity.session.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class CaptchaController {
    private final CaptchaGenerator generator;
    private final SessionService sessionService;
    private final String cookieName;

    public CaptchaController(CaptchaGenerator generator, SessionService sessionService,
                             @org.springframework.beans.factory.annotation.Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.generator = generator; this.sessionService = sessionService; this.cookieName = cookieName;
    }

    @GetMapping("/api/auth/captcha")
    public Mono<ResponseEntity<byte[]>> captcha(ServerHttpRequest request) {
        String text = generator.generateText();
        String svg = generator.generateSvg(text);
        String cookie = extractCookie(request);
        return sessionService.generateAndStoreCaptcha(cookie, text, svg)
            .map(result -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/svg+xml")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
                if (result.setCookieHeader() != null) {
                    builder.header(HttpHeaders.SET_COOKIE, result.setCookieHeader());
                }
                return builder.body(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
    }

    private String extractCookie(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst(cookieName);
        return cookie != null ? cookie.getValue() : null;
    }
}
