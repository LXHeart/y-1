<script setup lang="ts">
import { computed, ref } from 'vue'
import { recheckSafety } from '../composables/useContentSafety'
import type { SafetyReport } from '../composables/useContentSafety'
import type { SafetyFinding } from '../types/content-safety'

/**
 * 内容安全 findings 面板（任务书 #34 B4 / ADR-D16 D6/D9）：severity 排序 + 类别 chip + advice +
 * 词库版本标注 + 「重新检查」（手动端点复查当前文本）。advisory 姿态——警告不阻断。
 * 词库不下发前端；本组件只渲染 findings。
 *
 * 任务书 #63 卡4：可选 props 开启逐项「查看/修复」与「一键修复」（emit 给父级执行，面板本身
 * 不调修复端点）；enableFix 默认 false——五个旧视图（moments/comedy/image/video/创作助手）不传
 * 新 props 时渲染输出与升级前一致，零回归。
 */

const props = withDefaults(defineProps<{
  /** 当前 safety 报告（null = 未检查/无数据，面板隐藏）。 */
  report: SafetyReport | null
  /** 复查用的当前文本（用户可能已编辑）。 */
  text: string
  /** 开启逐项查看/修复与一键修复（仅文章流检查步开启）。 */
  enableFix?: boolean
  /** 复查与修复请求透传的平台值（修「未知平台」根因）。 */
  platform?: string
  /** 复查与修复请求透传的内容形态（answer|article）。 */
  contentForm?: string
  /** 修复进行中（由父级传入）：所有修复类按钮禁点。 */
  fixing?: boolean
}>(), { enableFix: false })

const emit = defineEmits<{
  updated: [report: SafetyReport]
  view: [finding: SafetyFinding]
  fix: [finding: SafetyFinding | 'all']
}>()

const rechecking = ref(false)
const recheckError = ref('')

const CATEGORY_LABEL: Record<string, string> = {
  absolute_claims: '广告法极限词',
  false_promises: '违规承诺',
  diversion: '导流联系',
  politics: '涉政敏感',
  porn: '低俗内容',
  illegal: '涉嫌违法',
  platform_unwanted: '平台不推荐表达',
  platform_overlay: '平台规则',
  industry_overlay: '行业规则',
  duplicate_content: '内容重复度',
  low_originality: '低原创度',
}

const OVERLAY_LABEL: Record<string, string> = {
  douyin: '抖音',
  kuaishou: '快手',
  food: '餐饮',
  beauty: '美业',
  medical: '医疗',
}

const SEVERITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 }

const sortedFindings = computed(() => {
  if (!props.report) return []
  return [...props.report.findings].sort(
    (a, b) => (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9))
})

const hasFindings = computed(() => sortedFindings.value.length > 0)

const fixDisabled = computed(() => props.fixing === true || !hasFindings.value)

async function recheck(): Promise<void> {
  if (rechecking.value || !props.text.trim()) return
  rechecking.value = true
  recheckError.value = ''
  const fresh = await recheckSafety(props.text, props.platform, props.contentForm)
  rechecking.value = false
  if (!fresh) {
    recheckError.value = '复查失败，请稍后再试'
    return
  }
  emit('updated', fresh)
}
</script>

<template>
  <section v-if="report" class="sfp" aria-label="内容安全检查">
    <header class="sfp-head">
      <h4>内容安全检查{{ hasFindings ? `（${sortedFindings.length} 项提醒）` : '（未发现问题）' }}</h4>
      <span v-if="enableFix" class="sfp-head-actions">
        <span v-if="report.lexiconVersion" class="sfp-version">按 {{ report.lexiconVersion }} 检查</span>
        <button
          type="button"
          class="sfp-fix-all"
          data-test="sfp-fix-all"
          :disabled="fixDisabled"
          @click="emit('fix', 'all')"
        >一键修复（{{ sortedFindings.length }} 项）</button>
      </span>
      <span v-else-if="report.lexiconVersion" class="sfp-version">按 {{ report.lexiconVersion }} 检查</span>
    </header>

    <p v-if="report.appliedOverlays?.length" class="sfp-overlays">
      已叠加：{{ report.appliedOverlays.map((item) => OVERLAY_LABEL[item] || item).join('、') }}
    </p>

    <p v-if="recheckError" class="sfp-error" role="alert">{{ recheckError }}</p>

    <ul v-if="hasFindings" class="sfp-list">
      <li v-for="(finding, i) in sortedFindings" :key="i" :class="`sfp-sev-${finding.severity}`">
        <div class="sfp-row">
          <span class="sfp-chip" :class="`sfp-chip-${finding.severity}`">
            {{ CATEGORY_LABEL[finding.category] || finding.category }}
          </span>
          <code class="sfp-match">“{{ finding.match }}”</code>
          <span v-if="finding.deep" class="sfp-deep">AI 深检</span>
          <span v-if="enableFix" class="sfp-item-actions">
            <button
              type="button"
              :data-test="`sfp-view-${i}`"
              @click="emit('view', finding)"
            >查看</button>
            <button
              type="button"
              :data-test="`sfp-fix-${i}`"
              :disabled="fixing"
              @click="emit('fix', finding)"
            >修复</button>
          </span>
        </div>
        <p v-if="finding.advice" class="sfp-advice">{{ finding.advice }}</p>
      </li>
    </ul>
    <p v-else class="sfp-clean">暂未发现敏感词或违规表达，发布前仍建议人工确认。</p>

    <div class="sfp-foot">
      <span class="sfp-hint">{{ report.deepCheck ? '已含 AI 语境深检' : '本次为词库快速检查' }} · 提醒不阻断发布</span>
      <button type="button" :disabled="rechecking || !text.trim()" @click="recheck">
        {{ rechecking ? '复查中…' : '重新检查' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.sfp { display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.sfp-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.sfp-head h4 { margin: 0; font-size: 13px; }
.sfp-version { font-size: 11px; opacity: 0.55; }
.sfp-head-actions { display: flex; align-items: center; gap: 8px; }
.sfp-overlays { margin: 0; font-size: 11px; color: var(--color-text-secondary); }
.sfp-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.sfp-list li { display: flex; flex-direction: column; gap: 2px; padding: 6px 8px; border-radius: var(--radius-sm); background: var(--color-surface-strong, var(--color-surface)); }
.sfp-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sfp-chip { font-size: 11px; padding: 1px 8px; border-radius: var(--radius-lg); white-space: nowrap; }
.sfp-chip-high { background: color-mix(in srgb, var(--color-danger) 18%, transparent); color: var(--color-danger); }
.sfp-chip-medium { background: color-mix(in srgb, var(--color-warning) 18%, transparent); color: var(--color-warning); }
.sfp-chip-low { background: color-mix(in srgb, var(--color-accent) 16%, transparent); color: var(--color-accent); }
.sfp-match { font-size: 12px; word-break: break-all; }
.sfp-deep { font-size: 10px; opacity: 0.6; border: 1px solid currentColor; border-radius: var(--radius-xs); padding: 0 4px; }
.sfp-item-actions { display: inline-flex; gap: 6px; margin-left: auto; }
.sfp-advice { margin: 0; font-size: 12px; opacity: 0.75; }
.sfp-clean { margin: 0; font-size: 12px; color: var(--color-success); }
.sfp-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.sfp-hint { font-size: 11px; opacity: 0.55; }
.sfp-error { margin: 0; padding: 6px 10px; border-radius: var(--radius-sm); font-size: 12px; background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
button { padding: 4px 12px; font-size: 12px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text); cursor: pointer; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.sfp-fix-all { white-space: nowrap; }
</style>
