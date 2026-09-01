package com.grassland.intelligence.videoproduction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import reactor.core.publisher.Mono;

/**
 * 沙箱 TTS（任务书 #64 卡5 / §4.7）：纯 Java 合成 16-bit PCM 正弦波 wav——
 * 时长 = 旁白字数 / 4 秒（最少 1 秒），确定性、可被 ffmpeg 合成链消费、断言零宿主依赖
 * （不经 ffmpeg 生成，CI 无 ffmpeg 也能跑全链）。
 */
public class SandboxTtsProvider implements TtsProvider {

    static final int SAMPLE_RATE = 24_000;
    static final int FREQUENCY = 440;

    @Override
    public String id() {
        return "sandbox";
    }

    @Override
    public Mono<TtsResult> submit(TtsCommand command) {
        return Mono.fromCallable(() -> {
            int durationMs = durationMsFor(command.text());
            return new TtsResult(TtsResult.State.SUCCEEDED, "sandbox:" + command.audioId(), null,
                    durationMs, null, null);
        });
    }

    @Override
    public Mono<TtsResult> poll(String providerTaskId) {
        return Mono.just(new TtsResult(TtsResult.State.SUCCEEDED, providerTaskId, null, null, null, null));
    }

    /** §4.7：时长 = 旁白字数 / 4 秒，最少 1 秒；只计可发声字符（标点/空白不占时长）。 */
    static int durationMsFor(String text) {
        int chars = 0;
        if (text != null) {
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (!Character.isWhitespace(ch) && "。！？；，、.!?,;".indexOf(ch) < 0) {
                    chars++;
                }
            }
        }
        long millis = Math.round(chars / 4.0 * 1000L);
        return (int) Math.max(1000L, millis);
    }

    /** 16-bit 单声道 PCM wav 字节（worker 归档进对象存储用）。 */
    static byte[] sineWavBytes(int durationMs) {
        int totalSamples = (int) Math.max(1L, Math.round(SAMPLE_RATE * (durationMs / 1000.0)));
        int dataSize = totalSamples * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);            // PCM chunk size
        header.putShort((short) 1);   // PCM format
        header.putShort((short) 1);   // mono
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * 2);   // byte rate
        header.putShort((short) 2);   // block align
        header.putShort((short) 16);  // bits per sample
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(dataSize);

        byte[] wav = new byte[44 + dataSize];
        System.arraycopy(header.array(), 0, wav, 0, 44);
        ByteBuffer samples = ByteBuffer.wrap(wav, 44, dataSize).order(ByteOrder.LITTLE_ENDIAN);
        for (int sample = 0; sample < totalSamples; sample++) {
            double angle = 2.0 * Math.PI * FREQUENCY * sample / SAMPLE_RATE;
            samples.putShort((short) Math.round(Math.sin(angle) * 12000));
        }
        return wav;
    }
}
