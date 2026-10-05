<template>
  <section class="workspace-home" aria-labelledby="workspace-home-title">
    <header><p class="section-kicker">从内容到作品</p><h2 id="workspace-home-title">今天想创作什么？</h2><p class="gl-hint">从想法、已有稿件或素材开始，随时回到项目继续编辑。</p></header>
    <div class="workspace-entry-grid">
      <RouterLink v-for="item in entries" :key="item.name" :to="{ name: item.name }" class="gl-zone workspace-entry">
        <component :is="item.icon" :size="24" aria-hidden="true" />
        <h3>{{ item.title }}</h3><p>{{ item.description }}</p><span class="workspace-entry-action">{{ item.action }} →</span>
      </RouterLink>
    </div>
    <section class="gl-zone workspace-next" aria-label="继续创作">
      <div><h3>接着上次的作品</h3><p class="gl-hint">稿件、图片与视频项目，从保存的位置继续。</p></div>
      <RouterLink class="gl-btn-secondary" :to="{ name: 'projects' }">打开我的项目</RouterLink>
    </section>
    <p class="gl-hint">还没有方向？<RouterLink :to="{ name: 'assistant' }">和创作助手聊一聊</RouterLink>，也可以先到<RouterLink :to="{ name: 'assets' }">素材库</RouterLink>整理素材。</p>
  </section>
</template>
<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { FileText, Image, Film } from '@lucide/vue'
const entries = [
  { name: 'write', title: '文案与图文', description: '写文案、文章、评价和脚本，整理成适合发布的图文。', action: '开始写作', icon: FileText },
  { name: 'images', title: '图片', description: '生成新图片，或裁剪、调整已有图片，制作封面。', action: '制作图片', icon: Image },
  { name: 'videos', title: '视频', description: '用自己的素材制作视频，或参考已有视频的结构创作。', action: '选择制作方式', icon: Film },
]
</script>
