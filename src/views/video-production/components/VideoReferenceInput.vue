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
