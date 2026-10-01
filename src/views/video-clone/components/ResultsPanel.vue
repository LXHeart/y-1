<script setup lang="ts">
/**
 * ResultsPanel.vue — C107-21：结果与归档（输入 outputs/builds；事件
 * archive/selectBuild）。C107F2-12：归档按 output 名走服务端幂等动作；
 * 未知 size 不显示虚假 0，下载链接由归档 media 提供。
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

/** 已知字节数才展示（未知为 null，不拿 0 冒充已知值）。 */
function formatSize(sizeBytes: number | null): string {
  if (sizeBytes === null || sizeBytes < 0) return '';
  if (sizeBytes >= 1024 * 1024) return `${(sizeBytes / (1024 * 1024)).toFixed(1)}MB`;
  if (sizeBytes >= 1024) return `${(sizeBytes / 1024).toFixed(0)}KB`;
  return `${sizeBytes}B`;
}
</script>

<template>
  <section class="gl-zone" data-testid="clone-results-panel" aria-label="结果与导出">
    <h2>结果</h2>
    <!-- C107F2-37（缺陷 AA）：错误独立成行（同缺陷 T 形态）——归档失败时保留输出列表供单项重试，
         不能让 error 独占 v-if 链把列表整个卸掉。 -->
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-if="props.loading" class="clone-loading" aria-live="polite">正在读取结果…</p>
    <p v-else-if="props.builds.length === 0 && !props.error" class="clone-empty" data-testid="clone-empty">
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
          <span class="clone-output-meta">
            {{ output.kind }} · {{ output.archiveState }}<template v-if="formatSize(output.sizeBytes) !== ''"> · {{ formatSize(output.sizeBytes) }}</template>
          </span>
          <!-- C107F2-37（缺陷 AB）：/api/media/{id} 返回元数据 JSON；真实下载用
               归档后签发的短时 presigned downloadUrl（未签到前不渲染链接）。 -->
          <a v-if="output.archiveState === 'archived' && output.downloadUrl" class="gl-btn-secondary clone-output-link"
            :href="output.downloadUrl" download>下载</a>
          <button v-else-if="output.archiveState === 'archived'" type="button" class="gl-btn-secondary clone-output-link" disabled>下载签名中…</button>
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
