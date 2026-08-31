/**
 * 知乎目标问题的本地解析（任务书 #62 P2）。
 *
 * **零网络请求**：2026-08-31 实测知乎 `question/{id}` 与 `api/v4/questions/{id}` 对
 * curl / WebFetch / reader 三路一律 403（zse-ck JS 挑战壳），逆向签名、无头浏览器农场、
 * 存用户 cookie 三条绕路全部否决（#62 §3.7）。因此问题原文一律手输，程序只从粘贴的链接里
 * 正则抽 questionId **做溯源存档**，不据此拉标题、不发任何请求。任何人不得在此文件
 * reintroduce 抓取。
 *
 * 单一真相源：创作流（useArticleCreation）与商家任务表单（MerchantTaskForm）共用本函数，
 * 两处各写一遍正则必然漂移。
 */

/** 匹配 `zhihu.com/question/{id}`（后接 `/answer/...` 等路径不影响）。 */
const ZHIHU_QUESTION_URL = /zhihu\.com\/question\/(\d+)/i

/**
 * 从任意输入里提取知乎 questionId；非链接/非知乎/空输入 → 空串。
 *
 * @param raw 用户输入原文（问题正文或粘贴的链接皆可）
 * @returns 纯数字 questionId，或空串
 */
export function extractZhihuQuestionRef(raw: string): string {
  const match = ZHIHU_QUESTION_URL.exec(raw || '')
  return match ? match[1] : ''
}
