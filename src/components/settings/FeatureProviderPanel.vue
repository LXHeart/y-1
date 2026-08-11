<template>
  <div class="feature-provider-panel">
    <div v-if="kicker || sectionTitle" class="settings-section-head">
      <div>
        <p v-if="kicker" class="settings-section-kicker">{{ kicker }}</p>
        <h3 v-if="sectionTitle" class="settings-section-title">{{ sectionTitle }}</h3>
      </div>
      <p v-if="sectionNote" class="settings-section-note">{{ sectionNote }}</p>
    </div>

    <div class="settings-group">
      <div class="settings-group-head">
        <h4 class="settings-group-title">{{ connectionTitle || '连接配置' }}</h4>
        <p class="settings-group-copy">{{ connectionNote }}</p>
      </div>

      <div class="settings-fields">
        <label class="settings-label" :for="`${fieldPrefix}-base-url`">服务地址</label>
        <input
          :id="`${fieldPrefix}-base-url`"
          :value="baseUrl"
          class="settings-input"
          type="url"
          inputmode="url"
          :placeholder="baseUrlPlaceholder"
          autocomplete="off"
          spellcheck="false"
          @input="$emit('update:baseUrl', ($event.target as HTMLInputElement).value)"
        >

        <label class="settings-label" :for="`${fieldPrefix}-api-key`">{{ secretLabel }}</label>
        <p v-if="hasSavedSecret" class="settings-secret-hint">
          已保存，留空保持不变；输入空格后保存可清空。
        </p>
        <div class="token-row">
          <input
            :id="`${fieldPrefix}-api-key`"
            :value="apiKey"
            class="settings-input"
            :type="showSecret ? 'text' : 'password'"
            :placeholder="secretPlaceholder"
            autocomplete="off"
            spellcheck="false"
            @input="$emit('update:apiKey', ($event.target as HTMLInputElement).value)"
          >
          <button class="btn-secondary btn-sm" type="button" @click="$emit('toggle:showSecret')">
            {{ showSecret ? '隐藏' : '显示' }}
          </button>
        </div>
      </div>
    </div>

    <div class="settings-group">
      <div class="settings-group-head settings-group-head-inline">
        <div>
          <h4 class="settings-group-title">模型配置</h4>
          <p class="settings-group-copy">{{ modelNote || '如果已有模型列表，可直接选择后测试连通性。' }}</p>
        </div>
        <button
          class="btn-fetch-models"
          type="button"
          :disabled="!canFetchModels || modelState.loading"
          @click="$emit('fetchModels')"
        >
          <svg v-if="modelState.loading" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none">
            <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/>
          </svg>
          {{ modelState.loading ? '获取中…' : '刷新列表' }}
        </button>
      </div>

      <p v-if="modelState.error" class="settings-error settings-inline-status">{{ modelState.error }}</p>

      <div class="settings-fields">
        <label class="settings-label" :for="`${fieldPrefix}-model`">模型</label>

        <div v-if="modelState.availableModels.length && !useCustomModel" class="model-row">
          <select :id="`${fieldPrefix}-model`" :value="model" class="settings-input model-select" @change="$emit('update:model', ($event.target as HTMLSelectElement).value)">
            <option value="" disabled>请选择模型</option>
            <option v-for="m in modelState.availableModels" :key="m.id" :value="m.id">{{ m.id }}</option>
            <option value="__custom__">自定义输入…</option>
          </select>
          <button
            class="btn-secondary btn-sm"
            type="button"
            :disabled="modelState.verifying || !model.trim()"
            @click="$emit('verifyModel')"
          >
            {{ modelState.verifying ? '验证中' : '测试' }}
          </button>
        </div>
        <div v-else class="model-row">
          <input
            :id="`${fieldPrefix}-model`"
            :value="model"
            class="settings-input"
            type="text"
            :placeholder="modelPlaceholder"
            autocomplete="off"
            spellcheck="false"
            @input="$emit('update:model', ($event.target as HTMLInputElement).value)"
            @focus="$emit('toggle:useCustomModel')"
          >
          <button
            class="btn-secondary btn-sm"
            type="button"
            :disabled="modelState.verifying || !model.trim()"
            @click="$emit('verifyModel')"
          >
            {{ modelState.verifying ? '验证中' : '测试' }}
          </button>
        </div>
      </div>

      <p v-if="modelState.verifyResult === 'success'" class="verify-success settings-inline-status">模型可用</p>
      <p v-if="modelState.verifyResult === 'error'" class="settings-error settings-inline-status">{{ modelState.verifyError }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { FeatureModelState } from '../../types/settings'

defineProps<{
  fieldPrefix: string
  kicker?: string
  sectionTitle?: string
  sectionNote?: string
  connectionTitle?: string
  connectionNote?: string
  modelNote?: string
  baseUrl: string
  apiKey: string
  model: string
  baseUrlPlaceholder: string
  modelPlaceholder: string
  secretLabel?: string
  secretPlaceholder?: string
  showSecret: boolean
  hasSavedSecret: boolean
  modelState: FeatureModelState
  canFetchModels: boolean
  useCustomModel: boolean
}>()

defineEmits<{
  'update:baseUrl': [value: string]
  'update:apiKey': [value: string]
  'update:model': [value: string]
  'toggle:showSecret': []
  'toggle:useCustomModel': []
  fetchModels: []
  verifyModel: []
}>()
</script>

<style scoped>
.feature-provider-panel {
  display: grid;
  gap: 16px;
}
</style>
