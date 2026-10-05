package com.grassland.intelligence.hypit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.mediaplatform.segments.SegmentSpec;
import com.grassland.intelligence.mediaplatform.segments.SharedSegmentRenderer;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Identity comes from the trusted runtime build, never an account/cache key in the request. */
@RestController
public class HypitMediaSegmentController {
    private final HypitProperties properties;
    private final DatabaseClient db;
    private final SharedSegmentRenderer renderer;
    private final ObjectMapper json;
    public HypitMediaSegmentController(HypitProperties properties, DatabaseClient db, SharedSegmentRenderer renderer) {
        this.properties = properties; this.db = db; this.renderer = renderer; this.json = new ObjectMapper();
    }
    private void authenticate(String authorization) {
        if (!properties.enabled() || properties.internalToken().length() < 32) throw new IntelligenceException(503,"Hypit disabled");
        if (!("Bearer " + properties.internalToken()).equals(authorization)) throw new IntelligenceException(401,"Invalid internal credential");
    }
    public record Binding(String commandId, String engineBuildId) { }
    @PostMapping(value="/internal/hypit/media-segments/bind", consumes="application/json")
    public Mono<ResponseEntity<Void>> bind(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Binding binding) {
        return Mono.defer(() -> {
            authenticate(authorization);
            java.util.UUID command;
            try { command=java.util.UUID.fromString(binding.commandId()); }
            catch (RuntimeException e) { return Mono.error(new IntelligenceException(400,"Invalid command")); }
            if (binding.engineBuildId()==null || binding.engineBuildId().isBlank() || binding.engineBuildId().length()>256) return Mono.error(new IntelligenceException(400,"Invalid build"));
            return db.sql("UPDATE hypit_build b SET engine_build_id=:engine WHERE command_id=:command "
                    + "AND lifecycle IN ('submitting','active') AND (engine_build_id IS NULL OR engine_build_id=:engine) "
                    + "AND EXISTS (SELECT 1 FROM hypit_project p WHERE p.id=b.project_id AND p.status <> 'deleted')")
                    .bind("engine",binding.engineBuildId()).bind("command",command).fetch().rowsUpdated()
                    .flatMap(count -> count==1 ? Mono.just(ResponseEntity.noContent().<Void>build()) : Mono.error(new IntelligenceException(409,"Build binding unavailable")));
        });
    }
    @PostMapping(value = "/internal/hypit/media-segments/{build}", consumes = "application/octet-stream", produces = "video/mp4")
    public Mono<ResponseEntity<byte[]>> render(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String build, @RequestHeader("X-Segment-Spec") String specification, @RequestBody Flux<DataBuffer> body) {
        return Mono.defer(() -> {
            authenticate(authorization);
            if (build.length() > 256 || specification.length() > 2048) return Mono.error(new IntelligenceException(400,"Invalid segment request"));
            final SegmentSpec spec;
            try { spec = json.readValue(specification, SegmentSpec.class); }
            catch (Exception e) { return Mono.error(new IntelligenceException(400,"Invalid segment specification")); }
            // Also checked on cache hits: deleted projects/cancelled builds cannot read cached bytes.
            return db.sql("SELECT p.account_id FROM hypit_build b JOIN hypit_project p ON p.id=b.project_id "
                    + "WHERE b.engine_build_id=:build AND p.status <> 'deleted' "
                    + "AND b.lifecycle IN ('submitting','active','execution_decided','result_pending') "
                    + "AND NOT EXISTS (SELECT 1 FROM hypit_job j WHERE j.command_id=b.command_id AND j.cancel_requested_at IS NOT NULL)")
                    .bind("build",build).map(row -> row.get("account_id",String.class)).one()
                    .switchIfEmpty(Mono.error(new IntelligenceException(404,"Build unavailable")))
                    .flatMap(account -> DataBufferUtils.join(body, SharedSegmentRenderer.MAX_BYTES)
                        .switchIfEmpty(Mono.error(new IntelligenceException(400,"Empty media")))
                        .map(buffer -> { try { byte[] bytes=new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; } finally { DataBufferUtils.release(buffer); } })
                        .flatMap(bytes -> Mono.fromCallable(() -> renderer.render(account,bytes,spec)).subscribeOn(Schedulers.boundedElastic())))
                    .map(result -> ResponseEntity.ok().header("X-Segment-Cache",result.cacheHit()?"hit":"miss")
                            .header("Cache-Control","no-store").body(result.bytes()));
        });
    }
}
