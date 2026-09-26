<template>
  <section class="clone-entry gl-field" data-testid="video-clone-entry" aria-labelledby="clone-entry-title">
    <div class="clone-entry-copy">
      <h3 id="clone-entry-title">视频克隆</h3>
      <p>把参考视频复刻成完整作品：方案确认、多文件生成、审片修改、批量变体一站式完成。</p>
    </div>
    <p v-if="checking" class="clone-entry-state" role="status" data-testid="video-clone-entry-checking">
      正在检查可用性…
    </p>
    <template v-else>
      <p v-if="!enabled" class="clone-entry-state" role="note" data-testid="video-clone-entry-disabled">
        视频克隆暂未开放，开放后这里可以直接进入工作台。
      </p>
      <button v-else type="button" class="gl-btn-primary clone-entry-go" data-testid="video-clone-enter"
        @click="enter">进入视频克隆工作台</button>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * VideoCloneEntry.vue — C107-22：AI 中心视频工坊内的独立入口卡。
 * 可用性只查一次 /api/hypit/capabilities：关闭时如实说明且不发起循环失败请求
 * （TC107-22-03）；跳转走 ai 壳路由名，不落用户端 index 路径。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchApi } from '../../../composables/grassland-http'

const router = useRouter()
const checking = ref(true)
const enabled = ref(false)

onMounted(async () => {
  try {
    const response = await fetchApi('/api/hypit/capabilities')
    const body = (await response.json()) as { success: boolean; data?: { enabled?: boolean } }
    enabled.value = response.ok && body.success && body.data?.enabled === true
  } catch {
    enabled.value = false
  } finally {
    checking.value = false
  }
})

function enter(): void {
  void router.push({ name: 'video-clone' })
}
</script>

<style scoped>
.clone-entry {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.clone-entry-copy h3 {
  margin: 0 0 var(--space-xxs);
  font-family: var(--font-display);
  font-size: var(--type-subheading);
  font-weight: var(--weight-heading);
}

.clone-entry-copy p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.clone-entry-state {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.clone-entry-go {
  min-height: var(--control-height);
}
</style>
