<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CanvasShot } from '../useVideoCanvas'
import type { ShotMediaSource } from '../../../types/video-canvas'
import type { VideoTaskSessionHost } from '../../../composables/useVideoTaskSession'
import TakePreview from '../../video-production/components/TakePreview.vue'
import { useCanvasMediaPreview } from '../composables/useCanvasMediaPreview'

/**
 * 任务书 #100 C100-06：画布候选面板——预览/比较/采用与正确重抽入口。
 *
 * 采用反馈绑定共享任务会话的待确认队列（C100-05）：点击即「保存中」回显，失败回滚
 * 乐观层并可重试，未确认期间提示不能合成；重抽按实际阶段区分（成片后=reroll，
 * 其余=regenerate，R09）。own-media 镜头的候选来源属后置卡，这里保留扩展位但不伪造入口。
 */
const props = defineProps<{
  shot: CanvasShot | null
  session: VideoTaskSessionHost | null
  /** 任务书 #100 C100-13：该镜制作来源（own-media 显示确定来源就绪态，禁重抽）。 */
  source?: ShotMediaSource | null
}>()

const isOwnMedia = computed(() => props.source?.kind === 'own-media')
const preview = useCanvasMediaPreview({ identity: () => props.shot?.id ?? '', source: () => props.source })

const emit = defineEmits<{
  (e: 'refresh-media'): void
}>()

const task = computed(() => props.session?.task.value ?? null)
const taskError = computed(() => props.session?.taskError.value ?? '')
const pendingCount = computed(() => props.session?.pendingSelectionCount.value ?? 0)
const selection = computed(() => task.value?.selection ?? {})
const adoptedTakeId = computed(() => props.shot ? selection.value[props.shot.id] : undefined)
const adoptedTake = computed(() =>
  props.shot?.takes.find(take => take.id === adoptedTakeId.value) ?? null)
/** 成片后重抽走 reroll，非终态走 regenerate（R09）。 */
const rerollAction = computed<'reroll' | 'regenerate'>(() =>
  task.value?.phase === 'succeeded' ? 'reroll' : 'regenerate')

/** 本面板在途的采用（per-shot）：await 会话写链完成才解除「保存中」。 */
const savingTakeId = ref<string | null>(null)

/** 失败原因等扩展字段来自任务侧 TaskTake（分镜详情的 CanvasTake 只有基础展示面）。 */
function taskTakeNote(takeId: string): string | null {
  const shotId = props.shot?.id
  const taskShot = shotId ? task.value?.shots.find(candidate => candidate.id === shotId) : null
  return taskShot?.takes.find(candidate => candidate.id === takeId)?.errorMessage ?? null
}

function takeStatusLabel(status: string): string {
  return { queued: '排队中', submitted: '已提交', processing: '生成中', succeeded: '已完成',
    failed: '失败', cancelled: '已取消' }[status] || status
}

/** 质检角标色阶（#66 D2）：≥80 优、60-79 提示、<60 风险——复用既有 badge token。 */
function scoreBadgeClass(score: number): string {
  if (score >= 80) return 'badge-success'
  if (score >= 60) return 'badge-warning'
  return 'badge-danger'
}

async function adopt(takeId: string): Promise<void> {
  if (!props.session || !props.shot || savingTakeId.value) return
  savingTakeId.value = takeId
  try {
    await props.session.selectTake(props.shot.id, takeId)
  } finally {
    savingTakeId.value = null
  }
}

async function reroll(): Promise<void> {
  if (!props.session || !props.shot || isOwnMedia.value) return
  if (rerollAction.value === 'reroll') {
    await props.session.rerollShot(props.shot.id)
  } else {
    await props.session.regenerateShot(props.shot.id)
  }
}
</script>

<template>
  <div class="canvas-take-panel" data-test="canvas-take-panel">
    <template v-if="shot">
      <div class="take-panel-head">
        <span class="badge badge-accent">第 {{ shot.seq }} 镜</span>
        <span class="field-note">候选 {{ shot.takes.length }} 条</span>
      </div>

      <div v-if="isOwnMedia" data-test="canvas-take-own-source">
        <p class="field-note">使用自有素材{{ source?.kind === 'own-media' ? ` · ${{ source: '保留原音', narration: '合成时使用 AI 旁白', mute: '静音' }[source.audioMode]}` : '' }}。</p>
        <p v-if="source?.kind === 'own-media' && source.trimStartMs != null" class="field-note">截取 {{ source.trimStartMs }}–{{ source.trimEndMs }} ms</p>
        <p v-else class="field-note">图片按镜头时长展示。</p>
        <button type="button" class="gl-btn-ghost" :disabled="preview.loading.value" data-test="canvas-own-preview-load" @click="preview.load">{{ preview.loading.value ? '读取预览中…' : '预览所选素材' }}</button>
        <img v-if="preview.isImage.value && preview.url.value" :src="preview.url.value" alt="自有制作素材" class="own-image-preview">
        <TakePreview v-else-if="preview.url.value" :url="preview.url.value" v-bind="preview.range.value" :muted="preview.muted.value"
          data-test="canvas-own-preview" @media-error="preview.load" />
        <p v-if="preview.error.value" class="field-note" role="alert" data-test="canvas-own-preview-error">{{ preview.error.value }}</p>
      </div>

      <p v-else-if="!shot.takes.length" class="panel-empty" data-test="canvas-takes-empty">
        尚无候选——生成后在此比较与采用
      </p>

      <ul v-else class="take-list">
        <li
          v-for="take in shot.takes"
          :key="take.id"
          class="take-item"
          :class="{ 'take-item-adopted': take.id === adoptedTakeId }"
          :data-test="`canvas-take-${take.takeNo}`"
        >
          <TakePreview
            :url="take.url"
            :placeholder="takeStatusLabel(take.status)"
            :note="taskTakeNote(take.id)"
            :data-test="`canvas-take-preview-${take.takeNo}`"
            @media-error="emit('refresh-media')"
          />
          <div class="take-meta-row">
            <span v-if="take.score != null" class="badge" :class="scoreBadgeClass(take.score)">
              质检 {{ take.score }}
            </span>
            <span v-else class="field-note" :data-test="`canvas-take-unscored-${take.takeNo}`">未评分</span>
            <span
              v-for="label in take.scoreLabels"
              :key="label"
              class="badge badge-neutral"
            >{{ label }}</span>
          </div>
          <button
            type="button"
            class="take-adopt"
            :disabled="!take.selectable || !session || savingTakeId != null"
            :data-test="`canvas-take-adopt-${take.takeNo}`"
            @click="adopt(take.id)"
          >{{ savingTakeId === take.id ? '保存中…' : take.id === adoptedTakeId ? '已采用' : '采用' }}</button>
        </li>
      </ul>

      <button
        v-if="!isOwnMedia"
        type="button"
        class="take-reroll"
        :disabled="!session"
        data-test="canvas-take-regenerate"
        @click="reroll"
      >{{ rerollAction === 'reroll' ? '重抽（成片后重抽）' : '重抽' }}</button>
      <p v-if="taskError" role="alert" class="field-note panel-conflict" data-test="canvas-take-error">
        {{ taskError }}
      </p>
      <p v-if="pendingCount > 0" role="status" class="field-note" data-test="canvas-take-pending">
        选择保存中——未确认前不能合成
      </p>
      <p v-else-if="adoptedTake && !taskError" class="field-note" data-test="canvas-take-saved">
        已保存当前采用（候选 {{ adoptedTake.takeNo }}）
      </p>
      <p v-if="!isOwnMedia && (!session || !task)" class="field-note" data-test="canvas-take-no-task">
        尚未关联制作任务——候选为只读预览；从快速模式发起制作后可在此采用
      </p>
    </template>
    <p v-else class="panel-empty" data-test="canvas-take-empty-shot">点击画布中的镜头节点查看候选</p>
  </div>
</template>

<style scoped>
.canvas-take-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.take-panel-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.take-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.take-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-xxs);
  padding: var(--space-xs);
  border: var(--border-width) solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.take-item-adopted {
  border-color: var(--color-accent);
}

.take-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xxs);
}

.take-adopt,
.take-reroll {
  min-height: var(--touch-target);
  padding: 0 var(--space-md);
  border: var(--border-width) solid var(--color-border-control);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text);
  font-size: var(--text-sm);
  cursor: pointer;
}

.take-adopt:hover:not(:disabled),
.take-reroll:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--surface-hover);
}

.take-adopt:disabled {
  color: var(--color-text-secondary);
  opacity: 0.6;
  cursor: not-allowed;
}

.take-item-adopted .take-adopt {
  border-color: var(--color-accent);
  color: var(--color-accent);
}

.take-reroll {
  align-self: flex-start;
}

.panel-empty {
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
}

.own-image-preview { display: block; width: 100%; max-height: var(--layout-rail); object-fit: contain; }
</style>
