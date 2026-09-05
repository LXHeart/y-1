<template>
  <section class="ai-governance" aria-label="AI 与治理">
    <ModelSourceCard @changed="refreshOrgScopes" />

    <!-- platform 态：计费说明 + 个人预算卡（自 runs 板块迁入，任务书 #78 卡 C） -->
    <template v-if="loaded && modelSource === 'platform'">
      <p class="governance-note">平台模式：生成按积分计费、超个人预算硬停；内容安全深检、内容修复等平台免费能力全开。</p>
      <PersonalAiBudgetCard />
    </template>

    <!-- own 态：个人密钥面板（改造版，无 per-capability 开关）+ 能力可用性 -->
    <template v-else-if="loaded">
      <AiProviderKeysPanel />
    </template>

    <!-- 商家主体治理节（任务书 #78 卡 C，D2 受控放宽）：壳拉组织范围过滤 owner/admin，
         板块内主体下拉，无全局身份切换；无角色整节不渲染。后端仍有权威 403 门禁。 -->
    <section v-if="managedOrgs.length > 0" class="governance-orgs" aria-label="商家主体治理">
      <h3>商家主体治理</h3>
      <p class="governance-note">组织级 AI 预算、主体模型密钥与创作审计——按主体管理；个人模型来源开关只管无组织上下文的自由创作，不影响组织链。</p>
      <label class="governance-org-select">选择主体
        <select v-model="selectedOrgId" aria-label="商家主体">
          <option v-for="org in managedOrgs" :key="org.organizationId" :value="org.organizationId">
            {{ org.organizationName }}
          </option>
        </select>
      </label>
      <template v-if="selectedOrgId">
        <AiOrgBudgetPanel :organization-id="selectedOrgId" />
        <AiOrgProviderKeysPanel :organization-id="selectedOrgId" />
        <article class="governance-audit">
          <h3>主体创作审计</h3>
          <OrgCreationAuditPanel :organization-id="selectedOrgId" />
        </article>
      </template>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import ModelSourceCard from './ModelSourceCard.vue'
import AiProviderKeysPanel from '../../../components/AiProviderKeysPanel.vue'
import PersonalAiBudgetCard from '../../../components/PersonalAiBudgetCard.vue'
import AiOrgBudgetPanel from '../../../components/AiOrgBudgetPanel.vue'
import AiOrgProviderKeysPanel from '../../../components/AiOrgProviderKeysPanel.vue'
import OrgCreationAuditPanel from '../../../components/OrgCreationAuditPanel.vue'
import { useGrassland } from '../../../composables/useGrassland'
import { useModelSource } from '../../../composables/useModelSource'
import type { OrganizationAccessScope } from '../../../types/grassland'

/**
 * AI 与治理板块（任务书 #78 卡 C）：模型来源开关（platform→预算卡 / own→密钥面板）
 * + 商家主体治理三卡（组织预算/组织密钥/主体创作审计，零改动复用）。
 */
const grassland = useGrassland()
const { modelSource, loaded } = useModelSource()

const managedOrgs = ref<OrganizationAccessScope[]>([])
const selectedOrgId = ref('')

async function refreshOrgScopes(): Promise<void> {
  const scopes = await grassland.listMyOrganizationScopes()
  if (!scopes) return
  managedOrgs.value = scopes.filter((scope) => scope.role === 'owner' || scope.role === 'admin')
}

watch(managedOrgs, (orgs) => {
  // 主体清单变化（首次载入 / 切账号后重拉）时保持选中项有效，否则回落第一个。
  if (!orgs.some((org) => org.organizationId === selectedOrgId.value)) {
    selectedOrgId.value = orgs[0]?.organizationId ?? ''
  }
})

onMounted(() => { void refreshOrgScopes() })
</script>

<style scoped>
.ai-governance { display: grid; gap: var(--space-md); }
.governance-note { margin: 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.governance-orgs { display: grid; gap: var(--space-sm); padding-top: var(--space-md); border-top: 1px solid var(--color-border); }
.governance-orgs h3 { margin: 0; font-size: var(--text-lg); }
.governance-org-select { display: flex; align-items: center; gap: var(--space-xs); color: var(--color-text-secondary); font-size: var(--text-sm); }
.governance-org-select select { padding: var(--space-xs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.governance-audit { display: grid; gap: var(--space-sm); }
.governance-audit h3 { margin: 0; font-size: var(--text-lg); }
</style>
