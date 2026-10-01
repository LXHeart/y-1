/**
 * useHypitSource.ts — C107-21 (task-107) 源码编辑草稿（W21 固定职责）：
 * debounce 800ms 草稿、changeset 创建/CAS 应用、冲突保留输入；save 模式可保存
 * 无效语法，validated 模式必须过 check（行为区分在 applyMode，不由前端猜）。
 * C107F2-10：hash 为权威 CAS 基线（baseHash 别名不读）；提交内容发起时冻结，
 * 保存途中新输入保留 dirty；成功后重读新 hash；冲突态重开同文件=刷新基线保留草稿。
 */
import { computed, getCurrentInstance, onUnmounted, ref } from 'vue';
import {
  applyChangeset,
  createChangeset,
  listFiles,
  readFile,
} from './hypit-api';
import type { RefreshGate } from './useHypitProjectScope';
import type { HypitFileEntry } from '../../../types/hypit';

export type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'conflict' | 'error';

const DEBOUNCE_MS = 800;

export function useHypitSource() {
  const files = ref<HypitFileEntry[]>([]);
  const revision = ref<number | null>(null);
  const activePath = ref<string | null>(null);
  const baseHash = ref<string | null>(null);
  const draft = ref('');
  const savedContent = ref('');
  const saveState = ref<SaveState>('idle');
  const diagnostics = ref<{ file: string; message: string; severity: string }[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const controller = new AbortController();
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;
  let token = 0;

  async function refresh(projectId: string, gate?: RefreshGate): Promise<void> {
    const mine = ++token;
    const signal = gate?.signal ?? controller.signal;
    const ok = () => mine === token && !signal.aborted && (gate?.isCurrent?.() ?? true);
    loading.value = true;
    error.value = null;
    try {
      const tree = await listFiles(projectId, signal);
      if (!ok()) return;
      files.value = tree.files;
      revision.value = tree.revision;
    } catch (cause) {
      if (!ok()) return;
      error.value = (cause as Error).message;
    } finally {
      if (ok()) loading.value = false;
    }
  }

  async function open(projectId: string, path: string): Promise<void> {
    // 冲突态下重开同一文件 = 刷新基线（保留草稿，不覆盖本地输入）；
    // 其他路径切换仍是全新打开（服务端内容替换草稿）。
    const refreshBaseline = path === activePath.value && saveState.value === 'conflict';
    activePath.value = path;
    diagnostics.value = [];
    try {
      const content = await readFile(projectId, path, controller.signal);
      savedContent.value = content.content;
      baseHash.value = content.hash;
      revision.value = content.revision;
      if (!refreshBaseline) {
        draft.value = content.content;
        saveState.value = 'idle';
      } else {
        saveState.value = draft.value === content.content ? 'idle' : 'dirty';
      }
      error.value = null;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    }
  }

  function edit(next: string): void {
    draft.value = next;
    saveState.value = 'dirty';
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      // F09/C107F2-10：迟到的 debounce 回写不得踩掉保存链状态（saving 后的
      // saved/conflict/error），只允许在编辑中间态上重申 dirty。
      if (saveState.value === 'idle' || saveState.value === 'dirty') saveState.value = 'dirty';
    }, DEBOUNCE_MS);
  }

  /**
   * 保存：mode=save 允许坏语法；mode=validated 必须 check 通过。冲突 409 时输入保留。
   * C107F2-10：提交内容/基线在发起时冻结（保存途中新输入不混入本次请求），
   * 响应只确认该次提交副本；成功后重读目标文件新 hash 作下一次 CAS 基线。
   */
  async function save(projectId: string, mode: 'save' | 'validated'): Promise<boolean> {
    if (activePath.value === null || revision.value === null) return false;
    if (draft.value === savedContent.value && mode === 'save') return true;
    // 冻结编辑世代：保存发起即取消在途 debounce，防止迟到的 dirty 回写覆盖终态。
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    const submittedPath = activePath.value;
    const submitted = draft.value;
    let submittedBaseHash = baseHash.value;
    const submittedRevision = revision.value;
    saveState.value = 'saving';
    diagnostics.value = [];
    try {
      // C107F2-37（缺陷 AC）：open() 先置 activePath 再异步读基线，快速填入+
      // 保存会冻结 null 基线 → apply 必 409。保存时基线仍空则先读当前 head
      // 作为 CAS 基线（草稿是独立创作的，不存在「旧基线」可言）。
      if (submittedBaseHash === null) {
        const baseline = await readFile(projectId, submittedPath, controller.signal);
        submittedBaseHash = baseline.hash;
      }
      const changeset = await createChangeset(projectId, {
        requestId: crypto.randomUUID(),
        baseRevision: submittedRevision,
        applyMode: mode,
        changes: [{ path: submittedPath, action: 'put', content: submitted, baseHash: submittedBaseHash }],
      }, controller.signal);
      if (mode === 'validated' && changeset.checkStatus !== 'passed') {
        diagnostics.value = [{ file: submittedPath, message: '校验未通过，草稿已保留', severity: 'error' }];
        saveState.value = 'error';
        return false;
      }
      const applied = await applyChangeset(projectId, changeset.changesetId, {
        requestId: crypto.randomUUID(),
        baseRevision: submittedRevision,
      }, controller.signal);
      savedContent.value = submitted;
      if (activePath.value === submittedPath) {
        revision.value = applied.revision;
        const fresh = await readFile(projectId, submittedPath, controller.signal);
        baseHash.value = fresh.hash;
        if (draft.value === submitted) {
          draft.value = fresh.content;
          savedContent.value = fresh.content;
        }
      }
      saveState.value = draft.value === savedContent.value ? 'saved' : 'dirty';
      await refresh(projectId);
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      const err = cause as { status?: number; message?: string };
      // 409 = 基线已过期：草稿保留，等用户刷新基线后显式重提（禁止自动覆盖新 head）。
      saveState.value = err.status === 409 ? 'conflict' : 'error';
      error.value = err.message ?? '保存失败';
      return false;
    }
  }

  const dirty = computed(() => saveState.value === 'dirty' || draft.value !== savedContent.value);

  /**
   * 确认离开当前工程时清空源码域（草稿随确认放弃，不跨项目复用；§4.4
   * draft 属 account+project+path）。在途 debounce 与请求一并作废。
   */
  function reset(): void {
    token += 1;
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    files.value = [];
    revision.value = null;
    activePath.value = null;
    baseHash.value = null;
    draft.value = '';
    savedContent.value = '';
    saveState.value = 'idle';
    diagnostics.value = [];
    loading.value = false;
    error.value = null;
  }

  // 组件外调用（测试）由调用方 abort；仅组件内自动随卸载注销。
  if (getCurrentInstance()) onUnmounted(() => {
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    controller.abort();
  });

  return {
    files, revision, activePath, draft, savedContent, baseHash, saveState, diagnostics, loading, error, dirty,
    refresh, open, edit, save, reset,
  };
}
