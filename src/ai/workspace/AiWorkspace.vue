<template>
  <div class="workspace-page">
    <nav v-if="tabs.length" class="workspace-subnav" aria-label="当前工作区">
      <RouterLink v-for="tab in tabs" :key="tab.label" :to="tab.to" :class="{ 'is-current': tab.active }" :aria-current="tab.active ? 'page' : undefined">{{ tab.label }}</RouterLink>
    </nav>
    <CreationHome v-if="showHome" :authenticated="authenticated" @start-workflow="emit('start-workflow', $event)" @request-login="emit('request-login')" />
    <WorkspaceHub v-else-if="route.name === 'videos' || (route.name === 'tools' && !section)" :kind="route.name === 'videos' ? 'videos' : 'tools'" :retired="route.query.retired === 'realtime'" />
    <KeepAlive>
      <AiCreationCenter v-if="section" :authenticated="authenticated" :entry="workspaceEntry" mode="personal" :section="section" :show-navigation="false" :writing-only="route.name === 'write' && !workspaceEntry"
        @section-change="onSectionChange" @start-workflow="emit('start-workflow', $event)" @request-login="emit('request-login')" @open-grassland="emit('open-grassland')" />
    </KeepAlive>
    <VoiceChatView v-if="route.name === 'assistant' && !section" @request-login="emit('request-login')" />
    <ReferenceProjects v-if="route.name === 'projects' && route.query.tab === 'reference'" :authenticated="authenticated" @request-login="emit('request-login')" />
  </div>
</template>
<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { CreationEntry, CreationHandoff } from '../../types/ai-creation'
import type { AiCenterSection } from '../../views/ai-center/components/AiCenterNavigation.vue'
import { SECTION_DESTINATIONS, workspaceSection } from './navigation'
import { useWorkspaceEntry } from './useWorkspaceEntry'
import CreationHome from './CreationHome.vue'
import WorkspaceHub from './WorkspaceHub.vue'
const ReferenceProjects = defineAsyncComponent(() => import('./ReferenceProjects.vue'))
const AiCreationCenter = defineAsyncComponent(() => import('../../views/ai-center/AiCreationCenter.vue'))
const VoiceChatView = defineAsyncComponent(() => import('../../views/voice-chat/VoiceChatView.vue'))
const props = defineProps<{ authenticated: boolean; entry: CreationEntry | null }>()
const emit = defineEmits<{ 'start-workflow': [handoff: CreationHandoff]; 'request-login': []; 'open-grassland': [] }>()
const route = useRoute()
const router = useRouter()
const queryEntry = useWorkspaceEntry(route)
const workspaceEntry = computed(() => queryEntry.value ?? props.entry)
const legacySection = computed(() => typeof route.query.section === 'string' && Object.prototype.hasOwnProperty.call(SECTION_DESTINATIONS, route.query.section) ? route.query.section as AiCenterSection : null)
const sourceEntry = computed(() => workspaceEntry.value || ['entry', 'capability', 'taskId', 'storeId', 'draft'].some(key => route.query[key]))
const showHome = computed(() => route.name === 'create' && !sourceEntry.value && !legacySection.value)
const section = computed(() => route.name === 'create' ? legacySection.value ?? (sourceEntry.value ? 'create' : null) : workspaceSection(route.name, route.query.tab))
const tabs = computed(() => {
  const name = String(route.name)
  const tab = route.query.tab
  const choices = name === 'images' ? [['generate', '生成图片'], ['edit', '编辑图片']]
    : name === 'assistant' ? [['chat', '聊想法'], ['review', '草稿与内容检查']]
    : name === 'projects' ? [['drafts', '创作项目'], ['reference', '参考视频项目'], ['runs', '生成记录']]
    : name === 'tools' && section.value ? [['home', '全部工具'], ['speech', '语音转写'], ['video', '字幕与视频辅助']] : []
  return choices.map(([id, label], index) => ({ label, active: tab === id || (!tab && index === 0), to: { name, query: id === 'home' ? {} : { tab: id } } }))
})
function onSectionChange(next: AiCenterSection) {
  if (!section.value || next === section.value) return
  void router.push({ ...SECTION_DESTINATIONS[next] as object, query: { ...route.query, ...(SECTION_DESTINATIONS[next] as { query?: object }).query, section: undefined } })
}
</script>
