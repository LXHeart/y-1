import type { RouteLocationRaw } from 'vue-router'
import type { AiCenterSection } from '../../views/ai-center/components/AiCenterNavigation.vue'

export const WORKSPACE_LINKS = [
  { name: 'create', label: '创作首页' },
  { name: 'projects', label: '我的项目' },
  { name: 'assets', label: '素材库' },
  { name: 'assistant', label: '创作助手' },
  { name: 'tools', label: '工具箱' },
  { name: 'settings', label: '设置与用量' },
] as const

export const SECTION_DESTINATIONS: Record<AiCenterSection, RouteLocationRaw> = {
  create: { name: 'write' }, recent: { name: 'projects' }, library: { name: 'assets' },
  assistant: { name: 'assistant', query: { tab: 'review' } },
  speech: { name: 'tools', query: { tab: 'speech' } },
  'image-studio': { name: 'images', query: { tab: 'edit' } },
  'image-gen': { name: 'images', query: { tab: 'generate' } },
  'video-studio': { name: 'tools', query: { tab: 'video' } },
  runs: { name: 'projects', query: { tab: 'runs' } }, keys: { name: 'settings' },
}

export function activeWorkspace(name: unknown): string {
  if (['projects', 'assets', 'assistant', 'tools', 'settings'].includes(String(name))) return String(name)
  return 'create'
}

export function workspaceSection(name: unknown, tab: unknown): AiCenterSection | null {
  switch (name) {
    case 'write': return 'create'
    case 'projects': return tab === 'runs' ? 'runs' : tab === 'reference' ? null : 'recent'
    case 'assets': return 'library'
    case 'settings': return 'keys'
    case 'images': return tab === 'edit' ? 'image-studio' : 'image-gen'
    case 'assistant': return tab === 'review' ? 'assistant' : null
    case 'tools': return tab === 'speech' ? 'speech' : tab === 'video' ? 'video-studio' : null
    default: return null
  }
}
