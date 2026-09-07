<template>
  <section class="admin-view gl-field">
    <aside ref="navigationSidebar" class="admin-sidebar" :class="{ 'admin-sidebar-open': navigationOpen }"
      :role="navigationOpen ? 'dialog' : undefined" :aria-modal="navigationOpen || undefined" aria-label="管理导航">
      <div class="admin-sidebar-heading">
        <span>工作空间</span>
        <button class="ops-icon-button admin-close-nav" type="button" aria-label="收起管理导航"
          title="收起管理导航" @click="closeNavigation"><X :size="18" aria-hidden="true" /></button>
      </div>
      <nav class="admin-groups" aria-label="管理分组">
        <div v-for="group in visibleGroups" :key="group.id" class="admin-nav-group">
          <button type="button" class="admin-group-pill" :class="{ active: activeGroup === group.id }"
            :aria-expanded="activeGroup === group.id" :aria-controls="activeGroup === group.id ? `admin-links-${group.id}` : undefined"
            :data-testid="`admin-group-${group.id}`" @click="selectGroup(group.id)">
            <component :is="GROUP_ICONS[group.id]" :size="18" aria-hidden="true" />
            <span>{{ group.label }}</span>
            <ChevronDown :size="14" class="admin-group-chevron" aria-hidden="true" />
          </button>
          <div v-if="activeGroup === group.id" :id="`admin-links-${group.id}`" class="admin-tabs"
            role="tablist" aria-orientation="vertical" :aria-label="`${group.label}页签`" @keydown="handleTabKeydown">
            <button v-for="tab in visibleTabs" :id="`admin-link-${tab.key}`" :key="tab.key" type="button" role="tab"
              :aria-selected="activeSection === tab.key" :class="{ active: activeSection === tab.key }"
              :tabindex="activeSection === tab.key ? 0 : -1" aria-controls="admin-panel"
              :data-testid="`admin-tab-${tab.key}`" @click="selectTab(tab)">
              <span>{{ tab.label }}</span><span v-if="tabBadge(tab)" class="count-badge">{{ tabBadge(tab) }}</span>
            </button>
          </div>
        </div>
      </nav>
      <div class="admin-sidebar-footer"><ShieldCheck :size="16" aria-hidden="true" /><span>平台治理工作台</span></div>
    </aside>
    <button v-if="navigationOpen" class="admin-nav-backdrop" type="button" aria-label="关闭管理导航" @click="closeNavigation" />
    <div class="admin-workspace" :inert="navigationOpen || undefined">
      <header class="admin-context-bar">
        <button ref="navigationTrigger" class="ops-icon-button admin-open-nav" type="button"
          title="展开管理导航" aria-label="展开管理导航" :aria-expanded="navigationOpen" @click="navigationOpen = true">
          <PanelLeft :size="18" aria-hidden="true" />
        </button>
        <span>管理后台</span><ChevronRight :size="14" aria-hidden="true" />
        <span>{{ activeGroupDef?.label }}</span><ChevronRight :size="14" aria-hidden="true" />
        <strong>{{ activeTabDef?.label }}</strong>
      </header>
      <div class="admin-workspace-body">
        <header class="admin-section-header">
          <h2 class="admin-section-title">{{ activeTabDef?.label }}</h2>
          <span v-if="activeGroup === 'review'" class="badge badge-neutral">审核工作区</span>
        </header>
        <!-- 只缓存六个有本地编辑状态的面板；其余面板切走卸载，避免轮询常驻。 -->
        <div v-if="activeTabDef?.component" id="admin-panel" class="admin-panel" role="tabpanel" :aria-labelledby="`admin-link-${activeSection}`">
          <KeepAlive :include="ADMIN_STATEFUL_TABS">
            <component :is="activeTabDef.component" v-bind="activeTabDef.componentProps" />
          </KeepAlive>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { provide, ref } from 'vue'
import { ClipboardCheck, Users, WalletCards, Shapes, ShieldCheck, ChevronDown, ChevronRight, PanelLeft, X } from '@lucide/vue'
import { ADMIN_BADGE_BRIDGE, type AdminSection, type AdminTabDef } from './adminTabs'
import { useAdminUrlState } from './composables/useAdminUrlState'
import { useAdminNavigation } from './composables/useAdminNavigation'

const GROUP_ICONS = { review: ClipboardCheck, 'users-org': Users, finance: WalletCards, 'content-ai': Shapes, 'risk-audit': ShieldCheck }
const ADMIN_STATEFUL_TABS = ['AdminUsersPanel', 'AdminKybPanel', 'AdminReviewTasksPanel', 'AdminFinancePanel', 'AdminRecommendersPanel', 'AdminAiModelsPanel']
const { activeSection, activeTabDef, activeGroup, activeGroupDef, visibleGroups, visibleTabs, selectTab: activateTab, selectGroup } = useAdminUrlState()
const { navigationOpen, navigationTrigger, navigationSidebar, closeNavigation, handleTabKeydown } = useAdminNavigation(activeSection, visibleTabs, activateTab)
function selectTab(tab: AdminTabDef): void {
  activateTab(tab)
  closeNavigation()
}

// 替换 Map 触发导航重渲染，使徽标渲染追踪各面板的响应式数据。
const badgeControllers = ref(new Map<AdminSection, () => string | number>())
provide(ADMIN_BADGE_BRIDGE, {
  register: (key, source) => { badgeControllers.value = new Map(badgeControllers.value).set(key, source) },
  unregister: (key) => {
    const next = new Map(badgeControllers.value)
    next.delete(key)
    badgeControllers.value = next
  },
})
function tabBadge(tab: AdminTabDef): string | number | undefined {
  return badgeControllers.value.get(tab.key)?.()
}
</script>
