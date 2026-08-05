import DOMPurify from 'dompurify'
import { marked } from 'marked'

const SAFE_URI = /^(?:(?:https):|\/(?!\/)|#)/i
const SAFE_TAGS = new Set([
  'p', 'br', 'h1', 'h2', 'h3', 'strong', 'em', 'ul', 'ol', 'li',
  'blockquote', 'code', 'pre', 'a', 'img',
])
const DROP_WITH_CONTENT = new Set(['script', 'style', 'iframe', 'object', 'embed', 'template'])

function rebuildSafeNode(node: Node, outputDocument: Document): Node | null {
  if (node.nodeType === Node.TEXT_NODE) {
    return outputDocument.createTextNode(node.textContent || '')
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return null

  const source = node as Element
  const tag = source.tagName.toLowerCase()
  if (DROP_WITH_CONTENT.has(tag)) return null

  if (!SAFE_TAGS.has(tag)) {
    const fragment = outputDocument.createDocumentFragment()
    for (const child of Array.from(source.childNodes)) {
      const safeChild = rebuildSafeNode(child, outputDocument)
      if (safeChild) fragment.appendChild(safeChild)
    }
    return fragment
  }

  const target = outputDocument.createElement(tag)
  const allowedAttrs = tag === 'a'
    ? ['href', 'title']
    : tag === 'img' ? ['src', 'alt', 'title'] : []
  for (const name of allowedAttrs) {
    const value = source.getAttribute(name)
    if (value === null) continue
    if ((name === 'href' || name === 'src') && !SAFE_URI.test(value.trim())) continue
    if ((name === 'alt' || name === 'title') && /on[a-z]+\s*=/i.test(value)) continue
    target.setAttribute(name, value)
  }
  if (tag === 'a' && target.hasAttribute('href')) {
    target.setAttribute('rel', 'noopener noreferrer')
  }
  for (const child of Array.from(source.childNodes)) {
    const safeChild = rebuildSafeNode(child, outputDocument)
    if (safeChild) target.appendChild(safeChild)
  }
  return target
}

function enforceStructuredWhitelist(html: string): string {
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  const output = document.implementation.createHTMLDocument('')
  const container = output.createElement('div')
  for (const child of Array.from(parsed.body.childNodes)) {
    const safeChild = rebuildSafeNode(child, output)
    if (safeChild) container.appendChild(safeChild)
  }
  return container.innerHTML
}

export function renderSafeMarkdown(markdown: string): string {
  const parsed = marked.parse(markdown, {
    async: false,
    breaks: true,
    gfm: true,
  }) as string

  const purified = DOMPurify.sanitize(parsed, {
    ALLOWED_TAGS: [
      'p', 'br', 'h1', 'h2', 'h3', 'strong', 'em', 'ul', 'ol', 'li',
      'blockquote', 'code', 'pre', 'a', 'img',
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title'],
    ALLOWED_URI_REGEXP: SAFE_URI,
  })
  const parsedLower = parsed.toLowerCase()
  const purifiedLower = purified.toLowerCase()
  const preservesSafeStructure = Array.from(SAFE_TAGS).every((tag) =>
    !parsedLower.includes(`<${tag}`) || purifiedLower.includes(`<${tag}`))
  return enforceStructuredWhitelist(preservesSafeStructure ? purified : parsed)
}
