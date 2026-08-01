package com.grassland.marketplace.taskcatalog;

/**
 * 任务状态迁移请求体（publish/close/cancel，GL-P1-TASK-001 Stage 1）。
 *
 * <p>{@code expectedVersion} 必填（乐观锁：等于客户端读取时的 task.version）。服务端 guarded UPDATE
 * {@code WHERE status=:from AND version=:expected}，状态/版本不匹配 → 409。
 */
public record TaskLifecycleRequest(int expectedVersion) {}
