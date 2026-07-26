package com.grassland.trust.adjudication;

/**
 * 上诉请求（草场 Epic 6 Slice 6C Phase C-2 / HLD §10.5）。{@code note} 可空（上诉理由）。
 * 上诉资格/窗口由 endpoint 校验（须 decided 态 = 在上诉窗口内，workflow Timer 控制 decided→final）。
 */
public record AppealRequest(String note) {}
