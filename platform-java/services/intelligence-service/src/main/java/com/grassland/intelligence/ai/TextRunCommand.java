package com.grassland.intelligence.ai;

import java.util.List;

/** 文本生成命令（HLD §12.2 {@code startTextRun} 入参）。messages 至少 1 条。 */
public record TextRunCommand(List<ChatMessage> messages) {
    public TextRunCommand {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
    }
}
