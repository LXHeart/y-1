import { createPinia, setActivePinia } from 'pinia'
import { afterEach, vi } from 'vitest'

// 共享 Pinia 实例：setup 文件按测试文件加载一次，因此每个测试文件天然从干净状态开始。
// 刻意不在全局 afterEach 里替换/归还活动 Pinia 槽位：既有测试面广泛使用
// 「用例内自建 Pinia + 模块级单例在 beforeEach 时机绑定」模式（如 useAccountBootstrap、
// NotificationBell），全局槽位切换会让单例解析到另一个实例——违反 TC103-22-04
// 「不能干扰测试自有实例」的红线。Pinia 的释放边界 = 每测试文件一个新实例。
setActivePinia(createPinia())

process.env.NODE_ENV = 'test'

// 任务书 #107-4 C1074-31：warning 门禁。观察已知「掩盖测试质量」的 warning 家族
// （Pinia R1004 / 组件外生命周期注册 / Fragment attrs / 卸载后写入），逐用例断言清零。
// 观察者模式：原样转发原 console 方法，不吞输出、不 mock 行为；白名单为空集，
// 新增 warning 直接让所在用例失败并带定位文本（fail-closed）。
const WARNING_GATE_PATTERNS: RegExp[] = [
  /PINIA_R1004/,
  /onScopeDispose\(\) is called when there is no active component instance/i,
  /onUnmounted\(\) is called when there is no active component instance/i,
  /onMounted\(\) is called when there is no active component instance/i,
  /onBeforeMount\(\) is called when there is no active component instance/i,
  /onBeforeUnmount\(\) is called when there is no active component instance/i,
  /Extraneous non-(props|emits) attributes/i,
  /Vue received a Component which was made a reactive object/i,
]
const gateHits: string[] = []
const gateWrap = (method: 'error' | 'warn'): void => {
  const original = console[method].bind(console)
  console[method] = (...args: unknown[]) => {
    const text = args.map((a) => (typeof a === 'string' ? a : '')).join(' ')
    if (WARNING_GATE_PATTERNS.some((p) => p.test(text))) {
      gateHits.push(`[${method}] ${text.slice(0, 160)}`)
    }
    original(...args)
  }
}
gateWrap('error')
gateWrap('warn')

afterEach(() => {
  // 门禁断言先于全局资源收尾执行：命中即失败当前用例，文本里带全部命中行便于定位。
  if (gateHits.length > 0) {
    const hits = gateHits.splice(0)
    throw new Error(
      `warning-gate: 测试产生 ${hits.length} 条受管 warning（107-4 C1074-31 基线为 0）：\n${hits.join('\n')}`,
    )
  }
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
