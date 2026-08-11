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
