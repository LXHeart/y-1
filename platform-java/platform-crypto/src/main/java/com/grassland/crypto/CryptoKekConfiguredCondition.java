package com.grassland.crypto;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * KEK 已配置条件（GL-P3-AI-001 Phase 1）。
 *
 * <p>{@code crypto.kek.encoded} 存在且**非空白**才匹配。不能用 {@code @ConditionalOnProperty}：
 * compose/yml 占位未配时该属性是空串，{@code @ConditionalOnProperty} 对空串仍判定「存在」，
 * 会装配出构造即抛错的 bean（fail-fast 拖垮整个未用 BYOK 的上下文）。
 */
public class CryptoKekConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("crypto.kek.encoded");
        return value != null && !value.isBlank();
    }
}
