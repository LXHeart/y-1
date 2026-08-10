package com.grassland.intelligence.mediaplatform;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LocalMediaStreamer {
    public Mono<Void> stream(MediaArtifactStore.Artifact artifact, String range, String disposition, ServerHttpResponse response) {
        try {
            long size = Files.size(artifact.path());
            long start = 0, end = size - 1;
            if (range != null && !range.isBlank()) {
                if (!range.startsWith("bytes=") || range.contains(",")) return unsatisfied(response, size);
                String value = range.substring(6);
                int separator = value.indexOf('-');
                if (separator < 0 || separator != value.lastIndexOf('-')) return unsatisfied(response, size);
                String first = value.substring(0, separator);
                String last = value.substring(separator + 1);
                if (first.isBlank() && last.isBlank()) return unsatisfied(response, size);
                try {
                    if (first.isBlank()) {
                        long suffix = Long.parseLong(last);
                        if (suffix <= 0) return unsatisfied(response, size);
                        start = Math.max(0, size - suffix);
                    } else {
                        start = Long.parseLong(first);
                        if (!last.isBlank()) end = Math.min(end, Long.parseLong(last));
                    }
                } catch (NumberFormatException error) { return unsatisfied(response, size); }
                if (start < 0 || start >= size || end < start) return unsatisfied(response, size);
                response.setStatusCode(HttpStatus.PARTIAL_CONTENT);
                response.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            }
            response.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.getHeaders().setContentType(MediaType.parseMediaType(artifact.contentType()));
            response.getHeaders().setContentLength(end - start + 1);
            if (disposition != null) response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, disposition);
            AtomicLong remaining = new AtomicLong(end - start + 1);
            return response.writeWith(DataBufferUtils.read(new FileSystemResource(artifact.path()), start,
                            response.bufferFactory(), 64 * 1024)
                    .<org.springframework.core.io.buffer.DataBuffer>handle((buffer, sink) -> {
                        long left = remaining.get();
                        if (left <= 0) {
                            DataBufferUtils.release(buffer);
                            return;
                        }
                        int allowed = (int) Math.min(left, buffer.readableByteCount());
                        if (allowed < buffer.readableByteCount()) {
                            buffer.writePosition(buffer.readPosition() + allowed);
                        }
                        remaining.addAndGet(-allowed);
                        sink.next(buffer);
                    }).takeUntil(ignored -> remaining.get() <= 0));
        } catch (Exception error) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return response.setComplete();
        }
    }

    private Mono<Void> unsatisfied(ServerHttpResponse response, long size) {
        response.setStatusCode(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        response.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
        return response.setComplete();
    }
}
