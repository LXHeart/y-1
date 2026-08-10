package com.grassland.intelligence.douyin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class DouyinBrowserService {
    private static final List<String> QR_SELECTORS = List.of("[data-e2e=qrcode-img]", "[class*=qrcode] img",
            "[class*=qrcode] canvas", "[class*=qr] img", "[class*=qr] canvas", "img[alt*=二维码]");
    private static final Set<String> AUTH_COOKIES = Set.of("sessionid", "sessionid_ss", "sid_guard", "uid_tt",
            "uid_tt_ss", "passport_auth_status");

    private final DouyinResolveService resolver;
    private final Path statePath;
    private final String executable;
    private final String loginUrl;
    private final String userAgent;
    private final long loginTimeoutMs;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private Instant startedAt;
    private String status = "missing";
    private String message = "当前没有可用的抖音登录态。";
    private String lastAuthenticatedAt;
    private String lastUsedAt;

    public DouyinBrowserService(DouyinResolveService resolver, DouyinFetchProperties fetch, Environment environment) {
        this.resolver = resolver;
        this.statePath = Path.of(environment.getProperty("douyin.browser.storage-state-path",
                "/var/lib/grassland-media/douyin-storage-state.json")).toAbsolutePath();
        this.executable = environment.getProperty("douyin.browser.executable-path", "/usr/bin/chromium");
        this.loginUrl = environment.getProperty("douyin.browser.login-url", "https://www.douyin.com/");
        this.userAgent = fetch.cookieUserAgent();
        this.loginTimeoutMs = environment.getProperty("douyin.browser.login-timeout-ms", Long.class, 180_000L);
    }

    public synchronized Map<String, Object> snapshot() {
        refresh();
        return response(null);
    }

    public synchronized Map<String, Object> start() {
        closeJob();
        try {
            playwright = Playwright.create();
            var options = launchOptions();
            browser = playwright.chromium().launch(options);
            context = browser.newContext(new Browser.NewContextOptions().setLocale("zh-CN").setUserAgent(userAgent));
            page = context.newPage();
            page.navigate(loginUrl, new Page.NavigateOptions().setTimeout(30_000));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            assertAllowedPage(page.url());
            startedAt = Instant.now();
            status = "launching";
            message = "正在打开抖音登录页。";
            refresh();
        } catch (Exception error) {
            status = "error";
            message = "启动抖音扫码登录失败：" + error.getMessage();
            closeJob();
        }
        return response(null);
    }

    public synchronized Map<String, Object> logout() {
        closeJob();
        try { Files.deleteIfExists(statePath); } catch (Exception ignored) {}
        status = "missing";
        message = "已断开抖音登录态。";
        lastAuthenticatedAt = null;
        lastUsedAt = null;
        return response(null);
    }

    public DouyinSourceMaterial enhance(String input) {
        String entry = DouyinResolveService.extractEntryUrl(input);
        try (Playwright local = Playwright.create()) {
            var options = launchOptions();
            try (Browser localBrowser = local.chromium().launch(options)) {
                var contextOptions = new Browser.NewContextOptions().setLocale("zh-CN").setUserAgent(userAgent);
                boolean usedSession = Files.isRegularFile(statePath);
                if (usedSession) contextOptions.setStorageStatePath(statePath);
                try (BrowserContext localContext = localBrowser.newContext(contextOptions)) {
                    Page localPage = localContext.newPage();
                    localPage.navigate(entry, new Page.NavigateOptions().setTimeout(30_000));
                    localPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    assertAllowedPage(localPage.url());
                    DouyinSourceMaterial parsed = resolver.parseSourceMaterial(entry, localPage.url(), localPage.content());
                    if (usedSession) lastUsedAt = Instant.now().toString();
                    return new DouyinSourceMaterial(parsed.sourceUrl(), parsed.resolvedUrl(), parsed.videoId(), parsed.author(),
                            parsed.title(), parsed.coverUrl(), parsed.durationSeconds(), parsed.playableVideoUrl(),
                            parsed.requestHeaders(), usedSession, usedSession ? "session_browser" : "browser", parsed.challengePage());
                }
            }
        }
    }

    private void refresh() {
        if (page == null) {
            if (Files.isRegularFile(statePath) && !"expired".equals(status)) {
                status = "authenticated";
                message = "后端已保存可复用的抖音登录态。";
            }
            return;
        }
        if (startedAt.plusMillis(loginTimeoutMs).isBefore(Instant.now())) {
            status = "expired";
            message = "扫码登录已超时，请重新生成二维码。";
            closeJob();
            return;
        }
        if (authenticated(context.cookies())) {
            try {
                Files.createDirectories(statePath.getParent());
                try { Files.setPosixFilePermissions(statePath.getParent(), PosixFilePermissions.fromString("rwx------")); } catch (Exception ignored) {}
                context.storageState(new BrowserContext.StorageStateOptions().setPath(statePath));
                try { Files.setPosixFilePermissions(statePath, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
                lastAuthenticatedAt = Instant.now().toString();
                status = "authenticated";
                message = "抖音登录成功，后端已保存可复用会话。";
            } catch (Exception error) {
                status = "error";
                message = "保存抖音登录态失败。";
            }
            closeJob();
            return;
        }
        String text = page.locator("body").innerText();
        if (text.matches("(?s).*(确认登录|手机上确认|已扫码|扫描成功).*")) {
            status = "waiting_for_confirm";
            message = "已扫码，请在手机上确认登录。";
        } else {
            status = "qr_ready";
            message = "请使用抖音 App 扫描二维码。";
        }
    }

    private Map<String, Object> response(String qrOverride) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("hasPersistedSession", Files.isRegularFile(statePath));
        result.put("detailCode", "error".equals(status) ? "login_failed" : status);
        result.put("message", message);
        String qr = qrOverride == null ? qrImage() : qrOverride;
        if (qr != null) result.put("qrImageUrl", qr);
        if (lastAuthenticatedAt != null) result.put("lastAuthenticatedAt", lastAuthenticatedAt);
        if (lastUsedAt != null) result.put("lastUsedAt", lastUsedAt);
        return result;
    }

    private String qrImage() {
        if (page == null) return null;
        for (String selector : QR_SELECTORS) {
            try {
                var locator = page.locator(selector).first();
                if (locator.isVisible()) return "data:image/png;base64," + Base64.getEncoder().encodeToString(locator.screenshot());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean authenticated(List<Cookie> cookies) {
        var found = cookies.stream().filter(cookie -> AUTH_COOKIES.contains(cookie.name) && !cookie.value.isBlank())
                .collect(java.util.stream.Collectors.toMap(cookie -> cookie.name, cookie -> cookie.value, (a, b) -> a));
        return "1".equals(found.get("passport_auth_status")) ||
                ((found.containsKey("sessionid") || found.containsKey("sessionid_ss")) &&
                        (found.containsKey("sid_guard") || found.containsKey("uid_tt") || found.containsKey("uid_tt_ss")));
    }

    private BrowserType.LaunchOptions launchOptions() {
        var options = new BrowserType.LaunchOptions().setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage"));
        if (!executable.isBlank()) options.setExecutablePath(Path.of(executable));
        return options;
    }

    private static void assertAllowedPage(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !DouyinHosts.isAllowedPageHost(uri.getHost())) {
                throw new IllegalStateException("浏览器跳转到了不受信任的地址");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("浏览器跳转地址无效");
        }
    }

    private void closeJob() {
        try { if (page != null) page.close(); } catch (Exception ignored) {}
        try { if (context != null) context.close(); } catch (Exception ignored) {}
        try { if (browser != null) browser.close(); } catch (Exception ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        page = null; context = null; browser = null; playwright = null;
    }
}
