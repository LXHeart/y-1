package com.grassland.identity.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 只读 legacy connect-pg-simple session 表，解析 sess.user.id。
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
