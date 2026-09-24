<template>
  <section class="dh-end-panel glass-card" aria-labelledby="dh-end-title" data-testid="dh-end-panel">
    <h3 id="dh-end-title" class="dh-end-title">会话已结束</h3>

    <dl class="dh-end-facts">
      <div class="dh-end-fact">
        <dt>已确认费用</dt>
        <dd class="gl-num">{{ confirmedText }}</dd>
      </div>
      <div class="dh-end-fact">
        <dt>待核对调用</dt>
        <dd>
          <span v-if="pendingCount > 0" class="badge badge-warning" data-testid="dh-end-pending">{{ pendingCount }} 笔待核对</span>
          <span v-else class="dh-hint">无</span>
        </dd>
      </div>
      <div class="dh-end-fact">
        <dt>字幕保存窗口</dt>
        <dd>
          <template v-if="windowExpired">
            <span class="badge badge-neutral" data-testid="dh-end-window-expired">已过期</span>
            <span class="dh-hint">保存窗口已关闭，不能从本页重新上传。</span>
          </template>
          <template v-else-if="windowDeadlineText">
            <span class="gl-num">{{ windowDeadlineText }}</span> 前可保存
          </template>
          <template v-else>
            <span class="dh-hint">以服务端窗口为准。</span>
          </template>
        </dd>
      </div>
    </dl>

    <p class="dh-hint">
      「待核对」表示用量未知（不以 0 冒充），平台核对后更新；已确认费用只含服务端已结算部分。
    </p>

    <div class="gl-actions">
      <button
        type="button"
        class="gl-btn-primary"
        :disabled="windowExpired || busy"
        data-testid="dh-end-save"
        @click="emit('save-now')"
      >
        保存字幕
      </button>
      <button type="button" class="gl-btn-secondary" :disabled="busy" data-testid="dh-end-export" @click="emit('export')">
        导出已保存文本
      </button>
      <button type="button" class="gl-btn-secondary" data-testid="dh-end-restart" @click="emit('restart')">
        重新开始一场
      </button>
    </div>
    <p v-if="error" class="gl-alert gl-alert-error" role="alert">{{ error }}</p>

    <p class="dh-hint">
      删除字幕只影响本场文本；已另存素材库的录制视频/配音是独立产物，不受影响。
    </p>
  </section>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：待核对费用/保存窗口/删除说明；窗口过期禁用保存（E-05 步骤3）。
import { computed } from 'vue'
import { formatYuan } from '../../../lib/money'

const props = defineProps<{
  confirmedCents: number | null
  pendingCount: number
  /** epoch 毫秒；null=刷新后窗口未知（以服务端拒绝为准）。 */
  windowDeadline: number | null
  windowExpired: boolean
  busy?: boolean
  error?: string | null
}>()

const emit = defineEmits<{
  (e: 'save-now'): void
  (e: 'export'): void
  (e: 'restart'): void
}>()

/** null（未知）显示「待核对」；0 仅真正 0 才显示 ¥0.00（E-05 步骤3）。 */
const confirmedText = computed(() =>
  props.confirmedCents == null ? '待核对' : formatYuan(props.confirmedCents))

const windowDeadlineText = computed(() => {
  if (props.windowDeadline == null) return null
  return new Date(props.windowDeadline).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})
</script>

<style scoped>
.dh-end-panel { display: grid; gap: var(--space-sm); padding: var(--space-lg); }
.dh-end-title {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-end-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: var(--space-sm); margin: 0; }
.dh-end-fact { display: grid; gap: var(--space-xxs); }
.dh-end-fact dt { font-size: var(--type-caption); color: var(--color-text-muted); }
.dh-end-fact dd { margin: 0; font-size: var(--type-body-sm); color: var(--color-text); display: grid; gap: var(--space-xxs); }
</style>
