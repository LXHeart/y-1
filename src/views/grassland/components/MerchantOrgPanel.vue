<script setup lang="ts">
import { defineAsyncComponent, inject } from 'vue'
import OrgIdentityStrip from './OrgIdentityStrip.vue'
import OrgOverviewGrid, { type OrgSection } from './OrgOverviewGrid.vue'
import { ORG_SECTIONS } from '../workbench-tabs'
import { WORKBENCH_TASKS_CTX } from '../workbench-keys'
import type {
  OrgBrandSummary,
  OrgKybSummary,
  OrgPermissionSummary,
  OrgTeamSummary,
} from '../../../types/grassland'

// 任务书 #91 W5 增补（v1.1 拍板）：自 GrasslandWorkbench.vue 迁入；异步分包语义保持。
const MerchantKybCard = defineAsyncComponent(() => import('../../../components/MerchantKybCard.vue'))
const StoreStaffCard = defineAsyncComponent(() => import('../../../components/StoreStaffCard.vue'))
const OrgTeamCard = defineAsyncComponent(() => import('../../../components/OrgTeamCard.vue'))
const OrganizationBrandCard = defineAsyncComponent(() => import('../../../components/OrganizationBrandCard.vue'))
const MerchantPermissionCard = defineAsyncComponent(() => import('../../../components/MerchantPermissionCard.vue'))

/**
 * 商家「商家主体与门店」面板（任务书 #91 W5 增补自 GrasslandWorkbench.vue 模板整段迁入，纯搬运；
 * 身份条/五分节 v-show 语义原样，v-show 由父层挂在本组件标签上）。D-03：经 WORKBENCH_TASKS_CTX
 * 注入六域实例与关键 refs（子组件禁止自行 useGrassland()）。
 */
const ctx = inject(WORKBENCH_TASKS_CTX)!

const { grassland } = ctx
const {
  orgs, stores, activeOrgId, activeOrg,
  activeOrgHasOrganizationAccess, activeOrgStoreOnlyView, activeOrganizationRole,
  managerStoreScopes, canPublishBounty,
  pendingRename, renaming, requestRename, changeOrganization,
  loadOrganizations, loadActiveOrganizationStores,
} = ctx.session
const { orgSection } = ctx
const {
  team: teamSummary, brand: brandSummary, kyb: kybSummary, permission: permissionSummary,
} = ctx.summaries

/**
 * 身份条把新 orgId 直接抛上来（原先是 select 的 v-model + @change 两步）。
 * `changeOrganization` 读的是 `activeOrgId`，所以先写值再调它。
 */
async function selectOrganization(orgId: string): Promise<void> {
  if (orgId === activeOrgId.value) return
  activeOrgId.value = orgId
  await changeOrganization()
}
</script>

<template>
  <section class="gl-zone" aria-label="商家主体与门店">
    <div class="gl-zone-head">
      <h3 class="gl-zone-title">商家主体与门店</h3>
      <p class="gl-zone-note">商家主体、成员、品牌与认证资料</p>
    </div>

    <!-- 身份条：取代原「我的商家主体」磁贴；id 保留给通知锚点 -->
    <div id="gl-organizations">
      <OrgIdentityStrip
        :orgs="orgs"
        :active-org-id="activeOrgId"
        :active-org="activeOrg"
        :has-organization-access="activeOrgHasOrganizationAccess"
        :can-publish-bounty="canPublishBounty"
        :can-rename="Boolean(activeOrg) && activeOrgHasOrganizationAccess"
        :pending-rename="pendingRename"
        :renaming="renaming"
        :loading="grassland.loading.value"
        :kyb="kybSummary"
        :permission="permissionSummary"
        :team="teamSummary"
        @change-org="selectOrganization"
        @rename="requestRename"
      />
    </div>

    <!-- 门店工作台（#52 决策 H）：挂店 member（店长）视图——组织角色为 member 且有门店范围。
         #52 后建号一律入池，店长也有组织身份，故不再以「无组织身份」判流。 -->
    <div
      v-if="activeOrgStoreOnlyView"
      class="gl-zone-body"
    >
      <article
        v-if="activeOrg && managerStoreScopes.some((scope) => scope.organizationId === activeOrgId)"
        class="gl-tile gl-tile-wide"
      >
        <MerchantKybCard
          :org-id="activeOrgId"
          store-only
          :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
          @changed="() => loadOrganizations()"
        />
      </article>
      <article class="gl-tile gl-tile-wide">
        <StoreStaffCard :org-id="activeOrgId" :stores="stores" />
      </article>
    </div>

    <div v-else-if="activeOrg" class="org-split">
      <!-- 左栏：竖向分节（与一级横向 pill 正交，避免两行横导航叠着） -->
      <nav class="org-rail" role="tablist" aria-label="商家主体分节">
        <button
          v-for="section in ORG_SECTIONS"
          :key="section.id"
          type="button"
          role="tab"
          class="org-rail-item"
          :class="{ 'org-rail-active': orgSection === section.id }"
          :aria-selected="orgSection === section.id"
          :tabindex="orgSection === section.id ? 0 : -1"
          @click="orgSection = section.id"
        >{{ section.label }}</button>
      </nav>

      <!-- 右栏：分节内容。v-show 常驻 —— 子卡保持挂载，摘要才能在概览就绪 -->
      <div class="org-panel">
        <div v-show="orgSection === 'overview'" class="org-panel-section">
          <OrgOverviewGrid
            :kyb="kybSummary"
            :permission="permissionSummary"
            :team="teamSummary"
            :brand="brandSummary"
            @open="(section: OrgSection) => orgSection = section"
          />
        </div>

        <!-- 成员与门店：Slice 2F/2G/2J 的三级权限自助管理 -->
        <div v-show="orgSection === 'team'" class="org-panel-section">
          <OrgTeamCard
            :org-id="activeOrg.id"
            @stores-changed="loadActiveOrganizationStores"
            @summary="(summary: OrgTeamSummary) => teamSummary = summary"
          />
        </div>

        <!-- 组织品牌资料（#32）：独立于门店资料（KYB 卡的门店 tab）；member 只读，owner/admin 可编辑 -->
        <div v-show="orgSection === 'brand'" class="org-panel-section">
          <OrganizationBrandCard
            :org-id="activeOrg.id"
            :role="activeOrganizationRole"
            @summary="(summary: OrgBrandSummary) => brandSummary = summary"
          />
        </div>

        <!-- KYB 商家资料：GL-P3-MERCHANT-001 -->
        <div v-show="orgSection === 'kyb'" class="org-panel-section">
          <MerchantKybCard
            :org-id="activeOrg.id"
            :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
            @changed="() => loadOrganizations()"
            @summary="(summary: OrgKybSummary) => kybSummary = summary"
          />
        </div>

        <!-- 权限与额度：D-05 的商家侧入口（升级申请 / 申诉 / 额度已用-上限） -->
        <div v-show="orgSection === 'permission'" class="org-panel-section">
          <MerchantPermissionCard
            :org-id="activeOrg.id"
            :tier="activeOrg.permissionTier"
            :industry="activeOrg.industry"
            @changed="loadOrganizations"
            @summary="(summary: OrgPermissionSummary) => permissionSummary = summary"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ---------- 商家主体屏：左栏分节 + 右栏内容（自 SFC 随迁；finance 面板持同款副本） ---------- */
/* 竖栏与一级横向 pill 正交，避免同屏两行横导航；窄屏塌成横向滚动条 */
.org-split { display: grid; grid-template-columns: 152px minmax(0, 1fr); gap: var(--space-md); align-items: start; }
.org-rail { display: flex; flex-direction: column; gap: 2px; position: sticky; top: var(--space-md); }
.org-rail-item {
  min-height: 34px; padding: 0 var(--space-sm);
  border: none; border-left: 2px solid transparent; border-radius: var(--radius-xs);
  background: transparent; color: var(--color-text-muted);
  font-size: var(--text-sm); font-weight: 600; text-align: left; white-space: nowrap; cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}
.org-rail-item:hover { color: var(--color-text-secondary); background: var(--surface-furrow); }
.org-rail-active {
  border-left-color: var(--color-accent-2); background: var(--color-surface-highlight);
  color: var(--color-accent-2);
}
.org-panel { min-width: 0; }
.org-panel-section { min-width: 0; }

@media (max-width: 720px) {
  .org-split { grid-template-columns: minmax(0, 1fr); }
  .org-rail {
    position: static; flex-direction: row; gap: 4px;
    overflow-x: auto; scrollbar-width: none;
    padding-bottom: 2px; border-bottom: 1px solid var(--color-border);
  }
  .org-rail::-webkit-scrollbar { display: none; }
  .org-rail-item {
    border-left: none; border-bottom: 2px solid transparent; border-radius: 0;
  }
  .org-rail-active { border-bottom-color: var(--color-accent-2); background: transparent; }
}
</style>
