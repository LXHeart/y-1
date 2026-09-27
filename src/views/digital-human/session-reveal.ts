import { nextTick } from 'vue'

/**
 * 开始/接管后会话区渲染在页首，而页面常停在开始按钮所在的下方滚动位置；
 * 不滚入视口用户会以为没反应，这里统一滚到会话区。
 */
export async function revealLiveSession(): Promise<void> {
  await nextTick()
  document.querySelector('[data-testid="dh-live-session"]')?.scrollIntoView({ block: 'start' })
}
