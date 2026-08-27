<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  MerchantProfileStatus,
  OrgKybSummary,
  OrgPermissionSummary,
  OrgTeamSummary,
  Organization,
  OrganizationRenameRequest,
  PermissionTier,
} from '../../../types/grassland'

/**
 * 商家主体身份条——「商家主体与门店」屏的常驻页眉。
 *
 * 取代原先那张「我的商家主体」磁贴：原实现把主体名、等级、更名入口堆成一张全宽卡，
 * 而认证状态、额度余量、成员/门店数分散在下面 4 张卡内部，要滚屏才看得到。
 * 这里把「我是谁 + 现在什么状态」压成两行——主体名与切换在第一行，
 * 认证/额度/成员/门店四项事实在第二行，其余全部下沉到分节里。
 *
 * 纯呈现：所有数据由父组件传入，所有动作以事件抛出（本组件不发请求）。
 */

const props = defineProps<{
  orgs: readonly Organization[]
  activeOrgId: string
  activeOrg: Organization | null
  /** 组织成员身份（false = 仅门店经理，隐藏组织级事实与更名入口）。 */
  hasOrganizationAccess: boolean
  canPublishBounty: boolean
  /** owner/admin 才能发起更名。 */
  canRename: boolean
  pendingRename: OrganizationRenameRequest | null
  renaming: boolean
  loading: boolean
  kyb: OrgKybSummary | null
  permission: OrgPermissionSummary | null
  team: OrgTeamSummary | null
}>()

const emit = defineEmits<{
  'change-org': [orgId: string]
  rename: [name: string]
}>()

const TIER_LABEL: Record<PermissionTier, string> = {
  draft: '草稿',
  basic_publish: '基础发布',
  finance_transaction: '资金交易',
}

const KYB_LABEL: Record<MerchantProfileStatus, string> = {
  draft: '资料草稿',
  pending: '待审核',
  under_review: '审核中',
  approved: '已认证',
  rejected: '已驳回',
}

/** 认证状态 → 语义色档；null（无资料行）按「未提交」呈现为中性。 */
const kybTone = computed<'ok' | 'warn' | 'bad' | 'muted'>(() => {
  const status = props.kyb?.merchantStatus ?? null
  if (status === 'approved') return 'ok'
  if (status === 'rejected') return 'bad'
  if (status === 'pending' || status === 'under_review') return 'warn'
  return 'muted'
})

const kybText = computed(() =>
  props.kyb?.merchantStatus ? KYB_LABEL[props.kyb.merchantStatus] : '未提交认证')

/**
 * 额度文案：区分「余 N」与「用量看不到」——用量是 best-effort，
 * 非绑定 org 时 marketplace 403，此时只报上限（谎报「余 0」会让人以为发不了任务）。
 */
const quotaText = computed(() => {
  const permission = props.permission
  if (!permission) return null
  if (permission.remainingActiveTasks === null) {
    return permission.maxActiveTasks > 0 ? `活跃上限 ${permission.maxActiveTasks}` : null
  }
  return `活跃余 ${permission.remainingActiveTasks} / ${permission.maxActiveTasks}`
})

// ---------- 更名（收在「⋯」里，不占常驻位） ----------

const menuOpen = ref(false)
const renameOpen = ref(false)
/**
 * 更名输入用**本组件自己的 ref**：父组件的 `newOrgName` 同时绑在「创建主体」输入上，
 * 共用会让两个表单互相串字。
 */
const renameInput = ref('')

function openRename(): void {
  menuOpen.value = false
  renameOpen.value = true
  renameInput.value = ''
}

function submitRename(): void {
  const name = renameInput.value.trim()
  if (!name) return
  emit('rename', name)
  renameOpen.value = false
  renameInput.value = ''
}
</script>

<template>
  <div class="org-strip">
    <div class="org-strip-main">
      <div class="org-strip-identity">
        <!-- 单主体（产品常态）直接呈现名称；多条（组织成员 + 他处门店经理）才给切换器 -->
        <select
          v-if="orgs.length > 1"
          class="org-strip-select"
          :value="activeOrgId"
          aria-label="所属商家主体"
          name="organization"
          @change="emit('change-org', ($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>选择商家主体</option>
          <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}</option>
        </select>
        <h3 v-else class="org-strip-name">{{ activeOrg?.name || '未选择商家主体' }}</h3>

        <span v-if="activeOrg" class="org-strip-tier">{{ TIER_LABEL[activeOrg.permissionTier] }}</span>
      </div>

      <div v-if="canRename" class="org-strip-more">
        <button
          type="button" class="org-strip-more-btn" aria-label="主体更多操作"
          :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"
        >⋯</button>
        <div v-if="menuOpen" class="org-strip-menu">
          <button type="button" :disabled="Boolean(pendingRename)" @click="openRename">申请更名</button>
        </div>
      </div>
    </div>

    <!-- 事实行：一眼看到认证 / 额度 / 成员 / 门店，不必进分节 -->
    <div class="org-strip-facts">
      <span v-if="hasOrganizationAccess" class="org-strip-fact" :data-tone="kybTone">{{ kybText }}</span>
      <span v-if="quotaText" class="org-strip-fact">{{ quotaText }}</span>
      <span v-if="team" class="org-strip-fact">成员 {{ team.memberCount }}</span>
      <span v-if="team" class="org-strip-fact">门店 {{ team.storeCount }}</span>
      <span v-if="!hasOrganizationAccess" class="org-strip-fact" data-tone="muted">仅门店经理权限</span>
      <span v-else-if="!canPublishBounty" class="org-strip-fact" data-tone="warn">非 finance_transaction 等级不可发布赏金任务</span>
    </div>

    <p v-if="pendingRename" class="org-strip-note">
      更名审核中：「{{ activeOrg?.name }}」→「{{ pendingRename.requestedName }}」，等待平台审核通过后生效。
    </p>

    <div v-if="renameOpen" class="org-strip-rename">
      <input
        v-model="renameInput"
        aria-label="新主体名称"
        name="org-rename-name"
        autocomplete="off"
        placeholder="新主体名称（经平台审核后生效，30 天内只能改一次）"
        @keyup.enter="submitRename"
      />
      <button type="button" :disabled="renaming || loading" @click="submitRename">提交更名申请</button>
      <button type="button" class="quiet" @click="renameOpen = false">取消</button>
    </div>
  </div>
</template>

<style scoped>
.org-strip {
  display: flex; flex-direction: column; gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border-accent); border-radius: var(--radius-md);
  background: var(--color-surface-highlight);
}

.org-strip-main { display: flex; align-items: center; gap: var(--space-sm); }
.org-strip-identity { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; min-width: 0; flex: 1; }
.org-strip-name {
  margin: 0; font-size: var(--text-lg); font-weight: 700; letter-spacing: -0.01em;
  color: var(--color-text); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.org-strip-select {
  min-height: 32px; max-width: 260px; padding: 4px var(--space-xs);
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  background: var(--color-surface); color: var(--color-text);
  font-size: var(--text-base); font-weight: 600;
}
.org-strip-tier {
  padding: 2px var(--space-xs); border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent-2); font-size: var(--text-xs); font-weight: 600; white-space: nowrap;
}

/* 事实行：micro 文本 + 圆点分隔，不用边框免得和下面的分节卡撞层级 */
.org-strip-facts { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.org-strip-fact {
  font-size: var(--text-xs); color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}
.org-strip-fact + .org-strip-fact::before {
  content: "·"; margin-right: var(--space-sm); color: var(--color-text-muted);
}
.org-strip-fact[data-tone="ok"] { color: var(--color-success); font-weight: 600; }
.org-strip-fact[data-tone="warn"] { color: var(--color-warning); font-weight: 600; }
.org-strip-fact[data-tone="bad"] { color: var(--color-danger); font-weight: 600; }
.org-strip-fact[data-tone="muted"] { color: var(--color-text-muted); }

.org-strip-more { position: relative; flex: 0 0 auto; }
.org-strip-more-btn {
  min-height: 28px; min-width: 28px; padding: 0 6px;
  border: 1px solid transparent; border-radius: var(--radius-sm);
  background: transparent; color: var(--color-text-muted);
  font-size: var(--text-base); line-height: 1; cursor: pointer;
}
.org-strip-more-btn:hover { border-color: var(--color-border); color: var(--color-text-secondary); }
.org-strip-menu {
  position: absolute; right: 0; top: calc(100% + 4px); z-index: 2;
  padding: 4px; border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  background: var(--color-surface); box-shadow: var(--shadow-elevated); min-width: 132px;
}
.org-strip-menu button {
  width: 100%; min-height: 30px; padding: 0 var(--space-xs);
  border: none; border-radius: var(--radius-xs); background: transparent;
  color: var(--color-text); font-size: var(--text-sm); text-align: left; cursor: pointer;
}
.org-strip-menu button:hover:not(:disabled) { background: var(--surface-furrow); }
.org-strip-menu button:disabled { color: var(--color-text-muted); cursor: not-allowed; }

.org-strip-note { margin: 0; font-size: var(--text-xs); color: var(--color-warning); }
.org-strip-rename { display: flex; gap: var(--space-xs); align-items: center; flex-wrap: wrap; }
.org-strip-rename input { flex: 1 1 280px; min-width: 0; }
</style>
