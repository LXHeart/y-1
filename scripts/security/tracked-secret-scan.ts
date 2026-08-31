export interface TrackedSecretFinding {
  line: number
  rule: string
}

interface SecretRule {
  name: string
  pattern: RegExp
  valueGroup?: number
  valueGroups?: readonly number[]
  pathPattern?: RegExp
}

const SECRET_RULES: readonly SecretRule[] = [
  { name: 'provider-api-key', pattern: /\bsk-[A-Za-z0-9_-]{24,}\b/g },
  { name: 'github-token', pattern: /\bgh[pousr]_[A-Za-z0-9]{30,}\b/g },
  { name: 'aws-access-key', pattern: /\bAKIA[0-9A-Z]{16}\b/g },
  { name: 'private-key', pattern: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g },
  {
    name: 'structured-credential',
    pattern: /(?<![A-Za-z0-9_.-])(?:export[ \t]+)?(?:(?:const|let|var)[ \t]+)?["']?(?:(?:api[-_.]?key|token|secret|password|passwd|passphrase)|[A-Za-z][A-Za-z0-9_.-]*(?:api[-_.]?key|token|secret|password|passwd|passphrase)[A-Za-z0-9_.-]*)["']?[ \t]*(?::|=)[ \t]*(["'`])([^"'`\r\n]+)\1[ \t]*\+[ \t]*(["'`])([^"'`\r\n]+)\3/gi,
    valueGroups: [2, 4],
  },
  {
    name: 'structured-credential',
    pattern: /(?<![A-Za-z0-9_.-])(?:export[ \t]+)?(?:(?:const|let|var)[ \t]+)?["']?((?:api[-_.]?key|token|secret|password|passwd|passphrase)|[A-Za-z][A-Za-z0-9_.-]*(?:api[-_.]?key|token|secret|password|passwd|passphrase)[A-Za-z0-9_.-]*)["']?[ \t]*(?::|=)[ \t]*(?:\r?\n[ \t]*)?(["'`])([^"'`\r\n]{12,})\2(?![ \t]*\+[ \t]*["'`])/gi,
    valueGroup: 3,
  },
  {
    name: 'structured-credential',
    pattern: /(?<![A-Za-z0-9_.${-])((?:[A-Za-z][A-Za-z0-9_.-]*\.)?(?:api[-_.]?key|token|secret|password|passwd|passphrase)[A-Za-z0-9_.-]*)[ \t]*=[ \t]*([^"'\s#;,}]{12,})/gi,
    valueGroup: 2,
    pathPattern: /\.properties$/i,
  },
  {
    name: 'structured-credential',
    pattern: /(?:^|[ \t])(?![A-Z][A-Z0-9_]*_KID=)[A-Z][A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASS)[A-Z0-9_]*=([^"'\s#;]{12,})/gm,
    valueGroup: 1,
  },
  {
    name: 'structured-credential',
    pattern: /(?<![A-Za-z0-9_.${-])((?:api[-_.]?key|token|secret|password|passwd|passphrase)|[A-Za-z][A-Za-z0-9_.-]*(?:api[-_.]?key|token|secret|password|passwd|passphrase)[A-Za-z0-9_.-]*)[ \t]*:[ \t]*([^"'\s#;,}]{12,})/gi,
    valueGroup: 2,
    pathPattern: /\.(?:ya?ml|toml|properties)$/i,
  },
  {
    name: 'database-password',
    pattern: /\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis):\/\/[^:\s/@]+:([^@\s/]+)@[^\s"']+/g,
    valueGroup: 1,
  },
]

function isTestFile(path: string): boolean {
  return /(?:^|\/)(?:test|tests)(?:\/|$)|\.(?:test|spec)\.[^/]+$/i.test(path)
}

function isSyntheticMatch(value: string): boolean {
  return /(?:^|[-_])(?:test|fake|dummy|example)(?:[-_]|$)/i.test(value)
    || /^(?:password|pass|p|grassland|user|username|用户|密码)$/i.test(value)
    || /^(?:\$|<|\[|\.\.\.)/.test(value)
    || (value.includes('[') && value.includes(']'))
      || /^(?:access-token|cookie-session|ci-media-runtime|new-password|current-password)$/i.test(value)
    || /(?:replace-with|redacted|at-least|your-)/i.test(value)
}

/** Structured-credential 也会看到源码中的变量引用、路径和模板占位，它们不是秘密值。 */
function isNonSecretStructuredValue(value: string): boolean {
  return /^(?:[A-Z][A-Z0-9_]+|[a-z_$][A-Za-z0-9_$]*\.[A-Za-z_$][A-Za-z0-9_$]*)$/.test(value)
    || /^\/?(?:run|secure|tmp|var|etc|private|Users|home)\//i.test(value)
    || /^\$\{[^}]+\}$/.test(value)
    || /^https?:\/\//i.test(value)
    || /[!?()[\]{}]/.test(value)
    || /\s/.test(value)
}

function isDedicatedSecret(value: string): boolean {
  return /^(?:sk-[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16})$/.test(value)
}

export function decodeTrackedFile(content: Uint8Array): string {
  const buffer = Buffer.from(content)

  if (buffer.length >= 2 && buffer[0] === 0xff && buffer[1] === 0xfe) {
    return buffer.subarray(2).toString('utf16le')
  }
  if (buffer.length >= 2 && buffer[0] === 0xfe && buffer[1] === 0xff) {
    return swapUtf16Bytes(buffer.subarray(2)).toString('utf16le')
  }

  const pairCount = Math.floor(buffer.length / 2)
  let evenZeroes = 0
  let oddZeroes = 0
  for (let index = 0; index < pairCount * 2; index += 2) {
    if (buffer[index] === 0) evenZeroes += 1
    if (buffer[index + 1] === 0) oddZeroes += 1
  }
  if (pairCount >= 8 && oddZeroes >= pairCount * 0.3 && evenZeroes <= pairCount * 0.05) {
    return buffer.toString('utf16le')
  }
  if (pairCount >= 8 && evenZeroes >= pairCount * 0.3 && oddZeroes <= pairCount * 0.05) {
    return swapUtf16Bytes(buffer).toString('utf16le')
  }

  return buffer.toString(buffer.includes(0) ? 'latin1' : 'utf8')
}

function swapUtf16Bytes(content: Uint8Array): Buffer {
  const swapped = Buffer.from(content)
  for (let index = 0; index + 1 < swapped.length; index += 2) {
    const byte = swapped[index]
    swapped[index] = swapped[index + 1]
    swapped[index + 1] = byte
  }
  return swapped
}

export function findTrackedSecrets(path: string, content: string): TrackedSecretFinding[] {
  const lines = content.split(/\r?\n/)
  const findings = SECRET_RULES.flatMap((rule, ruleIndex) =>
    rule.pathPattern && !rule.pathPattern.test(path) ? [] : [...content.matchAll(rule.pattern)].flatMap((match) => {
      const value = rule.valueGroups
        ? rule.valueGroups.map((group) => match[group] ?? '').join('')
        : match[rule.valueGroup ?? 0]
      if (!value || isSyntheticMatch(value)) return []
      // Vue template bindings pass variable names/expressions to child props, not secret values.
      const matchLine = content.slice(0, match.index ?? 0).split(/\r?\n/).length
      if (rule.name === 'structured-credential' && /\.(?:vue)$/i.test(path)
        && /:(?:api-key|show-secret|has-saved-secret)=/i.test(lines[matchLine - 1] ?? '')) return []
      if (rule.name === 'structured-credential' && isNonSecretStructuredValue(value)) return []
      const dedicatedValue = isDedicatedSecret(value)
      if (rule.name === 'structured-credential' && dedicatedValue && !rule.valueGroups) return []

      const index = match.index ?? 0
      const line = content.slice(0, index).split(/\r?\n/).length
      const endLine = line + match[0].split(/\r?\n/).length - 1
      const allowMarker = isTestFile(path)
        && lines.slice(line - 1, endLine).some((item) => item.includes('secret-scan: allow'))
      if (allowMarker
        && !dedicatedValue
        && !['provider-api-key', 'github-token', 'aws-access-key', 'private-key'].includes(rule.name)) {
        return []
      }
      return [{ line, rule: rule.name, index, ruleIndex }]
    }),
  )

  return findings
    .sort((left, right) => left.index - right.index || left.ruleIndex - right.ruleIndex)
    .map(({ line, rule }) => ({ line, rule }))
}
