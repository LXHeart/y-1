<template>
  <section class="wechat-accounts studio-panel" data-test="wechat-accounts" aria-label="公众号连接管理">
    <header class="panel-head">
      <div>
        <h4>公众号连接</h4>
        <p class="hint">凭据仅加密保存；校验是单独动作。没有连接也不影响本地编辑和导出文件。</p>
      </div>
      <button
        type="button"
        class="secondary"
        data-test="wechat-bind-open"
        ref="bindOpenButton"
        @click="openBind"
      >绑定公众号</button>
    </header>

    <p v-if="loadError" class="error" role="alert" data-test="wechat-load-error">{{ loadError }}</p>
    <p v-if="loading" class="hint" data-test="wechat-loading">正在加载连接…</p>
    <p v-else-if="!loadError && accounts.length === 0" class="hint" data-test="wechat-empty">尚未连接公众号；可继续创作，发布前再绑定。</p>

    <ul v-if="accounts.length" class="account-list" data-test="wechat-account-list">
      <li v-for="account in accounts" :key="account.id" class="account-row" :data-test="`wechat-account-${account.id}`">
        <div class="account-main">
          <strong>{{ account.displayName }}</strong>
          <span class="app-id" data-test="wechat-account-appid">{{ account.appId }}</span>
          <span class="badge" :class="`state-${account.state}`" :data-test="`wechat-account-state-${account.id}`">
            {{ stateLabel(account.state) }}
          </span>
          <small v-if="account.verifiedAt" class="hint">校验于 {{ shortTime(account.verifiedAt) }}</small>
        </div>
        <p v-if="account.state === 'invalid' && account.error" class="error compact" role="alert">
          校验未通过：{{ account.error.message }}（可修正凭据后重新校验）
        </p>
        <div class="row-actions">
          <button
            v-if="account.state !== 'disconnected'"
            type="button"
            class="secondary"
            :data-test="`wechat-verify-${account.id}`"
            :disabled="Boolean(busy[account.id])"
            @click="onVerify(account)"
          >{{ busy[account.id] === 'verify' ? '校验中…' : '校验' }}</button>
          <button
            v-if="account.state !== 'disconnected'"
            type="button"
            class="secondary"
            :data-test="`wechat-rotate-open-${account.id}`"
            :disabled="Boolean(busy[account.id])"
            @click="openRotate(account)"
          >轮换凭据</button>
          <button
            v-if="account.state !== 'disconnected'"
            type="button"
            class="secondary danger"
            :data-test="`wechat-disconnect-open-${account.id}`"
            :disabled="Boolean(busy[account.id])"
            @click="openDisconnect(account)"
          >断开</button>
        </div>
      </li>
    </ul>
    <p v-if="actionNote" class="hint" role="status" data-test="wechat-action-note">{{ actionNote }}</p>
    <p v-if="actionError" class="error" role="alert" data-test="wechat-action-error">{{ actionError }}</p>

    <!-- 绑定：password 输入，关闭即清空（不持久缓存 secret） -->
    <GlModal v-if="bindOpen" title="绑定公众号" trap-focus @close="closeBind">
      <form class="gl-field studio-panel" @input="renewBindRequest" @submit.prevent="submitBind">
        <label>账号名称（用于辨认，1～80 字）
          <input v-model="bindForm.displayName" data-test="wechat-bind-name" maxlength="80" required>
        </label>
        <label>AppID（wx 加 16 位十六进制）
          <input v-model.trim="bindForm.appId" data-test="wechat-bind-appid" maxlength="18" required>
        </label>
        <label>AppSecret
          <input
            v-model="bindForm.appSecret"
            data-test="wechat-bind-secret"
            type="password"
            autocomplete="new-password"
            maxlength="512"
            required
          >
        </label>
        <p class="hint">保存只加密存储，不会自动访问微信；校验需保存后单独点击。</p>
        <p v-if="bindError" class="error" role="alert" data-test="wechat-bind-error">{{ bindError }}</p>
        <div class="modal-actions">
          <button type="button" class="secondary" data-test="wechat-bind-cancel" @click="closeBind">取消</button>
          <button type="submit" class="primary gl-btn-primary" data-test="wechat-bind-submit" :disabled="bindSubmitting">
            {{ bindSubmitting ? '保存中…' : '保存连接' }}
          </button>
        </div>
      </form>
    </GlModal>

    <!-- 轮换：换新密文，回未验证态 -->
    <GlModal v-if="rotateTarget" :title="`轮换凭据：${rotateTarget.displayName}`" trap-focus @close="closeRotate">
      <form class="gl-field studio-panel" @input="renewRotateRequest" @submit.prevent="submitRotate">
        <p class="hint">轮换后原凭据立即失效，连接回到「未验证」，需要重新校验后才能用于发布。</p>
        <label>新 AppSecret
          <input
            v-model="rotateSecret"
            data-test="wechat-rotate-secret"
            type="password"
            autocomplete="new-password"
            maxlength="512"
            required
          >
        </label>
        <p v-if="rotateError" class="error" role="alert" data-test="wechat-rotate-error">{{ rotateError }}</p>
        <div class="modal-actions">
          <button type="button" class="secondary" data-test="wechat-rotate-cancel" @click="closeRotate">取消</button>
          <button type="submit" class="primary gl-btn-primary" data-test="wechat-rotate-submit" :disabled="rotateSubmitting">
            {{ rotateSubmitting ? '轮换中…' : '轮换' }}
          </button>
        </div>
      </form>
    </GlModal>

    <!-- 断开：明确说明；取消不发请求 -->
    <GlModal v-if="disconnectTarget" :title="`断开连接：${disconnectTarget.displayName}`" trap-focus @close="cancelDisconnect">
      <p>断开会清除已保存的凭据并取消未提交的同步任务；微信草稿箱里已有的草稿不会被删除。此操作可随时重新绑定恢复。</p>
      <p v-if="disconnectError" class="error" role="alert" data-test="wechat-disconnect-error">{{ disconnectError }}</p>
      <div class="modal-actions">
        <button type="button" class="secondary" data-test="wechat-disconnect-cancel" @click="cancelDisconnect">取消</button>
        <button
          type="button"
          class="secondary danger"
          data-test="wechat-disconnect-confirm"
          :disabled="disconnectSubmitting"
          @click="submitDisconnect"
        >{{ disconnectSubmitting ? '断开中…' : '确认断开' }}</button>
      </div>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { useStudioGuard } from '../../../lib/creation-studio-http'
import {
  bindWechatAccount, disconnectWechatAccount, rotateWechatAccount, useWechatAccounts,
  validateWechatDisplayName, validateWechatSecret, verifyWechatAccount,
  WECHAT_APP_ID_PATTERN, wechatActionError, type WechatAccount,
} from '../creation/useWechatAccounts'

/**
 * 任务书 #101 C101-20：公众号连接管理面板（AC101-20 / TC101-093~095）。
 * password 输入不持久缓存；关闭/切换即清 secret；校验与保存分离；
 * 断开有明确说明且取消无请求；无连接不阻断创作与导出。
 */
const { accounts, loading, loadError, refresh, replaceAccount } = useWechatAccounts()
const busy = reactive<Record<string, 'verify' | 'rotate' | 'disconnect' | undefined>>({})
const actionNote = ref('')
const actionError = ref('')

const bindOpen = ref(false)
const bindOpenButton = ref<HTMLButtonElement | null>(null)
const bindForm = reactive({ displayName: '', appId: '', appSecret: '' })
const bindError = ref('')
const bindSubmitting = ref(false)
const bindRequestId = ref(crypto.randomUUID())
let bindEpoch = 0
function renewBindRequest(): void { bindRequestId.value = crypto.randomUUID() }

const rotateTarget = ref<WechatAccount | null>(null)
const rotateOpenButton = ref<HTMLButtonElement | null>(null)
const rotateSecret = ref('')
const rotateError = ref('')
const rotateSubmitting = ref(false)
const rotateRequestId = ref(crypto.randomUUID())
let rotateEpoch = 0
function renewRotateRequest(): void { rotateRequestId.value = crypto.randomUUID() }

const disconnectTarget = ref<WechatAccount | null>(null)
const disconnectOpenButton = ref<HTMLButtonElement | null>(null)
const disconnectError = ref('')
const disconnectSubmitting = ref(false)
const guard = useStudioGuard()
const intents = new Map<string, string>()
function actionId(kind: string, account: WechatAccount): string {
  const key = [kind, account.id, account.version].join(':')
  if (!intents.has(key)) intents.set(key, crypto.randomUUID())
  return intents.get(key)!
}
guard.onInvalidate(() => {
  bindEpoch += 1; rotateEpoch += 1; intents.clear()
  bindForm.appSecret = ''; rotateSecret.value = ''; bindOpen.value = false; rotateTarget.value = null
  disconnectTarget.value = null; bindSubmitting.value = false; rotateSubmitting.value = false; disconnectSubmitting.value = false
  actionError.value = ''; actionNote.value = ''
  Object.keys(busy).forEach(key => { delete busy[key] })
})

onMounted(() => { void refresh() })

function stateLabel(state: WechatAccount['state']): string {
  if (state === 'active') return '已验证'
  if (state === 'invalid') return '校验失败'
  if (state === 'disconnected') return '已断开'
  return '未验证'
}

function shortTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' })
}

/** 关闭弹窗：清空内存 secret 并把焦点还给触发按钮（AC101-20 键盘焦点返回）。 */
function focusBack(trigger: HTMLButtonElement | null): void {
  void nextTick(() => trigger?.focus())
}

function openBind(): void {
  bindEpoch += 1; bindSubmitting.value = false; renewBindRequest()
  actionError.value = ''
  actionNote.value = ''
  bindError.value = ''
  bindForm.displayName = ''
  bindForm.appId = ''
  bindForm.appSecret = ''
  bindOpen.value = true
}

function closeBind(): void {
  bindEpoch += 1; bindSubmitting.value = false
  bindOpen.value = false
  bindForm.appSecret = ''
  bindForm.appId = ''
  bindForm.displayName = ''
  bindError.value = ''
  focusBack(bindOpenButton.value)
}

async function submitBind(): Promise<void> {
  const valid = guard.capture()
  const formEpoch = bindEpoch
  if (bindSubmitting.value) return
  const nameError = validateWechatDisplayName(bindForm.displayName)
  if (nameError) { bindError.value = nameError; return }
  if (!WECHAT_APP_ID_PATTERN.test(bindForm.appId)) { bindError.value = 'AppID 须为 wx 加 16 位十六进制'; return }
  const secretError = validateWechatSecret(bindForm.appSecret)
  if (secretError) { bindError.value = secretError; return }
  bindSubmitting.value = true
  bindError.value = ''
  try {
    const account = await bindWechatAccount({
      requestId: bindRequestId.value,
      displayName: bindForm.displayName.trim(),
      appId: bindForm.appId,
      appSecret: bindForm.appSecret,
    })
    if (!valid()) return
    replaceAccount(account)
    if (formEpoch !== bindEpoch) return
    actionNote.value = '连接已加密保存；请点击「校验」确认凭据可用'
    closeBind()
  } catch (error) {
    if (!valid()) return
    if (formEpoch === bindEpoch) bindError.value = wechatActionError(error, '保存连接失败').message
  } finally {
    if (valid() && formEpoch === bindEpoch) bindSubmitting.value = false
  }
}

async function onVerify(account: WechatAccount): Promise<void> {
  const valid = guard.capture()
  if (busy[account.id]) return
  busy[account.id] = 'verify'
  actionError.value = ''
  actionNote.value = ''
  try {
    const next = await verifyWechatAccount(account.id, account.version, actionId('verify', account))
    if (!valid()) return
    replaceAccount(next)
    actionNote.value = next.state === 'active'
      ? '校验通过：API 凭据可用（不代表全部内容发布权限已验证）'
      : '校验未通过，请核对凭据或轮换后重试'
  } catch (error) {
    if (!valid()) return
    const failure = wechatActionError(error, '校验失败')
    actionError.value = failure.message
    if (failure.versionConflict) await refresh()
  } finally {
    if (valid()) busy[account.id] = undefined
  }
}

function openRotate(account: WechatAccount): void {
  rotateEpoch += 1; rotateSubmitting.value = false; renewRotateRequest()
  // 点击瞬间同步记录焦点来源（关闭归还，AC101-20）
  rotateOpenButton.value = document.activeElement as HTMLButtonElement | null
  rotateTarget.value = account
  rotateSecret.value = ''
  rotateError.value = ''
}

function closeRotate(): void {
  rotateEpoch += 1; rotateSubmitting.value = false
  rotateTarget.value = null
  rotateSecret.value = ''
  rotateError.value = ''
  focusBack(rotateOpenButton.value)
}

async function submitRotate(): Promise<void> {
  const valid = guard.capture()
  const formEpoch = rotateEpoch
  const target = rotateTarget.value
  if (!target || rotateSubmitting.value) return
  const secretError = validateWechatSecret(rotateSecret.value)
  if (secretError) { rotateError.value = secretError; return }
  rotateSubmitting.value = true
  rotateError.value = ''
  try {
    const next = await rotateWechatAccount(target.id, target.version, rotateSecret.value, rotateRequestId.value)
    if (!valid()) return
    replaceAccount(next)
    if (formEpoch !== rotateEpoch) return
    actionNote.value = '凭据已轮换；连接回到未验证，请重新校验'
    closeRotate()
  } catch (error) {
    if (!valid()) return
    const failure = wechatActionError(error, '轮换失败')
    if (formEpoch === rotateEpoch) rotateError.value = failure.message
    if (failure.versionConflict) await refresh()
  } finally {
    if (valid() && formEpoch === rotateEpoch) rotateSubmitting.value = false
  }
}

function openDisconnect(account: WechatAccount): void {
  disconnectOpenButton.value = document.activeElement as HTMLButtonElement | null
  disconnectTarget.value = account
  disconnectError.value = ''
  actionError.value = ''
  actionNote.value = ''
}

function cancelDisconnect(): void {
  disconnectTarget.value = null
  disconnectError.value = ''
  focusBack(disconnectOpenButton.value)
}

async function submitDisconnect(): Promise<void> {
  const valid = guard.capture()
  const target = disconnectTarget.value
  if (!target || disconnectSubmitting.value) return
  disconnectSubmitting.value = true
  try {
    const next = await disconnectWechatAccount(target.id, target.version, actionId('disconnect', target))
    if (!valid()) return
    replaceAccount(next)
    actionNote.value = `已断开 ${next.displayName}；如需恢复可重新绑定`
    disconnectTarget.value = null
    focusBack(disconnectOpenButton.value)
  } catch (error) {
    if (!valid()) return
    const failure = wechatActionError(error, '断开失败')
    disconnectError.value = failure.message
    if (failure.versionConflict) {
      await refresh()
      disconnectTarget.value = null
    }
  } finally {
    if (valid()) disconnectSubmitting.value = false
  }
}
</script>

<style scoped>
.wechat-accounts { display: grid; gap: var(--space-sm); }
.wechat-accounts h4 { margin: 0; font-size: var(--type-body); }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-sm); }
.panel-head .hint { margin: var(--space-xxs) 0 0; }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--type-body-sm); }
.error { color: var(--color-danger); font-size: var(--type-body-sm); margin: 0; }
.error.compact { margin: var(--space-xxs) 0 0; }
.account-list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-xs); }
.account-row { display: grid; gap: var(--space-xxs); padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-sm); }
.account-main { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }
.account-main strong { font-size: var(--type-body-sm); }
.app-id { font-family: var(--font-body); font-size: var(--type-caption); color: var(--color-text-muted); }
.badge { display: inline-block; padding: var(--space-micro) var(--space-sm); border-radius: var(--radius-pill); font-size: var(--type-caption); background: var(--surface-muted); color: var(--color-text-secondary); }
.badge.state-active { background: var(--surface-success); color: var(--color-success, var(--color-text-secondary)); }
.badge.state-invalid { background: var(--surface-danger); color: var(--color-danger); }
.row-actions { display: flex; gap: var(--space-xs); flex-wrap: wrap; }
.row-actions .secondary { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); font-size: var(--type-body-sm); }
.secondary.danger { color: var(--color-danger); }
.modal-actions { display: flex; justify-content: flex-end; gap: var(--space-sm); margin-top: var(--space-md); }
.modal-actions .secondary { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); }
</style>
