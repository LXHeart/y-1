# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Repository overview

Local-first short-video extractor for Douyin + Bilibili, with per-user auth and configuration.

Core capabilities:
- Vue 3 + Vite frontend
- Express + TypeScript backend API
- Douyin extraction with Playwright browser fallback
- Optional Douyin QR-login enhancement with persisted local session state
- Bilibili extraction with backend proxy/download flow
- Backend-mediated video analysis for both platforms
- Douyin ffmpeg audio extraction
- AI-powered article creation (topic → titles → outline → content) with SSE streaming and platform-specific output (微信公众号/知乎/小红书)
- Account registration/login with email verification and SVG CAPTCHA
- Per-user analysis settings and homepage hot-items configuration
- Multi-platform hot topics (60s API: 抖音、微博、知乎) with ALAPI alternative
- Image review generation with platform toggle (淘宝/大众点评), per-step timeline/duration display, post-run step replay, one-click copy, and Feishu docx export
- PostgreSQL-backed sessions, user data, and hot-topics cache

Primary flow:
1. User pastes Douyin or Bilibili share text / link.
2. Backend resolves playable media through platform-specific extraction logic.
3. Frontend previews via backend proxy URL.
4. User downloads video; Douyin may also extract audio.
5. User can trigger analysis via a configurable analysis provider (Coze workflow or Qwen LLM). Provider selection and credentials are managed in the settings modal and persisted server-side per user.
6. User can switch to "图片评价文案" to upload images, choose target platform (淘宝/大众点评), generate review copy with SSE progress updates, inspect per-step durations during generation, and reopen the same step timeline after completion. The generated result can also be exported to a Feishu docx document.
7. User can switch to "爆款文章" tab to create articles: enter topic → select target platform (微信公众号/知乎/小红书) → select AI-generated titles → edit outline → generate full content. Outline and content use SSE streaming. Only Qwen provider is supported for article generation. Platform selection affects title style, outline structure, and content tone.

## Commands

All real commands come from `package.json`.

### Development

```bash
npm run dev
npm run dev:client
npm run dev:server
```

- `npm run dev` runs frontend and backend together via `concurrently`
- Vite frontend defaults to port `5173`
- backend defaults to `http://localhost:3000`

### Build and production

```bash
npm run build
npm run build:client
npm run build:server
npm run start
npm run preview
```

- production server entry: `server/dist/index.js`
- in production, Express serves the frontend build output from `dist/`

### Docker Compose deployment

- `docker-compose.yml` is the deployment entrypoint.
- `Dockerfile.frontend` builds the Vite app and serves it with Nginx.
- `Dockerfile.backend` builds the Express API with `ffmpeg` installed.
- `nginx.conf` proxies `/api` and `/health` to `backend:3000`.
- `.env.docker.example` is the deployment env template.
- Current Compose scope is limited to parsing / download / audio / analysis and does **not** include Douyin login enhancement or browser fallback.
- README remains the user-facing source of truth for deployment steps and examples.

### Verification

```bash
npm run test
npm run typecheck
```

## Architecture

### Frontend

- `src/App.vue` — top-level shell with four tabs (首页 / 视频提取分析 / 图片评价文案 / 爆款文章) plus auth, settings, and hot-topics integration.
- `src/components/HomeView.vue` — Homepage with feature entry cards and multi-platform hot topics (platform tab bar for 60s provider).
- `src/components/LoginModal.vue` — Login/register modal with email verification and SVG CAPTCHA.
- `src/components/DouyinParsePanel.vue` — Douyin result / preview / download / audio / retry UI.
- `src/components/BilibiliParsePanel.vue` — Bilibili result / preview / download / retry / analysis UI.
- `src/components/DouyinSessionPanel.vue` — Douyin QR-login session UI.
- `src/components/ImageAnalysisView.vue` — Image upload + content evaluation UI with platform toggle (淘宝/大众点评), in-flight step timeline with per-step durations, post-run “查看生成步骤” entry, copy-to-clipboard button, and Feishu export trigger.
- `src/components/ArticleCreationView.vue` — Multi-stage article creation UI (topic → platform selection → titles → outline → content) with SSE streaming display.
- `src/components/AnalysisSettingsModal.vue` — Settings modal (provider selection, credentials, model config, homepage settings).
- `src/composables/useAuth.ts` — Auth state, login, register, logout, captcha, email verification code.
- `src/composables/useDouyinParse.ts` — `POST /api/douyin/extract-video` flow.
- `src/composables/useBilibiliParse.ts` — `POST /api/bilibili/extract-video` flow.
- `src/composables/useDouyinVideoAnalysis.ts` — Douyin analysis request state.
- `src/composables/useBilibiliVideoAnalysis.ts` — Bilibili analysis request state.
- `src/composables/useAnalysisSettings.ts` — Settings API composable (load/save analysis config, model discovery).
- `src/composables/useHomepageSettings.ts` — Homepage settings composable.
- `src/composables/useHomepageHotItems.ts` — Homepage hot items composable with multi-platform groups.
- `src/composables/useDouyinSession.ts` — Douyin session start/poll/logout flow.
- `src/composables/useImageAnalysis.ts` — Image upload + analysis composable, including SSE progress-event merge logic, duration-aware step state, and Feishu export request state.
- `src/composables/useArticleCreation.ts` — Article creation composable with 4-stage state machine, SSE stream consumer via fetch + ReadableStream, AbortController integration.
- `src/types/auth.ts` — Auth types (LoginFormValues, RegisterFormValues, AuthUser, etc.).
- `src/types/settings.ts` — Frontend settings types.
- `src/types/homepage-hot.ts` — Homepage hot items and groups types.
- `src/types/douyin.ts` / `src/types/bilibili.ts` — frontend response/state types.
- `src/types/article-creation.ts` — Article creation stage, title option, and platform (`ArticlePlatform`) types.

### Backend entry and HTTP surface

- `server/src/app.ts` — Express app factory, CORS, rate limits, session middleware, `/health`, static hosting, error handling.
- `server/src/routes/auth.ts` — Auth routes (login, register, captcha, send-code, logout, me).
- `server/src/routes/homepage.ts` — Homepage hot items route.
- `server/src/routes/douyin.ts` / `server/src/routes/bilibili.ts` — platform route definitions.
- `server/src/routes/settings.ts` — settings API routes (`GET/PUT /api/settings/analysis`), restricted to authenticated users.
- `server/src/routes/image-analysis.ts` — image analysis route with multer upload middleware.
- `server/src/routes/article-generation.ts` — article generation routes (`POST titles/outline/content`), SSE streaming for outline and content.
- `server/src/controllers/auth.controller.ts` — Auth handlers: login, register, captcha, send-code, logout, me.
- `server/src/controllers/homepage.controller.ts` — Homepage hot items handler.
- `server/src/controllers/settings.controller.ts` — Settings load/save handlers.
- `server/src/controllers/douyin.controller.ts` — Douyin extract/proxy/download/audio/analysis/session handlers.
- `server/src/controllers/bilibili.controller.ts` — Bilibili extract/proxy/download/analysis handlers.
- `server/src/controllers/image-analysis.controller.ts` — Image analysis SSE handler and Feishu export handler.
- `server/src/controllers/article-generation.controller.ts` — Article generation handlers.

### Backend services

- `providers/` — analysis provider abstraction: `types.ts` (interface + shared helpers + `ArticleGenerationProvider`), `registry.ts` (provider map), `coze-provider.ts` (Coze workflow), `qwen-provider.ts` (Qwen/OpenAI-compatible LLM + article generation with SSE streaming + platform-specific prompts + image review), `index.ts` (registration + exports).
- `analysis-settings.service.ts` — analysis settings persistence, merge-on-save secret preservation, credential masking. Supports per-user storage when database is configured.
- `user.service.ts` — User registration, authentication, profile lookup. Password hashing via `lib/password.ts`.
- `user-settings.service.ts` — Per-user settings (analysis + homepage) stored in database.
- `email-verification.service.ts` — Email verification code generation, storage, and validation.
- `captcha.service.ts` — SVG CAPTCHA generation, session storage with TTL, single-use validation.
- `hot-topics-60s.service.ts` — Multi-platform 60s hot topics fetcher (douyin, weibo, zhihu). Parallel fetching with graceful partial failure.
- `homepage-hot.service.ts` — Homepage hot items dispatcher. Supports 60s provider (multi-platform groups with DB caching) and ALAPI provider (in-memory cache).
- `douyin-hot.service.ts` — Douyin-specific hot items (used for video analysis, not homepage).
- `douyin-video.service.ts` / `bilibili-video.service.ts` — extraction orchestration.
- `douyin-resolve.service.ts` / `bilibili-resolve.service.ts` — upstream page/media resolution.
- `douyin-video-analysis.service.ts` / `bilibili-video-analysis.service.ts` — platform analysis orchestration.
- `douyin-analysis-media.service.ts` / `bilibili-analysis-media.service.ts` — temporary analysis-media asset lifecycle.
- `douyin-proxy.service.ts` / `bilibili-proxy.service.ts` — signed proxy/download URLs.
- `douyin-audio.service.ts` — Douyin ffmpeg audio extraction.
- `douyin-session.service.ts` — Douyin QR-login lifecycle.
- `bilibili-stream.service.ts` — Bilibili progressive vs DASH media handling.
- `video-analysis.service.ts` — shared analysis dispatcher. Routes to the selected provider (Coze or Qwen) based on persisted settings, with env-var defaults.
- `image-analysis-dispatch.service.ts` — Image analysis dispatcher.
- `feishu-export.service.ts` — Feishu docx export for image-review results. Current image path is: create empty image block → upload media with `docx_image` + `parent_node` → patch block with `replace_image`.
- `article-generation-dispatch.service.ts` — Article generation dispatcher. Only Qwen provider supported.

### Backend libraries and schemas

- `server/src/lib/env.ts` — source of truth for runtime config.
- `server/src/lib/auth.ts` — Auth helpers: session user extraction, requireAuthenticatedUser middleware, login attempt tracking.
- `server/src/lib/session.ts` — Express session configuration with PostgreSQL store.
- `server/src/lib/db.ts` — PostgreSQL connection pool (via `pg`), `isDatabaseConfigured()`, `queryDb()`.
- `server/src/lib/password.ts` — Password hashing and verification (Node.js `crypto.scryptSync`).
- `server/src/lib/email.ts` — Email sending via SMTP (nodemailer).
- `server/src/lib/provider-fetch.ts` — Provider-aware HTTP fetch with URL validation.
- `server/src/lib/provider-url.ts` — Provider URL validation with SSRF protection and trusted public API domain allowlist.
- `server/src/lib/douyin-hosts.ts` / `server/src/lib/bilibili-hosts.ts` — trusted host allowlists.
- `server/src/lib/browser.ts` — Playwright fetch path.
- `server/src/lib/http.ts` — HTTP helpers.
- `server/src/schemas/auth.ts` — Auth request validation (login, register, send-code with captchaCode).
- `server/src/schemas/douyin.ts` / `server/src/schemas/bilibili.ts` — request validation.
- `server/src/schemas/image-analysis.ts` — image analysis request validation.
- `server/src/schemas/article-creation.ts` — article generation request validation (topic, title, outline, platform).
- `server/src/schemas/settings.ts` — settings and model discovery request schemas.

## API routes

- Auth: `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/auth/captcha`, `POST /api/auth/send-code`, `POST /api/auth/logout`, `GET /api/auth/me`
- Homepage: `GET /api/homepage/hot-items`
- Douyin: `extract-video`, `analyze-video`, `analysis-media/:id`, `proxy/:token`, `download/:token`, `audio/:token`, `session`, `session/start`, `session/poll`, `session/logout`
- Bilibili: `extract-video`, `analyze-video`, `analysis-media/:id`, `proxy/:token`, `download/:token`
- Image Analysis: `POST /api/image-analysis/analyze` (SSE progress stream), `POST /api/image-analysis/export-feishu`
- Article Generation: `POST /api/article-generation/titles`, `POST /api/article-generation/outline` (SSE), `POST /api/article-generation/content` (SSE)
- Settings: `GET/PUT /api/settings/analysis`, `POST /api/settings/analysis/models`, `POST /api/settings/analysis/verify-model`
- Health: `GET /health`

## Non-obvious conventions

- Preview/download URLs are backend-tokenized proxy endpoints, not raw upstream media URLs.
- Browser must not call third-party analysis services directly.
- Analysis provider and credentials are configured via the settings modal and persisted server-side per user in database; blank secret fields in the modal mean "keep the saved secret".
- Settings access is gated behind authentication; unauthenticated users are prompted to log in.
- `video-analysis.service.ts` dispatches to the selected provider; platform services (douyin/bilibili) are provider-agnostic. Coze and Qwen env defaults are resolved independently.
- `proxyVideoUrl` used for analysis must stay site-relative or same-origin with `PUBLIC_BACKEND_ORIGIN`.
- Keep trusted-host validation, proxy URL validation, challenge detection, and session fallback semantics intact.
- Douyin login enhancement is fallback-only, not the default extraction path.
- Analysis-media endpoints are temporary internal assets; do not bypass their lifecycle/cleanup assumptions.
- `server/src/lib/env.ts` is the source of truth for runtime config.
- Article generation only supports Qwen provider; Coze is a workflow engine and does not support free-form text chat. The dispatch service returns a clear error if Coze is the active provider.
- Article outline and content use SSE streaming: the Express controller sets `text/event-stream` headers and pipes chunks from `articleQwenProvider.streamOutline/streamContent` (AsyncIterable) to the response. The frontend uses `fetch` + `response.body.getReader()` (not EventSource, which only supports GET).
- Article title generation is a non-streaming JSON request/response (fast operation).
- README is the user-facing deployment source of truth; keep `CLAUDE.md` implementation-oriented.
- Douyin direct `/video/:id` inputs skip low-value resolver-level `mobile_http` when desktop HTTP stays unresolved, but browser-internal mobile retry must remain enabled.
- Browser challenge pages may contribute useful network snippets, but challenge pages themselves are never final success material.
- Registration requires SVG CAPTCHA verification before email verification code can be sent. CAPTCHA is stored in session with 5-min TTL and single-use enforcement.
- 60s hot topics API supports douyin, weibo, zhihu (not xiaohongshu or bilibili). `hot-topics-60s.service.ts` uses `Promise.allSettled` for parallel fetching with graceful partial failure.
- Homepage hot items DB cache uses groups format; old flat-format cache data is detected and ignored by `isValidGroupsCache()`.
- Article generation accepts a `platform` parameter (`wechat`/`zhihu`/`xiaohongshu`) that selects different prompt strategies for title style, outline structure, and content tone. The platform is passed through the full pipeline: frontend composable → schema → controller → dispatch → provider.
- Image analysis accepts a `platform` parameter (`taobao`/`dianping`) that changes the review style. The analyze endpoint is an SSE stream; progress events may include `startedAt`, `completedAt`, and `durationMs`, and the frontend merges updates by logical step instead of append-only rendering.
- Image-review results include a one-click copy button and can be exported to Feishu when the user's settings contain valid Feishu app credentials.
- Feishu image export currently depends on the docx image-block flow: create image block, upload media with `parent_type=docx_image`, `parent_node=<block_id>`, and `size`, then patch that block with `replace_image`. If permissions are missing, the service surfaces an actionable message for `docs:document.media:upload`.
- `provider-url.ts` maintains a `TRUSTED_PUBLIC_API_SUFFIXES` allowlist of well-known public API domains (aliyuncs.com, api.openai.com, etc.). When the user's configured URL hostname matches a trusted suffix, the SSRF private-IP DNS check is bypassed — this handles proxy/VPN environments where legitimate domains resolve to local addresses.

## Testing notes

Default verification:

```bash
npm run test
npm run typecheck
npm run build
```

Priority tests:
- Extraction changes: `douyin-resolve.service.test.ts`, `douyin.controller.test.ts`, `douyin-proxy.service.test.ts`
- Audio changes: `douyin-audio.service.test.ts`
- Analysis changes: `video-analysis.service.test.ts`, `douyin-video-analysis.service.test.ts`, `bilibili-video-analysis.service.test.ts`, `bilibili.controller.test.ts`
- Article generation changes: `article-generation-dispatch.service.test.ts`
- Image analysis changes: `image-analysis.controller.test.ts`
- Auth changes: `auth.controller.test.ts`, `auth.test.ts`
- Hot topics changes: `hot-topics-60s.service.test.ts`, `homepage-hot.service.test.ts`
- Settings changes: `settings.controller.test.ts`, `analysis-settings.service.test.ts`

## Working conventions for edits

- Prefer small, focused changes.
- Reuse existing resolver/session/proxy/audio/analysis services instead of duplicating logic.
- Do not weaken trusted-host checks, proxy URL validation, challenge detection, or session fallback semantics.
- Public analyze endpoints must not accept browser-supplied provider endpoints or credentials; provider selection and secrets come only from persisted server settings or env defaults.
- Keep README and `CLAUDE.md` aligned with actual code and commands.
