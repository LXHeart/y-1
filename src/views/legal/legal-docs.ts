import userAgreementMd from '../../content/legal/user-agreement.md?raw'
import privacyPolicyMd from '../../content/legal/privacy-policy.md?raw'

/**
 * 法律文档元数据（任务书 #85）：kind 与路由尾段一致，两条字面量路由以 props 注入，
 * 不从 URL 动态拼导入路径（D-03）。占位版三件套（横幅 + 版本行 + 生效状态）见 D-07——
 * 正式版替换时换 md 文件、去横幅并置 placeholder=false、补生效日期。
 */
export type LegalDocKind = 'user-agreement' | 'privacy-policy'

export interface LegalDocMeta {
  kind: LegalDocKind
  title: string            // '用户协议' | '隐私政策'
  version: string          // 'v0.1-placeholder'
  effectiveStatus: string  // '未生效（占位预览）'
  placeholder: boolean     // true；正式版替换时移除横幅并置 false
  bodyMarkdown: string     // ?raw 导入的正文
}

export const PLACEHOLDER_NOTICE = '本页面为占位预览版本：以下内容不是正式生效的法律文档，仅用于产品开发与体验预览；正式版本将在产品上线前发布，并以正式版本为准。'

export const LEGAL_DOCS: Record<LegalDocKind, LegalDocMeta> = {
  'user-agreement': {
    kind: 'user-agreement',
    title: '用户协议',
    version: 'v0.1-placeholder',
    effectiveStatus: '未生效（占位预览）',
    placeholder: true,
    bodyMarkdown: userAgreementMd,
  },
  'privacy-policy': {
    kind: 'privacy-policy',
    title: '隐私政策',
    version: 'v0.1-placeholder',
    effectiveStatus: '未生效（占位预览）',
    placeholder: true,
    bodyMarkdown: privacyPolicyMd,
  },
}
