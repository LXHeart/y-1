<script setup lang="ts">
/**
 * CloneReferenceHandoff.vue — C107-22（§13.3 登记：VideoAnalysisView 体积门禁
 * 拆分）：「用作克隆参考」交接卡。只带稳定 ai_run 分析 id；解析代理 URL 属临时
 * 媒体不直传（服务端 media 引用只接受 active）。用户端壳无 video-clone 路由时
 * resolve 落空 → 如实说明，不跳错入口。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCloneReferenceTransfer } from '../composables/useCloneReferenceTransfer'

const props = defineProps<{
  runId: string | null
  label: string | null
}>()

const router = useRouter()
const { fromVideoAnalysis } = useCloneReferenceTransfer()
const shellBlocked = ref(false)
const transfer = computed(() => fromVideoAnalysis(props.runId, props.label))

function go(): void {
  if (!transfer.value.ok) return
  const resolved = router.resolve(transfer.value.target)
  if (resolved.matched.length === 0) {
    shellBlocked.value = true
    return
  }
  shellBlocked.value = false
  void router.push(transfer.value.target)
}
</script>

<template>
  <section class="reference-handoff gl-zone" data-testid="clone-reference-handoff">
    <div>
      <p class="reference-handoff-kicker">视频克隆</p>
      <h2>用作克隆参考</h2>
      <p>带着本次分析记录进入视频克隆工作台；解析的临时视频不会被直接引用。</p>
    </div>
    <div class="clone-handoff-action">
      <button class="btn-primary gl-btn-primary" type="button" data-testid="clone-reference-go"
        :disabled="!transfer.ok" @click="go">
        {{ transfer.ok ? '用作克隆参考' : '分析记录不可用' }}
      </button>
      <p v-if="!transfer.ok" class="clone-handoff-note" role="note" data-testid="clone-reference-missing">
        没有可用的分析记录（未分析或已过期），请重新分析后再试。
      </p>
      <p v-else-if="shellBlocked" class="clone-handoff-note" role="note" data-testid="clone-reference-blocked">
        视频克隆工作台仅在 AI 创作中心开放。
      </p>
    </div>
  </section>
</template>

<style scoped>
/* C107-22 克隆参考交接：说明文字与动作按钮纵向排布（容器复用页面 reference-handoff）。 */
.clone-handoff-action {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-xxs);
}

.clone-handoff-note {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  text-align: right;
}

@media (max-width: 640px) {
  .clone-handoff-action {
    align-items: stretch;
  }

  .clone-handoff-note {
    text-align: left;
  }
}
</style>
