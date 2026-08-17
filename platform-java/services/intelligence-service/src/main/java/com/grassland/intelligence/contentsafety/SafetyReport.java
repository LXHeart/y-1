package com.grassland.intelligence.contentsafety;

import java.util.List;

/**
 * 内容安全检查结果（ADR-D16 D7 findings 形态）。序列化进各流 result 帧的 {@code safety} 块与手动端点响应。
 *
 * @param findings       命中列表（L1 确定性 + L2 深检折叠；L2 来源标 {@code deep:true}）
 * @param lexiconVersion 词库版本（随快照冻结；前端展示「按 lexicon-v1 检查」）
 * @param deepCheck      本次是否跑了 LLM 深检（false = 未配置模型或短文本仅 L1，非错误态）
 */
public record SafetyReport(List<Finding> findings, String lexiconVersion, boolean deepCheck) {

    public SafetyReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static SafetyReport shallow(List<Finding> findings) {
        return new SafetyReport(findings, ContentSafetyLexicon.version(), false);
    }

    /** 无发现 + 未深检（空文本/短文本）。 */
    public static SafetyReport emptyShallow() {
        return shallow(List.of());
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /**
     * 单条发现。{@code index} 为命中起始字符位置（L2 深检无精确位置 → -1）；
     * {@code deep} 区分来源（L1=false 词库确定性 / true=LLM 语境判定）。
     */
    public record Finding(
            String category,
            String severity,
            String match,
            int index,
            String advice,
            boolean deep) {
    }
}
