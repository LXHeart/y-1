import { describe, expect, it } from 'vitest'
import { decodeTrackedFile, findTrackedSecrets } from '../../scripts/security/tracked-secret-scan.js'

describe('tracked secret scan', () => {
  it('detects provider tokens without returning the secret value', () => {
    const secret = `sk-live-${'a'.repeat(32)}`

    const findings = findTrackedSecrets('tmp/browser-observations.json', `{"apiKey":"${secret}"}`)

    expect(findings).toEqual([
      {
        line: 1,
        rule: 'provider-api-key',
      },
    ])
    expect(JSON.stringify(findings)).not.toContain(secret)
  })

  it('detects private keys and common hosted-service tokens', () => {
    const content = [
      '-----BEGIN ' + 'PRIVATE KEY-----',
      `github=${`ghp_${'A'.repeat(36)}`}`,
      `aws=${`AKIA${'A'.repeat(16)}`}`,
    ].join('\n')

    expect(findTrackedSecrets('config.txt', content)).toEqual([
      { line: 1, rule: 'private-key' },
      { line: 2, rule: 'github-token' },
      { line: 3, rule: 'aws-access-key' },
    ])
  })

  it('allows synthetic values but still detects real tokens in test files', () => {
    expect(findTrackedSecrets('artifact.json', '{"apiKey":"[REDACTED]"}')).toEqual([])
    expect(findTrackedSecrets('src/provider.test.ts', `const key = "sk-test-${'a'.repeat(32)}"`)).toEqual([])
    expect(findTrackedSecrets('src/provider.test.ts', `const key = "sk-live-${'a'.repeat(32)}"`)).toEqual([
      { line: 1, rule: 'provider-api-key' },
    ])
    expect(findTrackedSecrets(
      'src/provider.test.ts',
      `const key = "sk-live-${'a'.repeat(32)}" // secret-scan: allow`,
    )).toEqual([{ line: 1, rule: 'provider-api-key' }])
  })

  it('does not let a synthetic token hide a later secret on the same line', () => {
    const realCandidate = `sk-live-${'b'.repeat(32)}`

    expect(findTrackedSecrets(
      'src/provider.test.ts',
      `const keys = ["sk-test-${'a'.repeat(32)}", "${realCandidate}"]`,
    )).toEqual([{ line: 1, rule: 'provider-api-key' }])
  })

  it('detects custom credential assignments and password-bearing database urls', () => {
    const sessionSecret = 'A9f2c8D4e6B1a7C3f5D9e2B8c4A6f1D7' // secret-scan: allow - scanner fixture
    const databasePassword = 'Db9sQ4mX7vN2cK8p' // secret-scan: allow - scanner fixture
    const content = [
      `SESSION_SECRET=${sessionSecret}`,
      `DATABASE_URL=postgresql://app:${databasePassword}@db.example.invalid/app`,
      'QWEN_API_KEY=replace-with-qwen-api-key',
      'LOCAL_DATABASE_URL=postgresql://grassland:grassland@localhost:5432/grassland',
    ].join('\n')

    const findings = findTrackedSecrets('.env.production.example', content)

    expect(findings).toEqual([
      { line: 1, rule: 'structured-credential' },
      { line: 2, rule: 'database-password' },
    ])
    expect(JSON.stringify(findings)).not.toContain(sessionSecret)
    expect(JSON.stringify(findings)).not.toContain(databasePassword)
  })

  it('detects structured credentials and does not let a synthetic assignment hide a later value', () => {
    const candidates = Array.from(
      { length: 8 },
      (_, index) => `Live${index}${'A7b9C3d5E1f8'.repeat(3)}`,
    )
    const content = [
      `smtpPassword: "${candidates[0]}"`,
      `api_token = "${candidates[1]}"`,
      `const sessionSecret = "${candidates[2]}"`,
      `SESSION_SECRET=replace-with-session-secret MINIO_SECRET_KEY=${candidates[3]}`,
      `FIRST_TOKEN=${candidates[4]} SECOND_PASSWORD=${candidates[5]}`,
      'const multilineSecret =',
      `  "${candidates[6]}"`,
      `spring.mail.password: ${candidates[7]}`,
    ].join('\n')

    expect(findTrackedSecrets('config/application.yml', content)).toEqual([
      { line: 1, rule: 'structured-credential' },
      { line: 2, rule: 'structured-credential' },
      { line: 3, rule: 'structured-credential' },
      { line: 4, rule: 'structured-credential' },
      { line: 5, rule: 'structured-credential' },
      { line: 5, rule: 'structured-credential' },
      { line: 6, rule: 'structured-credential' },
      { line: 8, rule: 'structured-credential' },
    ])
  })

  it('keeps ASCII credential metadata visible when a tracked file contains NUL bytes', () => {
    const candidate = `sk-live-${'z'.repeat(32)}`
    const content = decodeTrackedFile(Buffer.from(`\u0000metadata=${candidate}\u0000`, 'utf8'))

    expect(findTrackedSecrets('artifact.bin', content)).toEqual([
      { line: 1, rule: 'provider-api-key' },
    ])
  })

  it('decodes UTF-16 credential metadata before scanning', () => {
    const candidate = `Session${'Q7w9E3r5T1y8'.repeat(3)}`
    const content = decodeTrackedFile(Buffer.from(`SESSION_SECRET=${candidate}`, 'utf16le'))

    expect(findTrackedSecrets('artifact.bin', content)).toEqual([
      { line: 1, rule: 'structured-credential' },
    ])
  })

  it('detects unquoted Spring properties and JavaScript template literals', () => {
    const propertyValue = `Mail${'R4t6Y8u2I0o9'.repeat(3)}`
    const templateValue = `Api${'P3l5K7j9H1g6'.repeat(3)}`

    expect(findTrackedSecrets('application.properties', `spring.mail.password=${propertyValue}`))
      .toEqual([{ line: 1, rule: 'structured-credential' }])
    expect(findTrackedSecrets('src/config.ts', `const apiKey = \`${templateValue}\``))
      .toEqual([{ line: 1, rule: 'structured-credential' }])
  })

  it('detects credentials split across adjacent string literals', () => {
    const content = "const password = 'LivePart' + 'CredentialSuffix12345'" // secret-scan: allow - scanner fixture

    expect(findTrackedSecrets('src/config.ts', content))
      .toEqual([{ line: 1, rule: 'structured-credential' }])
  })

  it('does not allow split provider tokens to bypass test-file allow markers', () => {
    const content = `const apiKey = 'sk-live-' + '${'a'.repeat(32)}' // secret-scan: allow`

    expect(findTrackedSecrets('src/provider.test.ts', content))
      .toEqual([{ line: 1, rule: 'structured-credential' }])
  })
})
