<script setup lang="ts">
/**
 * VideoCloneWorkbench.vue — C107-21 主装配（W21 固定职责：route params →
 * composables，编排面板）。不写 fetch/SSE/状态机细节（在各 composable/面板）。
 */
import { computed, ref, unref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ProjectList from './components/ProjectList.vue';
import NewProjectDialog from './components/NewProjectDialog.vue';
import ProjectHeader from './components/ProjectHeader.vue';
import ReferencePanel from './components/ReferencePanel.vue';
import ClonePlanPanel from './components/ClonePlanPanel.vue';
import MaterialPanel from './components/MaterialPanel.vue';
import PreviewPanel from './components/PreviewPanel.vue';
import AgentActivityPanel from './components/AgentActivityPanel.vue';
import ReviewPanel from './components/ReviewPanel.vue';
import ResultsPanel from './components/ResultsPanel.vue';
import VariantsPanel from './components/VariantsPanel.vue';
import SourcePanel from './components/SourcePanel.vue';
import RuntimePanel from './components/RuntimePanel.vue';
import StudioPanel from './components/StudioPanel.vue';
import { useVideoCloneUrlState, type VideoCloneStep } from './useVideoCloneUrlState';
import { useHypitProjects } from './composables/useHypitProjects';
import { useHypitSource } from './composables/useHypitSource';
import { useHypitJobs } from './composables/useHypitJobs';
import { useHypitPlan } from './composables/useHypitPlan';
import { useHypitPreview } from './composables/useHypitPreview';
import { useHypitResults } from './composables/useHypitResults';
import {
  exportProject,
  listBuilds,
  listTemplates,
  openStudioSession,
  patchProject,
} from './composables/hypit-api';
import { useHypitVariants } from './composables/useHypitVariants';
import { useHypitReviewFlow } from './composables/useHypitReviewFlow';
import type { HypitFeedbackComment, HypitJob, HypitProject, HypitTemplateSummary, HypitVariantItem } from '../../types/hypit';

const router = useRouter();
const route = useRoute();
const url = useVideoCloneUrlState();
const projects = useHypitProjects();
const source = useHypitSource();
const jobs = useHypitJobs();
const plan = useHypitPlan();
const preview = useHypitPreview();
const results = useHypitResults();
const variants = useHypitVariants();
const review = useHypitReviewFlow();

const activeProject = computed(() =>
  projects.projects.value.find((project: HypitProject) => project.id === url.projectId.value) ?? null);

const steps: { id: VideoCloneStep; label: string }[] = [
  { id: 'reference', label: '参考素材' },
  { id: 'plan', label: '复刻方案' },
  { id: 'generate', label: '生成与编辑' },
  { id: 'review', label: '审片与导出' },
];

// --- 新建弹窗与模板 ---
const dialogOpen = ref(false);
const templates = ref<HypitTemplateSummary[]>([]);
const creating = ref(false);
const createError = ref<string | null>(null);

/**
 * C107-22：交接 query（sourceKind/sourceId/label）。id 按 safeId 过滤；
 * label 只用于预填标题，不进创建载荷（归属/固化判定在服务端）。
 */
const handoffSource = computed<{ kind: 'media' | 'analysis'; id: string; label: string } | null>(() => {
  const kind = route.query.sourceKind;
  const id = route.query.sourceId;
  if ((kind !== 'media' && kind !== 'analysis') || typeof id !== 'string') return null;
  if (id.length === 0 || id.length > 64 || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/u.test(id)) return null;
  const label = typeof route.query.label === 'string' ? route.query.label.slice(0, 60) : '';
  return { kind, id, label };
});

function stripHandoffQuery(): Promise<void> {
  if (handoffSource.value === null) return Promise.resolve();
  const query: Record<string, string> = { ...route.query } as Record<string, string>;
  delete query.sourceKind;
  delete query.sourceId;
  delete query.label;
  return router.replace({ query }).then(() => undefined);
}

async function openDialog(): Promise<void> {
  dialogOpen.value = true;
  if (templates.value.length === 0) {
    try {
      templates.value = (await listTemplates()).items;
    } catch {
      templates.value = [];
    }
  }
}

async function submitCreate(input: { title: string; mode: string; templateId: string | null }): Promise<void> {
  creating.value = true;
  createError.value = null;
  const created = await projects.create({
    title: input.title,
    mode: input.templateId !== null ? 'template' : input.mode,
    templateId: input.templateId,
    sourceContext: handoffSource.value === null ? null : { kind: handoffSource.value.kind, id: handoffSource.value.id },
  });
  creating.value = false;
  if (created === null) {
    createError.value = projects.error.value?.message ?? '创建失败';
    return;
  }
  dialogOpen.value = false;
  await stripHandoffQuery();
  url.update({ step: 'reference' });
}

async function removeProject(projectId: string): Promise<void> {
  await projects.remove(projectId);
  if (url.projectId.value === projectId) {
    url.resetForProject();
  }
}

// --- 切工程：加载工程面数据（世代切换在 composables 内以 AbortController 实现） ---
watch(url.projectId, async (projectId) => {
  jobs.stop();
  preview.close();
  if (projectId === null) return;
  void source.refresh(projectId);
  void plan.refresh(projectId);
  void results.refresh(projectId);
  void variants.refresh(projectId);
  void review.refresh(projectId);
}, { immediate: true });

// --- 生成任务（Material/Results 共用 builds 数据；变体/审片状态在各 composable） ---
const buildLoading = ref(false);
const buildError = ref<string | null>(null);
const exporting = ref(false);
const studioSession = ref<Awaited<ReturnType<typeof openStudioSession>> | null>(null);
const studioLoading = ref(false);
const studioError = ref<string | null>(null);

async function refreshBuilds(): Promise<void> {
  if (url.projectId.value === null) return;
  buildLoading.value = true;
  buildError.value = null;
  try {
    const page = await listBuilds(url.projectId.value);
    results.builds.value = page.items;
  } catch (cause) {
    buildError.value = (cause as Error).message;
  } finally {
    buildLoading.value = false;
  }
}

// --- 审片 / 预览 / Studio / 导出 ---
async function openPreview(): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return;
  await preview.open(url.projectId.value, activeProject.value.selectedRun ?? undefined, activeProject.value.revision);
}

async function openStudio(): Promise<void> {
  if (url.projectId.value === null) return;
  studioLoading.value = true;
  studioError.value = null;
  try {
    studioSession.value = await openStudioSession(url.projectId.value, { requestId: crypto.randomUUID() });
  } catch (cause) {
    studioError.value = (cause as Error).message;
    studioSession.value = null;
  } finally {
    studioLoading.value = false;
  }
}

async function exportPackage(): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return;
  exporting.value = true;
  try {
    await exportProject(url.projectId.value, {
      requestId: crypto.randomUUID(),
      title: activeProject.value.title,
      runFile: activeProject.value.selectedRun ?? undefined,
    });
    studioError.value = null;
  } catch (cause) {
    studioError.value = `导出失败：${(cause as Error).message}`;
  } finally {
    exporting.value = false;
  }
}

async function renameProject(title: string): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return;
  try {
    await patchProject(url.projectId.value, {
      requestId: crypto.randomUUID(),
      title,
      baseVersion: activeProject.value.version,
    });
    await projects.refresh();
  } catch (cause) {
    studioError.value = `重命名失败：${(cause as Error).message}`;
  }
}

async function reviseByComments(): Promise<void> {
  if (url.projectId.value === null) return;
  const ok = await review.revise(url.projectId.value, source.revision.value ?? 0);
  if (ok) await source.refresh(url.projectId.value);
}

function addComment(text: string, at: number): Promise<boolean> {
  if (url.projectId.value === null || activeProject.value === null) return Promise.resolve(false);
  return review.add(url.projectId.value, activeProject.value.selectedRun ?? 'main.svrun', text, at);
}

function createBatchOnActive(axes: { key: string; values: string[] }[]): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return Promise.resolve();
  return variants.createBatch(url.projectId.value, activeProject.value.selectedRun ?? 'main.svrun', axes);
}

function actOnVariant(variant: HypitVariantItem, action: 'build' | 'retry' | 'cancel'): Promise<void> {
  if (url.projectId.value === null) return Promise.resolve();
  return variants.act(url.projectId.value, variant, action);
}

function resolveComment(comment: HypitFeedbackComment): Promise<boolean> {
  if (url.projectId.value === null) return Promise.resolve(false);
  return review.resolve(url.projectId.value, comment);
}

const activeJob = computed<HypitJob | null>(() => unref(unref(jobs.active)?.job) ?? null);
const activeEvents = computed<{ sequence: number; kind: string; data: Record<string, unknown> }[]>(() => unref(unref(jobs.active)?.events) ?? []);
const activeConnected = computed<boolean>(() => unref(unref(jobs.active)?.connected) ?? false);

const saveHeaderState = computed(() => {
  switch (source.saveState.value) {
    case 'idle': return 'idle' as const;
    case 'dirty': return 'dirty' as const;
    case 'saving': return 'saving' as const;
    case 'saved': return 'saved' as const;
    case 'conflict': return 'conflict' as const;
    default: return 'error' as const;
  }
});
</script>

<template>
  <div class="clone-workbench" data-testid="video-clone-workbench">
    <ProjectList :items="projects.projects.value" :loading="projects.loading.value"
      :error="projects.error.value?.message ?? null" :active-project-id="url.projectId.value"
      @open="(id: string) => router.push(`/video-clone/${id}`)"
      @create="openDialog" @remove="removeProject" />

    <NewProjectDialog :open="dialogOpen" :templates="templates" :submitting="creating"
      :initial-title="handoffSource?.label ?? null"
      :error="createError" @submit="submitCreate" @cancel="dialogOpen = false" />

    <template v-if="activeProject !== null">
      <ProjectHeader :project="activeProject" :save-state="saveHeaderState" :exporting="exporting"
        @rename="renameProject" @open-studio="openStudio" @export="exportPackage" />

      <nav class="clone-steps" aria-label="工作阶段">
        <button v-for="step in steps" :key="step.id" type="button" class="clone-step"
          :class="{ 'clone-step--active': url.step.value === step.id }"
          @click="url.update({ step: step.id })">{{ step.label }}</button>
      </nav>

      <div v-if="url.step.value === 'reference'" class="clone-step-body">
        <ReferencePanel :project="activeProject" @import-url="void 0" @analyze="void 0" />
        <RuntimePanel :project-ready="activeProject.status === 'ready'" />
      </div>

      <div v-else-if="url.step.value === 'plan'" class="clone-step-body">
        <ClonePlanPanel :plan="plan.plan.value" :plan-loading="plan.loading.value"
          :plan-error="plan.error.value?.message ?? null" :generating="false"
          @regenerate="void 0" @generate="url.update({ step: 'generate' })" />
        <PreviewPanel :session="preview.session.value" :loading="preview.loading.value"
          :error="preview.error.value" :current-frame="preview.currentFrame.value"
          :current-time="preview.currentTime.value" @close="preview.close()" @retry="openPreview" />
      </div>

      <div v-else-if="url.step.value === 'generate'" class="clone-step-body">
        <MaterialPanel :builds="results.builds.value" :build-loading="buildLoading" :build-error="buildError"
          @build="void refreshBuilds()" @retry="void refreshBuilds()" @cancel="void refreshBuilds()" />
        <AgentActivityPanel :project-id="activeProject.id" :job="activeJob"
          :events="activeEvents" :connected="activeConnected" @cancel="jobs.stop()" />
        <SourcePanel :files="source.files.value" :revision="source.revision.value"
          :active-path="source.activePath.value" :draft="source.draft.value"
          :saved-content="source.savedContent.value" :save-state="source.saveState.value"
          :diagnostics="source.diagnostics.value" :saving="source.saveState.value === 'saving'"
          @open="source.open(activeProject.id, $event)" @edit="source.edit" @save="source.save(activeProject.id, $event)" />
      </div>

      <div v-else class="clone-step-body">
        <ReviewPanel :comments="review.comments.value" :hash="review.hash.value" :loading="review.loading.value"
          :error="review.error.value" :revising="review.revising.value" @add="addComment" @resolve="resolveComment"
          @revise="reviseByComments" />
        <ResultsPanel :builds="results.builds.value" :outputs="results.outputs.value"
          :active-build-id="results.activeBuildId.value" :loading="results.loading.value"
          :error="results.error.value" :archiving-id="results.archiving.value"
          @select-build="results.selectBuild(activeProject.id, $event)"
          @archive="results.archive(activeProject.id, results.activeBuildId.value ?? '', $event.id)" />
        <VariantsPanel :items="variants.items.value" :loading="variants.loading.value" :error="variants.error.value"
          :creating="variants.loading.value" :acting-id="variants.actingId.value" @create="createBatchOnActive"
          @build="actOnVariant($event, 'build')" @retry="actOnVariant($event, 'retry')"
          @cancel="actOnVariant($event, 'cancel')" />
      </div>

      <StudioPanel v-if="studioSession !== null || studioLoading || studioError !== null"
        :session="studioSession" :loading="studioLoading" :error="studioError"
        @close="studioSession = null; studioError = null" @reopen="openStudio" />
    </template>
    <section v-else-if="url.projectId.value !== null" class="gl-zone" data-testid="clone-loading" aria-live="polite">
      正在打开工程…
    </section>
  </div>
</template>

<style scoped>
.clone-workbench { display: grid; gap: 16px; }
.clone-steps { display: flex; gap: 4px; overflow-x: auto; }
.clone-step { background: none; border: 1px solid transparent; color: var(--color-text-secondary); padding: 8px 14px; border-radius: var(--radius-md); cursor: pointer; font-size: 14px; white-space: nowrap; }
.clone-step--active { background: var(--surface-muted); color: var(--color-text); border-color: var(--color-border); }
.clone-step-body { display: grid; gap: 16px; }
</style>
