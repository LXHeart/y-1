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
from fastapi.responses import JSONResponse, Response

_SURFACE_ROUTES: dict[str, tuple[str, ...]] = {
    "internal": ("/health", "/internal/v1/sessions", "/internal/v1/sessions/{session_id}/commands",
                 "/internal/v1/sessions/{session_id}/state", "/internal/v1/sessions/{session_id}/webrtc-offer",
                 "/internal/v1/avatars/{avatar_id}/prepare", "/internal/v1/artifacts/{resource_id}/{object_ref}",
                 "/internal/v1/resources/{resource_id}/delete"),
    "audio": ("/health", "/api/digital-human/sessions/{session_id}/audio"),
}


def _dh_enabled() -> bool:
    return os.environ.get("DH_ENABLED", "false").strip().lower() == "true"


def create_app(
    test_mode: bool = False,
    surface: Literal["audio", "internal"] = "internal",
    *,
    bridge: Any | None = None,
) -> FastAPI:
    """构建受控 app；import 无副作用、不触网、不加载模型。

    ``bridge``（C105D-02）注入受控 ExecutionBridge：audio 面的 WS 认证（C105D-04 路由）与
    internal 面的命令处理（C105D-05）都只经它访问 Java 内部端点——固定 authority、有界 body、
    不落日志。未注入时 audio WS 保持 A03 的 fail-closed（认证前 4401）。
    """
    app = FastAPI(title="grassland-dh-runtime", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.test_mode = test_mode
    app.state.surface = surface
    app.state.sessions: dict[str, Any] = {}
    app.state.bridge = bridge

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
        async def audio_ws_route(ws: WebSocket, session_id: str) -> None:
            # C105D-04：真实音频路由（首帧鉴权/序号/背压/时限），bridge 未注入时认证即 1011 fail-closed。
            from grassland_dh.routes_audio import audio_ws

            await audio_ws(ws, session_id)

        return app

    # C105D-05：internal 控制面命令/offer 走 routes_internal（幂等 commandId、K07.3 判别、
    # ProgramAdapter 媒体代次）；状态查询保留直读。
    # C105F-01：形象面 INTERNAL10/12/13（无模型规范化 + 受控产物读取 + 资源删除），
    # bridge 缺席时 prepare 明确 503（无法回执不假装完成）。
    from grassland_dh.routes_internal import AvatarSurface, create_avatar_router, create_internal_router

    router = create_internal_router(
        lambda session_id: app.state.sessions.get(session_id),
        enabled_check=lambda: test_mode or _dh_enabled(),
    )
    avatar_surface = AvatarSurface(bridge=bridge)
    avatar_router = create_avatar_router(avatar_surface,
                                         enabled_check=lambda: test_mode or _dh_enabled())

    @app.post("/internal/v1/sessions")
    async def create_session(request: Request) -> JSONResponse:
        return await router["create_session"](request)

    @app.get("/internal/v1/sessions/{session_id}/state")
    async def session_state(session_id: str, request: Request) -> JSONResponse:
        return await router["session_state"](request)

    @app.post("/internal/v1/sessions/{session_id}/commands")
    async def session_commands(session_id: str, request: Request) -> JSONResponse:
        return await router["session_commands"](request)

    @app.post("/internal/v1/sessions/{session_id}/webrtc-offer")
    async def webrtc_offer(session_id: str, request: Request) -> JSONResponse:
        return JSONResponse(
            {"success": False, "error": "WebRTC 合同在 D 阶段落地。", "code": "dh_state_conflict"},
            status_code=409,
        )

    @app.post("/internal/v1/avatars/{avatar_id}/prepare")
    async def avatar_prepare(avatar_id: str, request: Request) -> JSONResponse:
        return await avatar_router["avatar_prepare"](request)

    @app.get("/internal/v1/artifacts/{resource_id}/{object_ref}")
    async def artifact_read(resource_id: str, object_ref: str, request: Request) -> Response:
        return await avatar_router["artifact_read"](request)

    @app.post("/internal/v1/resources/{resource_id}/delete")
    async def resource_delete(resource_id: str, request: Request) -> JSONResponse:
        return await avatar_router["resource_delete"](request)

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


def start_internal_mtls(cert_file: str, key_file: str, ca_file: str, port: int = 9443) -> None:
    """internal 控制面的 mTLS 启动（K13.2：DH_RUNTIME_CONTROL_PORT=9443，双向强制）。

    证书路径来自部署注入的 secret 文件（env 只承载路径不承载内容）；身份只认握手证书。
    该入口供 compose/部署调用；测试与 Fake 不经过真实 TLS。
    """
    import ssl

    import uvicorn

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=cert_file, keyfile=key_file)
    context.load_verify_locations(cafile=ca_file)
    context.verify_mode = ssl.CERT_REQUIRED
    uvicorn.run(
        create_internal,
        factory=True,
        host="0.0.0.0",
        port=port,
        ssl_certfile=cert_file,
        ssl_keyfile=key_file,
        ssl_ca_certs=ca_file,
        ssl_cert_reqs=int(ssl.CERT_REQUIRED),
    )
