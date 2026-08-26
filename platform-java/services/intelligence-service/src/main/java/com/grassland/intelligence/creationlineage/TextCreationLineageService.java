package com.grassland.intelligence.creationlineage;

import com.grassland.intelligence.ai.PlatformModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 文本创作流 lineage 钩子（任务书 #44 登记扩展）：文章正文/朋友圈/喜剧脚本/创作助手引导等 SSE 生成流
 * 落 {@code creation_generation}。
 *
 * <p>SSE 姿态：内容帧已发出后 lineage 落库失败不能再破坏流——recordAdvisory 吞错告警（区别于
 * videorecreation 非流式路径的强一致 inline 落痕）。游客（无 accountId）不落痕。
 *
 * <p>独立模式文本流经 {@code RoutedTextCompletionService} 路由：调用方自带 resolution 真实值时
 * 优先（文章/朋友圈/改编均如此）；个别未携带的引导流回落 {@link #INDEPENDENT_PROVIDER} +
 * {@link PlatformModelConfig}。任务模式经 {@code FrozenTextExecutionService.executeTraced} 携带真实 run/provider/model。
 */
@Service
public class TextCreationLineageService {

    private static final Logger log = LoggerFactory.getLogger(TextCreationLineageService.class);

    /** 独立模式文本生成的平台适配器标识（intelligence 唯一文本 adapter 实现）。 */
    public static final String INDEPENDENT_PROVIDER = "qwen";

    private final CreationGenerationRecorder recorder;
    private final PlatformModelConfig platformDefaults;

    public TextCreationLineageService(CreationGenerationRecorder recorder, PlatformModelConfig platformDefaults) {
        this.recorder = recorder;
        this.platformDefaults = platformDefaults;
    }

    /** 独立模式平台默认 model（任务模式用 trace 真实值，不用此兜底）。 */
    public String independentModel() {
        return platformDefaults.model();
    }

    /** SSE 流尾落痕：游客跳过；失败告警不破坏内容流。 */
    public Mono<Void> recordAdvisory(CreationGenerationRecorder.Command command) {
        if (command == null || command.ownerAccountId() == null || command.ownerAccountId().isBlank()) {
            return Mono.empty();
        }
        return recorder.record(command)
                .onErrorResume(error -> {
                    log.warn("creation lineage record failed kind={} owner={}",
                            command.kind(), command.ownerAccountId(), error);
                    return Mono.empty();
                })
                .then();
    }
}
