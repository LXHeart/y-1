/**
 * useHypitSource.ts — C107-21 (task-107) 源码编辑草稿（W21 固定职责）：
 * debounce 800ms 草稿、changeset 创建/CAS 应用、冲突保留输入；save 模式可保存
 * 无效语法，validated 模式必须过 check（行为区分在 applyMode，不由前端猜）。
 */
import { computed, onUnmounted, ref } from 'vue';
import {
  applyChangeset,
  createChangeset,
  listFiles,
  readFile,
} from './hypit-api';
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

  async function refresh(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const tree = await listFiles(projectId, controller.signal);
      files.value = tree.files;
      revision.value = tree.revision;
    } catch (cause) {
      if (controller.signal.aborted) return;
      error.value = (cause as Error).message;
    } finally {
      loading.value = false;
    }
  }

  async function open(projectId: string, path: string): Promise<void> {
    activePath.value = path;
    saveState.value = 'idle';
    diagnostics.value = [];
    try {
      const content = await readFile(projectId, path, controller.signal);
      draft.value = content.content;
      savedContent.value = content.content;
      baseHash.value = content.baseHash;
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
      saveState.value = 'dirty';
    }, DEBOUNCE_MS);
  }

  /** 保存：mode=save 允许坏语法；mode=validated 必须 check 通过。冲突 409 时输入保留。 */
  async function save(projectId: string, mode: 'save' | 'validated'): Promise<boolean> {
    if (activePath.value === null || revision.value === null) return false;
    if (draft.value === savedContent.value && mode === 'save') return true;
    saveState.value = 'saving';
    diagnostics.value = [];
    try {
      const changeset = await createChangeset(projectId, {
        requestId: crypto.randomUUID(),
        baseRevision: revision.value,
        applyMode: mode,
        changes: [{ path: activePath.value, action: 'put', content: draft.value, baseHash: baseHash.value }],
      }, controller.signal);
      if (mode === 'validated' && changeset.checkStatus !== 'passed') {
        diagnostics.value = [{ file: activePath.value, message: '校验未通过，草稿已保留', severity: 'error' }];
        saveState.value = 'error';
        return false;
      }
      const applied = await applyChangeset(projectId, changeset.changesetId, {
        requestId: crypto.randomUUID(),
        baseRevision: revision.value,
      }, controller.signal);
      revision.value = applied.revision;
      savedContent.value = draft.value;
      saveState.value = 'saved';
      await refresh(projectId);
      return true;
    } catch (cause) {
      if (controller.signal.aborted) return false;
      const err = cause as { status?: number; message?: string };
      // 409 = base revision 已前进：草稿保留，等用户基于新 head 重提。
      saveState.value = err.status === 409 ? 'conflict' : 'error';
      error.value = err.message ?? '保存失败';
      return false;
    }
  }

  const dirty = computed(() => saveState.value === 'dirty' || draft.value !== savedContent.value);

  onUnmounted(() => {
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    controller.abort();
  });

  return {
    files, revision, activePath, draft, savedContent, saveState, diagnostics, loading, error, dirty,
    refresh, open, edit, save,
  };
}
