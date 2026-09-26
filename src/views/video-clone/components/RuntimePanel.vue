<script setup lang="ts">
/**
 * RuntimePanel.vue — C107-21：高级运行环境（输入 capabilities；事件
 * refresh/programAction）。回显脱敏 readiness；operator 动作交服务端权限，
 * 页面隐藏不是唯一保护；密钥输入写后即清、不留 localStorage。
 */
import { onMounted, onUnmounted, ref } from 'vue';
import { hypitRequest } from '../composables/hypit-api';
import type { HypitCapabilities } from '../../../types/hypit';

defineProps<{ projectReady: boolean }>();

const capabilities = ref<HypitCapabilities | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const program = ref('whisperx.local');
const programAction = ref('status');
const programResult = ref<string | null>(null);
const secretInput = ref('');
const secretSlot = ref('');
const controller = new AbortController();

async function refresh(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    capabilities.value = await hypitRequest<HypitCapabilities>('/capabilities', {
      method: 'GET', signal: controller.signal,
    });
  } catch (cause) {
    if (controller.signal.aborted) return;
    error.value = (cause as Error).message;
  } finally {
    loading.value = false;
  }
}

/** operator 动作：403 属预期口径（普通用户），错误如实展示。 */
async function runProgramAction(): Promise<void> {
  programResult.value = null;
  try {
    const result = await hypitRequest<Record<string, unknown>>(
      `/runtime/programs/${encodeURIComponent(programAction.value)}`, {
      method: 'POST', body: JSON.stringify({ program: program.value }), signal: controller.signal,
    });
    programResult.value = JSON.stringify(result);
  } catch (cause) {
    if (controller.signal.aborted) return;
    programResult.value = `失败：${(cause as Error).message}`;
  }
}

/** 凭据直写后立即清空输入框（§8.2：密钥写后清输入，不留 localStorage）。 */
async function putSecret(): Promise<void> {
  if (secretInput.value.length === 0) return;
  try {
    await hypitRequest(`/runtime/credentials/${encodeURIComponent(secretSlot.value)}`, {
      method: 'PUT',
      body: JSON.stringify({ requestId: crypto.randomUUID(), kind: 'api-key', secret: secretInput.value }),
      signal: controller.signal,
    });
    programResult.value = '凭据已写入。';
  } catch (cause) {
    programResult.value = `失败：${(cause as Error).message}`;
  } finally {
    secretInput.value = '';
  }
}

onMounted(refresh);
onUnmounted(() => controller.abort());
</script>

<template>
  <section class="gl-zone" data-testid="clone-runtime-panel" aria-label="运行环境（高级）">
    <header class="clone-runtime-head">
      <h2>运行环境（高级）</h2>
      <button type="button" class="gl-btn-secondary" @click="refresh">刷新</button>
    </header>
    <p v-if="error" class="clone-error" data-testid="clone-error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="clone-loading" aria-live="polite">正在读取运行时状态…</p>
    <template v-else-if="capabilities">
      <p class="clone-runtime-enabled" data-testid="clone-disabled" v-if="!capabilities.enabled" role="status">
        视频克隆未启用：请联系部署者配置 Hypit 引擎。
      </p>
      <ul class="clone-runtime-features">
        <li v-for="feature in capabilities.features" :key="feature.id" class="clone-runtime-feature">
          <span class="clone-runtime-feature-name">{{ feature.id }}</span>
          <span class="clone-runtime-feature-state" :data-ready="String(feature.ready)">
            {{ feature.ready ? '就绪' : '未就绪' }}
          </span>
          <span v-if="feature.reason" class="clone-runtime-reason">{{ feature.reason }}</span>
        </li>
      </ul>
      <div class="clone-runtime-tools">
        <label class="gl-field clone-runtime-program">
          <span>Program</span>
          <input v-model="program" type="text" aria-label="程序 id（如 whisperx.local）" />
        </label>
        <label class="gl-field clone-runtime-action">
          <span>操作</span>
          <select v-model="programAction" aria-label="程序操作">
            <option value="status">status</option>
            <option value="prepare">prepare</option>
            <option value="up">up</option>
            <option value="down">down</option>
            <option value="logs">logs</option>
          </select>
        </label>
        <button type="button" class="gl-btn-secondary" @click="runProgramAction">执行</button>
      </div>
      <form class="clone-runtime-secret" @submit.prevent="putSecret">
        <label class="gl-field clone-runtime-slot">
          <span>凭据槽（endpoint/slot）</span>
          <input v-model="secretSlot" type="text" placeholder="hypihub.default/apiKey" aria-label="凭据槽" />
        </label>
        <label class="gl-field clone-runtime-secret-input">
          <span>密钥（写后即清）</span>
          <input v-model="secretInput" type="password" autocomplete="off" aria-label="密钥" />
        </label>
        <button type="submit" class="gl-btn-secondary">写入凭据</button>
      </form>
      <pre v-if="programResult !== null" class="clone-runtime-result" aria-live="polite">{{ programResult }}</pre>
    </template>
  </section>
</template>

<style scoped>
.clone-runtime-head { display: flex; align-items: center; justify-content: space-between; }
.clone-runtime-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading { color: var(--color-text-secondary); }
.clone-runtime-enabled { color: var(--color-warning, #d8a024); }
.clone-runtime-features { list-style: none; margin: 8px 0; padding: 0; display: grid; gap: 4px; }
.clone-runtime-feature { display: flex; gap: 8px; align-items: baseline; font-size: 13px; }
.clone-runtime-feature-name { font-weight: 600; }
.clone-runtime-feature-state[data-ready='true'] { color: var(--color-success, #4c9e6b); }
.clone-runtime-feature-state[data-ready='false'] { color: var(--color-danger); }
.clone-runtime-reason { color: var(--color-text-secondary); font-size: 12px; }
.clone-runtime-tools { display: flex; align-items: flex-end; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.clone-runtime-program { width: 200px; }
.clone-runtime-action { width: 140px; }
.clone-runtime-secret { display: flex; align-items: flex-end; gap: 8px; flex-wrap: wrap; border-top: 1px solid var(--color-border); padding-top: 12px; }
.clone-runtime-slot { width: 240px; }
.clone-runtime-secret-input { width: 220px; }
.clone-runtime-result { background: var(--surface-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px; font-size: 12px; overflow: auto; max-height: 20vh; }
</style>
