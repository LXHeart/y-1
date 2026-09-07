<script setup lang="ts">
import { defineAsyncComponent, inject } from 'vue'
import { FINANCE_SECTIONS } from '../workbench-tabs'
import { WORKBENCH_TASKS_CTX } from '../workbench-keys'
import { formatYuan } from '../../../lib/money'

// 任务书 #91 W5 增补（v1.1 拍板）：自 GrasslandWorkbench.vue 迁入；异步分包语义保持。
const MerchantMonthlyBillCard = defineAsyncComponent(() => import('../../../components/MerchantMonthlyBillCard.vue'))
const MerchantCommerceCard = defineAsyncComponent(() => import('../../../components/MerchantCommerceCard.vue'))
const BusinessAnalyticsPanel = defineAsyncComponent(() => import('../../../components/BusinessAnalyticsPanel.vue'))

/**
 * 商家「资金与经营」面板（任务书 #91 W5 增补自 GrasslandWorkbench.vue 模板整段迁入，纯搬运；
 * 二级分节 v-show 语义原样——#gl-wallet 通知锚点断言与组件常驻不重拉依赖它）。
 * D-03：经 WORKBENCH_TASKS_CTX 注入六域实例与关键 refs。
 */
const ctx = inject(WORKBENCH_TASKS_CTX)!

const { grassland, subTab } = ctx
const {
  activeOrgId, selectedStoreId, account, creditAmountYuan,
  activeOrgHasOrganizationAccess, activeOrgStoreOnlyView, managerStoreScopes,
  provision, credit,
} = ctx.session
const { financeSection } = ctx
const { openNewTaskForm } = ctx.drawer
</script>

<template>
  <section class="gl-zone" aria-label="资金与经营">
    <div class="gl-zone-head">
      <h3 class="gl-zone-title">资金与经营</h3>
      <p class="gl-zone-note">余额与充值、月度账单、核销订单与营收分析</p>
    </div>
    <div class="gl-zone-body">
      <div class="org-split">
        <nav class="org-rail" role="tablist" aria-label="资金与经营分节">
          <button
            v-for="section in FINANCE_SECTIONS"
            :key="section.id"
            type="button"
            role="tab"
            class="org-rail-item"
            :class="{ 'org-rail-active': financeSection === section.id }"
            :aria-selected="financeSection === section.id"
            :tabindex="financeSection === section.id ? 0 : -1"
            @click="financeSection = section.id"
          >{{ section.label }}</button>
        </nav>

        <!-- 右栏：分节内容。v-show 常驻 —— #gl-wallet 通知锚点断言 + 组件不重挂载不重拉数据 -->
        <div class="org-panel">
          <div v-show="financeSection === 'account'" class="org-panel-section">
            <!-- id 与推荐官侧钱包卡同名：两侧是 v-if/v-else，同一时刻只有一个在 DOM 里 -->
            <article v-if="(activeOrgHasOrganizationAccess && !activeOrgStoreOnlyView) || managerStoreScopes.length === 0"
              id="gl-wallet" class="gl-tile">
              <h3>资金账户</h3>
              <p class="gl-balance">余额 <strong class="gl-num">{{ account ? formatYuan(account.balanceCents) : '¥—' }}</strong></p>
              <div class="gl-row">
                <button type="button" :disabled="!activeOrgId || grassland.loading.value" @click="provision">开通账户</button>
              </div>
              <div class="gl-row">
                <input v-model.number="creditAmountYuan" aria-label="充值金额（元）" name="credit-amount" autocomplete="off" type="number" min="1" />
                <button type="button" :disabled="!account || grassland.loading.value" @click="credit">充值（sandbox）</button>
              </div>
            </article>
          </div>

          <div v-show="financeSection === 'bill'" class="org-panel-section">
            <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
              <MerchantMonthlyBillCard :organization-id="activeOrgId" />
            </article>
          </div>

          <div v-show="financeSection === 'commerce'" class="org-panel-section">
            <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
              <MerchantCommerceCard
                :organization-id="activeOrgId"
                :store-id="selectedStoreId || undefined"
                @create-promotion-task="openNewTaskForm({ commercePackageId: $event })"
                @go-tasks="subTab = 'tasks'"
              />
            </article>
          </div>

          <div v-show="financeSection === 'analytics'" class="org-panel-section">
            <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
              <BusinessAnalyticsPanel :organization-id="activeOrgId" :store-id="selectedStoreId" />
            </article>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* 二级分栏原语（与 MerchantOrgPanel 同款副本——scoped 不跨组件，D-10 双份随迁） */
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

/* 资金账户：余额走台账等宽大字（自 SFC 随迁） */
.gl-balance { margin: 0; font-size: var(--text-sm); color: var(--color-text-secondary); }
.gl-balance strong {
  display: block; margin-top: 2px; font-size: var(--text-xl); font-weight: 700;
  color: var(--color-text); letter-spacing: -0.01em;
}
</style>
