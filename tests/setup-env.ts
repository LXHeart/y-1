import { createPinia, setActivePinia } from 'pinia'
import { afterEach, vi } from 'vitest'

// 共享 Pinia 实例：setup 文件按测试文件加载一次，因此每个测试文件天然从干净状态开始。
// 刻意不在全局 afterEach 里替换/归还活动 Pinia 槽位：既有测试面广泛使用
// 「用例内自建 Pinia + 模块级单例在 beforeEach 时机绑定」模式（如 useAccountBootstrap、
// NotificationBell），全局槽位切换会让单例解析到另一个实例——违反 TC103-22-04
// 「不能干扰测试自有实例」的红线。Pinia 的释放边界 = 每测试文件一个新实例。
setActivePinia(createPinia())

process.env.NODE_ENV = 'test'

// 任务书 #103 C103-22：共享测试环境的逐用例收尾——释放 stub globals 与 fake timers。
// 这两类是全局槽位资源，不属任何测试自有实例；用例自带的 afterEach 先于本钩子执行，
// 这里兜底跨用例泄漏（TC103-22-02/03）。
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
