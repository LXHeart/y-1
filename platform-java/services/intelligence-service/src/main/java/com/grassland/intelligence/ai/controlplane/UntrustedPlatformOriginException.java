package com.grassland.intelligence.ai.controlplane;

/**
 * 平台 base-url 的 origin 不在受信端点表（{@code platform_trusted_origin}）中（任务书 #58 决策 B）。
 *
 * <p>运行时校验路径（{@code TextCompletionClient} 等）把它当普通 IllegalArgumentException 处理；
 * 控制面 CRUD（模型行/凭据保存）捕获后转 422 + 引导文案「请先在受信端点中添加 {origin}」。
 */
public final class UntrustedPlatformOriginException extends IllegalArgumentException {

    private final String origin;

    public UntrustedPlatformOriginException(String origin) {
        super("平台模型 base-url 不在受信地址范围内: " + origin);
        this.origin = origin;
    }

    public String origin() {
        return origin;
    }
}
