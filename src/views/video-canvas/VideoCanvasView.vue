<script setup lang="ts">
import {
  computed,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  provide,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import CanvasBoard from "./CanvasBoard.vue";
import DirectorPanel from "./DirectorPanel.vue";
import { useVideoCanvas } from "./useVideoCanvas";
import type { CanvasShot } from "./useVideoCanvas";
import type { TaskShot } from "../../types/video-production";
import { useCanvasHistory } from "./composables/useCanvasHistory";
import { useCanvasShotEditor } from "./composables/useCanvasShotEditor";
import {
  useCanvasWorkspace,
  readCanvasLayout,
} from "./composables/useCanvasWorkspace";
import { useCanvasProduction } from "./composables/useCanvasProduction";
import { useCanvasDocument } from "./composables/useCanvasDocument";
import { useCanvasGraph, type GraphMediaAsset } from "./composables/useCanvasGraph";
import CanvasAssetRail from "./components/CanvasAssetRail.vue";
import CanvasRunBar from "./components/CanvasRunBar.vue";
import CanvasDeliveryPanel from "./components/CanvasDeliveryPanel.vue";
import { useCreationDraftSessions } from "../../lib/creation-draft-session";
import type { CreationDeliveryContract } from "../../types/creation";
import { useVideoCanvasUrlState } from "./useVideoCanvasUrlState";
import { clampPosition, clampScale } from "./useCanvasViewport";
import { request } from "../../composables/grassland-http";
import type { VideoCanvasLayout } from "../../types/video-canvas";

/**
 * 画布式分镜导演台·专业模式（任务书 #66 C2/C3 + #100 C100-02~04）：/video-canvas?storyboard={id}&draft={id}。
 * 与快速模式（四步向导）同数据互切——仅前端路由，后端零感知；未保存态先提示。
 * 布局撤销/重做只覆盖节点移动（R07）；镜头编辑走每镜草稿会话（载入抑制/切镜 flush/冲突保留）；
 * 轻量布局（视口/坐标/分支）经共享草稿会话存 inputs.videoCanvas（C100-04）。
 */
const route = useRoute();
const router = useRouter();
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

const selectedShotId = ref<string | null>(null);

/** committed 分镜只读（§8.2：内容字段只读，旁边给「创建独立方案」提示）。 */
const storyboardReadonly = computed(
  () => storyboard.value?.status === "committed",
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
  flushBeforeCreate: async () => {
    const editorOk = await editor.flush();
    if (!editorOk) return false;
    await workspace.flushLayout();
    return true;
  },
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

/** 候选/评分/播放 URL 的活动真相是共享任务会话（C100-05 §6.8）：分镜详情只在进页拉一次，
 * 发起制作后生成的 takes/预签名 URL 只进任务态。节点与候选面板统一吃「分镜骨架 +
 * 任务会话 takes」的合并镜头；无任务（编辑期/历史只读）回退分镜详情自带候选。 */
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
  fallbackShots: () => (storyboard.value?.shots ?? []).map(shot => ({ id: shot.id })),
});
/** 一次性升级：GET null 才用旧轻量布局构建初始文档（§7.3；失败保留轻量布局）。 */
watch(
  () => workspace.draftId.value,
  (draftId) => {
    if (!draftId) return;
    void canvasDocument.load().then((exists) => {
      if (exists || canvasDocument.revision.value > 0) return;
      void canvasDocument.upgradeFromLegacy(collectLegacyLayout());
    });
  },
  { immediate: true },
);
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
const mediaAssets = ref<GraphMediaAsset[]>([]);
const graph = useCanvasGraph({
  draftId: workspace.draftId,
  document: canvasDocument.document,
  shots: liveShots,
  task: productionTask.session.task,
  mediaAssets,
});
const selectedNodeId = ref<string | null>(null);
/** 图编辑统一经文档 CAS 保存（参考连线不触发制作/扣费，§6.3）。 */
function applyGraphEdit(edit: { result: { ok: boolean; error?: string }; document: object | null }): void {
  if (!edit.result.ok || !edit.document) return;
  void canvasDocument.save(edit.document as never);
}
function onAddMediaAsset(asset: GraphMediaAsset): void {
  applyGraphEdit(graph.addUserNode("media", asset.id, 40, 40 + (graph.nodes.value.length * 130)));
}
function onSelectNode(nodeId: string): void {
  selectedNodeId.value = selectedNodeId.value === nodeId ? null : nodeId;
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

/** 任务 id 回写草稿（C100-07 恢复链）：服务端绑定响应从 inputs.video.productionTaskId
 * 派生 productionTaskId，刷新/AI 入口恢复全靠它——发起制作后必须落草稿。幂等：草稿已
 * 带同值（恢复场景）跳过，不空转版本。 */
watch(productionTaskId, (taskId) => {
  const draftId = workspace.draftId.value;
  if (!taskId || !draftId) return;
  const session = getDraftSession(draftId);
  const current = session.draft.value;
  if (!current) return;
  const workspaceNow = (current.workspace ?? {}) as {
    inputs?: { video?: Record<string, unknown> };
  } & Record<string, unknown>;
  if (workspaceNow.inputs?.video?.productionTaskId === taskId) return;
  session.queueSave({
    workspace: {
      ...workspaceNow,
      inputs: {
        ...(workspaceNow.inputs ?? {}),
        video: { ...(workspaceNow.inputs?.video ?? {}), productionTaskId: taskId },
      },
    },
  });
  void session.flush();
});

/** SRT 下载（presign 短链新窗）；失败落会话 taskError。 */
async function downloadSubtitle(): Promise<void> {
  const id = productionTask.task.value?.id;
  if (!id) return;
  try {
    const body = await request<{ downloadUrl: string }>(
      `/api/video-production/tasks/${id}/subtitle`,
      {},
      { fallbackError: "字幕下载失败" },
    );
    if (body?.downloadUrl) {
      window.open(body.downloadUrl, "_blank", "noopener");
    }
  } catch (err: unknown) {
    productionTask.session.taskError.value =
      err instanceof Error ? err.message : "字幕下载失败";
  }
}

function onUpdateDelivery(next: Partial<CreationDeliveryContract>): void {
  const draftId = workspace.draftId.value;
  if (!draftId) return;
  const session = getDraftSession(draftId);
  const current = session.draft.value;
  if (!current) return;
  session.queueSave({
    workspace: {
      ...(current.workspace ?? {}),
      // 部分字段补全为完整契约（version/platform/contentForm 缺省补底，与快速模式 autosave 同构）
      delivery: {
        version: 1,
        platform: storyboardPlatform.value,
        contentForm: "video",
        ...deliveryWorkspace.value.delivery,
        ...next,
      },
    },
  });
  void session.flush();
}

/** 候选媒体失效（签名过期）：重取分镜详情拿新 URL（重载保位，选片在任务会话不受影响）。 */
function onRefreshMedia(): void {
  const storyboardId = urlState.key.value?.storyboard;
  if (storyboardId) void loadStoryboard(storyboardId);
}

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

function onViewportChange(next: {
  panX: number;
  panY: number;
  scale: number;
}): void {
  const previous = currentViewport.value;
  if (
    previous.panX === next.panX &&
    previous.panY === next.panY &&
    previous.scale === next.scale
  )
    return;
  currentViewport.value = next;
  workspace.queueLayoutSave();
}

/** 绑定 + 载入 + 布局恢复（KeepAlive 激活/路由 key 变化/epoch 失效后重进）。 */
async function ensureWorkspace(): Promise<void> {
  const key = urlState.key.value;
  if (!key) {
    error.value = "缺少 storyboard 参数";
    return;
  }
  if (
    workspace.binding.value?.storyboardId === key.storyboard &&
    storyboard.value?.id === key.storyboard
  )
    return;
  const bound = await workspace.bind(key);
  if (!bound) return;
  urlState.syncDraft(workspace.draftId.value);
  await loadStoryboard(key.storyboard);
  history.clear();
  tryApplyPendingLayout();
}

watch(
  () => urlState.key.value?.storyboard,
  (next, previous) => {
    if (next && next !== previous) void ensureWorkspace();
  },
);
onActivated(() => {
  if (!workspace.binding.value && urlState.key.value) void ensureWorkspace();
});

onMounted(() => {
  void ensureWorkspace();
  window.addEventListener("keydown", onHistoryKeydown);
});

onUnmounted(() => window.removeEventListener("keydown", onHistoryKeydown));
onDeactivated(() => window.removeEventListener("keydown", onHistoryKeydown));

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

function applyHistory(changes: ReturnType<typeof history.undo>): void {
  if (!changes) return;
  for (const change of changes) {
    moveShot(change.shotId, change.to.x, change.to.y);
  }
}

/** 双模式互切（C3 + C100-03/04）：先 flush 镜头草稿与布局（失败停留），dirty 再确认；同数据源 + 同草稿。 */
async function switchToQuickMode(): Promise<void> {
  if (!(await editor.flush())) return;
  if (!(await workspace.flushLayout())) return;
  const storyboardKey =
    urlState.key.value?.storyboard ?? storyboard.value?.id ?? "";
  if (
    (dirty.value || editor.state.dirty) &&
    !window.confirm("有未保存的改动，确定切换到快速模式？未保存内容将丢失。")
  )
    return;
  router.push({
    name: "video-production",
    query: {
      ...(storyboardKey ? { storyboard: storyboardKey } : {}),
      ...(workspace.draftId.value ? { draft: workspace.draftId.value } : {}),
    },
  });
}

async function goToCreationCenter(): Promise<void> {
  if (!(await editor.flush())) return;
  if (!(await workspace.flushLayout())) return;
  if (
    (dirty.value || editor.state.dirty) &&
    !window.confirm("有未保存的改动，确定返回创作中心？未保存内容将丢失。")
  )
    return;
  emit("open-view", "ai-center"); // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由
}

/** 选中镜头：切镜先 flush 当前草稿（失败停留原镜头、内容保留），再载入新镜头（hydration 抑制）。 */
async function onSelect(shotId: string): Promise<void> {
  if (editor.state.editingShotId === shotId) {
    selectedShotId.value = shotId;
    return;
  }
  if (editor.state.dirty && !(await editor.flush())) return;
  editor.beginEdit(shotId);
  selectedShotId.value = shotId;
}

/** 拖拽中的瞬时位置（不进历史、不触发保存）。 */
function onDragMove(shotId: string, x: number, y: number): void {
  moveShot(shotId, x, y);
}

/** 拖拽/键盘落位（一次一条历史；零位移不记录；布局排队保存）。 */
function onMove(
  shotId: string,
  x: number,
  y: number,
  fromX: number,
  fromY: number,
): void {
  history.record([{ shotId, from: { x: fromX, y: fromY }, to: { x, y } }]);
  moveShot(shotId, x, y);
  workspace.queueLayoutSave();
}

function onSaveGrouping(grouping: Parameters<typeof saveGrouping>[0]): void {
  void saveGrouping(grouping, storyboard.value?.editVersion ?? null);
}

async function onSwitchBranch(branchId: string | null): Promise<void> {
  if (editor.state.dirty && !(await editor.flush())) return;
  activeBranchId.value = branchId;
  selectedShotId.value = null;
  editor.beginEdit(null);
  workspace.queueLayoutSave();
}
</script>

<template>
  <div class="video-canvas gl-field">
    <header class="canvas-header">
      <div class="canvas-title-row">
        <button class="btn-back" type="button" @click="goToCreationCenter">
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M10 3L5 8l5 5"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          返回创作中心
        </button>
        <div class="canvas-title">
          <h2 class="eyebrow">专业模式</h2>
          <h3 class="card-title">分镜导演台</h3>
          <span v-if="storyboard" class="field-note gl-num">
            {{ storyboard.shots.length }} 镜 · 目标
            {{ storyboard.targetDurationSeconds }}s ·
            {{ storyboard.resolution }}
          </span>
        </div>
      </div>
      <div class="canvas-header-actions">
        <span
          v-if="workspace.bindingPending.value"
          class="field-note"
          data-test="canvas-binding"
          >工作区连接中…</span
        >
        <span
          v-else-if="workspace.bindingError.value"
          class="badge badge-warning"
          data-test="canvas-binding-error"
        >
          {{ workspace.bindingError.value }}
        </span>
        <span
          v-else-if="
            workspace.saveState.value === 'saving' ||
            workspace.saveState.value === 'pending'
          "
          class="field-note"
          data-test="canvas-layout-saving"
          >布局保存中…</span
        >
        <span
          v-if="dirty"
          class="badge badge-warning"
          data-test="canvas-dirty-badge"
          >未保存</span
        >
        <button
          type="button"
          class="gl-btn-primary"
          data-test="switch-quick-mode"
          @click="switchToQuickMode"
        >
          切换到快速模式
        </button>
      </div>
    </header>

    <p
      v-if="!urlState.key.value"
      class="canvas-empty"
      data-test="canvas-missing-id"
    >
      缺少 storyboard 参数——请从快速模式的分镜步骤进入专业模式。
    </p>
    <p
      v-else-if="loading || workspace.bindingPending.value"
      class="canvas-empty"
      data-test="canvas-loading"
    >
      分镜加载中…
    </p>
    <p
      v-else-if="workspace.bindingError.value"
      class="canvas-empty"
      data-test="canvas-binding-failed"
    >
      {{ workspace.bindingError.value }}
    </p>
    <p v-else-if="error" class="canvas-empty" data-test="canvas-error">
      {{ error }}
    </p>

    <template v-else-if="storyboard">
      <!-- 生成栏（C100-07）：费用/进度/提交主行动；与画布同组（分镜就绪即出） -->
      <CanvasRunBar
        v-if="!workspace.bindingError.value"
        :production="productionTask"
        :storyboard-id="storyboard.id"
      />

      <div class="canvas-main">
        <CanvasAssetRail
          class="canvas-asset-rail"
          :authenticated="true"
          @add-media="onAddMediaAsset"
        />
        <CanvasBoard
          :shots="liveShots"
          :reference-nodes="graph.nodes.value.filter(node => node.kind === 'media' || node.kind === 'note')"
          :graph-edges="graph.edges.value"
          :selected-node-id="selectedNodeId"
          @select-node="onSelectNode"
          :selected-shot-id="selectedShotId"
          :branches="branches"
          :active-branch-id="activeBranchId"
          :can-undo="history.canUndo.value"
          :can-redo="history.canRedo.value"
          :initial-viewport="restoredViewport"
          @select="onSelect"
          @drag-move="onDragMove"
          @move="onMove"
          @undo="applyHistory(history.undo())"
          @redo="applyHistory(history.redo())"
          @viewport-change="onViewportChange"
        />
        <DirectorPanel
          :shot="selectedShot"
          :grouping="storyboard.grouping"
          :active-branch-id="activeBranchId"
          :dirty="dirty"
          :editor="editor"
          :readonly="storyboardReadonly"
          :session="productionTask.session"
          @edit="markDirty"
          @save-grouping="onSaveGrouping"
          @switch-branch="onSwitchBranch"
          @refresh-media="onRefreshMedia"
        />
      </div>
    </template>
    <!-- C100-07：交付与成片导出（仅在服务端确认 succeeded 后出现） -->
    <CanvasDeliveryPanel
      v-if="
        storyboard &&
        productionTask.terminal.value &&
        productionTask.task.value?.phase === 'succeeded'
      "
      :task="productionTask.task.value"
      :delivery="delivery"
      :platform="storyboardPlatform"
      :draft-id="workspace.draftId.value"
      :draft-version="draftVersion"
      :disabled="storyboardReadonly"
      :download-subtitle="downloadSubtitle"
      :report-error="
        (message) => {
          productionTask.session.taskError.value = message;
        }
      "
      @update-delivery="onUpdateDelivery"
    />
  </div>
</template>

<style scoped>
.video-canvas {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-height: 0;
  height: 100%;
}
.canvas-header {
  display: flex;
  align-items: flex-end;
  gap: var(--space-md);
}
.canvas-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  flex: 1;
}
.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}
.btn-back:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}
.canvas-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-md);
}
.canvas-header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.canvas-main {
  display: flex;
  gap: var(--space-md);
  flex: 1;
  min-height: 0;
}
.canvas-empty {
  color: var(--color-text-secondary);
  padding: var(--space-xl);
  text-align: center;
}
.eyebrow {
  font-size: var(--text-xs);
  color: var(--color-accent-2);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin: 0;
}
.card-title {
  font-size: var(--text-lg, 1.1rem);
  margin: 0;
  font-weight: 600;
}
/* <768px：标题区按单元换行；画布与导演面板纵向堆叠（300px 定宽面板会把画布挤成细条），§8.3 */
@media (max-width: 767px) {
  .canvas-header {
    flex-wrap: wrap;
    align-items: flex-start;
    row-gap: var(--space-xs);
  }
  .canvas-title-row {
    flex-wrap: wrap;
    row-gap: var(--space-xxs);
  }
  .canvas-title {
    flex-wrap: wrap;
    row-gap: var(--space-xxs);
  }
  .canvas-main {
    flex-direction: column;
  }
  .canvas-main :deep(.canvas-board) {
    min-height: 280px;
  }
  .canvas-main :deep(.director-panel) {
    width: auto;
    max-height: 46%;
  }
}
</style>
