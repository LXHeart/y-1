<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SafetyReport, SafetyFinding } from '../../../types/content-safety'
import { fixSafety } from '../../../composables/useContentSafety'
import { findingCategoryLabel } from '../../../lib/finding-labels'
import GlModal from '../../../components/GlModal.vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import TextDiffPreview from '../../../components/TextDiffPreview.vue'

interface Props {
  content: string
  safetyReport: SafetyReport | null
  platform: string
  contentForm: string | undefined
  safetyChecking: boolean
  imagesStageSkipped: boolean
  genreName?: string
  styleName?: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  recheck: []
  'go-edit': []
  proceed: []
  rechecked: [report: SafetyReport]
  'apply-fix': [text: string]
}>()

interface PreviewSegment { text: string; highlight: boolean; start: number }

/**
 * 正文预览分段：词库类（deep=false）finding 的 match 在正文中首次命中的位置包高亮。
 * 纯字符串切片 + 组件化渲染（禁 v-html 裸拼）；重叠命中区间合并，深检/元信息类不参与高亮。
 * start 记录段起始偏移，供「查看」按 finding 定位到自己的 mark（任务书 #63 卡5）。
 */
const previewSegments = computed<PreviewSegment[]>(() => {
  const text = props.content
  const findings = props.safetyReport?.findings ?? []
  const marks: Array<{ start: number; end: number }> = []
  for (const finding of findings) {
    if (finding.deep || finding.category === 'duplicate_content' || finding.category === 'low_originality') continue
    const match = finding.match
    if (!match || match.length < 2) continue
    const index = text.indexOf(match)
    if (index < 0) continue
    marks.push({ start: index, end: index + match.length })
  }
  if (marks.length === 0) return [{ text, highlight: false, start: 0 }]
  marks.sort((a, b) => a.start - b.start || a.end - b.end)
  const merged: Array<{ start: number; end: number }> = []
  for (const mark of marks) {
    const last = merged[merged.length - 1]
    if (last && mark.start < last.end) {
      last.end = Math.max(last.end, mark.end)
      continue
    }
    merged.push({ ...mark })
  }
  const segments: PreviewSegment[] = []
  let cursor = 0
  for (const mark of merged) {
    if (mark.start > cursor) segments.push({ text: text.slice(cursor, mark.start), highlight: false, start: cursor })
    segments.push({ text: text.slice(mark.start, mark.end), highlight: true, start: mark.start })
    cursor = mark.end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), highlight: false, start: cursor })
  return segments
})

const previewEl = ref<HTMLElement | null>(null)

/**
 * 「查看」：词库类滚动到该 finding 自己的首个命中（非预览区第一个 mark）；
 * match 搜不到或元信息/深检类 → 详情弹层（卡5「搜不到 → 弹详情」）。
 */
function handleView(finding: SafetyFinding): void {
  const isMeta = finding.category === 'duplicate_content' || finding.category === 'low_originality'
  if (!isMeta && !finding.deep) {
    const hitIndex = finding.match ? props.content.indexOf(finding.match) : -1
    const segmentIndex = hitIndex >= 0
      ? previewSegments.value.findIndex(
        (seg) => seg.highlight && hitIndex >= seg.start && hitIndex < seg.start + seg.text.length)
      : -1
    if (segmentIndex >= 0) {
      const ordinal = previewSegments.value.slice(0, segmentIndex).filter((seg) => seg.highlight).length
      const mark = previewEl.value?.querySelectorAll('mark')[ordinal]
      if (mark) {
        mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
        return
      }
    }
  }
  detailFinding.value = finding
}

const detailFinding = ref<SafetyFinding | null>(null)

const detailNote = computed(() => {
  const finding = detailFinding.value
  if (!finding) return ''
  if (finding.deep) return 'AI 深检未能在正文中定位该表述,以下为模型报告,仅供参考'
  const isMeta = finding.category === 'duplicate_content' || finding.category === 'low_originality'
  if (!isMeta) return '未能在正文中定位该表述，以下为检查报告，仅供参考'
  return ''
})

// ---------- 修复流（P3：先 diff 预览再应用） ----------
const fixing = ref(false)
const fixError = ref('')
const diffVisible = ref(false)
const diffOriginal = ref('')
const diffRevised = ref('')

/** 「修复」/「一键修复」：调修复端点（免费），成功后进 diff 预览弹层；失败留在面板错误条不阻断。 */
async function handleFix(target: SafetyFinding | 'all'): Promise<void> {
  if (fixing.value) return
  fixing.value = true
  fixError.value = ''
  const source = target === 'all' ? props.safetyReport?.findings ?? [] : [target]
  // 修复端点契约 findings 1..20 条——超限截断（advisory 场景下静默取前 20 优于整单 400）
  const findings = source.slice(0, 20).map((finding) => ({
    category: finding.category,
    match: finding.match,
    advice: finding.advice,
  }))
  try {
    const fixed = await fixSafety({
      text: props.content,
      findings,
      platform: props.platform,
      contentForm: props.contentForm,
      // 文风句用展示名（4.1「{genre 描述}」），仅小红书/知乎风格三选激活时有值
      genre: props.genreName,
      style: props.styleName,
    })
    diffOriginal.value = props.content
    diffRevised.value = fixed
    diffVisible.value = true
  } catch (err: unknown) {
    fixError.value = err instanceof Error ? err.message : '修复失败，请稍后再试'
  } finally {
    fixing.value = false
  }
}

/** diff 弹层「应用修复」：回写正文、同步检查快照并自动复查。 */
function applyFix(): void {
  diffVisible.value = false
  emit('apply-fix', diffRevised.value)
}

function onPanelRechecked(report: SafetyReport): void {
  emit('rechecked', report)
}
</script>

<template>
  <section class="stage-card gl-zone fade-in" data-test="check-stage">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" data-test="check-back" @click="emit('go-edit')">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回
        </button>
        <p class="eyebrow">第五步</p>
      </div>
      <h2 class="card-title">内容检查</h2>
      <p class="field-note">处理完提醒再配图,发布效果更稳;提醒不阻断流程。</p>
    </header>

    <div class="check-body">
      <div class="check-pane">
        <p class="check-pane-label">正文预览（只读，词库命中原句已高亮）</p>
        <div ref="previewEl" class="check-preview" data-test="check-preview">
          <template v-for="(seg, i) in previewSegments" :key="i"><mark
            v-if="seg.highlight"
            class="check-mark"
          >{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template>
        </div>
      </div>

      <p v-if="fixing" class="field-note check-fixing" data-test="fixing-hint">AI 修复中,通常需要数十秒</p>
      <p v-if="fixError" class="check-error" role="alert" data-test="fix-error">{{ fixError }}</p>

      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="content"
        enable-fix
        :platform="platform"
        :content-form="contentForm"
        :fixing="fixing"
        @updated="onPanelRechecked"
        @view="handleView"
        @fix="handleFix"
      />
      <p v-else class="field-note" data-test="check-pending">
        {{ safetyChecking ? '正在检查正文…' : '尚未检查，点「重新检查」开始。' }}
      </p>
    </div>

    <div class="action-row">
      <button class="btn-secondary" data-test="check-edit" @click="emit('go-edit')">返回正文编辑</button>
      <button
        class="btn-secondary"
        data-test="check-recheck"
        :disabled="safetyChecking || !content.trim()"
        @click="emit('recheck')"
      >{{ safetyChecking ? '检查中…' : '重新检查' }}</button>
      <button class="btn-primary gl-btn-primary" data-test="check-proceed" @click="emit('proceed')">
        {{ imagesStageSkipped ? '完成' : '继续配图' }}
      </button>
    </div>

    <!-- 任务书 #63 卡5：finding 详情弹层（深检未定位标注 / 元信息 + fragments 列表） -->
    <GlModal v-if="detailFinding" :title="findingCategoryLabel(detailFinding.category)" @close="detailFinding = null">
      <div class="finding-detail" data-test="finding-detail">
        <p v-if="detailNote" class="field-note" data-test="detail-note">{{ detailNote }}</p>
        <p class="finding-detail-match">"{{ detailFinding.match }}"</p>
        <p v-if="detailFinding.advice" class="field-note">{{ detailFinding.advice }}</p>
        <div v-if="detailFinding.fragments?.length" class="finding-detail-fragments" data-test="detail-fragments">
          <p class="field-note">文内重复片段（按重复权重排序）：</p>
          <ul>
            <li v-for="(fragment, i) in detailFinding.fragments" :key="i">"{{ fragment }}"</li>
          </ul>
        </div>
      </div>
      <template #actions>
        <button type="button" class="btn-secondary" data-test="detail-close" @click="detailFinding = null">知道了</button>
      </template>
    </GlModal>
    <!-- 任务书 #63 卡5：修复 diff 预览弹层——应用前原文不动（P3 拍板） -->
    <GlModal v-if="diffVisible" title="修复预览" scroll persistent @close="diffVisible = false">
      <TextDiffPreview
        :original="diffOriginal"
        :revised="diffRevised"
        @apply="applyFix"
        @discard="diffVisible = false"
      />
    </GlModal>
  </section>
</template>

<style scoped>
/* 复制父文件检查步实际用到的共享样式 */
.stage-card {
  display: grid;
  gap: 14px;
}

.card-head {
  display: grid;
  gap: 14px;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.eyebrow {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.card-title {
  margin: 0;
  font-size: 1.14rem;
  font-weight: 600;
  line-height: 1.25;
  color: var(--color-text);
}

.field-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.6;
}

.btn-back,
.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-primary {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

/* 检查步专属样式 */
.check-body {
  display: grid;
  gap: 14px;
}

.check-pane {
  display: grid;
  gap: 6px;
}

.check-pane-label {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.78rem;
}

.check-preview {
  max-height: 380px;
  overflow-y: auto;
  padding: 14px 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.88rem;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.check-mark {
  background: color-mix(in srgb, var(--color-warning) 26%, transparent);
  color: inherit;
  border-radius: var(--radius-xs);
  padding: 0 2px;
}

/* 暗色下 26% 警示底几乎融入深底（2026-09-01 冒烟目检实锤）——补一条 warning 下边线增强定位 */
[data-theme='dark'] .check-mark {
  background: color-mix(in srgb, var(--color-warning) 32%, transparent);
  box-shadow: inset 0 -2px 0 color-mix(in srgb, var(--color-warning) 55%, transparent);
}

.check-fixing {
  color: var(--color-accent);
}

.check-error {
  margin: 0;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  background: color-mix(in srgb, var(--color-danger) 14%, transparent);
  color: var(--color-danger);
}

.finding-detail {
  display: grid;
  gap: 10px;
}

.finding-detail-match {
  margin: 0;
  font-size: 0.92rem;
  line-height: 1.6;
  word-break: break-all;
}

.finding-detail-fragments ul {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: grid;
  gap: 4px;
  font-size: 0.84rem;
  color: var(--color-text-secondary);
}

@media (max-width: 720px) {
  .card-head-row {
    flex-direction: column;
    align-items: stretch;
  }

  .btn-primary,
  .btn-secondary,
  .btn-back {
    width: 100%;
  }
}
</style>
