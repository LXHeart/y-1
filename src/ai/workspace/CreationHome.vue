<template>
  <section class="workspace-home workspace-creation-home">
    <div class="workspace-home-layout">
      <!-- 主区：行动。登录用户先回到未完成的创作，再选新起点。 -->
      <div class="workspace-home-main">
        <CreationHomeHero v-model:topic="topic" @start-workflow="emit('start-workflow', $event)" />
        <CreationHomeProjects v-if="authenticated" />
        <CreationPlatformMatrix :topic="topic" @start-workflow="emit('start-workflow', $event)" />
        <section class="workspace-start" aria-labelledby="workspace-start-title">
          <div class="workspace-section-head">
            <h2 id="workspace-start-title">其它起点</h2>
            <p class="gl-hint">不从平台出发时，从这儿进</p>
          </div>
          <div class="workspace-entry-grid">
            <RouterLink v-for="entry in START_ENTRIES" :key="entry.title" :to="entry.to" class="gl-zone workspace-entry">
              <h3>{{ entry.title }}</h3>
              <p>{{ entry.description }}</p>
              <span class="workspace-entry-action">{{ entry.action }} →</span>
            </RouterLink>
          </div>
        </section>
      </div>
      <!-- 侧栏：灵感与用量（热点多榜单 + 积分 + 最近素材） -->
      <aside class="workspace-home-rail" aria-label="灵感与用量">
        <CreationHotTopics :preview-count="3" />
        <CreationHomeRail :authenticated="Boolean(authenticated)" @request-login="emit('request-login')" />
      </aside>
    </div>
  </section>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import type { CreationHandoff } from '../../types/ai-creation'
import CreationHomeHero from './CreationHomeHero.vue'
import CreationHomeProjects from './CreationHomeProjects.vue'
import CreationPlatformMatrix from './CreationPlatformMatrix.vue'
import CreationHotTopics from './CreationHotTopics.vue'
import CreationHomeRail from './CreationHomeRail.vue'

/**
 * AI 创作中心首页（2026-10 重设计，docs/原型/ai-creation-home-redesign.html）：
 * 「九张平台卡」改为「一句话主输入 + 内容形式 × 平台 二维选择 + 侧栏灵感/用量」。
 * 本组件只做装配与一句话主题共享（平台卡与主行动共用同一份主题）。
 */
defineProps<{ authenticated?: boolean }>()
const emit = defineEmits<{ 'start-workflow': [handoff: CreationHandoff]; 'request-login': [] }>()

const topic = ref('')

/** 其它创作起点：与工具箱/工作台入口同一落点（navigation.ts 的 SECTION_DESTINATIONS）。 */
const START_ENTRIES: ReadonlyArray<{ title: string; description: string; action: string; to: RouteLocationRaw }> = [
  { title: '从素材开始', description: '已有图片、视频或文档，先上传再决定做什么。', action: '打开素材库', to: { name: 'assets' } },
  { title: '参考视频复刻', description: '分析参考视频，确认自己的内容与素材再制作。', action: '打开复刻项目', to: { name: 'video-clone' } },
  { title: '语音转写', description: '上传音频，整理成可继续编辑的文字。', action: '转写音频', to: { name: 'tools', query: { tab: 'speech' } } },
  { title: '图片处理', description: '裁剪、滤镜、背景与已有图片调整。', action: '编辑图片', to: { name: 'images', query: { tab: 'edit' } } },
]
</script>
