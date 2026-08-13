package com.grassland.intelligence.videoproduction;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Inbox and task lookup for provider callbacks. */
@Component
public class VideoProviderWebhookRepository {
    private final DatabaseClient db;

    public VideoProviderWebhookRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Boolean> claim(String provider, String eventId) {
        return db.sql("""
                INSERT INTO video_provider_webhook_inbox(provider, event_id)
                VALUES (:provider, :eventId) ON CONFLICT DO NOTHING
                """)
                .bind("provider", provider).bind("eventId", eventId)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Void> release(String provider, String eventId) {
        return db.sql("DELETE FROM video_provider_webhook_inbox WHERE provider=:provider AND event_id=:eventId")
                .bind("provider", provider).bind("eventId", eventId).then();
    }

    public Mono<VideoGenerationJob> findJob(String provider, String providerTaskId) {
        return db.sql("SELECT " + VideoGenerationJobRepository.columns()
                + " FROM video_generation_job WHERE provider=:provider AND provider_task_id=:taskId")
                .bind("provider", provider).bind("taskId", providerTaskId)
                .map(VideoGenerationJobRepository::mapRow).one();
    }
}
