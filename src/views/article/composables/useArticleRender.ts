import { ref } from 'vue'
import DOMPurify from 'dompurify'
import type { RenderPreview } from '../../../types/creation-studio'
import { studioPost, studioErrorMessage, useStudioGuard } from '../../../lib/creation-studio-http'

export interface ArticleRenderOptions { theme: 'standard' | 'compact'; includeTitle: boolean; citeExternalLinks: boolean }

/** Keep the renderer's inline typography; discard page CSS, executable URLs and layout overlays. */
export function sanitizeArticleHtml(html: string): string {
  const tags = ['section', 'div', 'figure', 'figcaption', 'h1', 'h2', 'h3', 'h4', 'p', 'span', 'strong', 'em', 'ul', 'ol', 'li',
    'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'pre', 'code', 'a', 'img', 'br', 'hr']
  const attributes = ['class', 'style', 'href', 'title', 'rel', 'src', 'alt', 'start', 'colspan', 'rowspan',
    'data-render', 'data-render-note', 'data-media-id']
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: tags,
    ALLOWED_ATTR: attributes,
    FORBID_TAGS: ['style', 'script', 'iframe', 'svg', 'form', 'input'],
  })
  const template = document.createElement('template')
  template.innerHTML = clean
  // Keep a DOM allowlist even in hosts where DOMPurify cannot install its parser hooks.
  for (const element of template.content.querySelectorAll('*')) {
    if (['style', 'script', 'iframe', 'svg', 'math', 'object', 'embed', 'template'].includes(element.localName)) {
      element.remove(); continue
    }
    if (!tags.includes(element.localName)) { element.replaceWith(...element.childNodes); continue }
    for (const attribute of Array.from(element.attributes)) {
      if (!attributes.includes(attribute.name)) element.removeAttribute(attribute.name)
    }
  }
  const styles = new Set(['font-family', 'font-size', 'font-weight', 'line-height', 'color', 'background', 'background-color',
    'max-width', 'width', 'height', 'margin', 'margin-top', 'margin-bottom', 'padding', 'padding-top',
    'border', 'border-top', 'border-left', 'border-radius', 'border-collapse', 'text-align', 'overflow-x', 'white-space'])
  for (const element of template.content.querySelectorAll<HTMLElement>('[style]')) {
    for (const property of Array.from({ length: element.style.length }, (_, index) => element.style.item(index))) {
      const value = element.style.getPropertyValue(property)
      if (!styles.has(property) || /url\s*\(|expression|@import|javascript|var\s*\(/i.test(value))
        element.style.removeProperty(property)
    }
  }
  for (const element of template.content.querySelectorAll('[src],[href]')) {
    for (const attr of ['src', 'href']) {
      const value = element.getAttribute(attr)
      if (value && !/^https?:\/\//i.test(value) && !/^\/(?!\/)/.test(value)) element.removeAttribute(attr)
    }
  }
  return template.innerHTML
}

export function useArticleRender(options: { draftId: () => string | null; draftVersion: () => number }) {
  const preview = ref<RenderPreview | null>(null)
  const rendering = ref(false)
  const error = ref('')
  const lastRequest = ref<ArticleRenderOptions | null>(null)
  const guard = useStudioGuard(() => [options.draftId(), options.draftVersion()].join(':'))
  let sequence = 0
  guard.onInvalidate(() => {
    sequence += 1; preview.value = null; rendering.value = false; error.value = ''; lastRequest.value = null
  })
  async function render(input: Partial<ArticleRenderOptions> & Pick<ArticleRenderOptions, 'theme'>): Promise<RenderPreview | null> {
    const draftId = options.draftId()
    const version = options.draftVersion()
    if (!draftId) return null
    const valid = guard.capture()
    const requestSequence = ++sequence
    rendering.value = true; error.value = ''
    const parameters: ArticleRenderOptions = { theme: input.theme, includeTitle: input.includeTitle ?? false,
      citeExternalLinks: input.citeExternalLinks ?? false }
    try {
      const data = await studioPost<RenderPreview>('/api/creation-studio/render-previews',
        { draftId, version, ...parameters }, 120_000)
      if (!valid() || requestSequence !== sequence) return null
      if (data.draftId !== draftId || data.version !== version) { error.value = '预览版本与保存版本不一致，请重新预览'; return null }
      preview.value = data; lastRequest.value = parameters
      return data
    } catch (failure) {
      if (valid() && requestSequence === sequence) error.value = studioErrorMessage(failure)
      return null
    } finally { if (valid() && requestSequence === sequence) rendering.value = false }
  }
  function isSameRequest(input: ArticleRenderOptions): boolean {
    return preview.value?.draftId === options.draftId() && preview.value?.version === options.draftVersion()
      && lastRequest.value?.theme === input.theme && lastRequest.value?.includeTitle === input.includeTitle
      && lastRequest.value?.citeExternalLinks === input.citeExternalLinks
  }
  return { preview, rendering, error, lastRequest, render, isSameRequest, dismiss: guard.invalidate }
}
export type ArticleRenderController = ReturnType<typeof useArticleRender>
