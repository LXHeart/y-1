/**
 * useHypitProjects.ts — C107-21 (task-107) 工程列表/新建/删除（W21 固定职责）。
 * 202 状态意味着后端 provisioning 仍进行中：列表轮询以工程 status 为准，
 * 不从前端猜测就绪。
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
  const loading = ref(false);
  const error = ref<{ status: number; message: string } | null>(null);
  const submitting = ref(false);
  const controller = new AbortController();

  async function refresh(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const page = await listProjects(controller.signal);
      projects.value = page.items;
    } catch (cause) {
      if (controller.signal.aborted) return;
      const err = cause as { status?: number; message?: string };
      error.value = { status: err.status ?? 0, message: err.message ?? '读取失败' };
    } finally {
      loading.value = false;
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

  const readyProjects = computed(() => projects.value.filter((project) => project.status === 'ready'));

  onUnmounted(() => controller.abort());
  void refresh();

  return { projects, readyProjects, loading, error, submitting, refresh, create, remove };
}
