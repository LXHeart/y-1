package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.bilibili.BilibiliHosts;
import com.grassland.intelligence.bilibili.BilibiliMediaTarget;
import com.grassland.intelligence.douyin.DouyinHosts;
import com.grassland.intelligence.douyin.DouyinMediaTarget;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class PlatformMediaService {
    private static final long BILIBILI_LIMIT = 400L * 1024 * 1024;
    private static final long DOUYIN_LIMIT = 200L * 1024 * 1024;
    private static final Predicate<URI> BILIBILI_GUARD = uri -> "https".equalsIgnoreCase(uri.getScheme())
            && BilibiliHosts.isAllowedVideoHost(uri.getHost());
    private static final Predicate<URI> DOUYIN_GUARD = uri -> "https".equalsIgnoreCase(uri.getScheme())
            && DouyinHosts.isAllowedVideoHost(uri.getHost());

    private final MediaArtifactStore store;
    private final RemoteMediaDownloader downloader;
    private final MediaProcessRunner processes;

    public PlatformMediaService(MediaArtifactStore store, RemoteMediaDownloader downloader, MediaProcessRunner processes) {
        this.store = store;
        this.downloader = downloader;
        this.processes = processes;
    }

    public Mono<String> prepareBilibili(BilibiliMediaTarget target) {
        return Mono.fromCallable(() -> prepareBilibiliBlocking(target)).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> prepareDouyinVideo(DouyinMediaTarget target) {
        return Mono.fromCallable(() -> {
            Path output = store.createPath(".mp4");
            try {
                downloader.download(URI.create(target.playableVideoUrl()), douyinHeaders(target.requestHeaders()),
                        DOUYIN_GUARD, output, DOUYIN_LIMIT);
                return store.register(output, filename(target.filename(), "douyin-video.mp4"), "video/mp4");
            } catch (Exception error) {
                Files.deleteIfExists(output);
                throw error;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> prepareDouyinAudio(DouyinMediaTarget target) {
        return Mono.fromCallable(() -> {
            Path source = store.createPath(".mp4");
            Path output = store.createPath(".mp3");
            try {
                downloader.download(URI.create(target.playableVideoUrl()), douyinHeaders(target.requestHeaders()),
                        DOUYIN_GUARD, source, DOUYIN_LIMIT);
                processes.ffmpeg(List.of("-y", "-i", source.toString(), "-vn", "-c:a", "libmp3lame",
                        "-b:a", "192k", output.toString()));
                if (Files.size(output) > 50L * 1024 * 1024) throw new IntelligenceException(413, "提取后的音频文件过大");
                return store.register(output, audioFilename(target.filename()), "audio/mpeg");
            } finally {
                Files.deleteIfExists(source);
                Files.deleteIfExists(output);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<String>> createClips(String artifactId, long durationSeconds, int clipSeconds) {
        return Mono.fromCallable(() -> {
            MediaArtifactStore.Artifact source = store.require(artifactId);
            var ids = new java.util.ArrayList<String>();
            try {
                for (long start = 0; start < durationSeconds; start += clipSeconds) {
                    Path clip = store.createPath(".mp4");
                    try {
                        long length = Math.min(clipSeconds, durationSeconds - start);
                        processes.ffmpeg(List.of("-y", "-ss", String.valueOf(start), "-i", source.path().toString(),
                                "-t", String.valueOf(length), "-c", "copy", "-movflags", "+faststart", clip.toString()));
                        ids.add(store.register(clip, "analysis-clip-" + (ids.size() + 1) + ".mp4", "video/mp4"));
                    } finally {
                        Files.deleteIfExists(clip);
                    }
                }
                return List.copyOf(ids);
            } catch (Exception error) {
                ids.forEach(store::remove);
                throw error;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public MediaArtifactStore.Artifact artifact(String id) { return store.require(id); }
    public void remove(String id) { store.remove(id); }

    private String prepareBilibiliBlocking(BilibiliMediaTarget target) throws Exception {
        Path output = store.createPath(".mp4");
        try {
            if (target instanceof BilibiliMediaTarget.Progressive progressive) {
                downloader.download(URI.create(progressive.playableVideoUrl()), progressive.requestHeaders(),
                        BILIBILI_GUARD, output, BILIBILI_LIMIT);
            } else if (target instanceof BilibiliMediaTarget.Dash dash) {
                Path video = store.createPath("-video.m4s");
                Path audio = store.createPath("-audio.m4s");
                try {
                    downloader.download(URI.create(dash.videoTrackUrl()), dash.requestHeaders(), BILIBILI_GUARD, video, BILIBILI_LIMIT);
                    downloader.download(URI.create(dash.audioTrackUrl()), dash.requestHeaders(), BILIBILI_GUARD, audio, BILIBILI_LIMIT);
                    processes.ffmpeg(List.of("-y", "-i", video.toString(), "-i", audio.toString(), "-c", "copy",
                            "-movflags", "+faststart", output.toString()));
                } finally {
                    Files.deleteIfExists(video);
                    Files.deleteIfExists(audio);
                }
            }
            return store.register(output, filename(target.filename(), "bilibili-video.mp4"), "video/mp4");
        } catch (Exception error) {
            Files.deleteIfExists(output);
            throw error;
        }
    }

    private static Map<String, String> douyinHeaders(Map<String, String> source) {
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Origin", "https://www.douyin.com");
        if (source != null) headers.putAll(source);
        return headers;
    }

    private static String filename(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String audioFilename(String value) {
        String name = filename(value, "douyin-video.mp4").replaceFirst("\\.[^.]+$", "");
        return name + ".mp3";
    }
}
