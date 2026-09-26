<script setup lang="ts">
/**
 * NewProjectDialog.vue — C107-21：新建工程弹窗（输入 templates/source；事件
 * submit/cancel）。不直接写源码或提交模型——只收集选择。
 */
import { computed, ref, watch } from 'vue';
import GlModal from '../../../components/GlModal.vue';
import type { HypitTemplateSummary } from '../../../types/hypit';

const props = defineProps<{
  open: boolean;
  templates: HypitTemplateSummary[];
  submitting: boolean;
  error: string | null;
  /** C107-22：来自 query 的展示 label 预填（只展示；身份/权限判定在服务端）。 */
  initialTitle?: string | null;
}>();

const emit = defineEmits<{
  submit: [input: { title: string; mode: 'clone' | 'brief' | 'template'; templateId: string | null }];
  cancel: [];
}>();

const title = ref('');
const mode = ref<'clone' | 'brief' | 'template'>('clone');
const templateId = ref<string | null>(null);

watch(() => props.open, (value) => {
  if (value) {
    title.value = (props.initialTitle ?? '').slice(0, 60);
    mode.value = 'clone';
    templateId.value = null;
  }
});

const canSubmit = computed(() => title.value.trim().length > 0 && !props.submitting);

function submit(): void {
  if (!canSubmit.value) return;
  emit('submit', { title: title.value.trim(), mode: mode.value, templateId: templateId.value });
}
</script>

<template>
  <GlModal v-if="props.open" title="新建视频克隆工程" persistent @close="emit('cancel')">
    <form class="clone-new-form" data-testid="clone-new-project-dialog" @submit.prevent="submit">
      <label class="gl-field">
        <span>工程名称</span>
        <input v-model="title" type="text" maxlength="60" required data-testid="clone-new-title"
          placeholder="例如：双十一榜单复刻" />
      </label>
      <fieldset class="clone-mode-fieldset">
        <legend>从哪里开始</legend>
        <label class="clone-mode-option">
          <input v-model="mode" type="radio" value="clone" name="clone-mode" />
          <span>参考视频克隆</span>
        </label>
        <label class="clone-mode-option">
          <input v-model="mode" type="radio" value="brief" name="clone-mode" />
          <span>想法直创</span>
        </label>
        <label class="clone-mode-option">
          <input v-model="mode" type="radio" value="template" name="clone-mode" />
          <span>从模板开始</span>
        </label>
      </fieldset>
      <label v-if="mode === 'template'" class="gl-field">
        <span>选择模板</span>
        <select v-model="templateId" data-testid="clone-new-template">
          <option :value="null" disabled>选择模板</option>
          <option v-for="template in props.templates" :key="template.templateId" :value="template.templateId">
            {{ template.title }}{{ template.materialState === 'ready' ? '' : '（素材未就绪）' }}
          </option>
        </select>
      </label>
      <p v-if="props.error" class="clone-form-error" data-testid="clone-new-error" role="alert">{{ props.error }}</p>
      <footer class="clone-form-actions">
        <button type="button" class="gl-btn-secondary" @click="emit('cancel')">取消</button>
        <button type="submit" class="gl-btn-primary" data-testid="clone-new-submit" :disabled="!canSubmit">
          {{ props.submitting ? '创建中…' : '创建工程' }}
        </button>
      </footer>
    </form>
  </GlModal>
</template>

<style scoped>
.clone-new-form { display: grid; gap: 16px; min-width: min(420px, 82vw); }
.clone-mode-fieldset { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px; display: grid; gap: 8px; }
.clone-mode-option { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.clone-form-error { color: var(--color-danger); margin: 0; }
.clone-form-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
