/**
 * 小红书图文（任务书 #60）：正文末尾的话题标签行（# 开头）由 AI 默认生成、用户可在正文里改。
 * 图卡拆卡与配图槽拆分前需剥离这些行——话题属于笔记正文，不应被拆成卡片要点或配图槽。
 */

/** 剥离文本末尾连续的「以 # 开头」非空行（容忍行首空格、一行多个标签；尾部空行一并去掉）。 */
export function stripTrailingHashtagLines(text: string): string {
  const lines = text.split('\n')
  while (lines.length > 0) {
    const last = lines[lines.length - 1].trim()
    if (last === '' || last.startsWith('#')) {
      lines.pop()
      continue
    }
    break
  }
  return lines.join('\n')
}
