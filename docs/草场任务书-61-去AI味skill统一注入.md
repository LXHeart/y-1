# 开发规划：去AI味 skill 统一注入（草场任务书 #61）

<!-- 弱模型执行时只需要读：第 4 节全局约束 + 当前卡。
     其余章节是给强模型自己和总控看的，不要求执行者通读。 -->

## 元信息
- 规划模型：GLM-5.3（ZCode），2026-08-31
- 执行模型：能力较弱的编码模型（Qoder / 同级）
- 任务卡总数：8
- 执行顺序：卡1 → 卡2 → 卡3 → 卡4 → 卡5 → 卡6 → 卡7 → 卡8（**严格串行**，任何一卡验收不绿不得进入下一卡）

## 1. 目标与范围

**一句话目标**：平台可在治理台从 3 条「去AI味」写作规则库（存数据库、内容可编辑）中单选激活一条，激活后所有 12 个创作型 AI 文字生成场景在请求 LLM 前自动把该规则注入 system prompt，从而系统性降低生成内容的模板感/AI 味。

**范围内（明确交付）**：
- 新表 `humanize_skill`（3 条种子，MIT 来源，内容可在治理台编辑）+ 单行配置表 `humanize_config`（记录当前激活的 skill code，NULL=不注入）
- `HumanizeInjectionService`：注入判定与消息变换（fail-open，读库失败不阻断生成）
- 计费流接线：`FrozenTextExecutionService` 7 个入口按 `CreditFeature` 白名单统一注入
- 免费流接线：5 个文件的创作型免费调用点显式接入
- 治理台新页签「去AI味」：列表 + 编辑弹窗（GlModal）+ 激活单选（含「不注入」）
- admin API：GET/PUT skill 行 + PUT 激活端点（乐观锁 409）
- edge-bff 路由注册 + 契约测试
- 后端 IT + 单测、前端组件测试

**范围外（明确不做，遇到也不处理）**：
- **分析/治理型 AI 流不注入**：B站/抖音视频分析、分段视频分析、任务视频分析、KYB 文档分析、履约凭证分析、门店媒体审核、风格偏好学习（StylePreferencesService）、内容安全（ContentSafetyAiChecker，capability=content_safety 且自带环）、冒烟（SmokeController，INTELLIGENCE_SMOKE）
- **`AiRunController` 不注入**：它是通用 text run 底座端点（自带环直连 TextCompletionClient），输出语义由调用方决定，不在 12 场景内
- 不改任何估价逻辑（`estimatedInputTokens` 按原消息计算，注入增量 1-3k 字符远小于预算粒度，忽略）
- 不改任何计费/扣费/退款行为
- 不做「生成后改写 pass」（已定案：生成时注入）
- 不做按场景分别选 skill、不做多 skill 叠加（已定案：平台级单选）
- 不动 database-bootstrap（两张新表均为 intelligence 自有表）
- 不做 skill 新增/删除行（治理台仅编辑既有 3 行 + 启停 + 激活单选，同任务书 #57 决策 G 的收敛口径）
- 不改 `#57` 已有的 `ArticlePrompts` 风格 skill 注入（两者天然叠加：文风 skill 在业务 prompt 内、去AI味 skill 由执行环最后注入，模板自带「最高优先级」句）

### 12 个注入场景清单（覆盖目标）

| # | 场景 | 代码位置 | 通道 | CreditFeature |
|---|---|---|---|---|
| 1 | 文章标题（任务/独立） | ArticleController | 计费 Frozen | ARTICLE_GENERATION |
| 2 | 文章大纲（任务模式） | ArticleController taskStream | 计费 Frozen | null（任务模式不传 feature） |
| 3 | 文章正文（任务模式） | ArticleController contentTaskStream | 计费 Frozen | null |
| 4 | 文章大纲/正文（独立免费 SSE） | ArticleController outline/content | 免费 Routed | 无（显式接入） |
| 5 | 创作助手 5 端点 | CreationAssistantController | 计费 Frozen | CREATION_ASSISTANT |
| 6 | 朋友圈文案（独立+任务） | MomentsGenerationService | 计费 Frozen | MOMENTS_GENERATION |
| 7 | 搞笑短剧脚本（独立+任务） | ComedyController | 计费 Frozen | COMEDY_GENERATION |
| 8 | 视频脚本（独立+任务） | VideoProductionController | 计费 Frozen | VIDEO_PRODUCTION_SCRIPT |
| 9 | 视频工坊 BGM 建议 | VideoStudioController | 计费 Frozen | VIDEO_STUDIO_BGM |
| 10 | 系列图卡拆卡计划 | CardSeriesService | 计费 Frozen | CARD_SERIES_PLAN |
| 11 | 图片评价（计费管线 + 免费单轮） | ImageAnalysisService | 双通道 | IMAGE_ANALYSIS / 显式 |
| 12 | 视频改编（任务计费 + 独立免费）、游客试用（titles/score）、配图推荐/参考图描述 | VideoRecreationAdaptationService / GuestTrialService / ArticleImageService | 双通道 | AI_RUN_TEXT / 显式 |

注：游客试用 imageReview（多模态、无 system 消息）按注入规则自动跳过（见 2.5 注入规则），可接受——其输出是结构化 JSON 点评。

## 2. 技术决策（已定案，执行期不得更改）

| 决策项 | 结论 |
|---|---|
| 语言/框架/版本 | Java 21（项目现状，gradle toolchain）/ Spring WebFlux + R2DBC DatabaseClient / Vue 3 + vitest + happy-dom（项目现状） |
| 新增依赖 | **无**。禁止新增任何 gradle/npm 包 |
| 构建环境 | `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"`，用 `./gradlew`（wrapper），不依赖系统 Gradle。JDK 8 会直接失败 |
| 错误处理策略 | 注入服务 **fail-open**：任何读库异常 → 原样返回消息 + WARN 日志，绝不阻断生成。admin 端点错误统一 `IntelligenceException(status, 中文文案)`，全局 ErrorHandler 自动转 `{success:false,error}` 信封 |
| 命名约定 | 后端包 `com.grassland.intelligence.humanize`；类名 `HumanizeSkill` / `HumanizeSkillRepository` / `HumanizeConfigRepository` / `HumanizeInjectionService` / `HumanizeSkillSeeder` / `HumanizeSkillController`；前端 `HumanizeSkillsAdminPanel.vue`；tab key `humanize-skills`；表 `humanize_skill` / `humanize_config` |
| 迁移号 | `V58__humanize_skill.sql`（当前最大 V57）。DDL 一律 `IF NOT EXISTS`（迁移重放测试要求幂等） |
| 配置读取 | 生成时**直读无缓存**（照 #57 决策 F：admin 改完下一次生成立即生效）。不做缓存、不做失效事件 |
| 默认状态 | 种子后 `humanize_config` **无行 = 未激活 = 完全不注入**。上线后由 admin 在治理台主动激活（先配后用）。此默认保证存量 IT/单测零行为变化 |
| 风格参照 | Repository 照 `creationstyle/CreationStyleSkillRepository.java`；Seeder 照 `CreationStyleSkillSeeder.java`；Controller 照 `CreationStyleSkillController.java`（信封 Map.of("success",true,"data",...) + requireAdmin + 乐观锁 409）；单行配置表照 `homepage/HomepageHotConfigRepository.java` 的 upsert 模式；前端照 `src/ops/admin/components/CreationSkillsAdminPanel.vue` + GlModal 用法照 `src/components/AiPlatformCredentialsPanel.vue` |
| Jackson | intelligence **没有 ObjectMapper bean**——Seeder 内自持 `private static final ObjectMapper MAPPER = new ObjectMapper()`（注入 bean 会炸整个上下文，红线） |
| 请求 record | 可选字段一律包装类型（Boolean/Integer/Long），缺失显式 400（Jackson record 惯例） |
| UI 规范 | 治理台 UI 改动前必读 `src/ops/DESIGN.md`（grassland-admin）；颜色/圆角/间距只用 token（`var(--...)`），禁止硬编码 hex；新弹窗一律用 `src/components/GlModal.vue`（#59 全弹窗化口径） |

### 2.5 注入规则（核心算法，定死）

```
injectForFeature(messages, feature):
  feature != null 且 feature 不在 CREATIVE_FEATURES 白名单 → 原样返回（不查库）
  否则 → injectCreative(messages)

injectCreative(messages):
  读库：SELECT skill.* FROM humanize_skill s JOIN humanize_config c
        ON c.active_skill_code = s.code WHERE c.id = 1 AND s.enabled = true
  0 行（未激活/激活的 skill 已停用/无行）→ 原样返回
  1 行 → append(messages, skill.promptContent)
  读库异常 → 原样返回 + log.warn（fail-open）

append(messages, promptContent):
  从后往前找最后一条 role="system" 的消息：
  - 找到 → 该消息 content 尾部追加 SEGMENT_APPENDED，其余消息不动
  - 未找到 → 列表头部插入一条新 ChatMessage.system(SEGMENT_STANDALONE)
  （消息列表是不可变 List.copyOf，必须新建列表；四种 provider 方言均已核实能消化
   多条 system——AnthropicMessagesDialect 会合并进顶层 system 字段）
```

`CREATIVE_FEATURES` 白名单（9 个）：
`ARTICLE_GENERATION, CREATION_ASSISTANT, MOMENTS_GENERATION, COMEDY_GENERATION, VIDEO_PRODUCTION_SCRIPT, VIDEO_STUDIO_BGM, CARD_SERIES_PLAN, IMAGE_ANALYSIS, AI_RUN_TEXT`

- `feature == null` → **视为创作型注入**。依据：当前 Frozen 入口唯一传 null 的是文章 content/outline 任务模式（场景 2/3）。此默认写进代码注释。
- `AI_RUN_TEXT` 在 Frozen 通道当前唯一消费者是视频改编任务模式（创作型）；`AiRunController` 虽也用 AI_RUN_TEXT 但它自带环不走 Frozen，不受影响。

注入文本模板（常量，两段头部文案定死）：
- 有 system 追加（SEGMENT_APPENDED 前缀）：`"\n\n【平台文风约束（最高优先级）】\n以下规则只约束语言风格，与前文任何风格、语气要求冲突时以本段为准；不得因此改变任何事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）；也不要在输出中提及、解释或引用这些规则：\n"`
- 无 system 新插入（SEGMENT_STANDALONE）：`"【平台文风约束（最高优先级）】\n以下规则只约束语言风格，不得因此改变任何事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）；也不要在输出中提及、解释或引用这些规则：\n"`

## 3. 接口契约（本节内容为最终代码，直接照抄进项目，不得修改）

### 3.1 Flyway 迁移 `V58__humanize_skill.sql` 全文

文件：`platform-java/services/intelligence-service/src/main/resources/db/migration/V58__humanize_skill.sql`

```sql
-- 任务书 #61（2026-08-31）：去AI味 skill 统一注入。
-- intelligence 自有表（非共享表，不涉 database-bootstrap 双处 DDL 铁律）。
-- humanize_skill：3 条种子经 Java 启动 Seeder（表空才种，/contracts/humanize-skills.json）。
-- humanize_config：单行表（固定 id=1），active_skill_code 为 NULL 表示不注入；
--   激活与 skill 行软关联（无 FK），注入时 JOIN + enabled=true 双检，停用即自动失效。
CREATE TABLE IF NOT EXISTS humanize_skill (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code           text NOT NULL,
    display_name   text NOT NULL,
    description    text NOT NULL DEFAULT '',
    prompt_content text NOT NULL,
    source_repo    text NOT NULL DEFAULT '',
    source_license text NOT NULL DEFAULT 'MIT',
    enabled        boolean NOT NULL DEFAULT true,
    version        int NOT NULL DEFAULT 0,
    updated_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT humanize_skill_code_key UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS humanize_config (
    id                 int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    active_skill_code  text,
    version            bigint NOT NULL DEFAULT 1,
    updated_by         text,
    updated_at         timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE humanize_skill IS '去AI味写作规则库（平台级单选激活，治理台可编辑内容）';
COMMENT ON COLUMN humanize_config.active_skill_code IS '当前激活的 skill code；NULL=不注入（默认无行同 NULL 语义）';
```

### 3.2 种子契约 `contracts/humanize-skills.json` 全文

文件：仓库根 `contracts/humanize-skills.json`（新建；经 build.gradle.kts copySpec 拷入 classpath，见卡1）。
三段 promptContent 是从三个 MIT 仓库蒸馏定稿的规则本体（生成式注入版），**一字不改照抄落盘**：

```json
{
  "version": 1,
  "skills": [
    {
      "code": "shuorenhua",
      "displayName": "说人话",
      "description": "中文优先的全面去模板感规则：禁套话开场收尾、禁表演性姿态、连接词降密、数字具体化。蒸馏自 MrGeDiao/shuorenhua v2.4.0（MIT）",
      "sourceRepo": "https://github.com/MrGeDiao/shuorenhua",
      "sourceLicense": "MIT",
      "promptContent": "写作规则（只约束语言风格，事实与结构零漂移）：\n1.禁用套话开场与收尾：不以「在……的今天」「随着……的不断发展」「在当今快节奏的生活中」等宏大铺垫开场；不以「总而言之」「综上所述」「希望这篇文章对你有帮助」「让我们一起……」等总结式或召唤式收尾；结尾落在具体内容或具体判断上。\n2.禁表演性姿态句：不写「不难发现」「显而易见」「值得一提的是」「不可否认」这类无信息量的强调；不用「赋能」「助力」「打造」「闭环」「抓手」「深度融合」等词去包装没有具体动作的事——写了什么动作就直说动作。\n3.连接词降密：「然而」「此外」「因此」「与此同时」「换言之」每段最多出现一次，且仅当逻辑关系真实存在时使用；段与段之间不靠路标词硬转。\n4.句式反模式：「不是X，而是Y」「不仅X，更Y」仅当两端确有事实差异或真递进时使用，两端只是换名字的同义拔高则直接写结论。\n5.数字具体化：同段已有具体数字时，结论必须落在数字上，禁再用「显著提升」「大幅增长」「效率飞跃」等概括词回写；没有数据就直接下判断，禁止编造数字、比例、研究结论。\n6.结构反模式：各段避免等长同构、每段先总后分的模板节奏；不为凑数硬拆「三点式」，内容天然几个层次就几个层次。\n7.段落开头：非首段不以「听起来」「值得强调的是」「更重要的是」这类零回指评论语开头；发表评论先接住上一段（用「这」「那」回指或直接承接话题）。\n8.破折号与冒号：不用破折号做揭晓式停顿（「——而这正是关键」式）；不用「核心是：」「关键在于：」这类空提示语引出内容，直接给内容。\n9.事实保护：专有名词、数字、版本号、命令、产品名、既定输出格式（JSON 字段、标题层级、列表条目、字数要求）一律原样遵守，本段规则不凌驾于任何明确的事实与格式要求之上。\n10.度：以上规则只为消除模板感；自然的口语、长短句错落、作者的既有语气都保留，不为「显得像人」而强加语气词、口癖或故意打乱结构。"
    },
    {
      "code": "lieflat-11",
      "displayName": "实证11条",
      "description": "基于 283 万字人类/AI 对照语料统计检验出的 11 项真有区分度的特征，白名单式规避（未列出的写法不处理）。蒸馏自 larashero3-dotcom/lieflat-less-ai-tone（MIT）",
      "sourceRepo": "https://github.com/larashero3-dotcom/lieflat-less-ai-tone",
      "sourceLicense": "MIT",
      "promptContent": "写作规则（白名单式：只规避以下 11 项，未列出的写法一律正常表达，不做额外处理）：\n1.不写翻案腔：不先立一个读者并不持有的误解再推翻（「不是……而是……」「看似……实则……」「你以为……其实……」），直接正面给出判断和依据。\n2.顿号罗列降密：一个分句内顿号不超过两个、并列项不超过三项；能概括就概括。必须完整列举的清单/条目类输出按既定格式走，不受此条限制。\n3.相邻句结构错开：相邻句子避免长度接近、成分顺序相同的同构句式，自然打散其中一句；操作步骤、并列条款、列表项的平行结构不受此条限制。\n4.不用破折号做揭晓式停顿或插入语，需要停顿就写完整句或用逗号句号。\n5.冒号克制：不用「核心是：」「关键在于：」等不承担信息的提示语引出内容；正文一行不要只以冒号结尾空转宣布「如下：」——要么直接给判断，要么直接列内容。\n6.小标题不用「一、二、三」「第一、第二」这类连续序号编号（输出格式明确要求编号的除外）。\n7.不用拟人化喻体：不把工具/方法比作「智慧的导师」「不知疲倦的助手」「贴心的管家」等理想化人物，写它实际做了什么；比喻本身可以用，但喻体要具体、能给读者带来正文之外没讲的理解。\n8.有数字禁概括：同段或相邻段已写出具体数值时，不再用「显著提升」「大幅增长」等概括表述指代它，直接引用数值；原文没有数据时保持无数据，不编造。\n9.禁起手式：不写「说白了」「说穿了」「先说结论」这类起手语，直接给内容。\n10.翻译腔五种：中心名词前的修饰不超过十五字、「的」不连用两个以上，超了就拆成两个分句；完整主谓的「当……时」从句删掉「当」和「时」直接说；句首「对于……来说」「就……而言」「在……方面」的话题壳改为把对象放进主语位；段首句首的「然而」「因此」「此外」路标词后移到主语之后或删除；「这意味着」「这表明」开头的复述句并入前一句。\n11.段首回指：非首段的段落不以「听起来」「值得注意的是」「更重要的是」「关键在于」等评论语开头且不带回指——评论段开头带「这」「那」等回指词，或直接承接上文话题。\n以上规则不改变任何事实、数字、专名、引用与既定输出结构；比喻、设问、口语、长短句等未列出的写法全部正常使用。"
    },
    {
      "code": "qu-ai-wei",
      "displayName": "改留对照",
      "description": "10 类常见 AI 句式的「何时改/何时留」对照，克制路线：只在反复出现、脱离事实时处理，正式文体不强改聊天腔。蒸馏自 LifelongLazyLearner/qu-ai-wei v0.9.0（MIT）",
      "sourceRepo": "https://github.com/LifelongLazyLearner/qu-ai-wei",
      "sourceLicense": "MIT",
      "promptContent": "写作规则（每条都带「可用条件」，满足条件时正常使用，不搞一刀切禁词）：\n仲裁顺序（冲突时前者优先）：①事实、原意与逻辑关系 ②明确的输出格式与用途要求 ③自然的中文表达 ④句式本身。\n1.「随着……的发展」「在……的背景下」：仅当背景只是营造宏大感时不用；背景确实构成条件或时间坐标时可用。\n2.「值得一提」「不可否认」：纯强调、不带新信息时不用；承担真实转折、限定或判断时可用。\n3.「不是X，而是Y」：两端只是抽象对称、换个说法时不用；两端确有事实区分时可用（仍可调整句式让区分更清楚）。\n4.「不仅X，更Y」：两端在拔高同一件事时不用；确有递进、两端信息不同时可用。\n5.「首先/其次/最后」：叙述性内容不硬拆成三点；操作步骤、清单、答题类输出按格式正常分点。\n6.「赋能/助力/打造/闭环」：密集出现且没有主语的具体动作时不用；确为行业固定术语时可用。\n7.「通过……的方式」「由于……的原因」：去掉后信息不减就删掉冗余框架；删掉会改变条件或因果则保留。\n8.连接词「然而/此外/因此」：仅当段落间确有对应逻辑关系时使用，不靠连接词维持表面连贯。\n9.长被动句、层层定语：主干被压住、像逐词翻译时拆短；受事关系明确或术语性定语时保留。\n10.段落节奏：避免通篇同句长、同结构的模板节奏；条款、清单类需要的平行结构保留。\n边界：不编造观点、经历、数据或因果；保持内容本来的正式程度，正式文案不改成聊天腔，口语内容不强行书面化；本段规则不凌驾于任何明确的格式、事实要求。"
    }
  ]
}
```

### 3.3 实体 `HumanizeSkill.java` 全文

文件：`platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/humanize/HumanizeSkill.java`

```java
package com.grassland.intelligence.humanize;

import java.time.Instant;
import java.util.UUID;

/**
 * 去AI味 skill 行（任务书 #61，V58 表 {@code humanize_skill}）。
 *
 * <p>admin 改库行即生效（生成时直读无缓存，照 #57 决策 F）；激活项存 {@code humanize_config}
 * 单行表（NULL=不注入）；种子不回写契约文件。
 */
public record HumanizeSkill(
        UUID id,
        String code,
        String displayName,
        String description,
        String promptContent,
        String sourceRepo,
        String sourceLicense,
        boolean enabled,
        int version,
        UUID updatedBy,
        Instant updatedAt) {
}
```

### 3.4 `HumanizeSkillRepository.java` 全文

文件：`.../humanize/HumanizeSkillRepository.java`

```java
package com.grassland.intelligence.humanize;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 仓储（任务书 #61）。{@code DatabaseClient} 裸 SQL 惯例
 * （照 {@code CreationStyleSkillRepository}）。
 */
@Component
public class HumanizeSkillRepository {

    static final String COLS = "id, code, display_name, description, prompt_content, "
            + "source_repo, source_license, enabled, version, updated_by, updated_at";

    private final DatabaseClient db;

    public HumanizeSkillRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> count() {
        return db.sql("SELECT count(*) AS n FROM humanize_skill")
                .map((row, meta) -> row.get("n", Long.class)).one();
    }

    /** 治理台全量（含停用含 promptContent）。 */
    public Flux<HumanizeSkill> listAll() {
        return db.sql("SELECT " + COLS + " FROM humanize_skill ORDER BY code")
                .map(HumanizeSkillRepository::map).all();
    }

    public Mono<HumanizeSkill> findById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM humanize_skill WHERE id = :id")
                .bind("id", id).map(HumanizeSkillRepository::map).one();
    }

    public Mono<HumanizeSkill> findByCode(String code) {
        return db.sql("SELECT " + COLS + " FROM humanize_skill WHERE code = :code")
                .bind("code", code).map(HumanizeSkillRepository::map).one();
    }

    /**
     * 生成时直读（无缓存）：当前激活且启用中的 skill——humanize_config 单行 JOIN humanize_skill，
     * active 为 NULL / 无行 / skill 已停用均返回空（= 不注入）。
     */
    public Mono<HumanizeSkill> findActiveSkill() {
        return db.sql("SELECT " + COLS + " FROM humanize_config c "
                + "JOIN humanize_skill s ON c.active_skill_code = s.code "
                + "WHERE c.id = 1 AND s.enabled = true")
                .map(HumanizeSkillRepository::map).one();
    }

    /** 启动种子逐条插入；UNIQUE(code) 冲突静默跳过。 */
    public Mono<Void> insertSeed(String code, String displayName, String description,
            String promptContent, String sourceRepo, String sourceLicense) {
        return db.sql("""
                        INSERT INTO humanize_skill(code, display_name, description, prompt_content,
                            source_repo, source_license)
                        VALUES (:code, :displayName, :description, :promptContent, :sourceRepo, :sourceLicense)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .bind("code", code).bind("displayName", displayName)
                .bind("description", description == null ? "" : description)
                .bind("promptContent", promptContent).bind("sourceRepo", sourceRepo)
                .bind("sourceLicense", sourceLicense).then();
    }

    /**
     * 乐观锁整行 UPDATE（治理台编辑）。{@code AND version = :expected} 命中 0 行 → 空 Mono，
     * 由上层区分「不存在/版本冲突」→ 409。
     */
    public Mono<HumanizeSkill> updateRow(UUID id, String displayName, String description, String promptContent,
            boolean enabled, int expectedVersion, UUID updatedBy) {
        return db.sql("""
                        UPDATE humanize_skill
                        SET display_name = :displayName,
                            description = :description,
                            prompt_content = :promptContent,
                            enabled = :enabled,
                            version = version + 1,
                            updated_by = :updatedBy,
                            updated_at = now()
                        WHERE id = :id AND version = :expected
                        RETURNING """ + " " + COLS)
                .bind("id", id).bind("displayName", displayName)
                .bind("description", description == null ? "" : description)
                .bind("promptContent", promptContent).bind("enabled", enabled)
                .bind("expected", expectedVersion)
                .bind("updatedBy", nullable(updatedBy, UUID.class))
                .map(HumanizeSkillRepository::map).one();
    }

    static HumanizeSkill map(Row row, RowMetadata meta) {
        return new HumanizeSkill(
                row.get("id", UUID.class),
                row.get("code", String.class),
                row.get("display_name", String.class),
                row.get("description", String.class),
                row.get("prompt_content", String.class),
                row.get("source_repo", String.class),
                row.get("source_license", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("version", Integer.class),
                row.get("updated_by", UUID.class),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
```

### 3.5 `HumanizeConfigRepository.java` 全文

文件：`.../humanize/HumanizeConfigRepository.java`

```java
package com.grassland.intelligence.humanize;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 去AI味激活配置仓储（任务书 #61）：单行表 {@code humanize_config}（固定 id=1），
 * upsert 乐观锁照 {@code HomepageHotConfigRepository} 模式。
 */
@Component
public class HumanizeConfigRepository {

    private final DatabaseClient db;

    public HumanizeConfigRepository(DatabaseClient db) {
        this.db = db;
    }

    /** activeSkillCode 当前激活 code；NULL = 不注入。version 行版本（写后 +1）。 */
    public record HumanizeConfig(String activeSkillCode, long version) {
    }

    /** 读配置；无行 → version=0（语义=未配置，首次写入走 INSERT 分支）。 */
    public Mono<HumanizeConfig> findOrDefault() {
        return db.sql("SELECT active_skill_code, version FROM humanize_config WHERE id = 1")
                .map((row, meta) -> new HumanizeConfig(row.get("active_skill_code", String.class),
                        row.get("version", Long.class)))
                .one()
                .defaultIfEmpty(new HumanizeConfig(null, 0L));
    }

    /**
     * 乐观锁写激活项：{@code expectedVersion==0} 表示预期无行走 INSERT（冲突 DO NOTHING → 空 Mono，
     * 上层转 409）；否则 UPDATE 带 {@code AND version = :expected}，命中 0 行 → 空 Mono。
     */
    public Mono<HumanizeConfig> upsertActive(String activeSkillCode, long expectedVersion, String adminId) {
        if (expectedVersion == 0L) {
            return db.sql("""
                            INSERT INTO humanize_config(id, active_skill_code, version, updated_by)
                            VALUES (1, :code, 1, :adminId)
                            ON CONFLICT (id) DO NOTHING
                            RETURNING active_skill_code, version
                            """)
                    .bind("code", nullable(activeSkillCode, String.class)).bind("adminId", adminId)
                    .map(HumanizeConfigRepository::map)
                    .one();
        }
        return db.sql("""
                        UPDATE humanize_config
                        SET active_skill_code = :code,
                            version = version + 1,
                            updated_by = :adminId,
                            updated_at = now()
                        WHERE id = 1 AND version = :expected
                        RETURNING active_skill_code, version
                        """)
                .bind("code", nullable(activeSkillCode, String.class)).bind("adminId", adminId)
                .bind("expected", expectedVersion)
                .map(HumanizeConfigRepository::map)
                .one();
    }

    private static HumanizeConfig map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new HumanizeConfig(row.get("active_skill_code", String.class), row.get("version", Long.class));
    }
}
```

（`nullable(value, nullType)` 来自项目现有 `config/R2dbcBindings`——`CreationStyleSkillRepository` 同款用法，可空列绑定统一走它，禁止自造帮助方法。）

### 3.6 `HumanizeInjectionService.java` 全文

文件：`.../humanize/HumanizeInjectionService.java`

```java
package com.grassland.intelligence.humanize;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.credits.CreditFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 统一注入（任务书 #61）：激活后往创作型文字生成的 system prompt 注入平台级文风规则。
 *
 * <ul>
 *   <li><b>fail-open</b>：任何读库异常 → 原样返回消息 + WARN，绝不阻断生成。</li>
 *   <li><b>直读无缓存</b>（照 #57 决策 F）：admin 改完/切换激活后下一次生成立即生效。</li>
 *   <li><b>注入形态</b>（照 #57 决策 D 的保守路径）：有 system 消息 → 追加到最后一条 system
 *       文本尾部；无 system 消息 → 头部插入一条新 system（四种方言均可消化，
 *       Anthropic 方言会合并进顶层 system 字段）。</li>
 * </ul>
 */
@Service
public class HumanizeInjectionService {

    private static final Logger log = LoggerFactory.getLogger(HumanizeInjectionService.class);

    /**
     * 创作型白名单（计费流）。feature == null 视为创作型注入——当前 Frozen 入口唯一传 null 的
     * 是文章 outline/content 任务模式（创作型）。分析型（VIDEO_ANALYSIS/INTELLIGENCE_SMOKE 等）
     * 不在集合内 → 不注入。
     */
    private static final Set<CreditFeature> CREATIVE_FEATURES = Set.of(
            CreditFeature.ARTICLE_GENERATION, CreditFeature.CREATION_ASSISTANT,
            CreditFeature.MOMENTS_GENERATION, CreditFeature.COMEDY_GENERATION,
            CreditFeature.VIDEO_PRODUCTION_SCRIPT, CreditFeature.VIDEO_STUDIO_BGM,
            CreditFeature.CARD_SERIES_PLAN, CreditFeature.IMAGE_ANALYSIS, CreditFeature.AI_RUN_TEXT);

    static final String SEGMENT_APPENDED = "\n\n【平台文风约束（最高优先级）】\n"
            + "以下规则只约束语言风格，与前文任何风格、语气要求冲突时以本段为准；"
            + "不得因此改变任何事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）；"
            + "也不要在输出中提及、解释或引用这些规则：\n";

    static final String SEGMENT_STANDALONE = "【平台文风约束（最高优先级）】\n"
            + "以下规则只约束语言风格，不得因此改变任何事实、数字、专有名词、代码与既定输出结构"
            + "（如 JSON 字段、标题层级、列表条目）；也不要在输出中提及、解释或引用这些规则：\n";

    private final HumanizeSkillRepository repository;

    public HumanizeInjectionService(HumanizeSkillRepository repository) {
        this.repository = repository;
    }

    /** 计费流入口（FrozenTextExecutionService 各入口调用）：白名单外原样返回（不查库）。 */
    public Mono<List<ChatMessage>> injectForFeature(List<ChatMessage> messages, CreditFeature feature) {
        if (feature != null && !CREATIVE_FEATURES.contains(feature)) {
            return Mono.just(messages);
        }
        return injectCreative(messages);
    }

    /** 免费创作流入口（调用方显式接入）：无条件走注入判定。 */
    public Mono<List<ChatMessage>> injectCreative(List<ChatMessage> messages) {
        return repository.findActiveSkill()
                .map(skill -> append(messages, skill.promptContent()))
                .defaultIfEmpty(messages)
                .onErrorResume(error -> {
                    log.warn("humanize injection skipped (fail-open): {}", error.getMessage());
                    return Mono.just(messages);
                });
    }

    /** 注入变换（纯函数，单测直测）：有 system 追加最后一条尾部；无 system 头部插入新 system。 */
    static List<ChatMessage> append(List<ChatMessage> messages, String promptContent) {
        int lastSystem = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("system".equals(messages.get(i).role())) {
                lastSystem = i;
                break;
            }
        }
        if (lastSystem >= 0) {
            ChatMessage original = messages.get(lastSystem);
            String base = original.content() == null ? "" : original.content();
            List<ChatMessage> result = new ArrayList<>(messages);
            result.set(lastSystem, ChatMessage.system(base + SEGMENT_APPENDED + promptContent));
            return List.copyOf(result);
        }
        List<ChatMessage> result = new ArrayList<>(messages);
        result.addFirst(ChatMessage.system(SEGMENT_STANDALONE + promptContent));
        return List.copyOf(result);
    }
}
```

### 3.7 `HumanizeSkillSeeder.java` 全文

文件：`.../humanize/HumanizeSkillSeeder.java`

```java
package com.grassland.intelligence.humanize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 启动种子（任务书 #61）：表空才按 {@code /contracts/humanize-skills.json}
 * 全量插入（3 条）。best-effort（照 {@code CreationStyleSkillSeeder} 姿态）：
 * 失败打 WARN 不阻断启动。JSON 解析用<b>自持</b> Jackson 实例（intelligence 无 ObjectMapper bean）。
 * {@code humanize_config} 不种（无行=未激活，admin 治理台主动开启）。
 */
@Component
public class HumanizeSkillSeeder {

    private static final Logger log = LoggerFactory.getLogger(HumanizeSkillSeeder.class);
    private static final Duration BLOCK = Duration.ofSeconds(20);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HumanizeSkillRepository repository;

    public HumanizeSkillSeeder(HumanizeSkillRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            Long count = repository.count().block(BLOCK);
            if (count != null && count > 0) {
                return;
            }
            List<SeedSkill> seeds = loadSeed();
            List<Mono<Void>> inserts = new ArrayList<>(seeds.size());
            for (SeedSkill seed : seeds) {
                inserts.add(repository.insertSeed(seed.code(), seed.displayName(), seed.description(),
                        seed.promptContent(), seed.sourceRepo(), seed.sourceLicense()));
            }
            Flux.concat(inserts).then().block(BLOCK);
            log.info("Seeded humanize skills: {} rows", seeds.size());
        } catch (Exception e) {
            log.warn("Humanize skill seed skipped (best-effort): {}", e.getMessage());
        }
    }

    private static List<SeedSkill> loadSeed() {
        try (var stream = HumanizeSkillSeeder.class.getClassLoader()
                .getResourceAsStream("contracts/humanize-skills.json")) {
            if (stream == null) {
                throw new IllegalStateException("contracts/humanize-skills.json missing from classpath"
                        + "（检查 build.gradle.kts processResources copySpec 是否登记）");
            }
            JsonNode root = MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            List<SeedSkill> seeds = new ArrayList<>();
            for (JsonNode node : root.path("skills")) {
                seeds.add(new SeedSkill(node.path("code").asText(), node.path("displayName").asText(),
                        node.path("description").asText(""), node.path("promptContent").asText(),
                        node.path("sourceRepo").asText(""), node.path("sourceLicense").asText("MIT")));
            }
            if (seeds.isEmpty()) {
                throw new IllegalStateException("humanize-skills.json skills 为空");
            }
            return seeds;
        } catch (Exception e) {
            throw new IllegalStateException("去AI味 skill 种子加载失败", e);
        }
    }

    private record SeedSkill(String code, String displayName, String description,
            String promptContent, String sourceRepo, String sourceLicense) {
    }
}
```

### 3.8 `HumanizeSkillController.java` 全文

文件：`.../humanize/HumanizeSkillController.java`

```java
package com.grassland.intelligence.humanize;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 治理台端点（任务书 #61）：
 *
 * <ul>
 *   <li>{@code GET /api/admin/humanize-skills}——全量行（含 promptContent）+ 当前激活项与配置版本。</li>
 *   <li>{@code PUT /api/admin/humanize-skills/{id}}——整行编辑（乐观锁 409）。</li>
 *   <li>{@code PUT /api/admin/humanize-skills/active}——切换单选激活（null=关闭注入；乐观锁 409）。</li>
 * </ul>
 */
@RestController
public class HumanizeSkillController {

    private final IntelligenceCallerResolver callers;
    private final HumanizeSkillRepository skills;
    private final HumanizeConfigRepository config;

    public HumanizeSkillController(IntelligenceCallerResolver callers, HumanizeSkillRepository skills,
            HumanizeConfigRepository config) {
        this.callers = callers;
        this.skills = skills;
        this.config = config;
    }

    @GetMapping("/api/admin/humanize-skills")
    public Mono<Map<String, Object>> adminList(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> Mono.zip(skills.listAll().collectList(), config.findOrDefault()))
                .map(tuple -> Map.<String, Object>of("success", true,
                        "data", Map.of("skills", tuple.getT1().stream()
                                .map(HumanizeSkillController::adminItem).toList(),
                                "activeSkillCode", tuple.getT2().activeSkillCode() == null ? "" : tuple.getT2().activeSkillCode(),
                                "configVersion", tuple.getT2().version())));
    }

    /** activeSkillCode 用空串表示 null（Map.of 不允许 null 值；前端约定空串=未激活）。 */
    private static Map<String, Object> adminItem(HumanizeSkill skill) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", skill.id().toString());
        item.put("code", skill.code());
        item.put("displayName", skill.displayName());
        item.put("description", skill.description());
        item.put("promptContent", skill.promptContent());
        item.put("sourceRepo", skill.sourceRepo());
        item.put("sourceLicense", skill.sourceLicense());
        item.put("enabled", skill.enabled());
        item.put("version", skill.version());
        item.put("updatedAt", skill.updatedAt() == null ? null : skill.updatedAt().toString());
        return item;
    }

    @PutMapping("/api/admin/humanize-skills/{id}")
    public Mono<Map<String, Object>> adminUpdate(@PathVariable UUID id, @RequestBody UpdateRequest body,
            ServerWebExchange exchange) {
        if (body.displayName() == null || body.displayName().isBlank()
                || body.displayName().trim().length() > 30) {
            throw new IntelligenceException(400, "名称不能为空且不超过 30 字");
        }
        if (body.promptContent() == null || body.promptContent().isBlank()
                || body.promptContent().trim().length() > 3000) {
            throw new IntelligenceException(400, "规则内容不能为空且不超过 3000 字");
        }
        if (body.expectedVersion() == null) {
            throw new IntelligenceException(400, "缺少版本号（expectedVersion）");
        }
        if (body.enabled() == null) {
            throw new IntelligenceException(400, "缺少启用状态（enabled）");
        }
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> skills.updateRow(id, body.displayName().trim(), body.description(),
                        body.promptContent().trim(), body.enabled(), body.expectedVersion(),
                        UUID.fromString(admin.accountId())))
                .map(skill -> Map.<String, Object>of("success", true,
                        "data", Map.of("skill", adminItem(skill))))
                .switchIfEmpty(Mono.defer(() -> Mono.error(
                        new IntelligenceException(409, "该规则已被他人修改，请刷新后重试"))));
    }

    /** 整行提交（可选字段一律包装类型——Jackson record 惯例，缺失即 400）。 */
    public record UpdateRequest(String displayName, String description, String promptContent, Boolean enabled,
            Integer expectedVersion) {
    }

    @PutMapping("/api/admin/humanize-skills/active")
    public Mono<Map<String, Object>> adminActivate(@RequestBody ActivateRequest body,
            ServerWebExchange exchange) {
        if (body.expectedConfigVersion() == null) {
            throw new IntelligenceException(400, "缺少配置版本号（expectedConfigVersion）");
        }
        String code = body.activeSkillCode() == null || body.activeSkillCode().isBlank()
                ? null : body.activeSkillCode().trim();
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> activate(admin.accountId(), code, body.expectedConfigVersion()))
                .map(configRow -> Map.<String, Object>of("success", true,
                        "data", Map.of("activeSkillCode",
                                configRow.activeSkillCode() == null ? "" : configRow.activeSkillCode(),
                                "configVersion", configRow.version())));
    }

    private Mono<HumanizeConfigRepository.HumanizeConfig> activate(String adminId, String code,
            long expectedVersion) {
        if (code == null) {
            return config.upsertActive(null, expectedVersion, adminId);
        }
        return skills.findByCode(code)
                .flatMap(skill -> skill.enabled()
                        ? config.upsertActive(code, expectedVersion, adminId)
                        : Mono.error(new IntelligenceException(400, "该规则已停用，请先启用再激活")))
                .switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(400, "未知的规则 code: " + code))));
    }

    /** activeSkillCode 传 null 或空串 = 关闭注入。 */
    public record ActivateRequest(String activeSkillCode, Long expectedConfigVersion) {
    }
}
```

（说明：`PUT /{id}` 与 `PUT /active` 并存时 Spring 精确字面路径优先于路径变量，`active` 不会被解析成 UUID，无需额外处理。）

### 3.9 admin API 契约（前端消费口径）

| 方法+路径 | 请求体 | 成功响应 data | 失败 |
|---|---|---|---|
| GET `/api/admin/humanize-skills` | - | `{skills:[{id,code,displayName,description,promptContent,sourceRepo,sourceLicense,enabled,version,updatedAt}], activeSkillCode:""|"code", configVersion:number}` | 非 admin 403 |
| PUT `/api/admin/humanize-skills/{id}` | `{displayName,description,promptContent,enabled,expectedVersion}` | `{skill:{...同上单行}}` | 400 校验 / 409 版本冲突或行不存在 |
| PUT `/api/admin/humanize-skills/active` | `{activeSkillCode:string\|null,expectedConfigVersion:number}` | `{activeSkillCode:""\|code,configVersion:number}` | 400 未知/停用 code / 409 配置版本冲突 |

信封：所有响应 `{success:true,data:...}` 或 `{success:false,error:"中文文案"}`（全局 ErrorHandler）。前端一律走 `src/composables/grassland-http.ts` 的 `request<T>()`（cookie 鉴权自动带）。

### 3.10 edge-bff 路由契约

`platform-java/services/edge-bff/src/main/resources/application.yml` 的 intelligence 路由列表内、#57 段之后新增：

```yaml
    # 任务书 #61：去AI味 skill 治理台读写。
    - path: /api/admin/humanize-skills
      upstream: intelligence
      enabled: ${EDGE_ROUTE_ADMIN_HUMANIZE_SKILLS_INTELLIGENCE:true}
```

并在 `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/RouteOwnershipContractTest.java` 中照 #57 的两行断言（92-93 行附近）同款补 `/api/admin/humanize-skills` 的路由归属断言。

## 4. 全局约束（适用于每一张卡）

- 只允许改动当前卡列出的文件，其他文件一律不碰。
- 不删除既有代码，除非当前卡明确要求。
- 不引入任何新依赖（gradle / npm 均禁止）。
- 不修改第 3 节任何签名、类型、模板常量和契约（含三段 promptContent 文本——一字不改）。
- 验收命令必须全绿才算完成；禁止为了绿灯修改断言或跳过用例。
- Java 构建：`cd /Users/LXH/claude/y-1/platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"`，统一用 `./gradlew`。若 spotless 格式检查失败，运行 `./gradlew :services:intelligence-service:spotlessApply`（或对应模块）后重跑，**不得手改无关文件**。
- 前端测试命令：在仓库根 `/Users/LXH/claude/y-1` 执行 `npx vitest run <文件>`。
- 迁移 DDL 一律幂等（`IF NOT EXISTS`），本计划已写好，照抄即可。
- **卡住时**：同一问题最多尝试 2 次，然后停止，原样报告错误信息和已尝试的做法。禁止猜测、禁止绕过、禁止编造一个看起来合理的结果。
- 每张卡完成后必须按卡面格式报告；「偏离卡面之处」必须如实列出。

## 5. 任务总表

| 卡 | 标题 | 主要文件 | 依赖 | 验收方式 |
|---|---|---|---|---|
| 1 | 迁移+实体+仓储+种子契约 | V58 sql / humanize 包 4 文件 / contracts JSON / build.gradle.kts | 无 | 编译过 + V58 语法自查 |
| 2 | 注入服务 + Mockito 单测 | HumanizeInjectionService + Test | 卡1 | 单测类全绿 |
| 3 | Seeder + 治理台 Controller + edge-bff 路由 | Seeder / Controller / application.yml / 契约测试 | 卡1 | edge-bff 契约测试绿 + intelligence 编译过 |
| 4 | 后端 IT | HumanizeSkillIT | 卡3 | IT 类全绿 |
| 5 | 计费流接线（Frozen 7 入口） | FrozenTextExecutionService | 卡2 | intelligence 全量 test 绿 |
| 6 | 免费流接线（5 文件）+ 受影响单测修复 | ArticleController 等 5 文件 + 测试补桩 | 卡2 | intelligence 全量 test 绿 |
| 7 | 治理台前端 | Panel / AdminView / 测试 / DESIGN.md 对照 | 卡3 | 前端 vitest 绿 |
| 8 | 集成验收 | 无新文件 | 全部 | 后端全量+前端全量+lint |

## 6. 任务卡

### 卡 1：V58 迁移 + 实体 + 双仓储 + 种子契约文件

**背景**：去AI味功能的数据层。两张新表（规则库 + 单行激活配置）+ 三个 Java 数据层类 + 种子 JSON。全部是新建文件，零风险新增。

**改动文件**：
- 新建 `platform-java/services/intelligence-service/src/main/resources/db/migration/V58__humanize_skill.sql`
- 新建 `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/humanize/HumanizeSkill.java`
- 新建 `.../humanize/HumanizeSkillRepository.java`
- 新建 `.../humanize/HumanizeConfigRepository.java`
- 新建 仓库根 `/Users/LXH/claude/y-1/contracts/humanize-skills.json`
- 修改 `platform-java/services/intelligence-service/build.gradle.kts`（仅 processResources copySpec 一处）

**开始前检查**：
- `ls platform-java/services/intelligence-service/src/main/resources/db/migration/ | tail -3` 确认最大版本是 V57（若已有 V58 占用，停止并报告，不得顺延改号）。
- `ls contracts/` 确认无 humanize-skills.json。

**参考代码**：
- 迁移 SQL、实体、两个 Repository 全文 = **第 3.1 / 3.3 / 3.4 / 3.5 节**，直接照抄（3.5 节注意其中关于 `nullable` 绑定的说明文字——只照抄最终正确版本，不要抄 3.5 中标注「占位是错的」的两段）。
- 种子 JSON 全文 = **第 3.2 节**。这是 JSON 文件，必须保持合法 JSON（中文原文中的 `\n` 是转义符）。落盘后用 `python3 -c "import json;json.load(open('contracts/humanize-skills.json'));print('ok')"` 自查。

**做法**：
1. 依次落盘 4 个新 Java 文件与迁移 SQL（内容=第 3 节对应小节，包名 `com.grassland.intelligence.humanize`）。
2. 落盘 `contracts/humanize-skills.json`（第 3.2 节原文）。
3. 打开 `platform-java/services/intelligence-service/build.gradle.kts`，找到 `tasks.processResources` 中 #57 的 copySpec（约 76-78 行，注释「任务书 #57：小红书图文创作风格 skill 种子」），在其后**照同款格式**追加：
```kotlin
    // 任务书 #61：去AI味 skill 种子（平台级单选激活，启动 Seeder 表空才种）。
    from(rootProject.file("../contracts/humanize-skills.json")) {
        into("contracts")
    }
```
4. 编译验证（见验收）。

**边界**：
- 不写 HumanizeInjectionService / Seeder / Controller（卡2/卡3 的事）。
- 不动 V55 及任何既有迁移。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:compileJava`，期望 BUILD SUCCESSFUL。
- `python3 -c "import json;json.load(open('/Users/LXH/claude/y-1/contracts/humanize-skills.json'));print('ok')"`，期望输出 `ok`。

**完成后按此格式报告**：
```
改动文件：...
执行的命令与结果：...
偏离卡面之处：无 / <列出>
卡住项：无 / <错误原文>
```

### 卡 2：HumanizeInjectionService + Mockito 单测

**背景**：注入判定与消息变换的核心逻辑。fail-open + 白名单 + 两种注入形态（追加最后一条 system / 头部插入新 system）。

**改动文件**：
- 新建 `.../humanize/HumanizeInjectionService.java`（全文=第 3.6 节）
- 新建 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/humanize/HumanizeInjectionServiceTest.java`

**开始前检查**：卡 1 已完成、`./gradlew :services:intelligence-service:compileJava` 绿。

**参考代码**（测试风格参照——Mockito 纯单测，参照 `src/test/java/com/grassland/intelligence/ai/controlplane/PlatformModelControlPlaneServiceTest.java` 的结构）：
```java
@ExtendWith(MockitoExtension.class)
@DisplayName("HumanizeInjectionService")
class HumanizeInjectionServiceTest {
    @Mock
    HumanizeSkillRepository repository;
    @InjectMocks
    HumanizeInjectionService service;
    // ChatMessage 构造：com.grassland.intelligence.ai.ChatMessage.system("...") / ChatMessage.user("...")
}
```

**做法**：
1. 落盘 `HumanizeInjectionService.java`（第 3.6 节原文）。
2. 新建测试类，**必须覆盖以下 8 个用例**（`@DisplayName` 用中文）：
   - `injectForFeature` feature=VIDEO_ANALYSIS（白名单外）→ 返回原列表，且 `verifyNoInteractions(repository)`。
   - `injectForFeature` feature=ARTICLE_GENERATION 且 `findActiveSkill()` 为 `Mono.empty()` → 原样返回（列表逐元素 equals 原列表）。
   - `injectForFeature` feature=null（视为创作型）且激活 → 注入生效。
   - `injectCreative` 激活 + 消息含一条 system（前）+ 两条 user → 最后一条 system content 尾部含 `SEGMENT_APPENDED` 文本与 promptContent，user 消息不变，列表长度不变。
   - `injectCreative` 激活 + 消息含两条 system（`[system("A"), system("B"), user("C")]`）→ 注入进**最后一条**（"B"那条），"A" 不变。
   - `injectCreative` 激活 + 只有 user 消息（含一个多模态 `ChatMessage.user(List.of(ContentPart.text("x")))`，其 content()==null）→ 头部插入一条新 system（role=system、content 以 `SEGMENT_STANDALONE` 开头），原消息全部保持原位且多模态消息的 parts 不变。
   - fail-open：`when(repository.findActiveSkill()).thenReturn(Mono.error(new RuntimeException("db down")))` → 原样返回，不抛错。
   - `append` 纯函数直测：promptContent 完整出现在结果里（无截断）。
   - 激活 skill 的 stub 写法：`when(repository.findActiveSkill()).thenReturn(Mono.just(new HumanizeSkill(UUID.randomUUID(), "shuorenhua", "说人话", "", "RULE-BODY-XYZ", "", "MIT", true, 0, null, null)))`。
3. 跑测试（见验收）。

**边界**：
- 不接线任何调用方（卡5/卡6 的事）。
- 不写 IT（卡4 的事）。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.humanize.HumanizeInjectionServiceTest"`，期望 BUILD SUCCESSFUL（8 用例全绿）。

**完成后按此格式报告**：（同上格式）

### 卡 3：Seeder + 治理台 Controller + edge-bff 路由

**背景**：启动种子（表空才种 3 条）+ 治理台 CRUD/激活端点 + BFF 路由放行。

**改动文件**：
- 新建 `.../humanize/HumanizeSkillSeeder.java`（全文=第 3.7 节）
- 新建 `.../humanize/HumanizeSkillController.java`（全文=第 3.8 节）
- 修改 `platform-java/services/edge-bff/src/main/resources/application.yml`（加一段路由）
- 修改 `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/RouteOwnershipContractTest.java`（加断言）

**开始前检查**：卡 2 完成；`grep -n "creation-style-skills" platform-java/services/edge-bff/src/main/resources/application.yml` 有输出（定位插入点）。

**参考代码**：
- Seeder/Controller 全文=第 3.7 / 3.8 节。
- edge-bff 路由段与契约测试断言=第 3.10 节。RouteOwnershipContractTest 的 #57 断言在 92-93 行附近，照其同款写法紧随其后补 humanize-skills 的两行。

**做法**：
1. 落盘 Seeder 与 Controller。
2. application.yml：在 #57 的 `/api/admin/creation-style-skills` 段之后插入第 3.10 节的 yaml 段（注意缩进与上下文一致）。
3. RouteOwnershipContractTest：照 #57 断言同款补两行。
4. 编译 + 跑 edge-bff 测试（见验收）。intelligence 全量 test 留到卡4 一起跑。

**边界**：
- 不写 IT（卡4）。
- 不改 Controller 之外的任何端点。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:compileJava :services:edge-bff:test`，期望 BUILD SUCCESSFUL。

**完成后按此格式报告**：（同上格式）

### 卡 4：HumanizeSkillIT（后端全链集成测试）

**背景**：真库验证——种子幂等、admin 鉴权、列表、编辑乐观锁、激活切换、注入读库联动。测试容器自跑 intelligence Flyway，V58 自动建表，**不需要**在 IntelligenceItSupport 补 DDL（自有表铁律）。

**改动文件**：
- 新建 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/humanize/HumanizeSkillIT.java`

**开始前检查**：卡 3 完成；Docker Desktop 在运行（`docker info` 正常——Testcontainers 需要）。

**参考代码**（测试基座与工具——全部来自 `src/test/java/com/grassland/intelligence/IntelligenceItSupport.java`，直接继承使用）：
```java
class HumanizeSkillIT extends IntelligenceItSupport {
    @Autowired HumanizeSkillRepository skills;
    @Autowired HumanizeConfigRepository config;
    @Autowired HumanizeSkillSeeder seeder;
    // HTTP 客户端：client() 返回 WebTestClient（responseTimeout 已 30s）
    // 管理员签名：signAdmin(accountId) → X-Grassland-Identity 头
    // 普通用户签名：sign(accountId, "user")
    // DatabaseClient 直接 SQL：@Autowired DatabaseClient db
}
```
参照样板：`src/test/java/com/grassland/intelligence/creationstyle/CreationStyleSkillIT.java`（结构、签发方式、snapshot/restore 手法全部同款）。

**做法**：
1. 新建 `HumanizeSkillIT extends IntelligenceItSupport`，类上无注解（`@SpringBootTest` 在基类）。
2. **用例清单（必须全部实现）**：
   - 种子：启动后 `skills.count()` == 3，且三行 code 分别为 `shuorenhua` / `lieflat-11` / `qu-ai-wei`、`source_license` 均为 MIT；再次调 `seeder.seedOnStartup()` 后 count 仍为 3（幂等）。
   - 鉴权：GET `/api/admin/humanize-skills` 不带签名头 → 401；普通用户签名 → 403。
   - 列表：admin 签名 GET → 200，`$.success` 为 true，`$.data.skills.length()` == 3，第一条含 `promptContent` 键，`$.data.activeSkillCode` == ""，`$.data.configVersion` == 0。
   - 编辑：取第一行 id，PUT `{displayName:"说人话", description:"x", promptContent:"新内容", enabled:true, expectedVersion:0}` → 200 且 `$.data.skill.version` == 1；用旧 expectedVersion=0 再 PUT → 409；PUT 随机 UUID → 409；promptContent 传 3001 字 → 400。
   - 激活：PUT `/api/admin/humanize-skills/active` body `{activeSkillCode:"bogus", expectedConfigVersion:0}` → 400；`{activeSkillCode:"shuorenhua", expectedConfigVersion:0}` → 200 且 `configVersion` == 1；随后 GET 列表 `activeSkillCode` == "shuorenhua"；再用旧 expectedConfigVersion=0 PUT → 409；`{activeSkillCode:null, expectedConfigVersion:1}` → 200 关闭（activeSkillCode 回 ""）。
   - 停用联动：重新激活 shuorenhua 后，SQL `UPDATE humanize_skill SET enabled=false WHERE code='shuorenhua'`（用 db 直改，随后恢复），断言 `skills.findActiveSkill().block()` 为 null。
   - **恢复现场**：测试类收尾（@AfterEach 或每用例末）把 `humanize_config` 清空（`DELETE FROM humanize_config`）并把被改过的 skill 行恢复（参照 CreationStyleSkillIT 的 snapshot/restore 手法）——共享容器里种子 3 行是全套件公共前提，污染会导致其他 IT 挂。
3. 跑 IT（见验收）。

**边界**：
- 不测注入对生成的端到端效果（LLM 行为不可断言）。
- 不碰 `IntelligenceItSupport`。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.humanize.HumanizeSkillIT"`，期望 BUILD SUCCESSFUL。
- 再跑 `./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.creationstyle.CreationStyleSkillIT"`，期望仍绿（证明现场恢复干净）。

**完成后按此格式报告**：（同上格式）

### 卡 5：计费流接线——FrozenTextExecutionService 7 个入口

**背景**：把注入挂进计费执行环。所有创作型计费流（文章/助手/朋友圈/短剧/视频脚本/BGM/拆卡/图片评价管线/视频改编任务）自动覆盖；白名单外 feature 原样直通，行为零变化。

**改动文件**：
- 修改 `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/run/FrozenTextExecutionService.java`

**开始前检查**：卡 2 完成；`./gradlew :services:intelligence-service:compileJava` 绿；确认全库无手工 `new FrozenTextExecutionService(`（已核实为零，如执行中 grep 到请报告）。

**参考代码**（当前文件结构关键段，执行时以此为准）：
```java
// 构造器（现状，26-31 行）——加第四个参数：
	public FrozenTextExecutionService(AiExecutionService executions, TextCompletionClient textClient,
			PlatformConcurrencyLimiter concurrencyLimiter) { ... }

// 单次入口模式（现状，execute 33-40 行）：
	public <T> Mono<T> execute(ServerWebExchange exchange, UUID snapshotId, List<ChatMessage> messages, int maxTokens,
			CreditFeature feature, Function<TextCompletionResult, T> transform) {
		int estimatedInputTokens = messages.stream().mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		return executions.prepareExecution(exchange, "text", feature, estimatedInputTokens, maxTokens, true, snapshotId)
				.flatMap(result -> result.allowed()
						? executePrepared(result.context(), messages, maxTokens, null, transform)
						: Mono.error(deniedException(result.denialReason())));
	}

// 管线内核（现状，137-153 行，IndependentStageExecutor 是轮次消息必经点）：
	private <T> Mono<Traced<T>> executePipelinePrepared(AiExecutionService.ExecutionContext context,
			java.time.Duration timeout, int maxTokensPerRound, Function<IndependentStageExecutor, Mono<T>> pipeline) {
		...
		IndependentStageExecutor stages = messages -> textClient
				.completeMessages(context.provider().provider(), context.provider().baseUrl(), bearer,
						context.provider().model(), messages, maxTokensPerRound,
						context.provider().isByok(), timeout)
				.map(completion -> executions.normalizeProviderUsage(context, completion))
				.doOnNext(completion -> { ... });
```

**做法**：
1. 构造器注入 `HumanizeInjectionService humanize`（import `com.grassland.intelligence.humanize.HumanizeInjectionService`；字段 private final；参数加在 `concurrencyLimiter` 之后）。
2. **单次入口 ×3**（`execute`、`executeIndependent` 带 timeout 重载、`executeTraced`；无 timeout 的 `executeIndependent` 是纯委托不用改）：统一模式——保持第一行 `estimatedInputTokens` 用**原 messages** 计算，然后把原方法体包进注入 flatMap，内核调用处 `messages` 换成注入结果。以 `execute` 为例改后：
```java
	public <T> Mono<T> execute(ServerWebExchange exchange, UUID snapshotId, List<ChatMessage> messages, int maxTokens,
			CreditFeature feature, Function<TextCompletionResult, T> transform) {
		int estimatedInputTokens = messages.stream().mapToInt(FrozenTextExecutionService::estimatedMessageBytes).sum();
		return humanize.injectForFeature(messages, feature)
				.flatMap(humanized -> executions
						.prepareExecution(exchange, "text", feature, estimatedInputTokens, maxTokens, true, snapshotId)
						.flatMap(result -> result.allowed()
								? executePrepared(result.context(), humanized, maxTokens, null, transform)
								: Mono.error(deniedException(result.denialReason()))));
	}
```
   `executeTraced`、`executeIndependent`（带 timeout 重载）同型改法（`executePrepared`/`executeTracedPrepared` 调用处传 `humanized`）。
3. **批量入口 ×2**（`executeIndependentBatch`、`executeBatch`）：模式——估价仍用原 `messageBatches`，方法体包进批量注入：
```java
		return Flux.fromIterable(messageBatches)
				.concatMap(batch -> humanize.injectForFeature(batch, feature))
				.collectList()
				.flatMap(humanizedBatches -> { /* 原方法体，messageBatches 全部替换为 humanizedBatches（估价行保持在 flatMap 外用原名） */ });
```
4. **管线入口** `executeIndependentPipeline`：把 `feature` 传进私有内核——`executePipelinePrepared(result.context(), timeout, maxTokensPerRound, pipeline)` 改为 `executePipelinePrepared(result.context(), feature, timeout, maxTokensPerRound, pipeline)`，私有方法签名加 `CreditFeature feature` 参数；内核里 stages lambda 改为：
```java
					IndependentStageExecutor stages = messages -> humanize.injectForFeature(messages, feature)
							.flatMap(humanized -> textClient
									.completeMessages(context.provider().provider(), context.provider().baseUrl(), bearer,
											context.provider().model(), humanized, maxTokensPerRound,
											context.provider().isByok(), timeout))
							.map(completion -> executions.normalizeProviderUsage(context, completion))
							.doOnNext(completion -> {
								inputTokens.addAndGet(completion.inputTokens());
								outputTokens.addAndGet(completion.outputTokens());
							});
```
5. 全量跑 intelligence 测试（见验收）。现有 IT 用真实 humanize bean，`humanize_config` 无行 → 不注入 → 全部行为不变，理应零修改通过。

**边界**：
- 不改 `AiExecutionService`、`TextCompletionClient`、`RoutedTextCompletionService`。
- 不改任何 `estimatedInputTokens` 计算口径。
- 不动 `AiRunController` 与 `ContentSafetyAiChecker`（范围外）。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:test`，期望 BUILD SUCCESSFUL（全量，含既有 IT——若个别 IT 因环境（Docker 未起/连接耗尽）失败而与本卡无关，报告原文，不得自行跳过）。

**完成后按此格式报告**：（同上格式）

### 卡 6：免费创作流接线（5 文件）+ 受影响单测补桩

**背景**：免费流（Routed 通道）没有 feature 维度，创作型调用点显式接 `injectCreative`。同时修复因构造器新参数而需要补桩的 Mockito 单测。

**改动文件**：
- 修改 `.../article/ArticleController.java`（2 处）
- 修改 `.../imageanalysis/ImageAnalysisService.java`（2 处）
- 修改 `.../videorecreation/VideoRecreationAdaptationService.java`（1 处）
- 修改 `.../articleimage/ArticleImageService.java`（2 处）
- 修改 `.../guesttrial/GuestTrialService.java`（2 处）
- 修改受影响测试：`src/test/.../imageanalysis/ImageAnalysisServiceTest.java`、`src/test/.../articleimage/ArticleImageServiceTest.java`（以及 grep 出的其他编译失败项）

**开始前检查**：卡 5 完成、全量 test 绿。

**参考代码**（各调用点现状 → 改法，全部是「构造器加字段 + 调用点包 injectCreative」同型变换）：

变换模板（非流式）：
```java
// 前
return routed.completeFor(accountId, organizationId, messages, 2048, TIMEOUT, "失败文案")...
// 后
return humanize.injectCreative(messages)
        .flatMap(msgs -> routed.completeFor(accountId, organizationId, msgs, 2048, TIMEOUT, "失败文案"))...
```
变换模板（流式）：
```java
// 前
Flux<ChatChunk> flux = routed.streamWith(resolution, messages, 2048, null, "失败文案");
// 后
Flux<ChatChunk> flux = humanize.injectCreative(messages)
        .flatMapMany(msgs -> routed.streamWith(resolution, msgs, 2048, null, "失败文案"));
```

**做法**：
1. **ArticleController**（构造器注入 `HumanizeInjectionService humanize` 字段）：
   - outline 独立模式（约 156-165 行）：`routed.streamWith(resolution, List.of(ArticlePrompts.outlineSystem(platform), ArticlePrompts.outlineUser(...)), ...)` → 先把 `List.of(...)` 提为局部变量 `List<ChatMessage> messages`，再按流式模板包。
   - content 独立模式（约 184-186 行）：同法处理 `List.of(system, ArticlePrompts.contentUser(...))`（注意后面 `concatWith(Mono.defer(...))` 的 lineage 链保持不动，只包 streamWith 那一段——把 `routed.streamWith(...)` 起点到 `.onErrorResume(...)` 的流构造段包进 `humanize.injectCreative(...).flatMapMany(...)`，`map/doOnNext/onErrorResume/concatWith` 等链式调用全部移入 flatMapMany 内继续）。
2. **ImageAnalysisService**（构造器加字段）：`completeMultimodal`（236 行）与 `completeText`（241 行）两处，各把 `routed.complete(exchange, List.of(...), ...)` 按非流式模板包。这两个调用点只有 user 消息——注入服务会走「头部插入 system」分支，属预期。
3. **VideoRecreationAdaptationService**（构造器加字段）：`adapt` 方法（65-67 行）`routed.resolveFor(...).flatMap(resolution -> routed.completeWith(resolution, List.of(ChatMessage.user(parts)), ...))` → 在 completeWith 外包 `humanize.injectCreative(...).flatMap(...)`（多模态 user-only 消息，同样走头部插入分支）。
4. **ArticleImageService**（构造器加字段）：`recommend`（63-66 行）与 `describe`（98-102 行）两处，非流式模板。
5. **GuestTrialService**（构造器加字段）：私有 `complete(List<ChatMessage> messages)`（52-56 行）一处包裹（titles/score 自动覆盖）；`imageReview`（47 行）也包上（多模态无 system → 注入服务自动跳过原样返回，包裹是为了语义统一）。
6. **受影响单测补桩**：先跑
   `./gradlew :services:intelligence-service:compileTestJava`，收集编译错误清单。对每个报「构造器参数不匹配」的测试类（已知大概率：`ImageAnalysisServiceTest`、`ArticleImageServiceTest`；可能还有其他），补标准桩——类内加：
```java
    @Mock
    com.grassland.intelligence.humanize.HumanizeInjectionService humanize;
```
   并在 `@BeforeEach`（无则新建）加统一透传 stub：
```java
        lenient().when(humanize.injectCreative(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> reactor.core.publisher.Mono.just(invocation.getArgument(0)));
```
   （用 `lenient()` 防止 UnnecessaryStubbing；import `org.mockito.Mockito.lenient`。）
7. 全量跑（见验收）。

**边界**：
- **不改** StylePreferencesService、VerificationAnalysisService、KybDocumentAnalysisService、StoreMediaModerationService（分析/治理型，范围外——一处都不要碰）。
- 不改 `RoutedTextCompletionService` 本身。
- 不为通过测试而弱化断言。

**验收**：
- `cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:test`，期望 BUILD SUCCESSFUL。

**完成后按此格式报告**：（同上格式，偏离处重点列出你补桩的测试类清单）

### 卡 7：治理台前端——去AI味页签

**背景**：治理台新页签：3 行规则卡（来源 repo/license 展示）+ 编辑弹窗（GlModal）+ 平台级单选激活（含「不注入」默认项）。

**改动文件**：
- 新建 `src/ops/admin/components/HumanizeSkillsAdminPanel.vue`
- 新建 `src/ops/admin/components/HumanizeSkillsAdminPanel.test.ts`
- 修改 `src/ops/admin/AdminView.vue`（4 处：tab 按钮 / panel 挂载 / import / activeSection 类型）

**开始前检查**：
- 卡 3 完成（API 契约可用）。
- **先读 `src/ops/DESIGN.md` 全文**（AGENTS.md 硬性规则：改治理台 UI 前必读）。
- 读参照文件：`src/ops/admin/components/CreationSkillsAdminPanel.vue`（结构基调）+ `src/components/AiPlatformCredentialsPanel.vue` 的 GlModal 用法 + `src/ops/admin/components/CreationSkillsAdminPanel.test.ts`（测试基调）。

**参考代码**：
- HTTP 封装（`src/composables/grassland-http.ts`）：
```ts
import { request, GrasslandHttpError } from '../../../composables/grassland-http'
const data = await request<{ skills: AdminSkill[]; activeSkillCode: string; configVersion: number }>(
  '/api/admin/humanize-skills')
// PUT 行编辑 / PUT active 同 request(url, { method: 'PUT', body: JSON.stringify(...) })
// 409 判断：err instanceof GrasslandHttpError && err.status === 409 → 提示「已被他人修改，请刷新后重试」
```
- AdminView tab 挂载 4 处改法（`creation-skills` 之后追加）：
```ts
// ① import 区
import HumanizeSkillsAdminPanel from './components/HumanizeSkillsAdminPanel.vue'
// ② activeSection 类型联合末尾追加 'humanize-skills'
// ③ .admin-tabs 内 creation-skills 按钮之后（保持 DOM 最末，测试按下标点页签，不得插中间）：
      <button v-if="!reviewerOnly" type="button" role="tab"
        :aria-selected="activeSection === 'humanize-skills'"
        :class="{ active: activeSection === 'humanize-skills' }"
        @click="activeSection = 'humanize-skills'">去AI味</button>
// ④ 面板挂载区（creation-skills panel div 之后）：
    <div v-else-if="activeSection === 'humanize-skills'" class="admin-panel" role="tabpanel">
      <HumanizeSkillsAdminPanel />
    </div>
```

**做法**：
1. **先读 DESIGN.md**，颜色/圆角/间距只用 token（`var(--…)`），禁止新 hex。
2. 新建 `HumanizeSkillsAdminPanel.vue`，结构与行为：
   - `onMounted` 调 `load()`：GET 列表 → `skills / activeSkillCode(''=未激活) / configVersion` 三个 ref；加载失败显示 error 行 + 重试按钮（照 CreationSkillsAdminPanel 姿态）。
   - 主体：每行一张卡/表格行——`displayName`、`code`、`description`、来源（`sourceRepo` 短链 + `sourceLicense` badge）、启用开关（change 即 PUT 行编辑，`enabled` 字段整行提交）、「编辑内容」按钮。
   - 编辑弹窗：`GlModal`（props `title="编辑去AI味规则"`，`@close` 关闭），内含 `gl-field` 风格的 description 输入 + promptContent textarea（maxlength 3000 + 字数提示）+ enabled checkbox + 保存/取消（actions 插槽）；保存 = PUT `/api/admin/humanize-skills/{id}` 整行（displayName 取行现值，expectedVersion 取行 version）；409 → 「已被他人修改，请刷新后重试」。
   - 激活区：radio 组「不注入」（value=''）+ 每个**启用中**的 skill 一项（停用行禁用并标注「已停用」）；change 即 PUT `/active` body `{activeSkillCode: code || null, expectedConfigVersion: configVersion}`，成功后用响应回写 `activeSkillCode/configVersion`；顶部说明文案：「激活后，所有创作型 AI 文字生成（文章/朋友圈/短剧/视频脚本等 12 场景）将自动注入所选规则；修改即刻生效」。
   - `data-test` 锚点（必加，供测试与后续浏览器实测）：`humanize-skill-row-<code>`、`humanize-skill-edit-<code>`、`humanize-skill-toggle-<code>`、`humanize-skill-modal-prompt`、`humanize-skill-modal-save`、`humanize-skill-modal-error`、`humanize-activate-off`、`humanize-activate-<code>`。
3. 新建 `HumanizeSkillsAdminPanel.test.ts`（happy-dom；**mount 必须带 `stubs: { teleport: true }`**——否则 GlModal 内容渲染到 body、findAll 为空；照 CreationSkillsAdminPanel.test.ts 的 mock `request` 手法）：
   - 用例①：mock GET 返回 3 行 + activeSkillCode='' → 渲染 3 行，radio「不注入」选中，停用行（mock 一行 enabled=false）的激活 radio 带 disabled。
   - 用例②：点编辑 → 弹窗显示 promptContent 回显；mock PUT 成功 → 弹窗关闭、行内容更新。
   - 用例③：mock PUT 抛 `new GrasslandHttpError(409, 'x')` → 显示「已被他人修改，请刷新后重试」。
   - 用例④：点某 skill 的激活 radio → 断言 request 被以 `/api/admin/humanize-skills/active` + 正确 body 调用，成功后选中态切换。
4. 修改 AdminView.vue 四处（参考代码 ①-④）。
5. 跑测试 + AdminView 既有测试回归（见验收）。

**边界**：
- 不改 `CreationSkillsAdminPanel.vue` 及任何其他面板。
- 不新增 npm 包；不写 scoped 新样式除非 DESIGN.md token 覆盖不到（优先复用全局 `.admin-panel` / `.gl-field` / `.badge` 等）。
- AdminView.test.ts 若因新 tab 下标失败：新 tab 必须在 DOM **最末**（creation-skills 之后），不得插中间；若仍失败按原文错误报告，不得改既有断言。

**验收**：
- 仓库根：`npx vitest run src/ops/admin/components/HumanizeSkillsAdminPanel.test.ts src/ops/admin/AdminView.test.ts`，期望全绿。
- `npx vue-tsc --noEmit -p tsconfig.json`（若项目无此 script 或命令形态不同，改跑 `npm run build`；任一通过即可），期望无类型错误。

**完成后按此格式报告**：（同上格式）

### 卡 8：集成验收（无新代码）

**背景**：全量门禁。

**改动文件**：无（只跑命令；若前卡遗漏导致失败，按错误报告并回到对应卡修复，不在本卡写新代码）。

**做法与验收**：
1. 后端全量：`cd platform-java && export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" && ./gradlew :services:intelligence-service:test :services:edge-bff:test` → BUILD SUCCESSFUL。
2. 前端全量：仓库根 `npx vitest run` → 全绿。
3. `git status` 检查改动文件清单与本计划各卡列出的清单一致，无计划外文件。
4. （人工/强模型触发，弱模型跳过）部署冒烟：治理台「去AI味」页签激活「说人话」→ 创作中心生成一篇文章 → ai_run 留痕的 prompt_text（creation_generation 表）应包含「【平台文风约束（最高优先级）】」字样。

**完成后按此格式报告**：（同上格式）

## 7. 集成验收（全部卡完成后执行，可由强模型或人工触发）

- 端到端（部署后人工）：
  1. 治理台 → 去AI味 → 三行种子可见（含来源 repo 与 MIT 标注）。
  2. 激活「说人话」→ 立即生成文章正文 → `creation_generation.prompt_text` 含「【平台文风约束（最高优先级）】」，生成内容风格符合规则（人工比对）。
  3. 切回「不注入」→ 再生成 → prompt_text 不含该字样。
  4. 编辑某行 promptContent（如尾部加标记词）→ 再生成 → 新内容生效（直读无缓存验证）。
  5. 游客试用 titles / 视频分析（B站）对比：前者注入、后者不注入（分析流排除验证）。
- 回归：`./gradlew :services:intelligence-service:test :services:edge-bff:test` 全绿；前端 `npx vitest run` 全绿。
- 产物检查：`./gradlew :services:intelligence-service:spotlessCheck`（或随 test 已含）；前端 type-check/build 过。

## 附：返工卡格式（强模型 review 后按此格式产出，编号 R-1、R-2…）

### 返工卡 R-x（针对卡 N）
**问题位置**：`<文件:行>`
**期望行为 vs 实际行为**：<一句话对比>
**修复做法**：
1. <步骤化，同任务卡写法>
**验收**：运行 `<命令>`，期望 `<…>`

---

## 附：给强模型（验收人）的备注

- 注入覆盖核对口径：激活任一 skill 后，12 场景的计费流看 `ai_run` 相关留痕（creation_generation.prompt_text / ai_run 记录），免费流靠代码 review（卡6 五文件）。
- 三段 promptContent 为蒸馏定稿（来源版本：shuorenhua v2.4.0 / lieflat-less-ai-tone 当前 main / qu-ai-wei v0.9.0，均 MIT）。入库即快照，上游更新不自动跟进；治理台可随时手改，改坏可将 `humanize_skill` 清空重启以恢复种子。
- 已知设计取舍（无需返工）：估价不含注入增量（<3k 字符，远小于预算粒度）；`AI_RUN_TEXT` 白名单依据 Frozen 通道当前唯一消费者为视频改编；游客 imageReview 因无 system 消息自动跳过。
