package com.grassland.intelligence.hypit.agent;

/**
 * 一次 agent 工具动作的输入/结果载体（持久进 hypit_job_action 的 result_json/ input_json）。kind
 * 必须在 {@link HypitAgentScope} 白名单内。
 */
public record HypitAgentAction(String kind, String inputJson, String resultJson, boolean succeeded) {
}
