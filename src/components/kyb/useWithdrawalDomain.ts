import { computed, ref, type Ref } from 'vue'
import { useGrassland } from '../../composables/useGrassland'
import type { WithdrawalAccount } from '../../types/grassland'

/**
 * 任务书 #68 卡 F：收款账户域（自 MerchantKybCard script 迁出，逻辑逐字符保真）。
 *
 * ⚠ grassland 实例由父组件创建后传入——useGrassland() 不是单例（每次调用各自新建
 * loading/error ref），各域自行调用会让「任一操作禁用全部按钮」的现状行为漂移。
 */
type GrasslandApi = ReturnType<typeof useGrassland>

interface WithdrawalDomainDeps {
  orgId: Ref<string>
  grassland: GrasslandApi
  onChanged: () => void
}

export function useWithdrawalDomain(deps: WithdrawalDomainDeps) {
  const grassland = deps.grassland

  const accounts = ref<WithdrawalAccount[]>([])
  const form = ref({
    accountType: 'bank_card' as const,
    accountName: '',
    accountNumber: '',
    bankName: '',
    branchName: '',
  })

  let loadVersion = 0

  /** summary watcher 用：已通过的收款账户数。 */
  const approvedWithdrawalCount = computed(() =>
    // Array.isArray 守卫：load 只判 truthy，上游给非数组时这里不能连带崩掉整卡
    // （卡身用 v-for 能容忍，`.filter` 不能）。
    Array.isArray(accounts.value)
      ? accounts.value.filter((item) => item.status === 'approved').length
      : 0,
  )

  function isCurrentOrganization(orgId: string, version: number): boolean {
    return deps.orgId.value === orgId && loadVersion === version
  }

  async function load(orgId: string): Promise<void> {
    const version = ++loadVersion
    const list = await grassland.listWithdrawalAccounts(orgId)
    if (list && isCurrentOrganization(orgId, version)) accounts.value = list
  }

  async function createAccount(): Promise<void> {
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.createWithdrawalAccount(orgId, {
      accountType: form.value.accountType,
      accountName: form.value.accountName,
      accountNumber: form.value.accountNumber,
      bankName: form.value.bankName || undefined,
      branchName: form.value.branchName || undefined,
    })
    if (result && isCurrentOrganization(orgId, version)) {
      accounts.value = [...accounts.value, result]
      form.value = {
        accountType: 'bank_card',
        accountName: '',
        accountNumber: '',
        bankName: '',
        branchName: '',
      }
    }
  }

  async function submitAccount(accountId: string): Promise<void> {
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.submitWithdrawalAccount(orgId, accountId)
    if (result && isCurrentOrganization(orgId, version)) {
      accounts.value = accounts.value.map((account) =>
        account.id === accountId ? result : account)
      deps.onChanged()
    }
  }

  async function setDefault(accountId: string): Promise<void> {
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.setDefaultWithdrawalAccount(orgId, accountId)
    if (result && isCurrentOrganization(orgId, version)) {
      accounts.value = accounts.value.map((a) => ({
        ...a,
        isDefault: a.id === accountId,
      }))
    }
  }

  async function deleteAccount(accountId: string): Promise<void> {
    const orgId = deps.orgId.value
    const version = loadVersion
    const result = await grassland.deleteWithdrawalAccount(orgId, accountId)
    if (result !== null && isCurrentOrganization(orgId, version)) {
      accounts.value = accounts.value.filter((a) => a.id !== accountId)
    }
  }

  function reset(): void {
    accounts.value = []
    form.value = {
      accountType: 'bank_card', accountName: '', accountNumber: '', bankName: '', branchName: '',
    }
  }

  return {
    accounts, form, approvedWithdrawalCount,
    createAccount, submitAccount, setDefault, deleteAccount,
    load, reset,
  }
}
