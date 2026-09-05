<template>
  <section class="model-source-card gl-tile" aria-labelledby="model-source-title">
    <header class="msc-head">
      <div>
        <h3 id="model-source-title">模型来源</h3>
        <p>决定各创作能力用谁的模型、谁付钱——一个总开关，取代旧的按能力「用我自己的密钥」碎片开关。</p>
      </div>
      <button type="button" :disabled="loading || switching" @click="refresh">刷新</button>
    </header>

    <div class="msc-options" role="radiogroup" aria-label="模型来源">
      <label class="msc-option" :class="{ active: modelSource === 'platform' }">
        <input
          type="radio" name="model-source" value="platform"
          v-model="selectedSource"
          :disabled="!loaded || switching || loading"
          @change="switchTo('platform')"
        />
        <span class="msc-option-main">
          <strong>平台统一模型（默认）</strong>
          <span>按平台计费（扣积分），个人预算生效；内容安全深检、内容修复等平台免费能力全开。</span>
        </span>
      </label>
      <label class="msc-option" :class="{ active: modelSource === 'own' }">
        <input
          type="radio" name="model-source" value="own"
          v-model="selectedSource"
          :disabled="!loaded || switching || loading"
          @change="switchTo('own')"
        />
        <span class="msc-option-main">
          <strong>自有模型密钥</strong>
          <span>各能力用你登记的个人密钥：不扣积分、跳过个人预算；未配密钥的能力不可用（不回退平台）。</span>
        </span>
      </label>
    </div>

    <p v-if="error || loadError" class="msc-error" role="alert">{{ error || loadError }}</p>
    <p v-else-if="!loaded" class="msc-loading">正在加载模型来源…</p>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useModelSource } from '../../../composables/useModelSource'
import type { ModelSource } from '../../../types/ai-control-plane'

/**
 * 模型来源开关卡（任务书 #78 卡 C，接卡 B 契约）。
 *
 * 切 own 二次确认（警示文案为 #78 定死文本）；切 platform 直接生效（省钱方向不拦，与旧
 * 密钥开关的确认方向一致）。409 冲突在 useModelSource 内重载后提示重试。
 */
const emit = defineEmits<{ changed: [] }>()

const { modelSource, loaded, loading, loadError, load, setSource } = useModelSource()
const switching = ref(false)
const error = ref('')
const selectedSource = ref(modelSource.value)

watch(modelSource, (source) => { selectedSource.value = source })

onMounted(() => { void load() })

async function refresh(): Promise<void> {
  error.value = ''
  await load()
  selectedSource.value = modelSource.value
}

async function switchTo(next: ModelSource): Promise<void> {
  if (next === modelSource.value || switching.value || loading.value || !loaded.value) {
    selectedSource.value = modelSource.value
    return
  }
  error.value = ''
  if (next === 'own' && !window.confirm(
    '切换到自有模型密钥后：平台内容安全深检、内容修复等免费能力将不再提供；'
    + '未配置密钥的能力将不可用（生成会被拒绝，不回退平台模型）。确认切换？')) {
    await nextTick()
    selectedSource.value = modelSource.value
    return
  }
  switching.value = true
  try {
    const failure = await setSource(next)
    error.value = failure ?? ''
    if (failure == null) emit('changed')
  } finally {
    switching.value = false
    selectedSource.value = modelSource.value
  }
}
</script>

<style scoped>
.model-source-card { display: grid; gap: var(--space-sm); }
.msc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-sm); }
.msc-head h3 { margin: 0; font-size: var(--text-lg); }
.msc-head p { margin: 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.msc-head button { flex-shrink: 0; }
.msc-options { display: grid; gap: var(--space-xs); }
.msc-option { display: flex; align-items: flex-start; gap: var(--space-xs); padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; }
.msc-option.active { border-color: var(--color-accent); }
.msc-option input { cursor: pointer; }
.msc-option-main { display: grid; gap: var(--space-xs); }
.msc-option-main strong { color: var(--color-text); font-size: var(--text-base); }
.msc-option-main span { color: var(--color-text-muted); font-size: var(--text-sm); }
.msc-error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
.msc-loading { margin: 0; color: var(--color-text-muted); font-size: var(--text-sm); }
</style>
