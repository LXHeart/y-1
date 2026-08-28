/**
 * 草场（Grassland）Java 微服务域的前端类型。
 *
 * 字段与各服务 controller 的 `toBody` 一一对应（marketplace TaskController/ApplicationController、
 * trust DisputeController/AdjudicationController、finance EscrowController）。
 * 时间字段均为 ISO-8601 字符串（Java `Instant.toString()`），可空。
 *
 * 请求经 vite proxy → edge-bff（:8081）→ 对应 Java 服务；BFF 按 RouteManifest 分流并签发内部身份断言。
 */

/** 统一响应信封（与 legacy Express 一致：{success, data} / {success:false, error}）。 */
export interface GrasslandResponse<T> {
  success: boolean
  data?: T
  error?: string
}

/** 活动身份：merchant/recommender；null = 消费者。按 session 隔离（多设备互不影响）。 */
export type IdentityType = 'merchant' | 'recommender'

/**
 * 通用分页结果（任务 #3 信封契约）：所有触达端点响应 `{success, data:{items,total,limit,offset}}`，
 * `request()` 解信封后 composable 统一收敛为 `{items, total}`。
 */
export interface PagedResult<T> {
  items: T[]
  total: number
}

/** 分页查询参数（各列表方法默认 limit=50、offset=0，调用方显式传参优先）。 */
export interface PageQuery {
  limit?: number
  offset?: number
}

/**
 * 过渡期兼容分页数组（任务 #3 → #4/#5 面板分页改造落定前的桥）：
 * 既是真数组（旧调用方迭代/展开/`Array.isArray`/赋给 `Ref<T[]>` 均原样工作），
 * 又带 `{items, total}`（新调用方按信封消费）。面板迁完后此类型与 {@link toPagedArray} 一并删除。
 */
export type PagedArrayCompat<T> = T[] & PagedResult<T>

/**
 * 把信封分页结果转成 {@link PagedArrayCompat}。容忍测试 stub 缺字段（items 缺省为空数组，
 * total 缺省回退条目数）——`request()` 的既有 fetch mock 返回 `data:{}`，不得在此崩。
 *
 * ⚠️ `items` 指向后端返回的原始数组而非本数组自身：`arr.items = arr` 造成循环引用，
 * vitest 断言匹配器（`expect(x).toEqual` 对数组元素递归检查）会在循环结构上栈溢出。
 */
export function toPagedArray<T>(page: PagedResult<T> | null | undefined): PagedArrayCompat<T> {
  const items = page?.items ?? []
  const arr = [...items] as PagedArrayCompat<T>
  arr.items = items
  arr.total = page?.total ?? items.length
  return arr
}
