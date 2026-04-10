import { chromium, type Browser, type BrowserContext, type BrowserContextOptions, type Page, devices } from 'playwright'
import { isAllowedDouyinPageHost, isAllowedDouyinVideoHost } from './douyin-hosts.js'
import { env } from './env.js'
import { AppError } from './errors.js'
import { logger } from './logger.js'

interface BrowserLaunchOptions {
  desktopUserAgent?: string
  storageStatePath?: string
  preferMobile?: boolean
  allowMobileRetry?: boolean
}

export interface BrowserFetchResult {
  finalUrl: string
  html: string
  pageJsonSnippets: string[]
  networkJsonSnippets: string[]
  mediaUrls: string[]
}

interface DouyinLoginLaunchResult {
  browser: Browser
  context: BrowserContext
  page: Page
}

export interface DouyinLoginState {
  status: 'launching' | 'qr_ready' | 'waiting_for_confirm' | 'authenticated' | 'expired'
  qrImageUrl?: string
  message?: string
}

const qrSelectors = [
  '[data-e2e="qrcode-img"]',
  '[class*="qrcode"] img',
  '[class*="qrcode"] canvas',
  '[class*="qr"] img',
  '[class*="qr"] canvas',
  'img[alt*="二维码"]',
  'img[src^="data:image/"]',
]

const authenticatedCookieNames = new Set([
  'sessionid',
  'sessionid_ss',
  'sid_guard',
  'uid_tt',
  'uid_tt_ss',
  'passport_auth_status',
])

function isAllowedDouyinBrowserUrl(url: string): boolean {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:'
      && (isAllowedDouyinPageHost(parsed.hostname) || isAllowedDouyinVideoHost(parsed.hostname))
  } catch {
    return false
  }
}

function assertAllowedDouyinHost(url: string): void {
  if (!isAllowedDouyinBrowserUrl(url)) {
    throw new AppError('浏览器抓取跳转到了不受信任的目标地址', 502)
  }
}

function extractVideoId(url: string): string | undefined {
  const match = url.match(/video\/(\d+)/) || url.match(/modal_id=(\d+)/) || url.match(/aweme_id=(\d+)/)
  return match?.[1]
}

function serializeBrowserError(error: unknown): Record<string, unknown> {
  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
    }
  }

  return { message: 'Unknown error' }
}

function hasBrowserChallengeSignals(html: string): boolean {
  return /Please wait|waf_js|_wafchallengeid|captcha|verify|安全验证|验证中|window\.WAFJS|argus-csp-token|verifyCenter|secsdk|bdms/i.test(html)
}

function sanitizeBrowserUrl(url: string): { host?: string, path?: string } {
  try {
    const parsed = new URL(url)
    return {
      host: parsed.hostname,
      path: parsed.pathname,
    }
  } catch {
    return {}
  }
}

function buildBrowserContextOptions(options: BrowserLaunchOptions): BrowserContextOptions {
  return {
    userAgent: options.desktopUserAgent || env.DOUYIN_USER_AGENT,
    locale: 'zh-CN',
    ...(options.storageStatePath ? { storageState: options.storageStatePath } : {}),
  }
}

async function logBrowserPageState(page: Page, stage: 'after_goto' | 'after_waits'): Promise<void> {
  try {
    const pageUrl = sanitizeBrowserUrl(page.url())
    const diagnostics = await page.evaluate(() => {
      const bodyText = document.body?.textContent || ''

      return {
        pageTitleLength: document.title.length,
        bodyLength: bodyText.length,
        hasVerifyText: /验证码|安全验证|captcha|verify/i.test(bodyText),
      }
    }, { timeout: 200 }).catch(() => ({ pageTitleLength: 0, bodyLength: 0, hasVerifyText: false }))

    logger.info({
      browserStage: stage,
      currentHost: pageUrl.host,
      currentPath: pageUrl.path,
      pageTitleLength: diagnostics.pageTitleLength,
      bodyLength: diagnostics.bodyLength,
      hasVerifyText: diagnostics.hasVerifyText,
    }, 'Douyin browser page state')
  } catch (error: unknown) {
    logger.warn({ browserStage: stage, err: serializeBrowserError(error) }, 'Douyin browser diagnostics failed')
  }
}

const browserStageBudgetMs = 3500

async function waitForPageSettled(page: Page): Promise<void> {
  const deadline = Date.now() + browserStageBudgetMs

  await page.waitForLoadState('domcontentloaded', { timeout: Math.min(browserStageBudgetMs, 1200) }).catch(() => undefined)

  if (page.url().includes('/share/video/')) {
    return
  }

  const pageSignals = await page.evaluate(() => {
    const bodyText = document.body?.textContent || ''
    return {
      hasShareLink: Boolean(document.querySelector('a[href*="iesdouyin.com/share/video"], a[href*="/share/video/"]')),
      hasVerifyText: /验证码|安全验证|captcha|verify/i.test(bodyText),
    }
  }, { timeout: 200 }).catch(() => ({ hasShareLink: false, hasVerifyText: false }))

  if (pageSignals.hasShareLink || pageSignals.hasVerifyText) {
    return
  }

  const remainingMs = Math.max(deadline - Date.now(), 0)
  if (remainingMs === 0) {
    return
  }

  await page.waitForFunction(() => {
    const bodyText = document.body?.textContent || ''
    return window.location.href.includes('/share/video/')
      || Boolean(document.querySelector('a[href*="iesdouyin.com/share/video"], a[href*="/share/video/"]'))
      || /验证码|安全验证|captcha|verify/i.test(bodyText)
  }, { timeout: Math.min(remainingMs, 1500) }).catch(() => undefined)
}

function rankBrowserJsonSnippet(content: string): number {
  let score = 0

  if (/aweme|aweme_detail|detail|videoDetail|itemInfo/i.test(content)) {
    score += 6
  }
  if (/desc|caption|author|nickname|unique_id/i.test(content)) {
    score += 4
  }
  if (/__INITIAL_STATE__|SIGI_STATE|__NEXT_DATA__|RENDER_DATA/i.test(content)) {
    score += 3
  }

  return score
}

function sortBrowserJsonSnippets(snippets: Iterable<string>): string[] {
  return Array.from(new Set(snippets)).sort((left, right) => {
    const rankDiff = rankBrowserJsonSnippet(right) - rankBrowserJsonSnippet(left)
    if (rankDiff !== 0) {
      return rankDiff
    }

    const lengthDiff = right.length - left.length
    if (lengthDiff !== 0) {
      return lengthDiff
    }

    return left.localeCompare(right)
  })
}

function collectJsonStrings(value: unknown, results: Set<string>): void {
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed && trimmed.length >= 20 && (trimmed.startsWith('{') || trimmed.startsWith('['))) {
      results.add(trimmed)
    }
    return
  }

  if (Array.isArray(value)) {
    for (const item of value) {
      collectJsonStrings(item, results)
    }
    return
  }

  if (typeof value === 'object' && value !== null) {
    for (const nestedValue of Object.values(value)) {
      collectJsonStrings(nestedValue, results)
    }
  }
}

async function collectBrowserJsonSnippets(page: Page): Promise<string[]> {
  const jsonStrings = await page.evaluate(() => {
    const results = new Set<string>()
    const maybeSerialize = (value: unknown) => {
      if (!value || typeof value !== 'object') {
        return
      }

      try {
        const serialized = JSON.stringify(value)
        if (serialized && serialized.length >= 20) {
          results.add(serialized)
        }
      } catch {
        // ignore non-serializable values
      }
    }

    const windowRecord = window as unknown as Record<string, unknown>
    const preferredKeys = [
      '__INITIAL_STATE__',
      'SIGI_STATE',
      '__NEXT_DATA__',
      '__RENDER_DATA__',
      '_ROUTER_DATA',
      '__STORE__',
    ]

    for (const key of preferredKeys) {
      maybeSerialize(windowRecord[key])
    }

    for (const key of Object.keys(windowRecord)) {
      if (/state|render|store|aweme|detail|video|item/i.test(key)) {
        maybeSerialize(windowRecord[key])
      }
    }

    return Array.from(results)
  }).catch(() => [])

  const results = new Set<string>()
  collectJsonStrings(jsonStrings, results)
  return sortBrowserJsonSnippets(results).slice(0, 20)
}

function hasUsefulBrowserNetworkSignal(networkJsonSnippets: Set<string>, mediaUrls: Set<string>): boolean {
  return networkJsonSnippets.size > 0 || mediaUrls.size > 0
}

async function readBrowserHtml(page: Page): Promise<string> {
  return page.content().catch(() => '')
}

const responseInspectionDeadlineMs = 250
const responseInspectionQuietWindowMs = 60

function waitForTimeout(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

async function waitForResponseInspectionSettled(
  pendingJsonInspections: Set<Promise<void>>,
  networkJsonSnippets: Set<string>,
  mediaUrls: Set<string>,
): Promise<void> {
  const deadline = Date.now() + responseInspectionDeadlineMs

  while (Date.now() < deadline) {
    if (hasUsefulBrowserNetworkSignal(networkJsonSnippets, mediaUrls)) {
      return
    }

    if (pendingJsonInspections.size === 0) {
      await waitForTimeout(responseInspectionQuietWindowMs)
      if (pendingJsonInspections.size === 0 || hasUsefulBrowserNetworkSignal(networkJsonSnippets, mediaUrls)) {
        return
      }
      continue
    }

    const pending = Array.from(pendingJsonInspections)
    const remainingTime = Math.max(deadline - Date.now(), 0)
    await Promise.race([
      Promise.allSettled(pending),
      waitForTimeout(Math.min(responseInspectionQuietWindowMs, remainingTime)),
    ])
  }
}

async function tryFetchPage(browser: Browser, url: string, options: BrowserContextOptions): Promise<BrowserFetchResult> {
  const context = await browser.newContext(options)
  const page = await context.newPage()
  const networkJsonSnippets = new Set<string>()
  const mediaUrls = new Set<string>()
  const pendingJsonInspections = new Set<Promise<void>>()
  const attemptStartedAt = Date.now()

  try {
    page.on('response', (response) => {
      const inspection = (async () => {
        try {
          const responseUrl = response.url()
          const hostname = new URL(responseUrl).hostname
          if (!isAllowedDouyinPageHost(hostname) && !isAllowedDouyinVideoHost(hostname)) {
            return
          }

          const contentType = response.headers()['content-type'] || ''
          if (contentType.startsWith('video/')) {
            mediaUrls.add(responseUrl)
            return
          }

          if (!contentType.includes('application/json')) {
            return
          }

          const json = await response.json()
          const serialized = JSON.stringify(json)
          if (serialized.length >= 20) {
            networkJsonSnippets.add(serialized)
          }
        } catch {
          // ignore response inspection failures
        }
      })()

      pendingJsonInspections.add(inspection)
      void inspection.finally(() => {
        pendingJsonInspections.delete(inspection)
      })
    })

    await page.route('**/*', (route) => {
      const resourceType = route.request().resourceType()
      const requestUrl = route.request().url()

      if (resourceType === 'document' && !isAllowedDouyinBrowserUrl(requestUrl)) {
        return route.abort()
      }

      if (resourceType === 'image' || resourceType === 'font') {
        return route.abort()
      }

      return route.continue()
    })

    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'webdriver', {
        get: () => false,
      })
    })

    await page.goto(url, {
      waitUntil: 'domcontentloaded',
      timeout: env.DOUYIN_FETCH_TIMEOUT_MS,
    })

    await logBrowserPageState(page, 'after_goto')
    await waitForPageSettled(page)
    await logBrowserPageState(page, 'after_waits')

    const currentUrl = page.url()
    assertAllowedDouyinHost(currentUrl)

    const shareHref = await page.evaluate(() => {
      const anchor = document.querySelector('a[href*="iesdouyin.com/share/video"], a[href*="/share/video/"]')
      return anchor instanceof HTMLAnchorElement ? anchor.href : undefined
    }).catch(() => undefined)
    const candidateUrl = shareHref || currentUrl
    assertAllowedDouyinHost(candidateUrl)

    const finalUrl = extractVideoId(candidateUrl) ? candidateUrl : currentUrl
    await waitForResponseInspectionSettled(pendingJsonInspections, networkJsonSnippets, mediaUrls)

    const html = await readBrowserHtml(page)
    const pageJsonSnippets = hasUsefulBrowserNetworkSignal(networkJsonSnippets, mediaUrls)
      ? []
      : sortBrowserJsonSnippets(await collectBrowserJsonSnippets(page)).slice(0, 20)

    logger.info({
      browserTarget: { host: new URL(url).hostname, path: new URL(url).pathname },
      finalTarget: { host: new URL(finalUrl).hostname, path: new URL(finalUrl).pathname },
      durationMs: Date.now() - attemptStartedAt,
      networkJsonSnippetCount: networkJsonSnippets.size,
      mediaUrlCount: mediaUrls.size,
      challengeDetected: hasBrowserChallengeSignals(html),
      pageJsonSnippetCount: pageJsonSnippets.length,
      networkSignalReady: hasUsefulBrowserNetworkSignal(networkJsonSnippets, mediaUrls),
    }, 'Douyin browser fetch timing')

    return {
      finalUrl,
      html,
      pageJsonSnippets,
      networkJsonSnippets: sortBrowserJsonSnippets(networkJsonSnippets).slice(0, 20),
      mediaUrls: Array.from(mediaUrls),
    }
  } finally {
    await context.close()
  }
}

async function resolveQrImageUrl(page: Page): Promise<string | undefined> {
  return page.evaluate((selectors) => {
    type Candidate = {
      score: number
      dataUrl?: string
    }

    const candidates: Candidate[] = []

    for (const selector of selectors) {
      for (const node of Array.from(document.querySelectorAll(selector))) {
        if (!(node instanceof HTMLElement)) {
          continue
        }

        const style = window.getComputedStyle(node)
        const rect = node.getBoundingClientRect()
        if (style.display === 'none' || style.visibility === 'hidden' || rect.width < 120 || rect.height < 120) {
          continue
        }

        const ownText = [
          node.getAttribute('alt') || '',
          node.getAttribute('aria-label') || '',
          node.getAttribute('class') || '',
          node.getAttribute('data-e2e') || '',
        ].join(' ')

        const containerText = [
          node.parentElement?.textContent || '',
          node.parentElement?.getAttribute('class') || '',
          node.closest('[role="dialog"]')?.textContent || '',
          node.closest('[class*="login"]')?.textContent || '',
          node.closest('[class*="qr"]')?.textContent || '',
        ].join(' ')

        const text = `${ownText} ${containerText}`

        let dataUrl: string | undefined
        if (node instanceof HTMLImageElement) {
          const src = node.currentSrc || node.src
          if (src.startsWith('data:image/')) {
            dataUrl = src
          }
        }

        if (node instanceof HTMLCanvasElement) {
          dataUrl = node.toDataURL('image/png')
        }

        if (!dataUrl?.startsWith('data:image/')) {
          continue
        }

        let score = 0
        if (/二维码|扫码|scan|qrcode|qr|登录/.test(text)) {
          score += 5
        }
        if (/logo/i.test(text)) {
          score -= 6
        }
        if (node instanceof HTMLCanvasElement) {
          score += 4
        }
        if (Math.abs(rect.width - rect.height) <= 20) {
          score += 2
        }
        if (rect.width >= 160 && rect.width <= 420 && rect.height >= 160 && rect.height <= 420) {
          score += 2
        }

        candidates.push({ score, dataUrl })
      }
    }

    candidates.sort((left, right) => right.score - left.score)
    return candidates[0]?.score >= 4 ? candidates[0].dataUrl : undefined
  }, qrSelectors).catch(() => undefined)
}

async function hasAuthenticatedCookies(context: BrowserContext): Promise<boolean> {
  const cookies = await context.cookies([
    'https://douyin.com/',
    'https://www.douyin.com/',
    'https://iesdouyin.com/',
    'https://www.iesdouyin.com/',
  ])
  const authenticatedCookies = cookies.filter((cookie) => authenticatedCookieNames.has(cookie.name) && Boolean(cookie.value))
  const authenticatedCookieNamesFound = new Set(authenticatedCookies.map((cookie) => cookie.name))
  const hasSessionCookie = authenticatedCookieNamesFound.has('sessionid') || authenticatedCookieNamesFound.has('sessionid_ss')
  const hasGuardCookie = authenticatedCookieNamesFound.has('sid_guard')
  const hasUserCookie = authenticatedCookieNamesFound.has('uid_tt') || authenticatedCookieNamesFound.has('uid_tt_ss')
  const passportAuthCookie = authenticatedCookies.find((cookie) => cookie.name === 'passport_auth_status')
  const isPassportAuthenticated = passportAuthCookie?.value === '1'
  const hasCookieBundle = hasSessionCookie && (hasGuardCookie || hasUserCookie)

  logger.info({
    cookieNames: Array.from(authenticatedCookieNamesFound),
    hasSessionCookie,
    hasGuardCookie,
    hasUserCookie,
    isPassportAuthenticated,
    hasCookieBundle,
  }, 'Douyin login cookie signals')

  return isPassportAuthenticated || hasCookieBundle
}

async function readLoginPageText(page: Page): Promise<string> {
  return page.locator('body').innerText().catch(() => '')
}

async function openLoginModal(page: Page): Promise<void> {
  const rolePatterns = [/登录/, /立即登录/, /扫码登录/]

  for (const pattern of rolePatterns) {
    const button = page.getByRole('button', { name: pattern }).first()
    const isVisible = await button.isVisible().catch(() => false)
    if (!isVisible) {
      continue
    }

    await button.click().catch(() => undefined)
    await page.waitForTimeout(1200)
    return
  }

  const textPatterns = ['登录', '立即登录', '扫码登录']
  for (const text of textPatterns) {
    const locator = page.getByText(text, { exact: false }).first()
    const isVisible = await locator.isVisible().catch(() => false)
    if (!isVisible) {
      continue
    }

    await locator.click().catch(() => undefined)
    await page.waitForTimeout(1200)
    return
  }
}

export async function launchDouyinLoginPage(options: { loginUrl: string, desktopUserAgent?: string }): Promise<DouyinLoginLaunchResult> {
  assertAllowedDouyinHost(options.loginUrl)

  const browser = await chromium.launch({
    headless: false,
    channel: 'chrome',
  })

  const context = await browser.newContext(buildBrowserContextOptions({
    desktopUserAgent: options.desktopUserAgent,
  }))
  const page = await context.newPage()

  try {
    await page.goto(options.loginUrl, {
      waitUntil: 'domcontentloaded',
      timeout: env.DOUYIN_FETCH_TIMEOUT_MS,
    })
    assertAllowedDouyinHost(page.url())
    await page.waitForTimeout(1500)
    await logBrowserPageState(page, 'after_goto')
    await openLoginModal(page)
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined)
    await page.waitForTimeout(1500)
    await logBrowserPageState(page, 'after_waits')

    return { browser, context, page }
  } catch (error: unknown) {
    await page.close().catch(() => undefined)
    await context.close().catch(() => undefined)
    await browser.close().catch(() => undefined)
    throw new AppError(error instanceof Error ? error.message : '打开抖音登录页失败', 502)
  }
}

export async function readDouyinLoginState(context: BrowserContext, page: Page): Promise<DouyinLoginState> {
  const text = await readLoginPageText(page)
  const currentUrl = page.url()
  const qrImageUrl = await resolveQrImageUrl(page)
  const authenticated = await hasAuthenticatedCookies(context)
  const hasQrText = /扫码登录|请扫码|打开抖音扫码|二维码/.test(text)
  const isWaitingForConfirm = /确认登录|请在手机上确认|手机确认|已扫码|扫描成功|确认本次登录/.test(text)
  const isExpired = /二维码已失效|刷新二维码/.test(text)
  const isLikelyAuthenticatedPage = !/login|passport/.test(currentUrl) && !hasQrText && !isWaitingForConfirm
  const isLikelyAnonymousContentPage = isLikelyAuthenticatedPage && !authenticated

  logger.info({
    loginStateUrl: sanitizeBrowserUrl(currentUrl),
    loginTextLength: text.length,
    hasQrImage: Boolean(qrImageUrl),
    authenticated,
    hasQrText,
    isWaitingForConfirm,
    isLikelyAuthenticatedPage,
    isLikelyAnonymousContentPage,
  }, 'Douyin login state check')

  if (authenticated && isLikelyAuthenticatedPage) {
    return {
      status: 'authenticated',
      message: '检测到抖音登录已完成。',
    }
  }

  if (isExpired) {
    return {
      status: 'expired',
      qrImageUrl,
      message: '二维码已失效，请重新发起扫码。',
    }
  }

  if (isWaitingForConfirm) {
    return {
      status: 'waiting_for_confirm',
      qrImageUrl,
      message: '已扫码，请在手机上确认登录。',
    }
  }

  if (authenticated && !qrImageUrl) {
    return {
      status: 'authenticated',
      message: '检测到抖音登录已完成。',
    }
  }

  if (qrImageUrl || hasQrText || /login|passport/.test(currentUrl) || isLikelyAnonymousContentPage) {
    return {
      status: 'qr_ready',
      qrImageUrl,
      message: '请使用抖音 App 扫码登录。',
    }
  }

  return {
    status: 'launching',
    qrImageUrl,
    message: '正在等待抖音登录页加载二维码。',
  }
}

export async function saveBrowserStorageState(context: BrowserContext, storageStatePath: string): Promise<void> {
  await context.storageState({ path: storageStatePath })
}

export async function fetchPageWithBrowser(url: string, options: BrowserLaunchOptions = {}): Promise<BrowserFetchResult> {
  assertAllowedDouyinHost(url)

  const allowMobileRetry = options.allowMobileRetry ?? true
  let browser: Browser | undefined

  try {
    browser = await chromium.launch({
      headless: true,
      channel: 'chrome',
    })

    if (options.preferMobile) {
      return await tryFetchPage(browser, url, {
        ...devices['iPhone 13'],
        locale: 'zh-CN',
        ...(options.storageStatePath ? { storageState: options.storageStatePath } : {}),
      })
    }

    const desktopAttempt = await tryFetchPage(browser, url, buildBrowserContextOptions(options))
    const desktopHasUsefulSignal = desktopAttempt.mediaUrls.length > 0 || desktopAttempt.networkJsonSnippets.length > 0

    if (desktopAttempt.finalUrl.includes('iesdouyin.com/share/video/') && desktopHasUsefulSignal) {
      return desktopAttempt
    }

    if (desktopHasUsefulSignal) {
      logger.info({
        finalTarget: sanitizeBrowserUrl(desktopAttempt.finalUrl),
        networkJsonSnippetCount: desktopAttempt.networkJsonSnippets.length,
        mediaUrlCount: desktopAttempt.mediaUrls.length,
        challengeDetected: hasBrowserChallengeSignals(desktopAttempt.html),
      }, 'Douyin browser fetch skipped mobile retry')
      return desktopAttempt
    }

    if (!allowMobileRetry) {
      logger.info({
        finalTarget: sanitizeBrowserUrl(desktopAttempt.finalUrl),
        networkJsonSnippetCount: desktopAttempt.networkJsonSnippets.length,
        mediaUrlCount: desktopAttempt.mediaUrls.length,
        challengeDetected: hasBrowserChallengeSignals(desktopAttempt.html),
        allowMobileRetry,
      }, 'Douyin browser fetch skipped mobile retry')
      return desktopAttempt
    }

    try {
      const mobileAttempt = await tryFetchPage(browser, url, {
        ...devices['iPhone 13'],
        locale: 'zh-CN',
        ...(options.storageStatePath ? { storageState: options.storageStatePath } : {}),
      })

      return mobileAttempt.finalUrl.includes('iesdouyin.com/share/video/') ? mobileAttempt : desktopAttempt
    } catch (error: unknown) {
      logger.warn({ err: serializeBrowserError(error) }, 'Douyin mobile browser fetch failed')
      return desktopAttempt
    }
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    throw new AppError('浏览器抓取抖音页面失败', 502)
  } finally {
    await browser?.close()
  }
}
