<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CanvasShot, GroupingBranch, StoryboardGrouping } from './useVideoCanvas'
import { useCanvasShotEditor } from './composables/useCanvasShotEditor'
import type { ShotEditorHandle } from './composables/useCanvasShotEditor'

const props = defineProps<{
  shot: CanvasShot | null
  grouping: StoryboardGrouping | null
  activeBranchId: string | null
  dirty: boolean
  /** 外部编辑会话（视图持有，切镜 flush 闸共用）；缺省时组件自持（保存走 save-shot 事件）。 */
  editor?: ShotEditorHandle | null
  /** committed 分镜只读（§8.2：内容字段只读，不让用户输入后才发现无法保存）。 */
  readonly?: boolean
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
const groupIdInput = ref('')
const branchNameInput = ref('')

/** 内部回退编辑器（无外部 editor 时的独立面板用法）：保存即上抛 save-shot（既有测试契约）。 */
const internalEditor = useCanvasShotEditor({
  loadFields: shotId => (props.shot?.id === shotId
    ? {
        visual: props.shot.visual,
        narration: props.shot.narration,
        plannedSeconds: props.shot.plannedSeconds,
        cameraMove: props.shot.cameraMove,
      }
    : null),
  save: async (shotId, fields) => {
    emit('save-shot', shotId, { ...fields })
    return { ok: true }
  },
  currentVersion: () => null,
})

const editor = computed<ShotEditorHandle>(() => props.editor ?? internalEditor)

/** 镜头切换跟随：外部会话由视图先 flush 再换目标，这里只负责把面板绑定到当前目标。 */
watch(() => props.editor?.state.editingShotId ?? props.shot?.id ?? null, (target) => {
  if (!props.editor && target !== internalEditor.state.editingShotId) {
    internalEditor.beginEdit(target)
  }
}, { immediate: true })

/** 真实输入进入 dirty 才上抛未保存态（载入抑制在编辑器内完成）。 */
watch(() => editor.value.state.dirty, (dirty) => {
  if (dirty) emit('edit')
})

const draft = computed(() => editor.value.state.draft)

const branchList = computed<GroupingBranch[]>(() => props.grouping?.branches ?? [])

function saveShot(): void {
  void editor.value.flush()
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
          v-model="draft.visual"
          rows="3"
          :disabled="readonly || editor.state.saving"
          aria-describedby="director-save-note"
          data-test="director-visual"
        ></textarea>
        <div class="gl-row">
          <label :for="`director-narration-${shot.id}`">旁白</label>
        </div>
        <textarea
          :id="`director-narration-${shot.id}`"
          v-model="draft.narration"
          rows="3"
          :disabled="readonly || editor.state.saving"
          aria-describedby="director-save-note"
          data-test="director-narration"
        ></textarea>
        <div class="gl-row">
          <label for="director-seconds">时长（4-6 秒）</label>
        </div>
        <div>
          <input
            id="director-seconds"
            v-model.number="draft.plannedSeconds"
            type="number"
            min="4"
            max="6"
            step="1"
            :disabled="readonly || editor.state.saving"
            aria-describedby="director-save-note"
            data-test="director-seconds"
          />
        </div>
        <div class="gl-row">
          <label for="director-camera">运镜</label>
        </div>
        <div>
          <select id="director-camera" v-model="draft.cameraMove" :disabled="readonly || editor.state.saving" data-test="director-camera">
            <option v-for="move in CAMERA_MOVES" :key="move" :value="move">{{ move }}</option>
          </select>
        </div>
        <button
          type="button"
          class="gl-btn-primary panel-save"
          :disabled="readonly || editor.state.saving || !editor.state.dirty"
          data-test="director-save-shot"
          @click="saveShot"
        >{{ editor.state.saving ? '保存中…' : '保存镜头' }}</button>
        <p id="director-save-note" role="status" data-test="director-save-note">
          <span v-if="readonly" class="field-note" data-test="director-readonly-hint">
            该分镜已用于成片制作，内容只读；如需修改请创建独立方案
          </span>
          <span v-else-if="editor.state.conflict" class="field-note panel-conflict" data-test="director-conflict-hint">
            分镜已被其他页面修改，草稿已保留——刷新载入最新后再试
          </span>
          <span v-else-if="editor.state.errorMessage" class="field-note panel-conflict" data-test="director-error-hint">
            {{ editor.state.errorMessage }}
          </span>
          <span v-else-if="dirty" class="field-note" data-test="director-dirty-hint">
            有未保存的改动，切换模式前会提示保存
          </span>
        </p>
      </template>
      <p v-else class="panel-empty" data-test="director-empty">点击画布中的镜头节点查看与编辑属性</p>
    </div>

    <div v-else class="panel-body">
      <div class="gl-row">
        <label for="director-group">选中镜头分组</label>
      </div>
      <div>
        <input
          id="director-group"
          v-model="groupIdInput"
          placeholder="如：开场钩子段"
          data-test="director-group-input"
        />
      </div>
      <button type="button" class="panel-assign" :disabled="!shot || !grouping" data-test="director-assign-group" @click="assignGroup">
        {{ shot ? `把镜头 ${shot.seq} 挂到分组` : '先选中镜头' }}
      </button>

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
      </div>
      <div>
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
.panel-conflict { color: var(--color-warning, #b45309); }
.panel-body textarea:disabled,
.panel-body input:disabled,
.panel-body select:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
