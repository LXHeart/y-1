package com.grassland.intelligence.creationlineage;

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
 * <p>独立模式文本流经 {@code RoutedTextCompletionService} 路由：provider/model 取调用方携带的
 * 路由解析真实值（任务书 #58 起 env 版 PlatformModelConfig 已删，不再有平台默认 model 兜底）；
 * 拿不到解析结果的调用方用 {@link #UNRESOLVED_MODEL} 显式标记。任务模式经
 * {@code FrozenTextExecutionService.executeTraced} 携带真实 run/provider/model。
 */
@Service
public class TextCreationLineageService {

    private static final Logger log = LoggerFactory.getLogger(TextCreationLineageService.class);

    /** 独立模式文本生成的平台适配器标识（intelligence 唯一文本 adapter 实现）。 */
    public static final String INDEPENDENT_PROVIDER = "qwen";

    /** 调用方拿不到路由解析结果时的 model 占位（env 默认 model 兜底已随 #58 删除）。 */
    public static final String UNRESOLVED_MODEL = "unresolved";

    private final CreationGenerationRecorder recorder;

    public TextCreationLineageService(CreationGenerationRecorder recorder) {
        this.recorder = recorder;
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
