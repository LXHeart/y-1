<script setup lang="ts">
import { ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { Membership, MembershipRole, Store, StoreMembership, StoreRole } from '../types/grassland'

/**
 * 组织成员 + 门店 + 门店成员管理（Slice 2F / 2G / 2J 的前端）。
 *
 * 后端三级权限早已建好且全测通，但此前零 UI——多门店、多成员的商家没法自助管理。
 *
 * 授权分档（后端口径，UI 只做提示，真正门禁在服务端）：
 * - 组织成员增删：org **OWNER**（且不能经此端点授予 owner）
 * - 建门店：org **ADMIN+**
 * - 门店列表：org MEMBER+；门店成员列表：门店 STAFF+
 * - 任命门店 manager：org **ADMIN+**；加 staff：**门店 MANAGER+**（店长可自管本店员工）
 * - 守卫：移除最后一个 owner / 唯一 manager 均 409
 */

const props = defineProps<{ orgId: string }>()

const grassland = useGrassland()

const members = ref<Membership[]>([])
const stores = ref<Store[]>([])
const selectedStoreId = ref('')
const storeMembers = ref<StoreMembership[]>([])
const notice = ref('')

const newMemberAccount = ref('')
const newMemberRole = ref<'admin' | 'member'>('member')
const newStoreName = ref('')
const newStoreMemberAccount = ref('')
const newStoreMemberRole = ref<StoreRole>('staff')

const ROLE_LABEL: Record<MembershipRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
}

const STORE_ROLE_LABEL: Record<StoreRole, string> = {
  manager: '店长',
  staff: '店员',
}

async function refresh(): Promise<void> {
  if (!props.orgId) return
  selectedStoreId.value = ''
  storeMembers.value = []
  const m = await grassland.listMemberships(props.orgId)
  const s = await grassland.listStores(props.orgId)
  if (m) members.value = m
  if (s) stores.value = s
}

watch(() => props.orgId, refresh, { immediate: true })

async function reloadMembers(): Promise<void> {
  const list = await grassland.listMemberships(props.orgId)
  if (list) members.value = list
}

async function addMember(): Promise<void> {
  const accountId = newMemberAccount.value.trim()
  if (!accountId) return
  notice.value = ''
  const added = await grassland.addMembership(props.orgId, accountId, newMemberRole.value)
  if (!added) return
  newMemberAccount.value = ''
  notice.value = `已添加${ROLE_LABEL[newMemberRole.value]}`
  await reloadMembers()
}

async function removeMember(m: Membership): Promise<void> {
  notice.value = ''
  const removed = await grassland.removeMembership(props.orgId, m.accountId)
  if (removed === null) return  // 失败（如末位 owner 守卫 409）已由 error 条呈现
  notice.value = '成员已移除'
  await reloadMembers()
}

async function addStore(): Promise<void> {
  const name = newStoreName.value.trim()
  if (!name) return
  notice.value = ''
  const created = await grassland.createStore(props.orgId, name)
  if (!created) return
  newStoreName.value = ''
  notice.value = `门店「${created.name}」已创建`
  const list = await grassland.listStores(props.orgId)
  if (list) stores.value = list
}

async function selectStore(storeId: string): Promise<void> {
  selectedStoreId.value = storeId
  storeMembers.value = []
  const list = await grassland.listStoreMemberships(props.orgId, storeId)
  if (list) storeMembers.value = list
}

async function addStoreMember(): Promise<void> {
  const accountId = newStoreMemberAccount.value.trim()
  if (!accountId || !selectedStoreId.value) return
  notice.value = ''
  const added = await grassland.addStoreMembership(
    props.orgId, selectedStoreId.value, accountId, newStoreMemberRole.value)
  if (!added) return
  newStoreMemberAccount.value = ''
  notice.value = `已添加${STORE_ROLE_LABEL[newStoreMemberRole.value]}`
  await selectStore(selectedStoreId.value)
}

async function removeStoreMember(m: StoreMembership): Promise<void> {
  notice.value = ''
  const removed = await grassland.removeStoreMembership(props.orgId, selectedStoreId.value, m.accountId)
  if (removed === null) return  // 末位 manager 守卫 409 等由 error 条呈现
  notice.value = '门店成员已移除'
  await selectStore(selectedStoreId.value)
}
</script>

<template>
  <article class="team">
    <header class="team-head">
      <h3>成员与门店</h3>
      <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="team-alert team-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="team-alert team-ok">{{ notice }}</p>

    <!-- 组织成员 -->
    <section class="team-sec">
      <h4>组织成员</h4>
      <p v-if="members.length === 0" class="team-hint">暂无成员记录。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in members" :key="m.id">
            <td><code>{{ m.accountId.slice(0, 8) }}…</code></td>
            <td>{{ ROLE_LABEL[m.role] || m.role }}</td>
            <td>
              <button
                type="button" class="team-quiet"
                :disabled="grassland.loading.value"
                @click="removeMember(m)"
              >移除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="team-row">
        <input v-model="newMemberAccount" placeholder="账号 ID（UUID）" />
        <select v-model="newMemberRole">
          <option value="member">成员</option>
          <option value="admin">管理员</option>
        </select>
        <button type="button" :disabled="grassland.loading.value || !newMemberAccount.trim()" @click="addMember">
          添加成员
        </button>
      </div>
      <p class="team-hint">
        需组织所有者权限；所有者角色只能在建组织时产生，不能在此授予。
        目前只能填账号 UUID —— identity 尚无「按邮箱查人/邀请」端点。
      </p>
    </section>

    <!-- 门店 -->
    <section class="team-sec">
      <h4>门店</h4>
      <p v-if="stores.length === 0" class="team-hint">暂无门店。</p>
      <ul v-else class="team-list">
        <li v-for="s in stores" :key="s.id">
          <button
            type="button" class="team-link"
            :class="{ active: selectedStoreId === s.id }"
            @click="selectStore(s.id)"
          >{{ s.name }}</button>
          <span class="team-tag">{{ s.status }}</span>
        </li>
      </ul>

      <div class="team-row">
        <input v-model="newStoreName" placeholder="新门店名称" @keyup.enter="addStore" />
        <button type="button" :disabled="grassland.loading.value || !newStoreName.trim()" @click="addStore">
          新建门店
        </button>
      </div>
      <p class="team-hint">建门店需组织管理员及以上。点门店名可管理其成员。</p>
    </section>

    <!-- 门店成员 -->
    <section v-if="selectedStoreId" class="team-sec">
      <h4>门店成员</h4>
      <p v-if="storeMembers.length === 0" class="team-hint">该门店暂无成员。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in storeMembers" :key="m.id">
            <td><code>{{ m.accountId.slice(0, 8) }}…</code></td>
            <td>{{ STORE_ROLE_LABEL[m.role] || m.role }}</td>
            <td>
              <button
                type="button" class="team-quiet"
                :disabled="grassland.loading.value"
                @click="removeStoreMember(m)"
              >移除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="team-row">
        <input v-model="newStoreMemberAccount" placeholder="账号 ID（UUID）" />
        <select v-model="newStoreMemberRole">
          <option value="staff">店员</option>
          <option value="manager">店长</option>
        </select>
        <button
          type="button"
          :disabled="grassland.loading.value || !newStoreMemberAccount.trim()"
          @click="addStoreMember"
        >添加</button>
      </div>
      <p class="team-hint">
        加店员需本店店长及以上；任命店长需组织管理员及以上。不可移除本店唯一店长。
      </p>
    </section>
  </article>
</template>

<style scoped>
.team { border: 1px solid var(--color-border); border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.team-head { display: flex; justify-content: space-between; align-items: center; }
.team-head h3 { margin: 0; font-size: 15px; }
.team-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.team-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.team-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.team-sec { display: flex; flex-direction: column; gap: 8px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.team-sec h4 { margin: 0; font-size: 13px; }
.team-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.team-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.team-table th, .team-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.team-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.team-list li { display: flex; align-items: center; gap: 8px; }
.team-link { background: transparent; border: none; padding: 2px 0; color: var(--color-text); cursor: pointer; text-decoration: underline; font-size: 13px; }
.team-link.active { color: var(--color-accent); font-weight: 500; }
.team-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); }
.team-hint { margin: 0; font-size: 12px; opacity: 0.62; }
input, select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
input { min-width: 200px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.team-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
