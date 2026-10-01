/**
 * useHypitProjects.ts — C107-21 (task-107) 工程列表/新建/删除（W21 固定职责）。
 * 202 状态意味着后端 provisioning 仍进行中：列表轮询以工程 status 为准，
 * 不从前端猜测就绪。
 * C107F2-11（§5.3）：cursor 分页——refresh 拉首页，loadMore 追加下一页并按
 * id 稳定去重（已存在的不重复插入、不重排）；nextCursor=null 即到底。
 */
import { computed, onUnmounted, ref } from 'vue';
import {
  createProject,
  deleteProject,
  listProjects,
} from './hypit-api';
import type { HypitProject } from '../../../types/hypit';

export function useHypitProjects() {
  const projects = ref<HypitProject[]>([]);
  const nextCursor = ref<string | null>(null);
  const loading = ref(false);
  const loadingMore = ref(false);
  const error = ref<{ status: number; message: string } | null>(null);
  const submitting = ref(false);
  const controller = new AbortController();
  let token = 0;

  async function refresh(): Promise<void> {
    const mine = ++token;
    loading.value = true;
    error.value = null;
    try {
      const page = await listProjects(undefined, controller.signal);
      if (mine !== token) return;
      projects.value = page.items;
      nextCursor.value = page.nextCursor ?? null;
    } catch (cause) {
      if (mine !== token || controller.signal.aborted) return;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '读取失败' };
    } finally {
      if (mine === token) loading.value = false;
    }
  }

  /** 追加下一页：按 id 去重（同 id 保留已有位置，字段以新页为准更新）。 */
  async function loadMore(): Promise<void> {
    if (nextCursor.value === null || loadingMore.value) return;
    const mine = ++token;
    loadingMore.value = true;
    error.value = null;
    try {
      const page = await listProjects(nextCursor.value, controller.signal);
      if (mine !== token) return;
      const byId = new Map(projects.value.map((project) => [project.id, project]));
      for (const project of page.items) byId.set(project.id, project);
      projects.value = [...byId.values()];
      nextCursor.value = page.nextCursor ?? null;
    } catch (cause) {
      if (mine !== token || controller.signal.aborted) return;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '加载更多失败' };
    } finally {
      if (mine === token) loadingMore.value = false;
    }
  }

  async function create(input: {
    title: string;
    mode: string;
    templateId?: string | null;
    /** C107-22：{kind,id} 引用（归属/固化由服务端核验，label 不上报）。 */
    sourceContext?: Record<string, unknown> | null;
  }): Promise<HypitProject | null> {
    submitting.value = true;
    error.value = null;
    try {
      const created = await createProject({
        requestId: crypto.randomUUID(),
        title: input.title,
        mode: input.mode,
        templateId: input.templateId ?? null,
        sourceContext: input.sourceContext ?? null,
      }, controller.signal);
      await refresh();
      return created.project;
    } catch (cause) {
      if (controller.signal.aborted) return null;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '创建失败' };
      return null;
    } finally {
      submitting.value = false;
    }
  }

  async function remove(projectId: string): Promise<boolean> {
    error.value = null;
    try {
      await deleteProject(projectId, controller.signal);
      await refresh();
      return true;
    } catch (cause) {
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '删除失败' };
      return false;
    }
  }

  /** 列表内就地更新单条（provisioning 轮询用；不在列表则忽略）。 */
  function upsert(project: HypitProject): void {
    const index = projects.value.findIndex((item) => item.id === project.id);
    if (index === -1) {
      projects.value = [project, ...projects.value];
      return;
    }
    projects.value = [
      ...projects.value.slice(0, index),
      project,
      ...projects.value.slice(index + 1),
    ];
  }

  const readyProjects = computed(() => projects.value.filter((project) => project.status === 'ready'));

  onUnmounted(() => controller.abort());
  void refresh();

  return { projects, readyProjects, nextCursor, loading, loadingMore, error, submitting, refresh, loadMore, create, remove, upsert };
}
