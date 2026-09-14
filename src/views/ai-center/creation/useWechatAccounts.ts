import { readonly, ref } from 'vue'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import { studioRequest as request, StudioHttpError, useStudioGuard } from '../../../lib/creation-studio-http'

/**
 * 任务书 #101 C101-20：公众号连接管理（API101-21~25）。
 * owner 级账号列表／绑定／校验／轮换／断开；epoch 守卫丢弃迟到响应；
 * AppSecret 只在提交瞬间使用，不落任何持久状态（TC101-093）。
 */

export type WechatAccountState = 'unverified' | 'active' | 'invalid' | 'disconnected'

export interface WechatAccount {
  id: string
  displayName: string
  appId: string
  state: WechatAccountState
  version: number
  verifiedAt: string | null
  error: { code: string; message: string } | null
}

export interface WechatAccountPage {
  items: WechatAccount[]
  nextCursor: string | null
}

/** §6.8 输入契约（与服务端一致，前置拦截省一次往返）。 */
export const WECHAT_APP_ID_PATTERN = /^wx[0-9a-fA-F]{16}$/

export function validateWechatSecret(secret: string): string | null {
  if (secret.length < 16) return 'AppSecret 至少 16 位'
  if (secret.length > 512) return 'AppSecret 超过 512 位上限'
  // eslint 禁 control 字面量——用码点判定（空格/换行/C0·C1 控制字符均拒）
  const hasForbidden = Array.from(secret).some((ch) => {
    const code = ch.codePointAt(0) ?? 0
    return ch === ' ' || (code >= 0x00 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f)
  })
  if (hasForbidden) return 'AppSecret 不允许空格／换行／控制字符'
  return null
}

export function validateWechatDisplayName(name: string): string | null {
  if (!name.trim()) return '请填写账号名称'
  if ([...name].length > 80) return '账号名称最多 80 字'
  return null
}

export async function listWechatAccounts(limit = 20, cursor?: string): Promise<WechatAccountPage> {
  return request<WechatAccountPage>(
    `/api/creation-channels/wechat/accounts?limit=${encodeURIComponent(limit)}${cursor ? '&cursor=' + encodeURIComponent(cursor) : ''}`, { method: 'GET' })
}

export async function bindWechatAccount(input: {
  requestId: string
  displayName: string
  appId: string
  appSecret: string
}): Promise<WechatAccount> {
  return request<WechatAccount>('/api/creation-channels/wechat/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

async function mutateAccount(id: string, verb: 'verify' | 'rotate' | 'disconnect', body: Record<string, unknown>): Promise<WechatAccount> {
  return request<WechatAccount>(
    `/api/creation-channels/wechat/accounts/${encodeURIComponent(id)}/${verb}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
}

export function verifyWechatAccount(id: string, expectedVersion: number, requestId: string): Promise<WechatAccount> {
  return mutateAccount(id, 'verify', { requestId, expectedVersion })
}

export function rotateWechatAccount(id: string, expectedVersion: number, appSecret: string,
  requestId: string): Promise<WechatAccount> {
  return mutateAccount(id, 'rotate', { requestId, expectedVersion, appSecret })
}

export function disconnectWechatAccount(id: string, expectedVersion: number,
  requestId: string): Promise<WechatAccount> {
  return mutateAccount(id, 'disconnect', { requestId, expectedVersion })
}

/** 把 HTTP 错误转成用户可读文案；409 版本冲突单独分支供 UI 提示刷新。 */
export function wechatActionError(error: unknown, fallback: string): { message: string; versionConflict: boolean } {
  if (error instanceof GrasslandHttpError || error instanceof StudioHttpError) {
    if (error.status === 409 && error.code === 'STUDIO_VERSION_CONFLICT')
      return { message: '连接刚被其他操作更新，已为你刷新列表，请重试', versionConflict: true }
    if (error.status === 409 && error.code === 'STUDIO_CHANNEL_ACCOUNT_BOUND')
      return { message: '该 AppID 已被其他账号绑定', versionConflict: false }
    if (error.status === 422 && error.code === 'STUDIO_CHANNEL_ACCOUNT_INVALID')
      return { message: '连接未验证或已失效，请先校验', versionConflict: false }
    return { message: error.message || fallback, versionConflict: false }
  }
  return { message: error instanceof Error && error.message ? error.message : fallback, versionConflict: false }
}

export function useWechatAccounts() {
  const accounts = ref<WechatAccount[]>([])
  const loading = ref(false)
  const loadError = ref('')
  /** epoch：账号切换／重挂载／并发刷新时丢弃迟到响应（TC101-093 / useWechatAccounts.test）。 */
  let epoch = 0
  const guard = useStudioGuard()
  guard.onInvalidate(() => { epoch += 1; accounts.value = []; loadError.value = ''; loading.value = false })

  async function refresh(): Promise<void> {
    const current = ++epoch
    loading.value = true
    try {
      let page = await listWechatAccounts()
      const all = [...page.items]
      const cursors = new Set<string>()
      while (page.nextCursor && !cursors.has(page.nextCursor)) {
        if (current !== epoch) return
        cursors.add(page.nextCursor)
        page = await listWechatAccounts(20, page.nextCursor)
        all.push(...page.items)
      }
      if (current !== epoch) return
      accounts.value = [...new Map(all.map(item => [item.id, item])).values()]
      loadError.value = ''
    } catch (error) {
      if (current !== epoch) return
      loadError.value = wechatActionError(error, '连接列表加载失败').message
    } finally {
      if (current === epoch) loading.value = false
    }
  }

  function replaceAccount(next: WechatAccount): void {
    const index = accounts.value.findIndex((item) => item.id === next.id)
    if (index >= 0) {
      if (accounts.value[index].version <= next.version) accounts.value.splice(index, 1, next)
    } else accounts.value.unshift(next)
  }

  return {
    accounts: readonly(accounts),
    loading: readonly(loading),
    loadError: readonly(loadError),
    refresh,
    replaceAccount,
    /** 测试探针：当前 epoch（只读比较用）。 */
    currentEpoch: () => epoch,
    reset: guard.invalidate,
  }
}
