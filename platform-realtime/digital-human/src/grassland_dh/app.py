"""受控 FastAPI 应用：只挂 health 与受控 internal 路由（任务书 #105A-03）。

绝不挂上游原生管理/会话/记忆/outputs/runtime-config 路由，绝不启动 apps.api.main。
两个 surface：
  - surface="internal"（默认，控制面 INTERNAL01～04 的最小受控实现；完整合同 C/D 落地）
  - surface="audio"（公开音频 WS listener：仅精确 audio 路径 + health；A03 阶段未到
    C/D 的 WS 合同，一律 4401 关闭）
生产默认 test_mode=False 且 DH_ENABLED=false：internal 写路径返回 503
dh_runtime_unavailable，不派发任何 provider。
"""

from __future__ import annotations

import os
from typing import Any, Literal

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse

_SURFACE_ROUTES: dict[str, tuple[str, ...]] = {
    "internal": ("/health", "/internal/v1/sessions", "/internal/v1/sessions/{session_id}/commands",
                 "/internal/v1/sessions/{session_id}/state", "/internal/v1/sessions/{session_id}/webrtc-offer"),
    "audio": ("/health", "/api/digital-human/sessions/{session_id}/audio"),
}


def _dh_enabled() -> bool:
    return os.environ.get("DH_ENABLED", "false").strip().lower() == "true"


def create_app(
    test_mode: bool = False,
    surface: Literal["audio", "internal"] = "internal",
) -> FastAPI:
    """构建受控 app；import 无副作用、不触网、不加载模型。"""
    app = FastAPI(title="grassland-dh-runtime", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.test_mode = test_mode
    app.state.surface = surface
    app.state.sessions: dict[str, Any] = {}

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {
            "ok": True,
            "surface": surface,
            "testMode": test_mode,
            "enabled": _dh_enabled() or test_mode,
        }

    if surface == "audio":
        @app.websocket("/api/digital-human/sessions/{session_id}/audio")
        async def audio_ws(ws: WebSocket, session_id: str) -> None:
            # A03 未交付 C/D 的 WS 认证合同；唯一安全默认=认证前即关闭 4401。
            await ws.close(code=4401, reason="dh_auth_required")

        return app

    @app.post("/internal/v1/sessions")
    async def create_session(request: Request) -> JSONResponse:
        if not (test_mode or _dh_enabled()):
            return JSONResponse(
                {"success": False, "error": "数字人运行时未启用。", "code": "dh_runtime_unavailable"},
                status_code=503,
            )
        # 最小受控实现：仅 test_mode 接受合成 Binding（真实控制面合同在 C/D 落地）
        body = await request.json()
        from grassland_dh.adapters import RunnerAdapter
        from grassland_dh.bindings import SessionBinding

        wire = body.get("binding", body)
        binding = SessionBinding(
            session_id=str(wire["sessionId"]),
            lease_epoch=int(wire["leaseEpoch"]),
            media_epoch=int(wire["mediaEpoch"]),
            backend_id=str(wire["backendId"]),
            profile_revision=int(wire["profileRevision"]),
            expires_at=_parse_utc(str(wire["expiresAt"])),
            bridge_base_url=str(wire.get("bridgeBaseUrl", "")),
        )
        session = await RunnerAdapter(test_mode=True).create(binding)
        request.app.state.sessions[binding.session_id] = session
        return JSONResponse({"sessionId": binding.session_id, "workerId": "fake-worker-1",
                             "leaseEpoch": binding.lease_epoch, "mediaEpoch": session.media_epoch,
                             "state": "ready", "activeTurnId": None, "leaseExpiresAt": None,
                             "mediaReady": True, "cleanupPending": False}, status_code=202)

    @app.get("/internal/v1/sessions/{session_id}/state")
    async def session_state(session_id: str, request: Request) -> JSONResponse:
        session = request.app.state.sessions.get(session_id)
        if session is None:
            return JSONResponse(
                {"success": False, "error": "会话不存在。", "code": "dh_not_found"},
                status_code=404,
            )
        return JSONResponse({
            "sessionId": session_id,
            "workerId": "fake-worker-1",
            "leaseEpoch": session.binding.lease_epoch,
            "mediaEpoch": session.media_epoch,
            "state": "ready",
            "activeTurnId": session.active_turn.turn_id if session.active_turn else None,
            "leaseExpiresAt": None,
            "mediaReady": True,
            "cleanupPending": False,
        })

    @app.post("/internal/v1/sessions/{session_id}/commands")
    async def session_commands(session_id: str, request: Request) -> JSONResponse:
        session = request.app.state.sessions.get(session_id)
        if session is None:
            return JSONResponse(
                {"success": False, "error": "会话不存在。", "code": "dh_not_found"},
                status_code=404,
            )
        if not (test_mode or _dh_enabled()):
            return JSONResponse(
                {"success": False, "error": "数字人运行时未启用。", "code": "dh_runtime_unavailable"},
                status_code=503,
            )
        return JSONResponse(
            {"success": False, "error": "命令合同在 C/D 阶段落地。", "code": "dh_state_conflict"},
            status_code=409,
        )

    @app.post("/internal/v1/sessions/{session_id}/webrtc-offer")
    async def webrtc_offer(session_id: str, request: Request) -> JSONResponse:
        return JSONResponse(
            {"success": False, "error": "WebRTC 合同在 D 阶段落地。", "code": "dh_state_conflict"},
            status_code=409,
        )

    return app


def _parse_utc(value: str):
    from datetime import datetime

    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def create_internal() -> FastAPI:
    """uvicorn --factory 入口：internal 控制面（Dockerfile 默认 CMD）。"""
    return create_app(test_mode=False, surface="internal")


def create_audio() -> FastAPI:
    """uvicorn --factory 入口：公开音频 WS 面（compose 单独装配 9080 listener）。"""
    return create_app(test_mode=False, surface="audio")
