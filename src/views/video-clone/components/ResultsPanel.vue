<script setup lang="ts">
/**
 * ResultsPanel.vue — C107-21：结果与归档（输入 outputs/builds；事件
 * archive/selectBuild）。归档走服务端幂等动作；下载链接由归档 media 提供，
 * 不上传二次归档逻辑。
 */
import type { HypitBuild, HypitOutput } from '../../../types/hypit';

const props = defineProps<{
  builds: HypitBuild[];
  outputs: HypitOutput[];
  activeBuildId: string | null;
  loading: boolean;
  error: string | null;
  archivingId: string | null;
}>();

const emit = defineEmits<{
  selectBuild: [buildId: string];
  archive: [output: HypitOutput];
}>();
</script>

<template>
  <section class="gl-zone" data-testid="clone-results-panel" aria-label="结果与导出">
    <h2>结果</h2>
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-else-if="props.loading" class="clone-loading" aria-live="polite">正在读取结果…</p>
    <p v-else-if="props.builds.length === 0" class="clone-empty" data-testid="clone-empty">
      还没有完成的生成。结果出现后可在此归档与下载。
    </p>
    <template v-else>
      <label v-if="props.builds.length > 1" class="gl-field clone-results-select">
        <span>选择 Build</span>
        <select :value="props.activeBuildId ?? ''" data-testid="clone-results-build"
          @change="emit('selectBuild', ($event.target as HTMLSelectElement).value)">
          <option v-for="build in props.builds" :key="build.id" :value="build.id">
            {{ build.runFile }}（{{ build.outcome ?? build.lifecycle }}）
          </option>
        </select>
      </label>
      <p v-if="props.outputs.length === 0" class="clone-empty" data-testid="clone-result-pending">
        该 Build 的输出待补写或仍在生成。terminal 不代表全部归档完成。
      </p>
      <ul v-else class="clone-output-list">
        <li v-for="output in props.outputs" :key="output.id" class="clone-output">
          <span class="clone-output-name">{{ output.displayName ?? output.name }}</span>
          <span class="clone-output-meta">{{ output.kind }} · {{ output.archiveState }}</span>
          <a v-if="output.archiveState === 'archived' && output.mediaId" class="gl-btn-secondary clone-output-link"
            :href="`/api/media/${output.mediaId}`" download>下载</a>
          <button v-else type="button" class="gl-btn-primary" data-testid="clone-archive"
            :disabled="props.archivingId === output.id || output.archiveState === 'archiving'"
            @click="emit('archive', output)">
            {{ props.archivingId === output.id ? '归档中…' : '归档' }}
          </button>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.clone-results-select { max-width: 320px; }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-output-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.clone-output { display: flex; align-items: center; gap: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px 12px; }
.clone-output-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clone-output-meta { font-size: 12px; color: var(--color-text-secondary); }
.clone-output-link { text-decoration: none; }
</style>
