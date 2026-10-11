<template>
  <div class="xhs-side-config">
    <section class="glass-card xhs-config-card">
      <h2>创作配置</h2>
      <p class="xhs-config-note">以下选择在生成时生效，随草稿自动保存。</p>

      <div class="gl-form-field">
        <span class="field-label">内容赛道</span>
        <div class="gl-chips" role="radiogroup" aria-label="内容赛道">
          <button
            v-for="option in genreOptions"
            :key="option.code"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': genre === option.code }"
            role="radio"
            :aria-checked="genre === option.code"
            :title="option.description"
            @click="emit('update:genre', option.code)"
          >{{ option.name }}</button>
        </div>
      </div>

      <div class="gl-form-field">
        <span class="field-label">内容语气</span>
        <div class="gl-chips" role="radiogroup" aria-label="内容语气">
          <button
            v-for="option in styleOptions"
            :key="option.code"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': style === option.code }"
            role="radio"
            :aria-checked="style === option.code"
            :title="option.description"
            @click="emit('update:style', option.code)"
          >{{ option.name }}</button>
        </div>
      </div>

      <div class="gl-form-field">
        <span class="field-label">标题套路</span>
        <div class="gl-chips" role="radiogroup" aria-label="标题套路">
          <button
            v-for="option in formulaOptions"
            :key="option.code"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': titleFormula === option.code }"
            role="radio"
            :aria-checked="titleFormula === option.code"
            :title="option.description"
            @click="emit('update:titleFormula', option.code)"
          >{{ option.name }}</button>
        </div>
      </div>

      <div class="gl-form-field">
        <span class="field-label">目标人群</span>
        <div class="gl-chips" role="radiogroup" aria-label="目标人群">
          <button
            v-for="option in audienceOptions"
            :key="option"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': audience === option }"
            role="radio"
            :aria-checked="audience === option"
            @click="emit('update:audience', audience === option ? '' : option)"
          >{{ option }}</button>
        </div>
        <p class="xhs-config-hint">可不选；选择后写入创作简报，随生成请求生效。</p>
      </div>

      <p v-if="loading" class="xhs-config-hint">风格目录加载中…</p>
      <p v-else-if="error" class="xhs-config-error" role="alert">
        {{ error }}
        <button type="button" class="xhs-config-retry" @click="emit('retry')">重试</button>
      </p>

      <button
        type="button"
        class="gl-btn-primary xhs-config-generate"
        :disabled="generating || !canGenerate"
        @click="onGenerate"
      >{{ generating ? '生成中…' : generateLabel }}</button>

      <p class="xhs-config-credits">
        积分余额：
        <span class="gl-num">{{ balance === null ? (balanceError ? '获取失败' : '…') : `${balance} 次` }}</span>
      </p>
    </section>

    <section class="glass-card xhs-config-card" aria-label="平台规范">
      <h2>平台规范</h2>
      <p class="xhs-config-summary">{{ formatSummary }}</p>
      <p v-if="emojiHint" class="xhs-config-hint">{{ emojiHint }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 左栏创作配置（阶段 0 最终态，方案 §4.1/§3）：
 * - 三组风格 chips（赛道 GENRE/语气 STYLE/标题套路 TITLE_FORMULA）复用服务端目录，
 *   单选、再点不清空（「生成时生效」真实注入引擎 stylePayload）；
 * - 目标人群三段 chips 写 brief.audience（真实端到端字段，可清空——再点同项取消）；
 * - 主按钮触发真实生成链（pick 步=fetchTitles；generate 步=重新生成），未登录上抛
 *   request-login 由壳层拉起登录；余额经 useCredits 真实展示（null=未加载，不伪造 0）；
 * - 平台规范常读卡=真实契约句（formatRuleSummary）+ emojiHint 静态提示
 *   （正文长度/表情密度滑杆 defer：无生成参数通路，不渲染假控件）。
 */
import { computed } from 'vue'
import { useAuth } from '../../../composables/useAuth'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

const props = defineProps<{
  genreOptions: CreationStyleSkillOption[]
  styleOptions: CreationStyleSkillOption[]
  formulaOptions: CreationStyleSkillOption[]
  genre: string
  style: string
  titleFormula: string
  loading: boolean
  error: string
  audience: string
  /** null=未加载/失败（与成功 0 区分）；错误经 balanceError 区分文案。 */
  balance: number | null
  balanceError: string
  formatSummary: string
  /** 契约 emojiHint 静态提示（小红书 platform-format-rules）。 */
  emojiHint?: string
  canGenerate: boolean
  generating: boolean
  generateLabel: string
}>()

const emit = defineEmits<{
  'update:genre': [code: string]
  'update:style': [code: string]
  'update:titleFormula': [code: string]
  'update:audience': [value: string]
  retry: []
  generate: []
  /** 未登录点生成：上抛壳层拉起登录（AiAppLayout 已接同款事件）。 */
  'request-login': []
}>()

/** 【cfg-audience】三段固定值（brief.audience 值域）。 */
const audienceOptions = computed(() => ['学生党', '职场人', '宝妈'])
const auth = useAuth()

function onGenerate(): void {
  if (props.generating || !props.canGenerate) return
  if (!auth.isAuthenticated.value) {
    emit('request-login')
    return
  }
  emit('generate')
}
</script>

<style scoped>
.xhs-side-config {
  display: grid;
  gap: var(--space-md);
}

.xhs-config-card {
  display: grid;
  gap: var(--space-sm);
}

.xhs-config-card h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-config-card > p {
  margin: 0;
}

.xhs-config-note,
.xhs-config-hint {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-config-summary {
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
  line-height: var(--leading-body-sm);
}

.xhs-config-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-danger);
  font-size: var(--type-caption);
}

.xhs-config-retry {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-danger);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.xhs-config-generate {
  min-height: var(--control-height);
}

.xhs-config-credits {
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}
</style>
