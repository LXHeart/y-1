<template>
  <section class="gl-zone workspace-home" aria-labelledby="reference-projects-title">
    <header><h2 id="reference-projects-title">参考视频项目</h2><p class="gl-hint">继续已有工程，或从参考视频开始新作品。</p></header>
    <p v-if="!authenticated" class="gl-hint">登录后查看你的项目。<button class="gl-btn-secondary" @click="emit('request-login')">登录</button></p>
    <template v-else>
      <p v-if="loading" role="status">正在读取项目…</p>
      <p v-else-if="error" class="gl-alert" role="alert">{{ error.message }} <button class="gl-btn-secondary" @click="refresh">重试</button></p>
      <p v-else-if="!projects.length" class="gl-hint">还没有参考视频项目。</p>
      <div class="workspace-entry-grid">
        <RouterLink v-for="project in projects" :key="project.id" class="gl-zone workspace-entry" :to="{ name: 'video-clone-project', params: { projectId: project.id } }"><h3>{{ project.title }}</h3><span class="workspace-entry-action">继续编辑 →</span></RouterLink>
      </div>
      <button v-if="nextCursor" class="gl-btn-secondary" :disabled="loadingMore" @click="loadMore">{{ loadingMore ? '加载中…' : '加载更多' }}</button>
      <RouterLink class="gl-btn-primary" :to="{ name: 'video-clone' }">新建参考视频项目</RouterLink>
    </template>
  </section>
</template>
<script setup lang="ts">
import { watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useHypitProjects } from '../../views/video-clone/composables/useHypitProjects'
const props = defineProps<{ authenticated: boolean }>()
const emit = defineEmits<{ 'request-login': [] }>()
const { projects, nextCursor, loading, loadingMore, error, refresh, loadMore } = useHypitProjects()
watch(() => props.authenticated, value => { if (value) void refresh() }, { immediate: true })
</script>
