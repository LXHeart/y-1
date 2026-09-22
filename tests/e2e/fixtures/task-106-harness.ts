/**
 * 任务书 #106 C106-06（D06）：测试专用 harness——只装配、调用生产 API、显示计数。
 *
 * 直接 import 当前生产模块（零算法复刻）：
 * - src/lib/account-private-cache（登记/清理/读取/激活）
 * - src/stores/account-session + src/stores/auth（真实账号 store 驱动激活与换号清理）
 * - src/composables/useEngagementExitFunds（真实轮询 composable，含 #106 D05 门闸）
 * - src/composables/grassland-http 的 request（真实浏览器网络；Playwright route 控制延迟/失败）
 *
 * 页面只提供按钮（真实 DOM 点击）与状态渲染；window.__task106 仅供测试读取计数/结果，
 * 不注入生产入口、不复刻缓存/轮询/清理算法。同源两 Page 由 spec 创建：独立 sessionStorage、
 * 共享 localStorage、真实 BroadcastChannel/storage 事件。
 */
import { createApp, defineComponent, h, KeepAlive, reactive, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import {
  activateAccountCache, clearAccountCache, readAccountKey, registerAccountKey,
} from '../../../src/lib/account-private-cache'
import { useAuthStore } from '../../../src/stores/auth'
import { useAccountSessionStore } from '../../../src/stores/account-session'
import { useEngagementExitFunds } from '../../../src/composables/useEngagementExitFunds'
import { request } from '../../../src/composables/grassland-http'
import type { EngagementExitFunds } from '../../../src/types/grassland/task'

interface HarnessState {
  invalidations: number
  lastCleared: number | null
  reads: Array<{ account: string; area: string; key: string; value: string | null }>
  invalidationAccounts: string[]
  fundsRequests: number
  fundsUrls: string[]
}

const state: HarnessState = reactive({
  invalidations: 0,
  lastCleared: null,
  reads: [],
  invalidationAccounts: [],
  fundsRequests: 0,
  fundsUrls: [],
})

// 环境预置（非账号数据）：主题与其他账号值——清理不得外溢。
localStorage.setItem('theme', 'light')
localStorage.setItem('subtitle-cues-acct-other:seed', '[]')

const pinia = createPinia()
setActivePinia(pinia)
const auth = useAuthStore()

// 生产 cache API 直调包装：计数回调只观察，不改行为。
function harnessActivate(accountId: string): void {
  activateAccountCache(accountId, () => {
    state.invalidations += 1
    state.invalidationAccounts.push(accountId)
  })
}
void useAccountSessionStore()  // 真实账号会话 store 就位（watch immediate 驱动激活/换号清理）

// 资金面板客户端适配器：真实浏览器网络 + 生产 request 信封解析（传输层适配，非轮询逻辑）。
const clientError = { value: '' }
const fundsClient = {
  error: clientError,
  fetchEngagementExitFunds: async (taskId: string, appId: string, signal?: AbortSignal): Promise<EngagementExitFunds | null> => {
    state.fundsRequests += 1
    const url = `/api/tasks/${taskId}/applications/${appId}/exit-funds`
    state.fundsUrls.push(url)
    try {
      const data = await request<EngagementExitFunds | null>(url, signal ? { signal } : {})
      clientError.value = ''
      return data
    } catch (caught) {
      if (signal?.aborted) return null
      clientError.value = caught instanceof Error && caught.message ? caught.message : '资金状态查询失败'
      return null
    }
  },
}

type FundsApi = ReturnType<typeof useEngagementExitFunds>
let fundsApi: FundsApi | null = null

const FundsPanel = defineComponent({
  name: 'Task106FundsPanel',
  setup() {
    fundsApi = useEngagementExitFunds(fundsClient as never)
    return () => h('div', { 'data-testid': 'funds-panel' }, [
      h('div', { 'data-testid': 'funds-state' }, `state=${fundsApi!.funds.value?.state ?? 'null'}`),
      h('div', { 'data-testid': 'funds-loading' }, `loading=${String(fundsApi!.loading.value)}`),
      h('div', { 'data-testid': 'funds-error' }, `error=${fundsApi!.error.value}`),
    ])
  },
})

const app = createApp(defineComponent({
  setup() {
    const account = ref('acct-a')
    const otherAccount = ref('acct-a')
    const key = ref('video-canvas-bind:acct-a:1:s1:d1')
    const value = ref('v1')
    const area = ref<'session' | 'local'>('session')
    const bulk = ref(3)
    const storageKey = ref('')
    const storageValue = ref('')
    const opId = ref('')
    const fundsTask = ref('task-1')
    const fundsAppId = ref('app-1')
    const fundsShow = ref(true)
    const fundsMounted = ref(true)

    const input = (testid: string, model: { value: string | number }, placeholder: string) =>
      h('input', {
        'data-testid': testid,
        value: model.value,
        placeholder,
        onInput: (event: Event) => { model.value = (event.target as HTMLInputElement).value },
      })
    const button = (testid: string, label: string, onClick: () => void) =>
      h('button', { 'data-testid': testid, onClick }, label)
    const text = (testid: string, content: string) =>
      h('div', { 'data-testid': testid }, content)

    return () => h('div', [
      h('h1', 'task-106 harness'),
      text('invalidations', `invalidations=${state.invalidations} accounts=${state.invalidationAccounts.join(',')}`),
      text('last-cleared', `lastCleared=${String(state.lastCleared)}`),
      text('doc-hidden', `document.hidden=${String(document.hidden)}`),
      text('funds-requests', `fundsRequests=${state.fundsRequests}`),

      input('account-input', account, 'account'),
      button('login', 'login', () => {
        const id = account.value.trim()
        if (!id) return
        // 真实 auth → account-session store（flush:sync watch）驱动激活/旧账号清理。
        auth.currentUser = { id, email: `${id}@qa.invalid`, displayName: id, role: 'user' }
      }),
      button('logout', 'logout', () => { auth.currentUser = null }),
      button('activate', 'activate', () => harnessActivate(account.value.trim())),
      button('clear', 'clearAccountCache', () => {
        state.lastCleared = clearAccountCache(account.value.trim())
      }),

      input('key-input', key, 'key'),
      input('value-input', value, 'value'),
      h('select', {
        'data-testid': 'area-select',
        onChange: (event: Event) => { area.value = (event.target as HTMLSelectElement).value as 'session' | 'local' },
      }, [h('option', { value: 'session' }, 'session'), h('option', { value: 'local' }, 'local')]),
      button('write', 'write', () => {
        const storage = area.value === 'session' ? sessionStorage : localStorage
        storage.setItem(key.value, value.value)
      }),
      button('register', 'register(current)', () => {
        registerAccountKey(account.value.trim(), area.value, key.value)
      }),
      input('other-account-input', otherAccount, 'register account'),
      button('register-as', 'register(as)', () => {
        registerAccountKey(otherAccount.value.trim(), area.value, key.value)
      }),
      button('read', 'readAccountKey', () => {
        state.reads.push({
          account: otherAccount.value.trim() || account.value.trim(),
          area: area.value,
          key: key.value,
          value: readAccountKey(otherAccount.value.trim() || account.value.trim(), area.value, key.value),
        })
      }),

      input('bulk-input', bulk, 'bulk count'),
      button('bulk-register', 'bulk register', () => {
        const count = Math.max(0, Math.min(Number(bulk.value) || 0, 1001))
        const owner = account.value.trim()
        for (let index = 0; index < count; index += 1) {
          const bulkKey = `video-canvas-bind:${owner}:${index}:s:d`
          sessionStorage.setItem(bulkKey, `v${index}`)
          registerAccountKey(owner, 'session', bulkKey)
        }
      }),

      input('storage-key-input', storageKey, 'storage key'),
      input('storage-value-input', storageValue, 'storage value'),
      button('storage-write', 'localStorage write', () => {
        localStorage.setItem(storageKey.value, storageValue.value)
      }),
      input('op-input', opId, 'operationId'),
      button('bc-post', 'BroadcastChannel post', () => {
        // 真实同名信道（独立实例）：其他实例/他页收到与真实 peer 一致的消息。
        const channel = new BroadcastChannel('grassland:account-private-cache-clear')
        channel.postMessage({
          version: 2, type: 'clear',
          accountId: otherAccount.value.trim() || account.value.trim(),
          operationId: opId.value.trim(),
        })
        channel.close()
      }),

      h('hr'),
      text('funds-panel-slot', `fundsMounted=${String(fundsMounted.value)} fundsShow=${String(fundsShow.value)}`),
      fundsMounted.value
        ? h(KeepAlive, () => (fundsShow.value ? h(FundsPanel) : null))
        : h('div', { 'data-testid': 'funds-unmounted' }, 'unmounted'),
      input('funds-task-input', fundsTask, 'task'),
      input('funds-app-input', fundsAppId, 'application'),
      button('funds-target', 'funds target', () => {
        fundsApi?.target(fundsTask.value.trim(), fundsAppId.value.trim())
      }),
      button('funds-refresh', 'funds refresh', () => { void fundsApi?.refresh() }),
      button('funds-clear', 'funds clear target', () => { fundsApi?.target(null, null) }),
      button('funds-toggle-show', 'toggle KeepAlive show', () => { fundsShow.value = !fundsShow.value }),
      button('funds-toggle-mount', 'toggle mount', () => { fundsMounted.value = !fundsMounted.value }),
    ])
  },
}))
app.use(pinia)
app.mount('#app')

// 测试读取面（只读计数/结果；动作一律走上面真实 DOM 按钮）。
;(window as unknown as { __task106: { state: HarnessState } }).__task106 = { state }
