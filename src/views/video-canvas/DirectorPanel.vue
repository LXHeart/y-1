<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CanvasShot, GroupingBranch, StoryboardGrouping } from './useVideoCanvas'

const props = defineProps<{
  shot: CanvasShot | null
  grouping: StoryboardGrouping | null
  activeBranchId: string | null
  dirty: boolean
}>()

const emit = defineEmits<{
  (e: 'edit'): void
  (e: 'save-shot', shotId: string, patch: { visual?: string; narration?: string; plannedSeconds?: number; cameraMove?: string }): void
  (e: 'save-grouping', grouping: StoryboardGrouping): void
  (e: 'switch-branch', branchId: string | null): void
}>()

const CAMERA_MOVES = ['固定机位', '缓慢推近', '缓慢拉远', '左右横移', '跟随运镜', '环绕',
  '俯拍下摇', '仰拍上摇', '特写切换', '手持感轻晃', '升降镜头', '旋转']

const activeTab = ref<'property' | 'grouping'>('property')
const draftVisual = ref('')
const draftNarration = ref('')
const draftSeconds = ref(5)
const draftCamera = ref('固定机位')
const groupIdInput = ref('')
const branchNameInput = ref('')

watch(() => props.shot?.id, () => {
  draftVisual.value = props.shot?.visual ?? ''
  draftNarration.value = props.shot?.narration ?? ''
  draftSeconds.value = props.shot?.plannedSeconds ?? 5
  draftCamera.value = props.shot?.cameraMove ?? '固定机位'
}, { immediate: true })

// 草稿一旦变化即上报未保存态（保存成功由父级清脏）
watch([draftVisual, draftNarration, draftSeconds, draftCamera], () => {
  emit('edit')
})

const branchList = computed<GroupingBranch[]>(() => props.grouping?.branches ?? [])

function saveShot(): void {
  if (!props.shot) return
  emit('save-shot', props.shot.id, {
    visual: draftVisual.value,
    narration: draftNarration.value,
    plannedSeconds: draftSeconds.value,
    cameraMove: draftCamera.value,
  })
}

/** 分组指派：选中镜头挂到输入的 groupId（空=取消分组）。 */
function assignGroup(): void {
  if (!props.shot || !props.grouping) return
  const shots = props.grouping.shots.filter(entry => entry.id !== props.shot?.id)
  if (groupIdInput.value.trim()) {
    shots.push({ id: props.shot.id, groupId: groupIdInput.value.trim() })
  }
  emit('save-grouping', { ...props.grouping, shots })
}

/** 新建分支：以当前分支（或全部）镜头序列为命名快照。 */
function createBranch(): void {
  if (!props.grouping || !branchNameInput.value.trim()) return
  const currentShotIds = props.activeBranchId
    ? (props.grouping.branches.find(branch => branch.id === props.activeBranchId)?.shotIds
      ?? props.grouping.shots.map(entry => entry.id))
    : props.grouping.shots.map(entry => entry.id)
  const branch: GroupingBranch = {
    id: `b-${Date.now()}`,
    name: branchNameInput.value.trim(),
    shotIds: [...currentShotIds],
  }
  branchNameInput.value = ''
  emit('save-grouping', { ...props.grouping, branches: [...props.grouping.branches, branch] })
}
</script>

<template>
  <aside class="director-panel gl-zone" data-test="director-panel">
    <div class="panel-tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'property'"
        :class="{ 'panel-tab-active': activeTab === 'property' }"
        data-test="director-tab-property"
        @click="activeTab = 'property'"
      >镜头属性</button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'grouping'"
        :class="{ 'panel-tab-active': activeTab === 'grouping' }"
        data-test="director-tab-grouping"
        @click="activeTab = 'grouping'"
      >分组与分支</button>
    </div>

    <div v-if="activeTab === 'property'" class="panel-body">
      <template v-if="shot">
        <div class="gl-row">
          <label :for="`director-visual-${shot.id}`">画面描述</label>
        </div>
        <textarea
          :id="`director-visual-${shot.id}`"
          v-model="draftVisual"
          rows="3"
          data-test="director-visual"
        ></textarea>
        <div class="gl-row">
          <label :for="`director-narration-${shot.id}`">旁白</label>
        </div>
        <textarea
          :id="`director-narration-${shot.id}`"
          v-model="draftNarration"
          rows="3"
          data-test="director-narration"
        ></textarea>
        <div class="gl-row">
          <label for="director-seconds">时长（4-6 秒）</label>
          <input
            id="director-seconds"
            v-model.number="draftSeconds"
            type="number"
            min="4"
            max="6"
            step="1"
            data-test="director-seconds"
          />
        </div>
        <div class="gl-row">
          <label for="director-camera">运镜</label>
          <select id="director-camera" v-model="draftCamera" data-test="director-camera">
            <option v-for="move in CAMERA_MOVES" :key="move" :value="move">{{ move }}</option>
          </select>
        </div>
        <button type="button" class="gl-btn-primary panel-save" data-test="director-save-shot" @click="saveShot">
          保存镜头
        </button>
        <p v-if="dirty" class="field-note" data-test="director-dirty-hint">有未保存的改动，切换模式前会提示保存</p>
      </template>
      <p v-else class="panel-empty" data-test="director-empty">点击画布中的镜头节点查看与编辑属性</p>
    </div>

    <div v-else class="panel-body">
      <div class="gl-row">
        <label for="director-group">选中镜头分组</label>
        <input
          id="director-group"
          v-model="groupIdInput"
          placeholder="如：开场钩子段"
          data-test="director-group-input"
        />
      </div>
      <button
        type="button"
        class="panel-assign"
        :disabled="!shot || !grouping"
        data-test="director-assign-group"
        @click="assignGroup"
      >{{ shot ? `把镜头 ${shot.seq} 挂到分组` : '先选中镜头' }}</button>

      <div class="panel-divider"></div>

      <div class="gl-row">
        <label>版本分支</label>
      </div>
      <div
        v-for="branch in branchList"
        :key="branch.id"
        class="panel-branch"
        :class="{ 'panel-branch-active': branch.id === activeBranchId }"
        :data-test="`director-branch-${branch.name}`"
      >
        <button type="button" data-test="director-branch-switch" @click="emit('switch-branch', branch.id === activeBranchId ? null : branch.id)">
          {{ branch.name }}（{{ branch.shotIds.length }} 镜）
        </button>
      </div>
      <button
        v-if="activeBranchId"
        type="button"
        class="panel-assign"
        data-test="director-branch-all"
        @click="emit('switch-branch', null)"
      >回到全部分支</button>

      <div class="panel-divider"></div>

      <div class="gl-row">
        <label for="director-branch-name">新分支（当前序列快照）</label>
        <input id="director-branch-name" v-model="branchNameInput" placeholder="如：精简版" data-test="director-branch-name" />
      </div>
      <button
        type="button"
        class="panel-assign"
        :disabled="!grouping || !branchNameInput.trim()"
        data-test="director-create-branch"
        @click="createBranch"
      >创建分支</button>
    </div>
  </aside>
</template>

<style scoped>
.director-panel {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.panel-tabs { display: flex; border-bottom: 1px solid var(--color-border); }
.panel-tabs button {
  flex: 1;
  border: none;
  border-radius: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
}
.panel-tabs .panel-tab-active {
  color: var(--color-accent-2);
  border-bottom-color: var(--color-accent);
}
.panel-body { padding: var(--space-md); display: flex; flex-direction: column; gap: var(--space-sm); }
.panel-body textarea { width: 100%; }
.panel-empty { color: var(--color-text-secondary); font-size: var(--text-sm); text-align: center; padding: var(--space-xl) 0; }
.panel-save { margin-top: var(--space-sm); }
.panel-assign { align-self: flex-start; }
.panel-divider { border-top: 1px solid var(--color-border); margin: var(--space-xs) 0; }
.panel-branch button { width: 100%; text-align: left; border-radius: var(--radius-md); }
.panel-branch-active button { border-color: var(--color-accent); color: var(--color-accent-2); }
.field-note { color: var(--color-text-secondary); font-size: var(--text-xs); }
</style>
