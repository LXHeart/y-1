<template>
  <section
    v-if="mode === 'check' || inlineReady"
    class="xhs-compliance"
    :data-mode="mode"
    aria-label="平台合规检测"
  >
    <header class="xhs-compliance-head">
      <h3>平台合规检测</h3>
      <p class="xhs-compliance-note">词库 + AI 语境检测；提醒为建议性质，不阻断发布。</p>
    </header>

    <!-- proof 步：检查舞台（只读预览高亮 + 逐项修复 + 放行） -->
    <ArticleCheckStage
      v-if="mode === 'check'"
      :content="content"
      :safety-report="safetyReport"
      platform="xiaohongshu"
      :content-form="undefined"
      :safety-checking="safetyChecking"
      :images-stage-skipped="true"
      :genre-name="genreName"
      :style-name="styleName"
      @recheck="context.engine.checkSafety"
      @go-edit="goEdit"
      @proceed="context.engine.proceedFromCheck"
      @rechecked="context.engine.onPanelRechecked"
      @apply-fix="context.engine.applySafetyFix"
    />

    <!-- generate 步：正文期安全 findings 内联展示（advisory，不开逐项修复） -->
    <SafetyFindingsPanel
      v-else
      :report="safetyReport"
      :text="content"
      platform="xiaohongshu"
      @updated="context.engine.onPanelRechecked"
    />
  </section>
</template>

<script setup lang="ts">
/**
 * 平台合规检测区块（方案 §4.3 工程师B / §3 gen-compliance reuse）：双模式复用包装。
 * - mode='check'（proof 步）挂 ArticleCheckStage：go-edit→steps.go('generate')（状态机协同
 *   把引擎 stage 置回 'content' 回编辑工位）；proceed→engine.proceedFromCheck()——noteMode
 *   （imagesStageSkipped=true）内部直接 finish()，completed=true 后步骤机唯一自动跳转进
 *   publish（联动断言在 useXhsStudioSteps 测试，本组件只透传）。
 * - mode='inline'（generate 步）挂 SafetyFindingsPanel——【F-D】挂载条件=safetyReport 存在
 *   且正文非空：fetchTitles 返回的标题期报告（useArticleCreation.ts:308）与空正文语境错配
 *   （SafetyFindingsPanel 对空 text 的复查静默无效），正文安全随正文出现、enterCheck 自动
 *   复查后自愈，窗口期不渲染错配报告。
 * - contentForm 仅知乎需要（useArticleCreation.ts:424-425 同判据），小红书恒不传；
 *   顶部说明只陈述真实语义，无「N 条规则/重复度」类假数字。
 * - 数据经 useXhsStudioContext() 只读自取（F7）；props 仅作最小测试覆写口。
 */
import { computed } from 'vue'
import ArticleCheckStage from '../../article/components/ArticleCheckStage.vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'
import type { SafetyReport } from '../../../types/content-safety'

const props = defineProps<{
  /** 'inline'=generate 步内联 findings；'check'=proof 步检查舞台。 */
  mode: 'inline' | 'check'
  /** 测试覆写口：缺省用 context.engine.content。 */
  content?: string
  /** 测试覆写口：缺省用 context.engine.safetyReport（null=无报告也是合法显式值）。 */
  safetyReport?: SafetyReport | null
}>()

const context = useXhsStudioContext()
const content = computed(() => props.content ?? context.engine.content.value)
// safetyReport 须显式判 undefined（null 是合法显式值）；safetyChecking 不设覆写口——
// boolean prop 未传会被 Vue cast 为 false（无法与显式 false 区分），一律从 context 自取。
const safetyReport = computed(() =>
  props.safetyReport === undefined ? context.engine.safetyReport.value : props.safetyReport)
const safetyChecking = computed(() => context.engine.safetyChecking.value)

/** 【F-D】inline 模式挂载条件：报告存在且正文非空；不满足时整个区块不渲染。 */
const inlineReady = computed(() =>
  props.mode === 'inline' && safetyReport.value != null && content.value.trim() !== '')

/** 修复请求的文风句展示名（旧视图 selectedGenre/selectedStyle 同款派生，ArticleCreationView.vue:480-481）。 */
const genreName = computed(() =>
  context.tuning.genreOptions.value.find((item) => item.code === context.engine.genre.value)?.name)
const styleName = computed(() =>
  context.tuning.styleOptions.value.find((item) => item.code === context.engine.style.value)?.name)

/** 检查步返回编辑：经步骤机协同（stage='check'→'content'），组件不直接写引擎 stage。 */
function goEdit(): void {
  context.steps.go('generate')
}
</script>

<style scoped>
.xhs-compliance {
  display: grid;
  gap: var(--space-sm);
}

.xhs-compliance-head {
  display: grid;
  gap: var(--space-xs);
}

.xhs-compliance-head h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-compliance-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}
</style>
