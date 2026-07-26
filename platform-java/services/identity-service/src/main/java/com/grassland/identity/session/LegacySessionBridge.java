package com.grassland.identity.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 读写 legacy connect-pg-simple session 表：解析 sess.user.id、按 sid 删除会话。
 * Strangler 过渡组件，login 切片后 identity 自管会话时退出。
 */
@Component
public class LegacySessionBridge {
    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacySessionBridge(DatabaseClient db) {
        this.db = db;
    }

    public Mono<String> findUserId(String sid) {
        if (sid == null || sid.isBlank()) {
            return Mono.empty();
        }
        return db.sql("SELECT sess FROM session WHERE sid = :sid AND expire > now()")
            .bind("sid", sid)
            .map((row) -> row.get("sess", String.class))
            .one()
            .handle((sessJson, sink) -> {
                String userId = extractUserId(sessJson);
                if (userId != null) {
                    sink.next(userId);
                }
            });
    }

    /**
     * 按 sid 删除登录会话，返回受影响行数。
     *
     * <p>这是「撤销设备」真正让那台设备<b>登出</b>的一步：只删 {@code identity_session} 只是清掉该设备的
     * 活动身份，cookie 仍然有效、照样能继续操作，叫「撤销」名不副实。与 {@code LogoutController}
     * 用的是同一条语句（那里是登出自己，这里是登出自己的另一台设备）。
     */
    public Mono<Long> deleteSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return Mono.just(0L);
        }
        return db.sql("DELETE FROM session WHERE sid = :sid").bind("sid", sid).fetch().rowsUpdated();
    }

    String extractUserId(String sessJson) {
        try {
            JsonNode node = mapper.readTree(sessJson);
            JsonNode userId = node.path("user").path("id");
            return userId.isTextual() ? userId.asText() : null;
        } catch (Exception error) {
            return null;
        }
    }
}
