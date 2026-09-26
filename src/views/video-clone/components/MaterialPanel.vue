<script setup lang="ts">
/**
 * MaterialPanel.vue — C107-21：素材与生成依赖（输入 builds/buildLoading；事件
 * build/retry/cancel）。terminal 不代表全部成功：lifecycle/outcome 如实展示；
 * 失败局部重做，不用文件 URL 冒充可复用 Result。
 */
import type { HypitBuild } from '../../../types/hypit';

const props = defineProps<{
  builds: HypitBuild[];
  buildLoading: boolean;
  buildError: string | null;
}>();

const emit = defineEmits<{
  build: [];
  retry: [buildId: string];
  cancel: [buildId: string];
}>();

const lifecycleLabels: Record<HypitBuild['lifecycle'], string> = {
  submitting: '提交中',
  active: '生成中',
  execution_decided: '执行已定',
  result_pending: '结果待补写',
  finished: '已完成',
  submission_incomplete: '提交不完整',
};

function isTerminal(build: HypitBuild): boolean {
  return build.lifecycle === 'finished' || build.lifecycle === 'execution_decided';
}
</script>

<template>
  <section class="gl-zone" data-testid="clone-material-panel" aria-label="生成与编辑">
    <header class="clone-material-head">
      <h2>生成任务</h2>
      <button type="button" class="gl-btn-primary" data-testid="clone-build-submit" @click="emit('build')">
        提交生成
      </button>
    </header>
    <p v-if="props.buildError" class="clone-error" data-testid="clone-error" role="alert">{{ props.buildError }}</p>
    <p v-else-if="props.buildLoading" class="clone-loading" aria-live="polite">正在读取生成任务…</p>
    <p v-else-if="props.builds.length === 0" class="clone-empty" data-testid="clone-empty">
      还没有生成任务。方案就绪后提交生成。
    </p>
    <ul v-else class="clone-build-list">
      <li v-for="build in props.builds" :key="build.id" class="clone-build" :data-testid="`clone-job-${build.id}`">
        <span class="clone-build-title">{{ build.runFile }}</span>
        <span class="clone-build-state" data-testid="clone-job-running" role="status">
          {{ lifecycleLabels[build.lifecycle] }}<template v-if="build.outcome"> · {{ build.outcome }}</template>
        </span>
        <span v-if="build.lifecycle === 'result_pending'" class="clone-build-hint">结果待补写：可重新归档已有产物</span>
        <span v-if="isTerminal(build) && build.outcome === 'failed'" class="clone-build-actions">
          <button type="button" class="gl-btn-secondary" @click="emit('retry', build.id)">重试</button>
        </span>
        <span v-else-if="build.lifecycle === 'active' || build.lifecycle === 'submitting'" class="clone-build-actions">
          <button type="button" class="gl-btn-secondary" @click="emit('cancel', build.id)">取消</button>
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.clone-material-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-material-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-build-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.clone-build { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-build-title { flex: 1; min-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clone-build-state { font-size: 12px; color: var(--color-text-secondary); }
.clone-build-hint { font-size: 12px; color: var(--color-warning, #d8a024); }
.clone-build-actions { display: flex; gap: 6px; }
</style>
