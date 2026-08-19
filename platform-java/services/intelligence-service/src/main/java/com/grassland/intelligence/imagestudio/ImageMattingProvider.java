package com.grassland.intelligence.imagestudio;

import reactor.core.publisher.Mono;

/**
 * 图片抠图提供者端口（任务书 #43 D3）。
 *
 * <p>唯一 AI 图片步骤：服务端抠图输出带 alpha 通道 PNG。换背景 / 主体突出全部前端合成（D4），
 * 同一张抠图结果可多次组合不同背景，不再重复调模型。
 *
 * <p>两个实现：
 * <ul>
 *   <li>{@link SandboxImageMattingProvider}——IT / 本地开发兜底，中央 80% 不透明 + 边缘渐变 alpha。</li>
 *   <li>{@link OpenAiCompatibleImageMattingProvider}——生产，OpenAI images/edits 风格请求。</li>
 * </ul>
 */
public interface ImageMattingProvider {

    /** 抠图：输入原图字节，输出带 alpha 通道 PNG。 */
    Mono<MattingResult> matting(MattingCommand command);

    /** 抠图入参。 */
    record MattingCommand(byte[] image, String mimeType) {}

    /** 抠图结果：带 alpha 通道 PNG 字节 + 提供者 / 模型元信息。 */
    record MattingResult(byte[] pngWithAlpha, String providerModel, boolean sandbox) {}
}
