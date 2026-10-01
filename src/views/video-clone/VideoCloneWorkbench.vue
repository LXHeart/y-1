<script setup lang="ts">
/**
 * VideoCloneWorkbench.vue — C107-21 主装配（W21 固定职责：route params →
 * composables，编排面板）。不写 fetch/SSE/状态机细节（在各 composable/面板）。
 */
import { computed, ref, unref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import GlModal from '../../components/GlModal.vue';
import ProjectList from './components/ProjectList.vue';
import NewProjectDialog from './components/NewProjectDialog.vue';
import ProjectHeader from './components/ProjectHeader.vue';
import ProjectPackageDialog from './components/ProjectPackageDialog.vue';
import ReferencePanel from './components/ReferencePanel.vue';
import ClonePlanPanel from './components/ClonePlanPanel.vue';
import MaterialPanel from './components/MaterialPanel.vue';
import PreviewPanel from './components/PreviewPanel.vue';
import AgentActivityPanel from './components/AgentActivityPanel.vue';
import ReviewPanel from './components/ReviewPanel.vue';
import ResultsPanel from './components/ResultsPanel.vue';
import VariantsPanel from './components/VariantsPanel.vue';
import ExecutionGrantDialog from './components/ExecutionGrantDialog.vue';
import SourcePanel from './components/SourcePanel.vue';
import RuntimePanel from './components/RuntimePanel.vue';
import StudioPanel from './components/StudioPanel.vue';
import { importUrlAsset } from './composables/hypit-api';
import { useVideoCloneUrlState, type VideoCloneStep } from './useVideoCloneUrlState';
import { useHypitRuntime } from './composables/useHypitRuntime';
import { useHypitProjects } from './composables/useHypitProjects';
import { useHypitSource } from './composables/useHypitSource';
import { useHypitJobs } from './composables/useHypitJobs';
import { useHypitWorkflow } from './composables/useHypitWorkflow';
import { useHypitPlan } from './composables/useHypitPlan';
import { useHypitPreview } from './composables/useHypitPreview';
import { useHypitStudio } from './composables/useHypitStudio';
import { useHypitResults } from './composables/useHypitResults';
import {

  getJob,
  getProject,
  listBuilds,
  listTemplates,
  patchProject,
} from './composables/hypit-api';
import { useHypitVariants } from './composables/useHypitVariants';
import { useHypitPackages } from './composables/useHypitPackages';
import { useHypitReviewFlow } from './composables/useHypitReviewFlow';
import { useHypitProjectScope, type RefreshGate } from './composables/useHypitProjectScope';
import type { HypitFeedbackComment, HypitJob, HypitProject, HypitTemplateSummary, HypitVariantItem } from '../../types/hypit';

const router = useRouter();
const route = useRoute();
const url = useVideoCloneUrlState();

const emit = defineEmits<{ (e: 'request-login', message?: string): void }>();

/**
 * C107F2-09：顶层运行时闸门——mount 先解析 capabilities，disabled/unavailable
 * 时不发 projects 请求（闸门在 hypit-api 层，初始自动拉取被挡下）；恢复后
 * onReady 只补拉一次；会话失效清旧账号数据并复用既有登录入口。
 */
const runtime = useHypitRuntime({
  onReady: () => { void projects.refresh(); },
  onAccountReset: () => { projects.projects.value = []; },
});
const projects = useHypitProjects();
/** C107F2-11（RULE-07）：账号+项目两级 generation 的唯一来源。 */
const scope = useHypitProjectScope();
const source = useHypitSource();
const jobs = useHypitJobs();
const plan = useHypitPlan();
// C107F2-18（W128/F06）：分析/方案/生成/授权/取消真实动作编排——视图仅组合本 composable。
const workflow = useHypitWorkflow({
  // C107F2-37（缺陷 S）：受理切页签限定意图——分析/生成受理切到生成与编辑（分析→编辑、
  // 生成→看进度的既定动线）；author（regenerate）不抢页签，方案面板留在用户眼前。
  onJobAccepted: (jobId, intent) => {
    if (intent === 'analyze' || intent === 'generate') url.update({ job: jobId, step: 'generate' });
  },
  // C107F2-37（缺陷 W）：终态回调桥接 builds 刷新——job 事件流到位而素材面板不刷，
  // build 行会永远停在「提交中」（事件有了、列表没跟上）。
  watchJob: (projectId, jobId, onTerminal) => {
    jobs.watchJob(projectId, jobId, (terminal) => {
      void refreshBuilds();
      onTerminal?.(terminal);
    });
  },
});
const preview = useHypitPreview();
// C107F2-30：工程包导出/导入（浏览器下载 + zip 上传 + 进度）。
const packages = useHypitPackages();
const packageDialogOpen = ref(false);
// C107F2-20（W151）：Studio 会话按 generation 编排——切工程旧会话关闭/回执作废。
const studio = useHypitStudio();
const results = useHypitResults();
const variants = useHypitVariants();
const review = useHypitReviewFlow({
  // C107F2-25：revise intent job 终态经统一 job 订阅（URL 恢复/SSE 同源）。
  watchJob: (projectId, jobId, onTerminal) => { jobs.watchJob(projectId, jobId, onTerminal); },
});

const activeProject = computed(() =>
  projects.projects.value.find((project: HypitProject) => project.id === url.projectId.value)
  ?? (deepProject.value !== null && deepProject.value.id === url.projectId.value ? deepProject.value : null));

/**
 * C107F2-11（F07/RULE-07）：深链工程不依赖首屏 50 条列表——列表未命中时用
 * getProject(id) 独立加载；404 显示「不存在可返回列表」，其他错误可重试。
 */
const deepProject = ref<HypitProject | null>(null);
const deepNotFound = ref(false);
const deepError = ref<string | null>(null);
const deepLoading = ref(false);

async function ensureActiveProject(projectId: string, gate: ReturnType<typeof scope.switchProject>): Promise<void> {
  if (projects.projects.value.some((project: HypitProject) => project.id === projectId)) return;
  deepLoading.value = true;
  deepError.value = null;
  deepNotFound.value = false;
  try {
    const project = await getProject(projectId, gate.signal);
    if (!gate.isCurrent()) return;
    deepProject.value = project;
  } catch (cause) {
    if (!gate.isCurrent()) return;
    const err = cause as { status?: number; message?: string };
    deepNotFound.value = err.status === 404;
    deepError.value = err.status === 404 ? null : (err.message ?? '打开工程失败');
  } finally {
    if (gate.isCurrent()) deepLoading.value = false;
  }
}

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
  // C107F2-11（F07）：创建成功即导航到新工程深链；provisioning 轮询接手。
  await router.push(`/video-clone/${created.id}`);
  url.update({ step: 'reference' });
}

async function removeProject(projectId: string): Promise<void> {
  await projects.remove(projectId);
  if (url.projectId.value === projectId) {
    url.resetForProject();
  }
}

// --- 切工程：加载工程面数据（世代切换在 composables 内以 AbortController 实现） ---
// --- provisioning 轮询（§4.4）：2 秒一次、最多 60 次；超限显示仍处理中与继续查询 ---
// 声明先于下方 immediate watch：watch 同步回调即会调用 stopProvisionPoll。
const PROVISION_POLL_MS = 2000;
const PROVISION_POLL_MAX = 60;
const provisionExhausted = ref(false);
let provisionTimer: ReturnType<typeof setTimeout> | null = null;
let provisionCount = 0;

function stopProvisionPoll(): void {
  if (provisionTimer !== null) {
    clearTimeout(provisionTimer);
    provisionTimer = null;
  }
  provisionCount = 0;
  provisionExhausted.value = false;
}

watch(url.projectId, async (projectId) => {
  jobs.stop();
  preview.close();
  stopProvisionPoll();
  // 每次切换（含切回同 id）都开新世代：旧请求 success/error/finally 全部作废。
  const gate = scope.switchProject(projectId);
  deepProject.value = null;
  deepNotFound.value = false;
  deepError.value = null;
  deepLoading.value = false;
  if (projectId === null) return;
  void ensureActiveProject(projectId, gate);
  void source.refresh(projectId, gate);
  void plan.refresh(projectId, gate);
  void results.refresh(projectId, gate);
  void variants.refresh(projectId, gate);
  void review.refresh(projectId, gate);
  // C107F2-13（F24）：URL job 恢复——先 GET 权威快照再续流。
  if (url.jobId.value !== null) void restoreJob(projectId, url.jobId.value, gate);
}, { immediate: true });

/**
 * C107F2-13（F24）：深链 job 恢复。GET /jobs/{id} 权威快照先行（URL 不是权限
 * 来源，404/无权即丢弃 query）；随后 fetch 流续订（重复 sequence 丢弃、terminal
 * 即停、stop 只断观察不冒充取消）。
 */
async function restoreJob(projectId: string, jobId: string, gate: RefreshGate): Promise<void> {
  try {
    const authoritative = await getJob(projectId, jobId);
    if (gate.isCurrent?.() === false) return;
    jobs.watchJob(projectId, jobId, () => {
      void refreshBuilds();
    }, authoritative);
  } catch {
    if (gate.isCurrent?.() !== false) void url.update({ job: null });
  }
}

function scheduleProvisionPoll(): void {
  if (provisionTimer !== null) return;
  provisionTimer = setTimeout(async () => {
    provisionTimer = null;
    const projectId = url.projectId.value;
    if (projectId === null || activeProject.value?.status !== 'provisioning') return;
    try {
      const fresh = await getProject(projectId);
      if (url.projectId.value !== projectId) return;
      projects.upsert(fresh);
      if (deepProject.value?.id === projectId) deepProject.value = fresh;
    } catch {
      // 网络抖动不中断轮询；上限内继续，由调用方在状态变化时停止。
    }
    if (activeProject.value?.status === 'provisioning') {
      provisionCount += 1;
      if (provisionCount >= PROVISION_POLL_MAX) {
        provisionExhausted.value = true;
        return;
      }
      scheduleProvisionPoll();
    } else {
      provisionCount = 0;
      provisionExhausted.value = false;
    }
  }, PROVISION_POLL_MS);
}

/** 超限后的「继续查询」：重置计数并续跑，不把轮询超时写成服务端失败。 */
function continueProvisionPoll(): void {
  provisionCount = 0;
  provisionExhausted.value = false;
  scheduleProvisionPoll();
}

watch(activeProject, (project) => {
  if (project === null) {
    stopProvisionPoll();
    return;
  }
  if (project.status === 'provisioning') {
    if (!provisionExhausted.value) scheduleProvisionPoll();
  } else {
    stopProvisionPoll();
  }
});

/** 深链打开失败后的显式重试（复用 scope 世代闸）。 */
async function retryOpenProject(): Promise<void> {
  const projectId = url.projectId.value;
  if (projectId === null) return;
  await ensureActiveProject(projectId, scope.switchProject(projectId));
}

// --- 未保存草稿的离开确认（§4.4 draft 属 account+project+path，不跨项目复用） ---
const pendingLeaveTo = ref<string | null>(null);
let leaveConfirmed = false;

/** 确认离开时清旧工程本地态；服务端 job 不受影响（jobs.stop 只退订观察）。 */
function cleanupForProjectLeave(): void {
  source.reset();
  review.reset();
  variants.reset();
  results.reset();
  jobs.stop();
  preview.close();
  studio.reset();
  stopProvisionPoll();
  if (buildPollTimer !== null) {
    clearTimeout(buildPollTimer);
    buildPollTimer = null;
  }
}

/**
 * 工程切换/离开守卫（§4.4）：有未保存草稿且目标工程不同则拦截，弹确认。
 * 同记录参数变化与跨路由离开两条路径共用同一判定。
 */
function guardProjectSwitch(to: { params: { projectId?: unknown }; fullPath: string }): boolean {
  if (leaveConfirmed) return true;
  if (!source.dirty.value) return true;
  const nextProjectId = typeof to.params.projectId === 'string' ? to.params.projectId : null;
  if (nextProjectId === url.projectId.value) return true;
  pendingLeaveTo.value = to.fullPath;
  return false;
}

onBeforeRouteLeave(guardProjectSwitch);
// /video-clone/A → /video-clone/B 是同一路由记录的参数变化，leave 守卫不触发
// （vue-router 只在离开路由记录时调用它）；工程切换必须同时挂 update 守卫。
onBeforeRouteUpdate(guardProjectSwitch);

function cancelLeave(): void {
  pendingLeaveTo.value = null;
}

async function confirmLeave(): Promise<void> {
  const target = pendingLeaveTo.value;
  pendingLeaveTo.value = null;
  if (target === null) return;
  cleanupForProjectLeave();
  leaveConfirmed = true;
  try {
    await router.push(target);
  } finally {
    leaveConfirmed = false;
  }
}

// --- 生成任务（Material/Results 共用 builds 数据；变体/审片状态在各 composable） ---
const buildLoading = ref(false);
const buildError = ref<string | null>(null);

// C107F2-37（缺陷 W 兜底）：SSE 终态到达后刷新 builds；列表含非终态行时再加有界
// 收敛轮询——事件链（route/重连/单 watch 槽）任何一环失灵都不该让 build 行永远
// 停在「提交中」。全部行到终态即停；切工程由 reset 清表后自然停止。
const BUILD_POLL_MS = 4000;
let buildPollTimer: ReturnType<typeof setTimeout> | null = null;

function hasInFlightBuild(): boolean {
  return results.builds.value.some((build) => build.lifecycle !== 'finished');
}

function scheduleBuildPoll(): void {
  if (url.projectId.value === null || !hasInFlightBuild()) return;
  if (buildPollTimer !== null) return;
  buildPollTimer = setTimeout(() => {
    buildPollTimer = null;
    void refreshBuilds().then(() => scheduleBuildPoll());
  }, BUILD_POLL_MS);
}


// ── C107F2-18：分析/方案/生成/取消真实动作（W128 workflow 编排） ─────────
const activeProjectRef = computed(() => activeProject.value);

function startAnalyze(assetId: string): void {
  const project = activeProjectRef.value;
  if (!project) return;
  void workflow.analyze(project.id, {
    brief: '全片分析参考素材',
    assetIds: [assetId],
    baseRevision: project.revision,
  });
}

/** C107F2-31：URL 导入交服务端既有 SSRF/平台防线；失败保留具体原因（不静默）。 */
const urlImportError = ref<string | null>(null);

async function startImportUrl(assetUrl: string): Promise<void> {
  const project = activeProjectRef.value;
  if (!project) return;
  urlImportError.value = null;
  try {
    await importUrlAsset(project.id, assetUrl, crypto.randomUUID());
  } catch (cause) {
    urlImportError.value = (cause as Error).message;
  }
}

function startRegenerate(): void {
  const project = activeProjectRef.value;
  if (!project) return;
  void workflow.regeneratePlan(project.id, {
    brief: '按最新分析与素材重新生成方案',
    baseRevision: project.revision,
  }).then(() => plan.refresh(project.id));
}

function startGenerate(): Promise<void> {
  const project = activeProjectRef.value;
  if (!project) return Promise.resolve();
  return workflow.generate(project.id, {
    runFile: project.selectedRun ?? 'main.svrun',
    revision: project.revision,
  }).then(() => refreshBuilds());
}

/** 构建取消（Material 列表）：先 API 取消再刷新；在途连点只发一个动作。 */
function cancelBuild(buildId: string): void {
  const project = activeProjectRef.value;
  if (!project) return;
  void workflow.cancel(project.id, buildId).then(() => refreshBuilds());
}

/** Agent 任务取消：显示「取消申请中」，继续观察服务端终态——绝不把 stop SSE 当取消。 */
function cancelActiveJob(): void {
  const project = activeProjectRef.value;
  if (!project) return;
  const activeJob = jobs.active.value?.job ?? null;
  const jobId = activeJob?.id ?? url.jobId.value;
  if (jobId) void workflow.cancel(project.id, jobId);
}
async function refreshBuilds(): Promise<void> {
  if (url.projectId.value === null) return;
  buildLoading.value = true;
  buildError.value = null;
  try {
    const page = await listBuilds(url.projectId.value);
    results.builds.value = page.items;
    // C107F2-37（缺陷 X）：builds 刷新后若还没选中结果 build，而已有 finished 的
    // build（生成完成），补选中并拉产物——否则审片页签永远「待补写」、归档/下载
    // 入口不存在。选中条件与 useHypitResults.refresh 同口径（首个 finished）。
    if (results.activeBuildId.value === null) {
      const ready = page.items.find((build) => build.lifecycle === 'finished') ?? null;
      if (ready !== null) await results.selectBuild(url.projectId.value, ready.id);
    }
  } catch (cause) {
    buildError.value = (cause as Error).message;
  } finally {
    buildLoading.value = false;
  }
  scheduleBuildPoll();
}

// --- 审片 / 预览 / Studio / 导出 ---
async function openPreview(): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return;
  await preview.open(url.projectId.value, activeProject.value.selectedRun ?? undefined, activeProject.value.revision);
}

/** C107F2-20：打开/复用 Studio 会话（W151 generation 编排，绑定当前工程）。 */
async function openStudio(): Promise<void> {
  if (url.projectId.value === null) return;
  await studio.open(url.projectId.value);
}

/** C107F2-30：导出走工程包弹窗（准备/可下载/失败如实展示，触发真实下载事件）。 */
function exportPackage(): void {
  if (url.projectId.value === null) return;
  packageDialogOpen.value = true;
  if (packages.exportPhase.value === 'idle') {
    void startPackageExport();
  }
}

function startPackageExport(): Promise<void> {
  if (url.projectId.value === null || activeProject.value === null) return Promise.resolve();
  return packages.startExport(url.projectId.value, activeProject.value.title);
}

function resetPackageDialog(): void {
  packageDialogOpen.value = false;
  packages.reset();
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
    studio.error.value = `重命名失败：${(cause as Error).message}`;
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
  return variants.act(url.projectId.value, variant, action).then(() => { /* 状态经回执/订阅落定 */ });
}

// C107F2-27：主生成流与变体构建流共用一个授权对话框（主流优先展示）。
const activeGrant = computed(() => workflow.pendingGrant.value ?? variants.pendingGrant.value);

function onGrantConfirm(): void {
  if (url.projectId.value === null) return;
  if (workflow.pendingGrant.value !== null) {
    void workflow.confirmGrant(url.projectId.value, { revision: source.revision.value ?? 1 });
    return;
  }
  void variants.confirmGrant(url.projectId.value);
}

function onGrantDismiss(): void {
  if (workflow.pendingGrant.value !== null) workflow.dismissGrant();
  else variants.dismissGrant();
}

function resolveComment(comment: HypitFeedbackComment): Promise<boolean> {
  if (url.projectId.value === null) return Promise.resolve(false);
  return review.resolve(url.projectId.value, comment);
}

const activeJob = computed<HypitJob | null>(() => unref(unref(jobs.active)?.job) ?? null);
// C107F2-13：envelope → 面板旧形状适配（AgentActivityPanel 保持 {sequence,kind,data}）。
const activeEvents = computed<{ sequence: number; kind: string; data: Record<string, unknown> }[]>(() =>
  (unref(unref(jobs.active)?.events) ?? []).map((event) => ({
    sequence: event.sequence, kind: event.type, data: (event.data ?? {}) as Record<string, unknown>,
  })));
const activeReconnectExhausted = computed<boolean>(() => unref(unref(jobs.active)?.reconnectExhausted) ?? false);
const activeAuthRequired = computed<boolean>(() => unref(unref(jobs.active)?.authRequired) ?? false);
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
    <section v-if="runtime.status.value === 'checking'" class="gl-zone" data-testid="clone-runtime-checking"
      aria-live="polite">
      正在检查视频复刻服务…
    </section>

    <section v-else-if="runtime.status.value === 'unauthenticated'" class="gl-zone" data-testid="clone-runtime-login"
      role="alert">
      <p>{{ runtime.reason.value ?? '登录已过期，请重新登录。' }}</p>
      <button type="button" class="gl-btn-primary"
        @click="emit('request-login', '登录已过期，请重新登录后继续视频复刻。')">重新登录</button>
    </section>

    <section v-else-if="runtime.status.value === 'disabled'" class="gl-zone" data-testid="clone-runtime-disabled"
      role="status">
      <p>视频复刻服务未启用{{ runtime.reason.value === null ? '' : `：${runtime.reason.value}` }}</p>
      <p class="clone-runtime-hint">如需使用，请联系部署维护者启用 Hypit（HYPIT_ENABLED 并启动 hypit-backend
        引擎）；账号与既有数据不受影响。</p>
    </section>

    <section v-else-if="runtime.status.value === 'unavailable'" class="gl-zone" data-testid="clone-runtime-unavailable"
      role="alert">
      <p>视频复刻服务暂时不可用{{ runtime.reason.value === null ? '' : `：${runtime.reason.value}` }}</p>
      <button type="button" class="gl-btn-primary" :disabled="runtime.probing.value"
        @click="runtime.retry()">{{ runtime.probing.value ? '正在重试…' : '重试' }}</button>
    </section>

    <template v-else>
    <ProjectList :items="projects.projects.value" :loading="projects.loading.value"
      :error="projects.error.value?.message ?? null" :active-project-id="url.projectId.value"
      :next-cursor="projects.nextCursor.value" :loading-more="projects.loadingMore.value"
      @open="(id: string) => router.push(`/video-clone/${id}`)"
      @create="openDialog" @remove="removeProject" @load-more="void projects.loadMore()" />

    <NewProjectDialog :open="dialogOpen" :templates="templates" :submitting="creating"
      :initial-title="handoffSource?.label ?? null"
      :error="createError" @submit="submitCreate" @cancel="dialogOpen = false" />

    <template v-if="activeProject !== null">
      <ProjectHeader :project="activeProject" :save-state="saveHeaderState"
        @rename="renameProject" @open-studio="openStudio" @export="exportPackage" />

      <section v-if="provisionExhausted" class="gl-zone" data-testid="clone-provision-exhausted" role="status">
        <p>工程仍在准备中（已连续查询 {{ PROVISION_POLL_MAX }} 次）。这不是失败——服务端仍在后台完成初始化。</p>
        <button type="button" class="gl-btn-secondary" data-testid="clone-provision-continue"
          @click="continueProvisionPoll()">继续查询</button>
      </section>

      <nav class="clone-steps" aria-label="工作阶段">
        <button v-for="step in steps" :key="step.id" type="button" class="clone-step"
          :class="{ 'clone-step--active': url.step.value === step.id }"
          @click="url.update({ step: step.id })">{{ step.label }}</button>
      </nav>

      <div v-if="url.step.value === 'reference'" class="clone-step-body">
        <ReferencePanel :project="activeProject" @import-url="(u) => void startImportUrl(u)"
          @analyze="(assetId) => startAnalyze(assetId)" />
        <p v-if="urlImportError" class="clone-url-error" data-testid="clone-url-import-error" role="alert">
          {{ urlImportError }}
        </p>
        <RuntimePanel :project-ready="activeProject.status === 'ready'" />
      </div>

      <div v-else-if="url.step.value === 'plan'" class="clone-step-body">
        <ClonePlanPanel :plan="plan.plan.value" :plan-loading="plan.loading.value"
          :plan-error="plan.error.value?.message ?? null" :generating="workflow.busy.value"
          @regenerate="startRegenerate" @generate="startGenerate" />
        <PreviewPanel :session="preview.session.value" :loading="preview.loading.value"
          :error="preview.error.value" :current-frame="preview.currentFrame.value"
          :current-time="preview.currentTime.value" :bind-frame="preview.bindFrame"
          @close="void preview.close()" @retry="openPreview" />
      </div>

      <div v-else-if="url.step.value === 'generate'" class="clone-step-body">
        <MaterialPanel :builds="results.builds.value" :build-loading="buildLoading" :build-error="buildError"
          @build="void refreshBuilds()" @retry="void refreshBuilds()"
          @cancel="(buildId) => cancelBuild(buildId)" />
        <AgentActivityPanel :project-id="activeProject.id" :job="activeJob"
          :events="activeEvents" :connected="activeConnected" @cancel="cancelActiveJob" />
        <!-- C107F2-13（F24）：断流 5 次退避用尽 → 手动重连；未登录 → 引导重新登录。 -->
        <p v-if="activeAuthRequired" class="clone-error" data-testid="clone-job-auth-required" role="alert">
          登录已失效，实时任务事件已停止。请重新登录后再试。
        </p>
        <p v-else-if="activeReconnectExhausted" class="clone-error" data-testid="clone-job-reconnect-hint" role="alert">
          实时事件连接中断，自动重连已用尽。
          <button type="button" class="gl-btn-secondary" data-testid="clone-job-reconnect"
            @click="jobs.active.value?.reconnect()">手动重连</button>
        </p>
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
          @archive="results.archive(activeProject.id, results.activeBuildId.value ?? '', $event)" />
        <VariantsPanel :items="variants.items.value" :loading="variants.loading.value" :error="variants.error.value"
          :creating="variants.loading.value" :acting-id="variants.actingId.value" @create="createBatchOnActive"
          @build="actOnVariant($event, 'build')" @retry="actOnVariant($event, 'retry')"
          @cancel="actOnVariant($event, 'cancel')" />
      </div>

      <StudioPanel v-if="studio.session.value !== null || studio.loading.value || studio.error.value !== null"
        :session="studio.session.value" :loading="studio.loading.value" :error="studio.error.value"
        @close="void studio.close()" @reopen="openStudio" />
    </template>
    <section v-else-if="url.projectId.value !== null && deepNotFound" class="gl-zone" data-testid="clone-project-notfound"
      role="alert">
      <p>工程不存在或已被删除——可能是链接过期或工程已清理。</p>
      <button type="button" class="gl-btn-secondary" data-testid="clone-notfound-back"
        @click="router.push('/video-clone')">返回工程列表</button>
    </section>

    <section v-else-if="url.projectId.value !== null && deepError !== null" class="gl-zone"
      data-testid="clone-project-open-error" role="alert">
      <p>打开工程失败：{{ deepError }}</p>
      <button type="button" class="gl-btn-secondary" data-testid="clone-open-retry"
        @click="void retryOpenProject()">重试</button>
    </section>

    <section v-else-if="url.projectId.value !== null" class="gl-zone" data-testid="clone-loading" aria-live="polite">
      正在打开工程…
    </section>
    </template>

    <!-- C107F2-27：远程执行授权确认（主生成流/变体构建流共享组件） -->
    <ExecutionGrantDialog :pending="activeGrant" :confirming="workflow.phase.value === 'submitting' || variants.granting.value"
      :error="workflow.error.value?.message ?? null" @confirm="onGrantConfirm" @dismiss="onGrantDismiss" />

    <!-- C107F2-30：工程包导出/上传导入（真实下载事件 + 上传进度 + 导入导航） -->
    <ProjectPackageDialog :open="packageDialogOpen" :project-title="activeProject?.title ?? ''"
      :export-phase="packages.exportPhase.value" :download="packages.download.value"
      :export-error="packages.exportError.value" :import-phase="packages.importPhase.value"
      :import-progress="packages.importProgress.value" :import-error="packages.importError.value"
      :imported-project-id="packages.importedProjectId.value" @close="resetPackageDialog"
      @start-export="void startPackageExport()" @trigger-download="packages.triggerBrowserDownload()"
      @file-selected="packages.selectFile" @start-import="packages.startImport((projectId) => { void router.push(`/video-clone/${projectId}`); })"
      @navigate="(projectId: string) => { resetPackageDialog(); void router.push(`/video-clone/${projectId}`); }" />

    <!-- C107F2-11（§4.4）：未保存草稿离开确认——取消不动 URL；确认才清本地态 -->
    <GlModal v-if="pendingLeaveTo !== null" title="离开当前工程？" :persistent="true"
      @close="cancelLeave">
      <p>当前工程有未保存的修改。离开将放弃这些修改（服务端数据与进行中的任务不受影响）。</p>
      <template #actions>
        <button type="button" class="gl-btn-secondary" data-testid="clone-leave-cancel" @click="cancelLeave">
          继续编辑
        </button>
        <button type="button" class="gl-btn-primary" data-testid="clone-leave-confirm" @click="void confirmLeave()">
          放弃修改并离开
        </button>
      </template>
    </GlModal>
  </div>
</template>

<style scoped>
.clone-workbench { display: grid; gap: 16px; }
.clone-steps { display: flex; gap: 4px; overflow-x: auto; }
.clone-step { background: none; border: 1px solid transparent; color: var(--color-text-secondary); padding: 8px 14px; border-radius: var(--radius-md); cursor: pointer; font-size: 14px; white-space: nowrap; }
.clone-step--active { background: var(--surface-muted); color: var(--color-text); border-color: var(--color-border); }
.clone-step-body { display: grid; gap: 16px; }
.clone-runtime-hint { color: var(--color-text-secondary); font-size: 13px; margin: 4px 0 0; }
.clone-url-error { color: var(--color-danger); margin: 0; }
</style>
