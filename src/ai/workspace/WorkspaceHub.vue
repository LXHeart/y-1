<template>
  <section class="workspace-home" :aria-labelledby="`${kind}-title`">
    <header><p class="section-kicker">{{ kind === 'videos' ? '视频创作' : '独立处理素材' }}</p><h2 :id="`${kind}-title`">{{ kind === 'videos' ? '选择视频制作方式' : '工具箱' }}</h2><p class="gl-hint">{{ kind === 'videos' ? '按创作起点选择；进入项目后继续编辑、生成和导出。' : '完成一个小任务，也可以在创作项目中使用这些能力。' }}</p></header>
    <p v-if="retired" class="gl-alert" role="status">实时对话数字人已退役。数字人口播将用于主播照稿出镜，目前尚未接入主播视频生成服务。</p>
    <div class="workspace-entry-grid">
      <RouterLink v-for="item in entries" :key="item.title" :to="item.to" class="gl-zone workspace-entry"><h3>{{ item.title }}</h3><p>{{ item.description }}</p><span class="workspace-entry-action">{{ item.action }} →</span></RouterLink>
    </div>
    <p v-if="kind === 'videos'" class="gl-hint">数字人口播：平台主播讲稿＋商品画面穿插。主播生成能力尚未开放；现有素材成片可先制作配音视频。</p>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
const props = defineProps<{ kind: 'videos' | 'tools'; retired?: boolean }>()
const entries = computed(() => props.kind === 'videos' ? [
  { title: '素材成片', description: '从图片、实拍视频或脚本开始，编辑分镜、旁白并合成视频。', action: '开始制作', to: { name: 'video-production' } },
  { title: '参考视频复刻', description: '分析参考视频，确认自己的内容与素材，再按方案制作。', action: '打开复刻项目', to: { name: 'video-clone' } },
] : [
  { title: '语音转写', description: '上传音频，整理成可继续编辑的文字。', action: '转写音频', to: { name: 'tools', query: { tab: 'speech' } } },
  { title: '图片处理', description: '裁剪、滤镜、背景与已有图片调整。', action: '编辑图片', to: { name: 'images', query: { tab: 'edit' } } },
  { title: '字幕与视频辅助', description: '编辑字幕、制作封面，查看剪辑结构与配乐建议。', action: '打开工具', to: { name: 'tools', query: { tab: 'video' } } },
])
</script>
