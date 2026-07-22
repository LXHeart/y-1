package com.grassland.identity.auth;

import com.grassland.identity.notify.SmtpMailSender;
import com.grassland.identity.security.EmailVerificationService;
import com.grassland.identity.session.SessionService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class SendCodeController {
    private final SessionService sessionService;
    private final EmailVerificationService codeService;
    private final SmtpMailSender mailSender;
    private final String cookieName;

    public SendCodeController(SessionService sessionService, EmailVerificationService codeService,
                              SmtpMailSender mailSender,
                              @org.springframework.beans.factory.annotation.Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.sessionService = sessionService; this.codeService = codeService;
        this.mailSender = mailSender; this.cookieName = cookieName;
    }

    @PostMapping(value = "/api/auth/send-code", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> sendCode(@RequestBody Map<String, String> body, ServerHttpRequest request) {
        String email = body.get("email");
        String captchaCode = body.get("captchaCode");
        if (email == null || email.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            return Mono.just(error(400, "\u8bf7\u586b\u5199\u90ae\u7bb1\u548c\u9a8c\u8bc1\u7801"));
        }
        String cookie = request.getCookies().getFirst(cookieName) != null
            ? request.getCookies().getFirst(cookieName).getValue() : null;
        return sessionService.consumeCaptcha(cookie)
            .flatMap(optCap -> {
                if (optCap.isEmpty() || !optCap.get().text().equalsIgnoreCase(captchaCode.trim())) {
                    return Mono.just(error(400, "\u56fe\u5f62\u9a8c\u8bc1\u7801\u9519\u8bef\u6216\u5df2\u8fc7\u671f"));
                }
                if (!mailSender.isConfigured()) {
                    return Mono.just(error(500, "\u90ae\u4ef6\u670d\u52a1\u672a\u914d\u7f6e"));
                }
                return codeService.createCode(email)
                    .flatMap(code -> { mailSender.sendVerificationCode(email.trim().toLowerCase(), code); return Mono.just(ok()); })
                    .onErrorResume(e -> Mono.just(error(400, e.getMessage())));
            });
    }

    private ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("sent", true)));
    }
    private ResponseEntity<Map<String, Object>> error(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", msg));
    }
}
