<template>
  <section
    v-if="mode === 'check' || inlineReady"
    class="zhihu-compliance"
    :data-mode="mode"
    aria-label="平台合规检测"
  >
    <header class="zhihu-compliance-head">
      <h3>平台合规检测</h3>
      <p class="zhihu-compliance-note">词库 + AI 语境检测；提醒为建议性质，不阻断发布。</p>
    </header>

    <!-- proof 步：检查舞台（只读预览高亮 + 逐项修复 + 放行进配图） -->
    <ArticleCheckStage
      v-if="mode === 'check'"
      :content="content"
      :safety-report="safetyReport"
      platform="zhihu"
      :content-form="checkContentForm"
      :safety-checking="safetyChecking"
      :images-stage-skipped="false"
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
      platform="zhihu"
      @updated="context.engine.onPanelRechecked"
    />
  </section>
</template>

<script setup lang="ts">
/**
 * 平台合规检测区块（对位 xhs-studio CompliancePanel，知乎差异两点）：
 * - contentForm 必传：仅知乎的修复请求携带 answer|article（useArticleCreation
 *   checkContentForm 同判据——后端按形态分叉提示词）。
 * - imagesStageSkipped=false：知乎不跳配图，检查舞台放行按钮=「继续配图」，
 *   proceed → engine.proceedFromCheck() 内部置 stage='images'（发布步承接）。
 * - mode='inline'（generate 步）挂 SafetyFindingsPanel——挂载条件=safetyReport 存在
 *   且正文非空（标题期报告与空正文语境错配的窗口期不渲染）。
 */
import { computed } from 'vue'
import ArticleCheckStage from '../../article/components/ArticleCheckStage.vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'
import type { SafetyReport } from '../../../types/content-safety'

const props = defineProps<{
  /** 'inline'=generate 步内联 findings；'check'=proof 步检查舞台。 */
  mode: 'inline' | 'check'
  /** 测试覆写口：缺省用 context.engine.content。 */
  content?: string
  /** 测试覆写口：缺省用 context.engine.safetyReport（null=无报告也是合法显式值）。 */
  safetyReport?: SafetyReport | null
}>()

const context = useZhihuStudioContext()
const content = computed(() => props.content ?? context.engine.content.value)
// safetyReport 须显式判 undefined（null 是合法显式值）；safetyChecking 不设覆写口——
// boolean prop 未传会被 Vue cast 为 false（无法与显式 false 区分），一律从 context 自取。
const safetyReport = computed(() =>
  props.safetyReport === undefined ? context.engine.safetyReport.value : props.safetyReport)
const safetyChecking = computed(() => context.engine.safetyChecking.value)

/** 修复请求的 contentForm：仅知乎携带 answer|article（engine 同判据）。 */
const checkContentForm = computed(() =>
  context.engine.platform.value === 'zhihu' ? context.engine.contentMode.value : undefined)

/** 【F-D 同款】inline 模式挂载条件：报告存在且正文非空；不满足时整个区块不渲染。 */
const inlineReady = computed(() =>
  props.mode === 'inline' && safetyReport.value != null && content.value.trim() !== '')

/** 修复请求的文风句展示名（旧视图 selectedGenre/selectedStyle 同款派生）。 */
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
.zhihu-compliance {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-compliance-head {
  display: grid;
  gap: var(--space-xs);
}

.zhihu-compliance-head h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-compliance-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}
</style>
