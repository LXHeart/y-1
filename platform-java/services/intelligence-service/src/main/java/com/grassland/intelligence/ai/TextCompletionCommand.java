package com.grassland.intelligence.ai;

import java.time.Duration;
import java.util.List;

/** 非流式文本完成命令；业务指定安全对外失败消息与超时。 */
public record TextCompletionCommand(
        List<ChatMessage> messages,
        String failureMessage,
        Duration timeout) {

    public TextCompletionCommand {
        messages = messages == null ? List.of() : List.copyOf(messages);
        failureMessage = failureMessage == null ? "AI 服务请求失败" : failureMessage.trim();
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        if (failureMessage.isEmpty()) {
            throw new IllegalArgumentException("failureMessage 不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
