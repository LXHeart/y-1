package com.grassland.intelligence.mediaplatform.segments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Shared by video production and Hypit; one local heavy segment at a time. */
@Component
public class SharedSegmentRenderer {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private final MediaProcessRunner runner;
    private final SharedSegmentCache cache;
    private final ObjectMapper json;
    private final String ffprobe;
    private final String ffmpeg;
    private final Semaphore slots = new Semaphore(1, true);
    private volatile String toolchain;
    public record Rendered(byte[] bytes, boolean cacheHit, String fingerprint) { }
    public SharedSegmentRenderer(MediaProcessRunner runner, SharedSegmentCache cache, Environment environment) {
        this.runner = runner; this.cache = cache; this.json = new ObjectMapper();
        this.ffmpeg = environment.getProperty("media.platform.ffmpeg-path", "ffmpeg");
        this.ffprobe = environment.getProperty("speech.ffprobe-path", "ffprobe");
    }
    public Rendered render(String account, byte[] source, SegmentSpec spec) throws Exception {
        if (source == null || source.length == 0 || source.length > MAX_BYTES) throw new IllegalArgumentException("Segment source exceeds limit");
        slots.acquire();
        Path work = null;
        try {
            work = Files.createTempDirectory("shared-segment-");
            // Probe binary identity once; a binary upgrade invalidates persisted entries on restart.
            if (toolchain == null) toolchain = probe(List.of("-version"), work) + probe(List.of("-version"), work, ffmpeg);
            String fingerprint = cache.fingerprint(source, spec, toolchain);
            byte[] cached = cache.read(account, fingerprint);
            if (cached != null) return new Rendered(cached, true, fingerprint);
            Path input = work.resolve("input"), output = work.resolve("visual.mp4");
            Files.write(input, source);
            List<String> args = new ArrayList<>(List.of("-v", "error", "-nostdin", "-y", "-threads", "1", "-filter_threads", "1"));
            if (spec.kind().equals("image")) {
                boolean png = source.length > 26 && source[0] == (byte)137 && source[1] == 80 && source[2] == 78 && source[3] == 71;
                boolean jpeg = source.length > 3 && source[0] == (byte)255 && source[1] == (byte)216 && source[2] == (byte)255;
                if (!png && !jpeg) throw new IllegalArgumentException("Only raster images are accepted");
                args.addAll(List.of("-loop", "1", "-framerate", spec.rate(), "-f", "image2", "-c:v", png ? "png" : "mjpeg"));
            } else {
                args.addAll(List.of("-f", "mov"));
                if (spec.trimUnit().equals("microseconds")) args.addAll(List.of("-ss", Double.toString(spec.start() / 1_000_000.0)));
            }
            args.addAll(List.of("-protocol_whitelist", "file,pipe", "-i", "input", "-map", "0:v:0"));
            String filter = "";
            if (spec.kind().equals("video") && spec.trimUnit().equals("frames")) {
                filter = "trim=start_frame=" + spec.start() + ":end_frame=" + (spec.start() + spec.frameCount())
                        + ",setpts=N*" + spec.fpsDenominator() + "/(" + spec.fpsNumerator() + "*TB),";
            }
            filter += "scale=" + spec.width() + ":" + spec.height()
                    + (spec.fit().equals("contain") ? ":force_original_aspect_ratio=decrease,pad=" + spec.width() + ":" + spec.height() + ":(ow-iw)/2:(oh-ih)/2" : ":flags=bicubic")
                    + ",fps=" + spec.rate() + ",setsar=1,format=yuv420p";
            args.addAll(List.of("-vf", filter, "-frames:v", "" + spec.frameCount(), "-an", "-c:v", "libx264",
                    "-threads", "1", "-preset", "veryfast", "-crf", "" + spec.crf(), "visual.mp4"));
            runner.ffmpeg(args, Duration.ofMinutes(10), work);
            if (Files.size(output) > MAX_BYTES) throw new IllegalStateException("Segment output exceeds limit");
            JsonNode streams = json.readTree(probe(List.of("-v", "error", "-show_streams", "-count_frames", "-of", "json", "visual.mp4"), work)).path("streams");
            JsonNode stream = streams.path(0);
            String[] rate = stream.path("avg_frame_rate").asText().split("/");
            if (streams.size() != 1 || !stream.path("codec_name").asText().equals("h264")
                    || stream.path("width").asInt() != spec.width() || stream.path("height").asInt() != spec.height()
                    || stream.path("nb_read_frames").asInt() != spec.frameCount() || rate.length != 2
                    || Long.parseLong(rate[0]) * spec.fpsDenominator() != (long)spec.fpsNumerator() * Long.parseLong(rate[1])) {
                throw new IllegalStateException("Rendered segment differs from frame contract");
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Segment cancelled");
            byte[] bytes = Files.readAllBytes(output);
            cache.write(account, fingerprint, bytes);
            return new Rendered(bytes, false, fingerprint);
        } finally {
            if (work != null) try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } finally { slots.release(); }
            else slots.release();
        }
    }
    private String probe(List<String> arguments, Path work) throws Exception {
        return probe(arguments, work, ffprobe);
    }
    private String probe(List<String> arguments, Path work, String executable) throws Exception {
        Path log = work.resolve("probe.log");
        var command = new ArrayList<String>(); command.add(executable); command.addAll(arguments);
        Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Media probe timed out");
            if (process.exitValue() != 0 || Files.size(log) > 4 * 1024 * 1024) throw new IllegalStateException("Media probe failed");
            return Files.readString(log);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.onExit().join(); }
        }
    }
}
