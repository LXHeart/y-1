<script setup lang="ts">
import { computed } from 'vue'
import type { DhAdminConfigDraft } from '../composables/useDigitalHumanAdmin'

/**
 * 数字人治理配置表单（任务书 #105G C105G-04 / K10 ADMIN01-02）。
 *
 * 只编辑批准 backend / 容量 / 功能开关；模型、凭据与价格不在本面板——文案显式指引
 * 「AI 模型」面板。draft 是状态源持有的可编辑副本（defineModel 双向）：本组件直接
 * 改其字段；服务端版本前进（他人已保存）不重抄草稿，409 冲突保表单并提示重新加载
 * （tc105g_04_02）。
 */
const props = defineProps<{
  serverVersion: number | null
  baseVersion: number | null
  updating: boolean
  conflict: string | null
  error: string | null
  notice: string | null
}>()

// defineModel 而非普通 prop：本组件是草稿的指定编辑器（嵌套字段就地编辑，状态源
// 与表单共享同一对象引用），v-model 通道由 defineModel 显式声明。
const draft = defineModel<DhAdminConfigDraft | null>('draft', { required: true })

const emit = defineEmits<{
  submit: []
  reload: []
}>()

const submittable = computed(() => draft.value != null && draft.value.reason.trim() !== '' && !props.updating)
</script>

<template>
  <section class="dh-config-form" data-test="dh-config-form" aria-label="数字人配置">
    <p v-if="conflict" class="gl-alert dh-conflict" role="alert" data-test="dh-config-conflict">
      {{ conflict }}
      <button type="button" class="dh-inline-btn" data-test="dh-config-reload" @click="emit('reload')">重新加载</button>
    </p>
    <p v-if="error" class="error-msg" role="alert" data-test="dh-config-error">{{ error }}</p>
    <p v-if="notice" class="dh-notice" role="status" data-test="dh-config-notice">{{ notice }}</p>

    <p v-if="!draft" class="loading-state" data-test="dh-config-loading">正在读取配置…</p>
    <form v-else class="dh-form" @submit.prevent="emit('submit')">
      <div class="dh-field-grid">
        <label class="dh-check">
          <input type="checkbox" data-test="dh-config-enabled" v-model="draft.enabled" />
          <span>数字人服务总开关（关闭后个人端入口隐藏）</span>
        </label>
        <label class="dh-check">
          <input type="checkbox" data-test="dh-config-new-sessions" v-model="draft.newSessionsAllowed" />
          <span>允许创建新会话</span>
        </label>
        <label class="dh-check">
          <input type="checkbox" data-test="dh-config-recording" v-model="draft.recordingEnabled" />
          <span>允许会话内录制</span>
        </label>
        <label class="dh-check">
          <input type="checkbox" data-test="dh-config-custom-avatar" v-model="draft.customAvatarEnabled" />
          <span>允许自定义头像上传</span>
        </label>
      </div>

      <div class="dh-field-grid">
        <label class="dh-field">
          <span>全局并发会话上限（1–100）</span>
          <input class="field-input" type="number" min="1" max="100" data-test="dh-config-max-sessions"
            v-model.number="draft.maxSessionsGlobal" />
        </label>
        <label class="dh-field">
          <span>全局排队上限（0–100）</span>
          <input class="field-input" type="number" min="0" max="100" data-test="dh-config-max-queued"
            v-model.number="draft.maxQueuedGlobal" />
        </label>
        <label class="dh-field">
          <span>计费告知版本</span>
          <input class="field-input" type="text" data-test="dh-config-billing-notice"
            v-model="draft.billingNoticeVersion" />
        </label>
      </div>

      <label class="dh-field">
        <span>批准渲染后端 ID（逗号分隔；仅限控制面已启用且有凭据的行）</span>
        <input class="field-input" type="text" data-test="dh-config-backends" v-model="draft.allowedBackendIds"
          placeholder="backend-rt, backend-backup" />
      </label>

      <div class="dh-field-grid">
        <fieldset class="dh-toggle-list">
          <legend>预置形象上架状态</legend>
          <label v-for="state in draft.presetAvatarStates" :key="state.id" class="dh-check">
            <input type="checkbox" :data-test="`dh-config-avatar-${state.id}`" v-model="state.enabled" />
            <span>{{ state.id }}</span>
          </label>
        </fieldset>
        <fieldset class="dh-toggle-list">
          <legend>音色上架状态</legend>
          <label v-for="state in draft.voiceStates" :key="state.id" class="dh-check">
            <input type="checkbox" :data-test="`dh-config-voice-${state.id}`" v-model="state.enabled" />
            <span>{{ state.id }}</span>
          </label>
        </fieldset>
      </div>

      <label class="dh-field">
        <span>变更原因（必填，写入治理审计）</span>
        <textarea class="field-input field-textarea" data-test="dh-config-reason" v-model="draft.reason"
          placeholder="例如：开启灰度、扩容并发" maxlength="200"></textarea>
      </label>

      <div class="dh-form-actions">
        <button type="submit" class="approve-btn" data-test="dh-config-submit" :disabled="!submittable">
          {{ updating ? '保存中…' : '保存配置' }}
        </button>
        <span class="dh-version" data-test="dh-config-versions">
          服务端版本 v{{ serverVersion ?? '—' }} · 表单基于 v{{ baseVersion ?? '—' }}
        </span>
      </div>
    </form>

    <p class="dh-scope-note" data-test="dh-config-scope-note">
      渲染由第三方服务提供，仅批准集内的后端可被选用；未实测或无凭据的后端不会进入批准集。
      模型、凭据与价格不在本面板维护——请前往「AI 模型」面板配置（digital_human_render 能力）。
    </p>
  </section>
</template>

<style scoped src="../admin-shared.css"></style>
<style scoped>
.dh-config-form { display: grid; gap: var(--space-sm); }
</style>
