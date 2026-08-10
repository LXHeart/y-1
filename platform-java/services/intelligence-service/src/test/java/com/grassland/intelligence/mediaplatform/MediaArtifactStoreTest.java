package com.grassland.intelligence.mediaplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class MediaArtifactStoreTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void registersAndDeletesOnlyFilesInsideConfiguredRoot() throws Exception {
        MediaArtifactStore store = new MediaArtifactStore(new MockEnvironment().withProperty("media.platform.temp-dir", temp.toString()));
        store.initialize();
        var path = store.createPath(".mp4");
        Files.writeString(path, "media");
        String id = store.register(path, "clip.mp4", "video/mp4");

        assertThat(store.require(id).filename()).isEqualTo("clip.mp4");
        store.remove(id);
        assertThat(path).doesNotExist();
    }

    @Test
    void preservesMetadataWhenArtifactIsReadByAnotherReplica() throws Exception {
        var environment = new MockEnvironment().withProperty("media.platform.temp-dir", temp.toString());
        MediaArtifactStore first = new MediaArtifactStore(environment);
        MediaArtifactStore second = new MediaArtifactStore(environment);
        first.initialize();
        second.initialize();
        var path = first.createPath(".mp3");
        Files.writeString(path, "audio");

        String id = first.register(path, "voice.mp3", "audio/mpeg");
        var artifact = second.require(id);

        assertThat(artifact.filename()).isEqualTo("voice.mp3");
        assertThat(artifact.contentType()).isEqualTo("audio/mpeg");
        assertThat(artifact.path()).hasContent("audio");
    }

    @Test
    void cleanupRemovesExpiredSharedArtifactAndMetadata() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("media.platform.temp-dir", temp.toString())
                .withProperty("media.platform.artifact-ttl-seconds", "-1");
        MediaArtifactStore store = new MediaArtifactStore(environment);
        store.initialize();
        var path = store.createPath(".mp4");
        Files.writeString(path, "video");
        String id = store.register(path, "video.mp4", "video/mp4");

        store.cleanup();

        assertThat(temp.resolve(id)).doesNotExist();
        assertThat(temp.resolve(id + ".meta")).doesNotExist();
    }

    @Test
    void cleanupRemovesExpiredUnregisteredWorkFile() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("media.platform.temp-dir", temp.toString())
                .withProperty("media.platform.artifact-ttl-seconds", "-1");
        MediaArtifactStore store = new MediaArtifactStore(environment);
        store.initialize();
        var path = store.createPath("-video.m4s");
        Files.writeString(path, "partial");

        store.cleanup();

        assertThat(path).doesNotExist();
    }
}
