package com.grassland.intelligence.ai.controlplane;

import java.util.Set;

/**
 * 平台 provider 受控值集的**单一真相源**：控制面 DTO 的 {@code @Pattern} 与
 * {@link PlatformProviderPolicy} 的运行期白名单都从这里取。
 *
 * <p><b>为什么必须单源</b>：这份名单原先以字面量重复在四个 DTO 与 policy 里。
 * {@code PlatformModelConfigControllerIT} 记录过一次真实事故——正则收窄而存量行未迁移，
 * 结果 admin 对那些行的任何 PUT 都 400、行变得不可编辑。名单散落时这种偏移靠 review 拦不住。
 *
 * <p>provider 名同时是协议方言名（见 {@code ai.run.dialect.TextDialects}）：
 * <ul>
 * <li>{@code openai-completions} —— OpenAI Chat Completions 形状（最广的兼容口）；</li>
 * <li>{@code openai-responses} —— OpenAI Responses API；</li>
 * <li>{@code anthropic-messages} —— Anthropic Messages API；</li>
 * <li>{@code google-generative-ai} —— Google Gemini generateContent；</li>
 * <li>{@code openai-compatible} —— {@code openai-completions} 的等价别名，为存量行与调用方保留；</li>
 * <li>{@code sandbox} —— 免密占位，只允许内置地址。</li>
 * </ul>
 *
 * <p>{@code qwen} 已由 V57 迁为 {@code openai-completions} 后<b>移出</b>值集：分方言前
 * {@code TextCompletionClient} 对 provider 零分支，qwen 与 openai-compatible 走的是同一条
 * OpenAI 形状路径，qwen 只是个标签，所以这次改名是行为中性的。
 */
public final class PlatformProviderNames {

    public static final String OPENAI_COMPLETIONS = "openai-completions";
    public static final String OPENAI_RESPONSES = "openai-responses";
    public static final String ANTHROPIC_MESSAGES = "anthropic-messages";
    public static final String GOOGLE_GENERATIVE_AI = "google-generative-ai";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String SANDBOX = "sandbox";

    /**
     * Bean Validation 正则。注解取值必须是编译期常量，故此处用常量拼接而非从 {@link #ALL} 派生
     * ——两者取自同一批常量，加减方言只需改上面那组常量。
     */
    public static final String PATTERN = OPENAI_COMPLETIONS + "|" + OPENAI_RESPONSES + "|"
            + ANTHROPIC_MESSAGES + "|" + GOOGLE_GENERATIVE_AI + "|" + OPENAI_COMPATIBLE + "|" + SANDBOX;

    public static final String MESSAGE = "平台 provider 必须是 openai-completions、openai-responses、"
            + "anthropic-messages、google-generative-ai、openai-compatible 或 sandbox";

    /** 全部受控值（含 {@code sandbox}）。 */
    public static final Set<String> ALL = Set.of(
            OPENAI_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_GENERATIVE_AI,
            OPENAI_COMPATIBLE, SANDBOX);

    /** 走真实 origin 受信校验的值（{@code sandbox} 另有内置地址分支，故不在此列）。 */
    public static final Set<String> ORIGIN_CHECKED = Set.of(
            OPENAI_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_GENERATIVE_AI,
            OPENAI_COMPATIBLE);

    private PlatformProviderNames() {
    }
}
