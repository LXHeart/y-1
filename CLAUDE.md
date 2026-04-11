# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Repository overview

This repository is a local-first Douyin extractor app with:
- a Vue 3 + Vite frontend
- an Express + TypeScript backend API
- Playwright-based browser fallback for Douyin extraction
- optional QR-login-enhanced extraction with persisted local session state
- ffmpeg-based audio extraction for downloadable audio attachments

Primary user flow:
1. Paste Douyin share text or link in the frontend.
2. Backend resolves a playable video through anonymous HTTP/browser stages.
3. Frontend previews video through a backend proxy URL.
4. Users can download video or extract audio.
5. If anonymous extraction hits a challenge page, users can enable QR login enhancement and retry.

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

- `docker-compose.yml` is the default server deployment entrypoint for containerized deployment.
- `Dockerfile.frontend` builds the Vite app and serves it with Nginx.
- `Dockerfile.backend` builds and runs the Express API with `ffmpeg` installed.
- `nginx.conf` proxies `/api` and `/health` from the frontend container to `backend:3000`.
- `.env.docker.example` is the deployment env template and should be copied to `.env.docker` before running Compose.
- The current Compose setup is intentionally limited to the base parsing/download/audio/analysis flow and does **not** include Playwright login enhancement or browser fallback.
- For deployment commands and IP/custom-port examples, prefer the README Docker Compose section as the user-facing source of truth.

### Verification

```bash
npm run test
npm run typecheck
```

## Architecture

### Frontend

- `src/main.ts`
  - Vue app bootstrap.
- `src/App.vue`
  - Main page shell and top-level flow for extraction + login enhancement.
- `src/components/DouyinParsePanel.vue`
  - Extraction result panel, video preview, download video, download audio, retry UI.
- `src/components/DouyinSessionPanel.vue`
  - QR login enhancement panel, session state display, refresh/logout actions.
- `src/composables/useDouyinParse.ts`
  - Frontend state and request logic for `POST /api/douyin/extract-video`.
- `src/composables/useDouyinSession.ts`
  - Frontend session fetch/start/poll/logout flow.
- `src/types/douyin.ts`
  - Shared frontend response/state shapes.

### Backend entry and HTTP surface

- `server/src/index.ts`
  - Starts the backend server.
- `server/src/app.ts`
  - Express app factory, CORS, JSON parsing, per-endpoint rate limits, `/health`, production static hosting, error handling.
- `server/src/routes/douyin.ts`
  - Douyin route definitions.
- `server/src/controllers/douyin.controller.ts`
  - HTTP handlers for extract/proxy/download/audio/session APIs.

### Backend services

- `server/src/services/douyin-video.service.ts`
  - Main orchestration for extracting video metadata and playable/downloadable URLs.
- `server/src/services/douyin-resolve.service.ts`
  - Multi-stage Douyin source resolution, fallback logic, challenge detection, material selection, playable asset resolution.
- `server/src/services/douyin-session.service.ts`
  - QR login flow, persisted storage state management, session reuse/expiry tracking.
- `server/src/services/douyin-proxy.service.ts`
  - Signed token generation/parsing for preview/download/audio links.
- `server/src/services/douyin-audio.service.ts`
  - ffmpeg audio extraction and temp-file cleanup.

### Backend libraries and schemas

- `server/src/lib/browser.ts`
  - Playwright browser fetch path and browser-side extraction support.
- `server/src/lib/http.ts`
  - HTTP fetching helpers.
- `server/src/lib/douyin-hosts.ts`
  - Trusted page/video host allowlists.
- `server/src/lib/env.ts`
  - Environment variable schema and defaults. Treat this file as the source of truth for runtime config.
- `server/src/lib/errors.ts`
  - App error type.
- `server/src/lib/logger.ts`
  - Pino logger setup.
- `server/src/schemas/douyin.ts`
  - Zod request validation.

## API routes

Defined in `server/src/routes/douyin.ts`:
- `POST /api/douyin/extract-video`
- `GET /api/douyin/proxy/:token`
- `GET /api/douyin/download/:token`
- `GET /api/douyin/audio/:token`
- `GET /api/douyin/session`
- `POST /api/douyin/session/start`
- `GET /api/douyin/session/poll`
- `POST /api/douyin/session/logout`
- `GET /health`

## Non-obvious conventions

- Frontend preview/download URLs are backend tokenized proxy endpoints, not raw upstream Douyin media URLs.
- Backend must keep trusted-host validation intact for both page navigation and video/media URLs.
- Login enhancement is a fallback path for challenge/captcha cases, not the default extraction path.
- Persisted Douyin login state is local to the backend and should never be exposed to the frontend.
- Audio extraction depends on `ffmpeg`; temp files are created under the configured `server/.data` path and cleaned up after streaming.
- Production mode serves the built SPA from Express, so frontend/backend behavior is coupled at deployment time.
- When adding or changing runtime config, update `server/src/lib/env.ts` first and keep docs in sync.
- Anonymous resolver behavior differs by input shape: short-link/share inputs may still use `mobile_http`, while direct `douyin.com/video/:id` inputs now skip low-value `mobile_http` when desktop HTTP remains unresolved and go straight to `browser`.
- That direct-URL optimization must not disable browser-side mobile retry. Resolver-level `mobile_http` skipping and browser-internal mobile follow-up are separate controls.
- Browser challenge pages can still contribute useful network snippets, but challenge pages themselves must not be treated as final success material.

## Testing notes

Current test entrypoint:

```bash
npm run test
```

Important focused tests:
- `server/src/services/douyin-resolve.service.test.ts`
- `server/src/controllers/douyin.controller.test.ts`
- `server/src/services/douyin-audio.service.test.ts`
- `server/src/services/douyin-proxy.service.test.ts`

When changing extraction behavior, prioritize resolver and controller tests before broader validation.

## Working conventions for edits

- Prefer small, focused changes; do not broaden scope beyond the requested task.
- Reuse the existing resolver/session/proxy/audio services instead of duplicating logic.
- Do not weaken trusted host checks, challenge detection, or session fallback semantics when optimizing performance.
- Keep README and `CLAUDE.md` aligned with actual code and commands; do not document speculative infrastructure.
