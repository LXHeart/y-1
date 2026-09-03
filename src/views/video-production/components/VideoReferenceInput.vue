<template>
  <section class="input-methods" aria-label="可选输入方式">
    <div class="input-method">
      <button
        class="input-method-toggle"
        type="button"
        :aria-expanded="showReference"
        @click="showReference = !showReference"
      >
        <span class="toggle-caret" aria-hidden="true">{{ showReference ? '▾' : '▸' }}</span>
        粘贴参考视频链接（可选）
      </button>

      <div v-if="showReference" class="reference-area">
        <p class="field-note">粘贴抖音或 B 站分享文本/链接，提取视频并 AI 分析；分析结果只作为创作建议，可带入脚本生成。</p>

        <div class="reference-platform-switch" role="tablist" aria-label="参考视频平台">
          <button
            class="reference-platform-tab"
            :class="{ 'reference-platform-tab-active': referencePlatform === 'douyin' }"
            :aria-selected="referencePlatform === 'douyin'"
            type="button"
            @click="$emit('switchPlatform', 'douyin')"
          >抖音</button>
          <button
            class="reference-platform-tab"
            :class="{ 'reference-platform-tab-active': referencePlatform === 'bilibili' }"
            :aria-selected="referencePlatform === 'bilibili'"
            type="button"
            @click="$emit('switchPlatform', 'bilibili')"
          >B 站</button>
        </div>

        <textarea
          :value="referenceInput"
          class="reference-input"
          rows="3"
          :placeholder="referencePlatform === 'douyin' ? '例如：7.54 复制打开抖音 https://v.douyin.com/xxxx/' : '例如：https://www.bilibili.com/video/BV1xxxxxxxxx'"
          :disabled="parseLoading"
          @input="$emit('update:referenceInput', ($event.target as HTMLTextAreaElement).value)"
        ></textarea>

        <div class="action-row action-row-start">
          <button
            class="btn-secondary"
            :disabled="parseLoading || !referenceInput.trim()"
            @click="$emit('extract')"
          >
            {{ parseLoading ? '提取中…' : '提取并分析' }}
          </button>
          <button class="btn-secondary" :disabled="parseLoading" @click="$emit('clearReference')">清空</button>
        </div>

        <slot name="parse-panels" />

        <div v-if="referenceCards.length > 0" class="reference-apply">
          <p class="field-note">勾选要带入脚本生成的分析产出：</p>
          <label v-for="card in referenceCards" :key="card.key" class="reference-card-option">
            <input :checked="card.selected" type="checkbox" @change="toggleCard(card.key)" />
            <span>{{ card.label }}</span>
          </label>
          <div class="action-row action-row-start">
            <button class="btn-primary gl-btn-primary" :disabled="!hasSelectedCards" @click="$emit('applyToPrompt')">带入自定义要求</button>
          </div>
          <p v-if="applied" class="field-note reference-applied-hint">已带入自定义要求，可在上方「自定义要求」中查看和编辑。</p>
        </div>
      </div>
    </div>

    <div class="input-method">
      <button
        class="input-method-toggle"
        type="button"
        :aria-expanded="showTopic"
        @click="showTopic = !showTopic"
      >
        <span class="toggle-caret" aria-hidden="true">{{ showTopic ? '▾' : '▸' }}</span>
        从热点选主题（可选）
      </button>
      <div v-if="showTopic" class="topic-area">
        <p class="field-note">输入热点话题或主题关键词，会作为创作主题带入自定义要求。</p>
        <div class="topic-row">
          <input
            :value="hotTopicInput"
            type="text"
            class="topic-input"
            placeholder="例如：城市夜骑、冬日暖胃计划"
            @input="$emit('update:hotTopicInput', ($event.target as HTMLInputElement).value)"
          />
          <button class="btn-secondary" :disabled="!hotTopicInput.trim()" @click="$emit('applyHotTopic')">带入主题</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'

export interface ReferenceCard {
  key: string
  label: string
  content: string
  selected: boolean
}

defineProps<{
  referencePlatform: 'douyin' | 'bilibili'
  referenceInput: string
  hotTopicInput: string
  referenceCards: ReferenceCard[]
  hasSelectedCards: boolean
  applied: boolean
  parseLoading: boolean
}>()

const emit = defineEmits<{
  switchPlatform: [platform: 'douyin' | 'bilibili']
  'update:referenceInput': [value: string]
  'update:hotTopicInput': [value: string]
  extract: []
  clearReference: []
  applyToPrompt: []
  applyHotTopic: []
  toggleCard: [key: string]
}>()

const showReference = ref(false)
const showTopic = ref(false)

function toggleCard(key: string): void {
  emit('toggleCard', key)
}
</script>

<!-- 任务书 #68 卡 E（D4 拍板：样式随迁）：以下规则原写在父视图 scoped 块里，scoped 不穿透
     子组件导致从未生效（仅根节点 .input-methods 例外）；随迁后恢复设计意图。 -->
<style scoped>
.input-methods {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
}

.input-method-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
}

.input-method-toggle:hover {
  color: var(--color-accent);
}

.toggle-caret {
  color: var(--color-text-muted);
  font-size: 12px;
}

.reference-area,
.topic-area {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-furrow);
}

.reference-platform-switch {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  width: fit-content;
}

.reference-platform-tab {
  padding: 4px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
}

.reference-platform-tab-active {
  background: var(--color-accent);
  color: var(--color-on-accent);
}

.reference-input,
.topic-input {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}

.reference-input:focus,
.topic-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.action-row-start {
  justify-content: flex-start;
}

.reference-apply {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.reference-card-option {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
}

.reference-applied-hint {
  color: color-mix(in srgb, var(--color-success) 90%, transparent);
}

.topic-row {
  display: flex;
  gap: 8px;
}

.topic-input {
  flex: 1;
}
</style>
