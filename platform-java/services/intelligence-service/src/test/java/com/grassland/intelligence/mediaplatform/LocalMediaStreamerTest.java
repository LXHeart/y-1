package com.grassland.intelligence.mediaplatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class LocalMediaStreamerTest {
    @TempDir Path temp;

    private WebTestClient client(byte[] content) throws Exception {
        Path path = temp.resolve("media.bin");
        Files.write(path, content);
        MediaArtifactStore.Artifact artifact = new MediaArtifactStore.Artifact(
                path, "media.bin", "video/mp4", Instant.now().plusSeconds(60));
        return WebTestClient.bindToController(new TestEndpoint(artifact)).build();
    }

    @RestController
    static final class TestEndpoint {
        private final MediaArtifactStore.Artifact artifact;

        TestEndpoint(MediaArtifactStore.Artifact artifact) { this.artifact = artifact; }

        @GetMapping("/media")
        Mono<Void> media(@RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         org.springframework.http.server.reactive.ServerHttpResponse response) {
            return new LocalMediaStreamer().stream(artifact, range, "inline", response);
        }
    }

    @Test
    void streamsFullFile() throws Exception {
        client("0123456789".getBytes()).get().uri("/media").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
                .expectHeader().contentLength(10)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .expectBody(String.class).isEqualTo("0123456789");
    }

    @Test
    void streamsClosedOpenAndSuffixRanges() throws Exception {
        WebTestClient client = client("0123456789".getBytes());
        assertRange(client, "bytes=2-5", "bytes 2-5/10", "2345");
        assertRange(client, "bytes=7-", "bytes 7-9/10", "789");
        assertRange(client, "bytes=-3", "bytes 7-9/10", "789");
        assertRange(client, "bytes=-99", "bytes 0-9/10", "0123456789");
    }

    @Test
    void rejectsMalformedAndUnsatisfiedRanges() throws Exception {
        WebTestClient client = client("0123456789".getBytes());
        for (String range : new String[] {"bytes=", "bytes=1-2,4-5", "bytes=1-2-3", "bytes=10-", "items=0-1"}) {
            client.get().uri("/media").header(HttpHeaders.RANGE, range).exchange()
                    .expectStatus().isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes */10");
        }
    }

    @Test
    void streamsEmptyFileWithoutRange() throws Exception {
        client(new byte[0]).get().uri("/media").exchange()
                .expectStatus().isOk()
                .expectHeader().contentLength(0)
                .expectBody().isEmpty();
    }

    private static void assertRange(WebTestClient client, String range, String contentRange, String body) {
        client.get().uri("/media").header(HttpHeaders.RANGE, range).exchange()
                .expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, contentRange)
                .expectHeader().contentLength(body.length())
                .expectBody(String.class).isEqualTo(body);
    }
}
