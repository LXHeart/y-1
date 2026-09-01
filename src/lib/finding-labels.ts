/** 内容安全 finding 类别显示名（任务书 #63：面板 chip 与检查步详情弹层共用一份）。 */
export const FINDING_CATEGORY_LABELS: Record<string, string> = {
  absolute_claims: '广告法极限词',
  false_promises: '违规承诺',
  diversion: '导流联系',
  politics: '涉政敏感',
  porn: '低俗内容',
  illegal: '涉嫌违法',
  platform_unwanted: '平台不推荐表达',
  platform_overlay: '平台规则',
  industry_overlay: '行业规则',
  duplicate_content: '内容重复度',
  low_originality: '低原创度',
}

export function findingCategoryLabel(category: string): string {
  return FINDING_CATEGORY_LABELS[category] || category
}
