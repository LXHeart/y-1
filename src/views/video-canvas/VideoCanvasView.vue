<script setup lang="ts">
import {
  computed,
  provide,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAccountSessionStore } from "../../stores/account-session";
import { useAuth } from "../../composables/useAuth";
import { useCanvasProjectSession } from "./composables/useCanvasProjectSession";
import CanvasBoard from "./CanvasBoard.vue";
import DirectorPanel from "./DirectorPanel.vue";
import { useVideoCanvas } from "./useVideoCanvas";
import type { CanvasShot } from "./useVideoCanvas";
import type { TaskShot } from "../../types/video-production";
import { useCanvasHistory } from "./composables/useCanvasHistory";
import { useCanvasShotEditor } from "./composables/useCanvasShotEditor";
import { useCanvasShotSourceForm } from "./composables/useCanvasShotSourceForm";
import { useCanvasVariantHost } from "./composables/useCanvasVariantHost";
import { useCanvasTaskRestore } from "./composables/useCanvasTaskRestore";
import {
  useCanvasWorkspace,
  readCanvasLayout,
} from "./composables/useCanvasWorkspace";
import { useCanvasProduction } from "./composables/useCanvasProduction";
import { useCanvasDocument } from "./composables/useCanvasDocument";
import { useCanvasGraph, type GraphMediaAsset } from "./composables/useCanvasGraph";
import CanvasAssetRail from "./components/CanvasAssetRail.vue";
import CanvasAssistantPanel from "./components/CanvasAssistantPanel.vue";
import { useCanvasAssistant } from "./composables/useCanvasAssistant";
import { useCanvasSelection } from "./composables/useCanvasSelection";
import { useCanvasReferenceEditor } from "./composables/useCanvasReferenceEditor";
import CanvasReferenceInspector from "./components/CanvasReferenceInspector.vue";
import { upgradeLegacyCanvasOnBind, useCanvasDocumentLayout } from "./composables/useCanvasDocumentUpgrade";
import { queueDeliverySave } from "./composables/useCanvasDeliveryQueue";
import CanvasRunBar from "./components/CanvasRunBar.vue";
import CanvasDeliveryPanel from "./components/CanvasDeliveryPanel.vue";
import CanvasProjectHeader from "./components/CanvasProjectHeader.vue";
import CanvasResponsivePanel from "./components/CanvasResponsivePanel.vue";
import CanvasShotList from "./components/CanvasShotList.vue";
import EmptyState from "../../components/shared/EmptyState.vue";
import { useCanvasResponsive } from "./composables/useCanvasResponsive";
import { useCreationDraftSessions } from "../../lib/creation-draft-session";
import type { CreationDeliveryContract } from "../../types/creation";
import { useVideoCanvasUrlState } from "./useVideoCanvasUrlState";
import { clampPosition, clampScale } from "./useCanvasViewport";
import type { VideoCanvasLayout } from "../../types/video-canvas";

const route = useRoute();
const router = useRouter();
const account = useAccountSessionStore();
const { isAuthenticated } = useAuth();
const saveErrorElement = ref<HTMLElement | null>(null);
const emit = defineEmits<{ "open-view": [view: "ai-center"] }>();

const {
  storyboard,
  loading,
  error,
  dirty,
  branches,
  activeBranchId,
  visibleShots,
  loadStoryboard,
  saveGrouping,
  saveShotContent,
  moveShot,
  markDirty,
  reset: resetCanvas,
} = useVideoCanvas();

const history = useCanvasHistory();

/** 每镜草稿编辑会话（C100-03）：保存带 expectedEditVersion，409 冲突保留本地稿。 */
const editor = useCanvasShotEditor({
  loadFields: (shotId) => {
    const shot = storyboard.value?.shots.find((item) => item.id === shotId);
    return shot
      ? {
          visual: shot.visual,
          narration: shot.narration,
          plannedSeconds: shot.plannedSeconds,
          cameraMove: shot.cameraMove,
        }
      : null;
  },
  save: async (shotId, fields, expectedEditVersion) => {
    const result = await saveShotContent(shotId, fields, expectedEditVersion);
    return {
      ok: result.ok,
      conflict: result.conflict,
      message: result.message,
    };
  },
  currentVersion: () => storyboard.value?.editVersion ?? null,
});

const selectedShotId = computed({
  get: () => selection.focusedNodeId.value?.startsWith('shot:') ? selection.focusedNodeId.value.slice(5) : null,
  set: (id: string | null) => { selection.focusedNodeId.value = id ? `shot:${id}` : null; },
});

/** committed 分镜只读（§8.2：内容字段只读，旁边给「创建独立方案」提示）。 */
const storyboardReadonly = computed(
  () => storyboard.value?.status === "committed" || workspace.readonly.value || canvasDocument.readOnly.value,
);

// ---- C100-04：URL 状态 + 工作区绑定 + 轻量布局 ----

const urlState = useVideoCanvasUrlState(route, router);
/** 当前视口镜像（CanvasBoard 上抛；收集时钳制，避免瞬时越界值入库）。 */
const currentViewport = ref({ panX: 0, panY: 0, scale: 1 });
/** 恢复的视口（绑定时从 inputs.videoCanvas 读出；引用变化触发 CanvasBoard 重放）。 */
const restoredViewport = ref<{
  panX: number;
  panY: number;
  scale: number;
} | null>(null);
/** 绑定带回的布局：分镜载入完成后再应用（positions 依赖 shots 就位）。 */
let pendingLayout: VideoCanvasLayout | null = null;

const workspace = useCanvasWorkspace({
  collectLayout: () => ({
    schemaVersion: 1,
    storyboardId: urlState.key.value?.storyboard ?? storyboard.value?.id ?? "",
    viewport: {
      panX: clampPosition(currentViewport.value.panX),
      panY: clampPosition(currentViewport.value.panY),
      scale: clampScale(currentViewport.value.scale),
    },
    positions: Object.fromEntries(
      (storyboard.value?.shots ?? []).map((shot) => [
        shot.id,
        {
          x: clampPosition(shot.x),
          y: clampPosition(shot.y),
        },
      ]),
    ),
    activeBranchId: activeBranchId.value,
  }),
  applyLayout: (raw) => {
    pendingLayout = readCanvasLayout(raw);
    tryApplyPendingLayout();
  },
});

// ---- C100-06/C100-07：任务会话 + 制作主行动（绑定带回 productionTaskId；发起后由会话持有） ----
const productionTaskId = ref("");
watch(
  () => workspace.binding.value?.productionTaskId ?? "",
  (bound) => {
    // 服务端绑定是权威：绑定带回的任务 id 覆盖本地（画布发起制作后二者同值）
    productionTaskId.value = bound;
  },
  { immediate: true },
);
const productionTask = useCanvasProduction(productionTaskId, {
  // 生成前清空未保存输入（§4.2：切流程先 flush，失败停留当前页）
  flushBeforeCreate: () => projectSession.flushBeforeLeave(),
});
provide(
  "canvasTaskSelection",
  computed(() => productionTask.session.task.value?.selection ?? {}),
);
/** 恢复只读取现有任务（§6.8）：绑定带回任务 id 后首读一次；池中已有任务则跳过。 */
watch(
  () => workspace.binding.value?.productionTaskId ?? "",
  (id) => {
    if (id && !productionTask.session.task.value)
      void productionTask.session.refreshTask();
  },
  { immediate: true },
);

const taskShotsById = computed(() => {
  const map = new Map<string, TaskShot>();
  for (const shot of productionTask.session.task.value?.shots ?? []) {
    map.set(shot.id, shot);
  }
  return map;
});
const mergeTaskTakes = (shot: CanvasShot): CanvasShot => {
  const taskShot = taskShotsById.value.get(shot.id);
  return taskShot ? { ...shot, takes: taskShot.takes } : shot;
};
const liveShots = computed(() => visibleShots.value.map(mergeTaskTakes));
const selectedShot = computed(() => {
  const base =
    storyboard.value?.shots.find((shot) => shot.id === selectedShotId.value) ??
    null;
  return base ? mergeTaskTakes(base) : null;
});

// ---- C100-10：独立画布文档 + 权威节点/边投影 + 素材轨 ----
const canvasDocument = useCanvasDocument(workspace.draftId, {
  epoch: () => account.epoch,
  fallbackShots: () => (storyboard.value?.shots ?? []).map(shot => ({ id: shot.id })),
});

upgradeLegacyCanvasOnBind(canvasDocument, workspace.draftId, () => collectLegacyLayout(),
  computed(() => !!storyboard.value && storyboard.value.shots.length > 0));
function collectLegacyLayout(): VideoCanvasLayout {
  const shotsNow = storyboard.value?.shots ?? [];
  return {
    schemaVersion: 1,
    storyboardId: urlState.key.value?.storyboard ?? storyboard.value?.id ?? "",
    viewport: { ...currentViewport.value },
    positions: Object.fromEntries(shotsNow.map(shot => [shot.id, { x: shot.x, y: shot.y }])),
    activeBranchId: activeBranchId.value,
  };
}
const documentLayout = useCanvasDocumentLayout({ session: canvasDocument, storyboard,
  activeBranchId, viewport: currentViewport, restoredViewport, history, moveShot });
workspace.setLayoutWriter(documentLayout);
const applyHistory = documentLayout.applyHistory;
const onViewportChange = documentLayout.viewportChanged;
const mediaAssets = ref<GraphMediaAsset[]>([]);
const expandedShotId = ref<string | null>(null);
const allLiveShots = computed(() => (storyboard.value?.shots ?? []).map(mergeTaskTakes));
const graph = useCanvasGraph({ draftId: workspace.draftId, document: canvasDocument.document,
  shots: allLiveShots, task: productionTask.session.task, mediaAssets, expandedShotId });
const lastProjectKey = ref(urlState.key.value);
watch(urlState.key, key => { if (key) lastProjectKey.value = key; }, { flush: 'sync' });
const projectEpoch = computed(() => `${account.epoch}:${lastProjectKey.value?.storyboard ?? ''}:${lastProjectKey.value?.draft ?? ''}`);
const selection = useCanvasSelection(graph.nodes, projectEpoch);
const selectedNodeId = selection.focusedNodeId;
watch(selectedShotId, id => { if (id) expandedShotId.value = id; });
watch(projectEpoch, () => { expandedShotId.value = null; });

// ---- C100-18：画布 AI 助手（装配在 composables/useCanvasAssistant；视图只持开关/输入） ----
const responsive = useCanvasResponsive({ beforeChange: () => projectSession.flushBeforeLeave(), identity: () => projectEpoch.value });
const assistantActive = computed({ get: () => responsive.activeDetail.value === 'assistant',
  set: value => { responsive.activeDetail.value = value ? 'assistant' : 'shot'; } });
const assistantInstruction = ref("");
const assistant = useCanvasAssistant({
  graph,
  storyboard,
  draftId: workspace.draftId,
  storyboardIdRef: computed(() => urlState.key.value?.storyboard ?? storyboard.value?.id ?? ""),
  canvasRevision: canvasDocument.revision,
  instruction: assistantInstruction, selectedNodeIds: selection.selectedNodeIds, epoch: projectEpoch,
  draftVersion: () => draftVersion.value,
  flushBeforeSubmit: () => projectSession.flushBeforeLeave(),
  hasPending: () => editor.state.dirty || editor.state.saving || sourceForm.dirty.value || canvasDocument.dirty.value
    || ['pending', 'saving', 'error', 'conflict'].includes(workspace.saveState.value),
  readonly: () => workspace.readonly.value || canvasDocument.readOnly.value,
  canvas: canvasDocument,
  productionTask: () => productionTask.task.value,
  navigateVariant: async (variant) => { await router.push({ name: 'video-canvas', query: { storyboard: variant.storyboardId, draft: variant.draftId } }); },
  focusShot: (id, preparation) => { if (preparation) assistantActive.value = false; return onSelect(id, false, !!preparation); },
  reloadStoryboard: async (id) => {
    await loadStoryboard(id);
    await canvasDocument.load();
  },
});
const { agent, selectedNodeLabels, submitAgent, applyAgent } = assistant;
const references = useCanvasReferenceEditor({ graph, session: canvasDocument, history, selectedNodeId, shots: allLiveShots,
  readonly: () => workspace.readonly.value || canvasDocument.readOnly.value,
  beforeSelect: () => projectSession.flushBeforeLeave(),
  select: id => { selection.selectExclusive(id); responsive.revealDetail('reference'); },
});
async function onSelectNode(nodeId: string, additive = false): Promise<void> {
  if (!(await projectSession.flushBeforeLeave())) return;
  if (additive) selection.toggle(nodeId); else selection.selectExclusive(nodeId);
  if (!additive) await responsive.openDetail('reference');
}

/** 交付字段（C100-07）：只写草稿 delivery，不触发媒体重生成；与布局共用同一草稿会话。 */
const getDraftSession = useCreationDraftSessions();
const deliverySession = computed(() =>
  workspace.draftId.value ? getDraftSession(workspace.draftId.value) : null,
);
const deliveryWorkspace = computed(
  () =>
    (deliverySession.value?.draft.value?.workspace ?? {}) as {
      delivery?: Partial<CreationDeliveryContract>;
    },
);
const delivery = computed<Partial<CreationDeliveryContract>>(
  () => deliveryWorkspace.value.delivery ?? {},
);
/** 交付面板的平台与版本（草稿会话权威；导出绑定草稿版本）。 */
const storyboardPlatform = computed(
  () => deliverySession.value?.draft.value?.platform ?? "",
);
const draftVersion = computed(
  () => deliverySession.value?.draft.value?.version ?? null,
);

/** 任务 id 回写草稿 + SRT 下载（C100-07 恢复链；装配下沉 composables/useCanvasTaskRestore）。 */
const { downloadSubtitle, syncResultReferences } = useCanvasTaskRestore({
  sessions: getDraftSession,
  draftId: workspace.draftId,
  taskId: productionTaskId,
  currentTaskId: () => productionTask.task.value?.id,
  task: () => productionTask.task.value,
  onError: (message) => {
    productionTask.session.taskError.value = message;
  },
});

/** 交付字段写入下沉 composables/useCanvasDeliveryQueue（视图体积门禁）。 */
const onUpdateDelivery = queueDeliverySave(getDraftSession, () => workspace.draftId.value,
  () => deliveryWorkspace.value.delivery ?? {}, () => storyboardPlatform.value,
  { prepareReferences: syncResultReferences, flushProject: () => projectSession.flushBeforeLeave() });
const contentSaveState = computed(() => editor.state.saving || sourceForm.saving.value ? 'saving'
  : editor.state.conflict || sourceForm.conflict.value ? 'conflict'
  : editor.state.errorMessage || sourceForm.error.value ? 'error'
  : editor.state.dirty || sourceForm.dirty.value || dirty.value ? 'pending' : 'saved');
const hasDelivery = computed(() => productionTask.task.value?.phase === 'succeeded');

// ---- C100-19：独立方案装配（C100-15 面板；切换先 flush，失败停留当前方案） ----
const variants = useCanvasVariantHost({
  storyboard,
  storyboardKey: computed(
    () => urlState.key.value?.storyboard ?? storyboard.value?.id ?? "",
  ),
  draftVersion: () => deliverySession.value?.draft.value?.version ?? null,
  epoch: () => account.epoch, authenticated: () => isAuthenticated.value,
  flushBeforeSwitch: () => projectSession.flushBeforeLeave(),
  router,
});

/** 重取分镜详情（保位重载）：候选签名续取（onRefreshMedia）与来源保存后的权威刷新共用。 */
async function reloadStoryboard(): Promise<void> {
  const storyboardId = urlState.key.value?.storyboard;
  if (storyboardId) await loadStoryboard(storyboardId);
}
function onRefreshMedia(): void { void reloadStoryboard(); }

// C100-13 来源编辑装配（C100-20 接线）：个人库选项 + API-10 保存回路
const sourceForm = useCanvasShotSourceForm({
  currentShot: () => selectedShot.value,
  authenticated: () => isAuthenticated.value,
  epoch: () => account.epoch,
  storyboardId: () => urlState.key.value?.storyboard ?? storyboard.value?.id ?? null,
  editVersion: () => storyboard.value?.editVersion ?? null,
  reload: reloadStoryboard,
});

/** positions 依赖已载入的镜头；分镜未就位时挂起，载入完成补放。 */
function tryApplyPendingLayout(): void {
  const layout = pendingLayout;
  if (
    !layout ||
    !storyboard.value ||
    storyboard.value.id !== (urlState.key.value?.storyboard ?? "")
  )
    return;
  pendingLayout = null;
  if (layout.viewport) {
    restoredViewport.value = {
      panX: clampPosition(layout.viewport.panX),
      panY: clampPosition(layout.viewport.panY),
      scale: clampScale(layout.viewport.scale),
    };
    currentViewport.value = { ...restoredViewport.value };
  }
  for (const [shotId, point] of Object.entries(layout.positions ?? {})) {
    if (
      point &&
      typeof point.x === "number" &&
      typeof point.y === "number" &&
      storyboard.value.shots.some((shot) => shot.id === shotId)
    ) {
      moveShot(shotId, clampPosition(point.x), clampPosition(point.y));
    }
  }
  if (
    typeof layout.activeBranchId === "string" ||
    layout.activeBranchId === null
  ) {
    activeBranchId.value = layout.activeBranchId;
  }
}

const projectSession = useCanvasProjectSession({
  key: urlState.key, accountEpoch: () => account.epoch,
  authenticated: () => isAuthenticated.value, boundDraftId: () => workspace.draftId.value,
  bind: workspace.bind, load: loadStoryboard, syncDraft: urlState.syncDraft,
  queues: [() => editor.flush(), () => sourceForm.flush(), () => references.flush(), () => onUpdateDelivery.flush()],
  hasPending: () => editor.state.dirty || sourceForm.dirty.value || dirty.value
    || canvasDocument.dirty.value || references.invalidNote.value || ['pending', 'saving', 'error', 'conflict'].includes(workspace.saveState.value),
  focusError: () => saveErrorElement.value?.focus(), onKeydown: onHistoryKeydown,
  reset: () => {
    resetCanvas(); workspace.reset(); history.clear(); editor.beginEdit(null);
    sourceForm.reset(); void sourceForm.loadOptions();
    selection.clear(); agent.reset(); references.reset();
    assistantInstruction.value = ''; pendingLayout = null;
    currentViewport.value = { panX: 0, panY: 0, scale: 1 }; restoredViewport.value = null;
  },
  suspend: () => { resetCanvas(); workspace.reset(); },
  afterLoad: tryApplyPendingLayout,
});
projectSession.registerConsumer({ activate: assistant.activate, deactivate: assistant.deactivate, reset: agent.reset });

/** 布局撤销/重做（§8.2）：只处理当前布局栈；输入框内的 Ctrl+Z 保持文本编辑语义。 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!target || typeof (target as HTMLElement).closest !== "function")
    return false;
  return !!(target as HTMLElement).closest(
    'input,textarea,select,[contenteditable="true"]',
  );
}

function onHistoryKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
  if (isEditableTarget(event.target)) return;
  const key = event.key.toLowerCase();
  if (key === "z") {
    event.preventDefault();
    if (event.shiftKey) applyHistory(history.redo());
    else applyHistory(history.undo());
  } else if (key === "y") {
    event.preventDefault();
    applyHistory(history.redo());
  }
}

async function switchToQuickMode(): Promise<void> {
  if (!(await projectSession.flushBeforeLeave())) return;
  await router.push({ name: 'video-production', query: {
    storyboard: urlState.key.value?.storyboard ?? '', draft: workspace.draftId.value,
  } });
}
async function goToCreationCenter(): Promise<void> {
  if (await projectSession.flushBeforeLeave()) emit('open-view', 'ai-center');
}

/** 选中镜头：切镜先 flush 当前草稿（失败停留原镜头、内容保留），再载入新镜头（hydration 抑制）。 */
async function onSelect(shotId: string, additive = false, reveal = true): Promise<void> {
  if (editor.state.editingShotId === shotId) {
    if (additive) selection.toggle(`shot:${shotId}`); else selection.selectExclusive(`shot:${shotId}`);
    if (!additive && reveal) await responsive.openDetail('shot');
    return;
  }
  if (!(await projectSession.flushBeforeLeave())) return;
  editor.beginEdit(shotId);
  if (additive) selection.toggle(`shot:${shotId}`); else selection.selectExclusive(`shot:${shotId}`);
  if (!additive && reveal) await responsive.openDetail('shot');
}

/** 拖拽中的瞬时位置（不进历史、不触发保存）。 */
function onDragMove(shotId: string, x: number, y: number): void {
  if (shotId.includes(':')) { references.moveTransient(shotId, x, y); return; }
  if (!canvasDocument.readOnly.value && canvasDocument.document.value) moveShot(shotId, x, y);
}

/** 拖拽/键盘落位（一次一条历史；零位移不记录；布局排队保存）。 */
function onMove(
  shotId: string,
  x: number,
  y: number,
  fromX: number,
  fromY: number,
): void {
  if (canvasDocument.readOnly.value || !canvasDocument.document.value) return;
  if (shotId.includes(':')) { references.move(shotId, x, y); return; }
  history.record([{ shotId, from: { x: fromX, y: fromY }, to: { x, y } }]);
  moveShot(shotId, x, y);
  workspace.queueLayoutSave();
}

function onSaveGrouping(grouping: Parameters<typeof saveGrouping>[0]): void {
  void saveGrouping(grouping, storyboard.value?.editVersion ?? null);
}

async function onSwitchBranch(branchId: string | null): Promise<void> {
  if (!(await projectSession.flushBeforeLeave())) return;
  activeBranchId.value = branchId;
  selectedShotId.value = null;
  editor.beginEdit(null);
  workspace.queueLayoutSave();
}
</script>

<template>
  <div class="video-canvas gl-field" :data-detail="responsive.activeDetail.value">
    <CanvasProjectHeader :project="workspace.binding.value?.project ?? null" :shot-count="storyboard?.shots.length ?? 0"
      :duration="storyboard?.targetDurationSeconds" :content-state="contentSaveState"
      :canvas-state="canvasDocument.document.value && !canvasDocument.dirty.value && !canvasDocument.error.value ? 'saved' : canvasDocument.saveState.value"
      :delivery-state="onUpdateDelivery.state.value === 'idle' && draftVersion ? 'saved' : onUpdateDelivery.state.value"
      :readonly="workspace.readonly.value || canvasDocument.readOnly.value" :binding="workspace.bindingPending.value"
      @back="goToCreationCenter" @quick="switchToQuickMode" @retry="projectSession.flushBeforeLeave" @reload-canvas="canvasDocument.adoptLatest">
      <div v-if="storyboard" class="canvas-workspace-tools" role="group" aria-label="画布工具">
        <button type="button" class="gl-btn-ghost" data-test="canvas-toggle-assets" :aria-expanded="responsive.assetsOpen.value" @click="responsive.toggleAssets">素材</button>
        <button type="button" class="gl-btn-ghost" data-test="canvas-toggle-detail" :aria-pressed="responsive.activeDetail.value === 'shot' && responsive.detailOpen.value" @click="responsive.openDetail('shot')">镜头详情</button>
        <button type="button" class="gl-btn-ghost" data-test="canvas-toggle-assistant" :aria-pressed="assistantActive" @click="responsive.openDetail(assistantActive ? 'shot' : 'assistant')">{{ assistantActive ? '返回属性' : 'AI 助手' }}</button>
        <button v-if="!responsive.desktop.value" type="button" class="gl-btn-ghost" data-test="canvas-toggle-variants" @click="responsive.openDetail('variants')">方案</button>
        <button v-if="hasDelivery" type="button" class="gl-btn-ghost" data-test="canvas-toggle-delivery" @click="responsive.openDetail('delivery')">交付与导出</button>
        <button type="button" class="gl-btn-ghost" data-test="canvas-add-note" :disabled="!canvasDocument.document.value || workspace.readonly.value || canvasDocument.readOnly.value" @click="references.addNote">添加备注</button>
        <div class="canvas-view-switch" role="group" aria-label="主区显示方式">
          <button type="button" :aria-pressed="responsive.viewMode.value === 'list'" data-test="canvas-show-list" @click="responsive.setView('list')">镜头列表</button>
          <button type="button" :aria-pressed="responsive.viewMode.value === 'canvas'" data-test="canvas-show-board" @click="responsive.setView('canvas')">画布</button>
        </div>
      </div>
    </CanvasProjectHeader>
    <p v-if="projectSession.saveError.value" ref="saveErrorElement" tabindex="-1" role="alert" class="canvas-panel-error" data-test="canvas-save-error">{{ projectSession.saveError.value }}</p>
    <p v-if="canvasDocument.error.value" class="canvas-panel-error" role="alert" data-test="canvas-document-error">
      {{ canvasDocument.error.value }}
      <button v-if="canvasDocument.conflict.value" type="button" class="gl-btn-ghost" @click="canvasDocument.adoptLatest()">放弃本地布局并载入最新</button>
      <button v-else type="button" class="gl-btn-ghost" @click="canvasDocument.dirty.value ? canvasDocument.flush() : canvasDocument.load()">重试</button>
    </p>
    <p v-if="canvasDocument.readOnly.value" class="field-note" data-test="canvas-readonly">此画布版本暂不支持编辑，可继续查看项目。</p>
    <EmptyState v-if="!isAuthenticated" title="请先登录" description="登录后继续创作项目。" data-test="canvas-login-required" />
    <EmptyState v-else-if="!urlState.key.value" title="先选择创作项目" description="请从快速模式的分镜步骤进入专业模式。" data-test="canvas-missing-id" />
    <p v-else-if="loading || workspace.bindingPending.value" class="field-note" role="status" data-test="canvas-loading">分镜加载中…</p>
    <EmptyState v-else-if="workspace.bindingError.value" title="项目连接失败" :description="workspace.bindingError.value" data-test="canvas-binding-failed">
      <template #actions><button type="button" class="gl-btn-ghost" @click="projectSession.ensure">重试连接</button></template>
    </EmptyState>
    <EmptyState v-else-if="error" title="无法读取分镜" :description="error" data-test="canvas-error">
      <template #actions><button type="button" class="gl-btn-ghost" @click="projectSession.ensure">重试读取</button></template>
    </EmptyState>
    <template v-else-if="storyboard">
      <CanvasRunBar :production="productionTask" :storyboard-id="storyboard.id" :shot-count="allLiveShots.length" :readonly="storyboardReadonly" />
      <p v-if="selection.error.value" class="canvas-panel-error" role="status">{{ selection.error.value }}</p>
      <p v-if="assistant.preparedGeneration.value" class="field-note" role="status" data-test="canvas-prepared-action">
        已准备{{ assistant.preparedGeneration.value.mode === 'initial' ? '首次制作' : '镜头重抽' }}，请确认费用后点击运行栏或候选区的对应按钮。
      </p>
      <div class="canvas-main" :class="{ 'canvas-main-list': responsive.viewMode.value === 'list' }">
        <CanvasResponsivePanel :open="responsive.assetsOpen.value" :docked="responsive.desktop.value" title="素材与来源" kind="assets" keep-mounted
          :before-close="projectSession.flushBeforeLeave" return-focus-selector="[data-test='canvas-toggle-assets']" @close="responsive.assetsOpen.value = false">
          <CanvasAssetRail :authenticated="isAuthenticated" :epoch="account.epoch" @loaded="mediaAssets = $event" @add-media="references.addMedia" @collapse="responsive.toggleAssets" />
        </CanvasResponsivePanel>
        <main class="canvas-primary" aria-label="创作内容">
          <EmptyState v-if="!allLiveShots.length" title="还没有镜头" description="从快速模式创建分镜后，再来编排和编辑。" data-test="canvas-no-shots" />
          <CanvasShotList v-else-if="responsive.viewMode.value === 'list'" :shots="allLiveShots" :focused-shot-id="selectedShotId"
            :selected-node-ids="selection.selectedNodeIds.value" :references="references.nodes.value.filter(node => ['note','media','brief'].includes(node.kind))"
            @select="onSelect" @select-node="onSelectNode" />
          <CanvasBoard v-else :shots="liveShots" :readonly="workspace.readonly.value || canvasDocument.readOnly.value || !canvasDocument.document.value"
            :reference-nodes="references.nodes.value.filter(node => node.kind !== 'shot')" :graph-edges="graph.edges.value"
            :selected-node-id="selectedNodeId" :selected-node-ids="selection.selectedNodeIds.value" :selected-shot-id="selectedShotId"
            :branches="branches" :active-branch-id="activeBranchId" :can-undo="history.canUndo.value" :can-redo="history.canRedo.value" :initial-viewport="restoredViewport"
            @select="onSelect" @select-node="onSelectNode" @reselect-media="onSelectNode" @drag-move="onDragMove" @move="onMove"
            @undo="applyHistory(history.undo())" @redo="applyHistory(history.redo())" @viewport-change="onViewportChange" />
        </main>
        <CanvasResponsivePanel :open="responsive.detailOpen.value" :docked="responsive.desktop.value" :title="responsive.detailTitle.value"
          :before-close="projectSession.flushBeforeLeave" return-focus-selector="[data-test='canvas-toggle-detail']" @close="responsive.detailOpen.value = false">
          <nav v-if="!responsive.desktop.value" class="canvas-detail-switch" aria-label="详情类型">
            <button type="button" :aria-pressed="responsive.activeDetail.value === 'shot'" @click="responsive.openDetail('shot')">镜头</button>
            <button type="button" :aria-pressed="assistantActive" @click="responsive.openDetail('assistant')">AI 助手</button>
            <button type="button" :aria-pressed="responsive.activeDetail.value === 'variants'" @click="responsive.openDetail('variants')">方案</button>
            <button v-if="hasDelivery" type="button" :aria-pressed="responsive.activeDetail.value === 'delivery'" @click="responsive.openDetail('delivery')">交付</button>
          </nav>
          <CanvasAssistantPanel v-if="assistantActive" :selected-node-labels="selectedNodeLabels" :instruction="assistantInstruction" :plan="agent.plan.value"
            :submitting="agent.submitting.value || assistant.submitting.value" :applying="agent.applying.value" :error="agent.error.value"
            :can-retry-pending="agent.canRetryPending.value" :querying="agent.querying.value" :error-code="agent.errorCode.value" :apply-unknown="agent.applyUnknown.value"
            :baseline-shots="assistant.baseline.value?.shots" :plan-node-labels="assistant.planNodeLabels.value" :blocked-reason="assistant.blockedReason.value"
            :readonly="workspace.readonly.value || canvasDocument.readOnly.value" @submit="submitAgent" @apply="() => void applyAgent()"
            @recover-apply="() => void agent.recoverApply()" @retry-pending="() => void agent.retryPending()" @refresh="() => void agent.refreshPlan()"
            @update:instruction="(value: string) => (assistantInstruction = value)" />
          <DirectorPanel v-else-if="responsive.activeDetail.value === 'shot' || responsive.activeDetail.value === 'variants'"
            :shot="selectedShot" :shot-source="selectedShot?.source ?? taskShotsById.get(selectedShotId ?? '')?.source ?? null"
            :grouping="storyboard.grouping" :active-branch-id="activeBranchId" :dirty="dirty" :editor="editor" :readonly="storyboardReadonly"
            :session="productionTask.session" :storyboard-id="storyboard.id" :variants-host="variants.host" :all-shots="allLiveShots"
            :variant-readonly="workspace.readonly.value || canvasDocument.readOnly.value" :source-form="sourceForm" :detail-mode="responsive.activeDetail.value"
            @detail-mode="responsive.activeDetail.value = $event" @edit="markDirty" @save-grouping="onSaveGrouping" @switch-branch="onSwitchBranch"
            @refresh-media="onRefreshMedia" @create-variant="variants.createVariant" @switch-variant="variants.switchVariant" @retry-variant="variants.retryPending()"
            @save-source="(shotId, source) => void sourceForm.save(shotId, source)" />
          <CanvasReferenceInspector v-else-if="responsive.activeDetail.value === 'reference' && references.selectedNode.value" :node="references.selectedNode.value"
            :note-text="references.noteText.value" :targets="references.targets.value" :edges="references.referenceEdges.value" :media-options="mediaAssets"
            :readonly="workspace.readonly.value || canvasDocument.readOnly.value" :saving="canvasDocument.saveState.value === 'saving'" :error="references.error.value"
            :preview-url="references.previewUrl.value" :preview-mime="references.previewMime.value" :preview-loading="references.previewLoading.value" :parent-shot-id="references.parentShotId.value"
            @update-note="references.editNote" @add-reference="references.addReference" @remove-reference="references.removeReference"
            @replace-media="references.replaceMedia" @remove-node="references.removeNode" @preview="references.preview" @retry="references.flush" @focus-shot="onSelect" />
          <CanvasDeliveryPanel v-else-if="responsive.activeDetail.value === 'delivery' && hasDelivery && productionTask.task.value"
            :task="productionTask.task.value" :delivery="delivery" :platform="storyboardPlatform" :draft-id="workspace.draftId.value" :draft-version="draftVersion"
            :disabled="workspace.readonly.value || deliverySession?.draft.value?.status === 'archived' || !!workspace.bindingError.value"
            :saving="onUpdateDelivery.state.value === 'saving'" :save-state="onUpdateDelivery.state.value" :save-error="onUpdateDelivery.error.value"
            :before-export="onUpdateDelivery.beforeExport" :cover-options="sourceForm.options.value" :download-subtitle="downloadSubtitle"
            :report-error="message => { productionTask.session.taskError.value = message; }" @update-delivery="onUpdateDelivery" />
          <EmptyState v-else title="选择一个节点" description="选择镜头、素材或备注，继续编辑。" />
        </CanvasResponsivePanel>
      </div>
    </template>
  </div>
</template>
