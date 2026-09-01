package com.grassland.intelligence.videoproduction;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * BGM 选曲（任务书 #64 卡7，§4.8）：输入 bgm-advice 的 {@code moodDirection.label}
 * → 情绪标签映射过滤启用曲随机取一；无匹配取任意启用曲；空库返回 null（合成跳过 BGM）。
 *
 * <p>映射表定死（P3）：轻快→轻快/电子、温暖→温暖/治愈、燃→燃、悬念→悬念、舒缓→舒缓/治愈，
 * 其余 label 全量。label 是 LLM 自由输出——只做包含式匹配（如「温暖治愈」命中「温暖」）。
 */
@Service
public class BgmSelectionService {

    private static final Logger log = LoggerFactory.getLogger(BgmSelectionService.class);

    /** 情绪 label 关键字 → 曲库标签集（有序：温暖先于治愈，复合词如「温暖治愈」命中温暖）。 */
    private static final List<Map.Entry<String, List<String>>> MOOD_MAPPING = List.of(
            Map.entry("轻快", List.of("轻快", "电子")),
            Map.entry("温暖", List.of("温暖", "治愈")),
            Map.entry("燃", List.of("燃")),
            Map.entry("悬念", List.of("悬念")),
            Map.entry("舒缓", List.of("舒缓", "治愈")),
            Map.entry("治愈", List.of("治愈")),
            Map.entry("电子", List.of("电子")),
            Map.entry("国风", List.of("国风")));

    private final BgmTrackRepository tracks;

    public BgmSelectionService(BgmTrackRepository tracks) {
        this.tracks = tracks;
    }

    /** label → 标签映射（测试锚点 + 治理台展示共用；含式匹配按声明顺序首个命中）。 */
    public static List<String> tagsFor(String moodLabel) {
        if (moodLabel == null || moodLabel.isBlank()) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : MOOD_MAPPING) {
            if (moodLabel.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    public Mono<BgmTrack> pick(String moodLabel) {
        List<String> tags = tagsFor(moodLabel);
        Mono<BgmTrack> pick = tags.isEmpty()
                ? tracks.pickRandomAny()
                : tracks.pickRandomByAnyMood(tags)
                        .switchIfEmpty(Mono.defer(tracks::pickRandomAny));
        return pick.doOnNext(track -> log.info("BGM picked trackId={} tags={} label={}",
                track.id(), tags, moodLabel));
    }
}
