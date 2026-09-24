"""公开音频 WS 路由（任务书 #105D C105D-04 / 共享契约 K07）。

`/api/digital-human/sessions/{id}/audio`：auth 首帧（JSON ≤4096 bytes、5 秒内）之前任何二进制帧
立即关闭 4401；认证经 security.consume_grant（mTLS→Java INTERNAL05）。背压判定与容量上限独立
（queue_exceeded/collector 双检查）；idle 5 秒无 chunk 终止；60 秒/1.92MB 双检查。end 后 WAV（内存）
提交 STT——Java INTERNAL08 执行面接线随 C105D-05（§9.1 已授权），未接通前明确 1011、不产生任何
经济写入。close code：4400 序号、4401 认证、4403 origin、4408 超时、4409 旧租约、4413 大小、
4429 容量、1011 依赖故障。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, AsyncIterator, Optional

from fastapi import WebSocket, WebSocketDisconnect

from grassland_dh.audio import (
    AudioFrameError,
    EndFrame,
    PcmCollector,
    is_abort,
    parse_auth,
    parse_frame,
)
from grassland_dh.security import GrantRejected, consume_grant

AUTH_MAX_JSON_BYTES = 4096
IDLE_TIMEOUT_SECONDS = 5.0
QUEUE_LIMIT_CHUNKS = 100  # 100 × 20ms = 2 秒（K07）

CLOSE_CODES = {
    "dh_auth_required": 4401,
    "dh_origin_rejected": 4403,
    "dh_grant_invalid": 4401,
    "dh_grant_expired": 4408,
    "dh_lease_stale": 4409,
    "dh_invalid_input": 4413,
    "dh_audio_invalid": 4413,
    "dh_audio_frame_too_large": 4413,
    "dh_audio_sequence": 4400,
    "dh_audio_summary_mismatch": 4400,
    "dh_audio_too_long": 4413,
    "dh_capacity_full": 4429,
    "dh_runtime_unavailable": 1011,
}


def close_code_for(code: str) -> int:
    return CLOSE_CODES.get(code, 1011)


def queue_exceeded(pending_chunks: int) -> bool:
    """发送队列水位（K07：满 2 秒等效块关闭 4429 并清缓冲）。"""
    return pending_chunks > QUEUE_LIMIT_CHUNKS


class _Close(Exception):
    def __init__(self, code: int, reason: str) -> None:
        super().__init__(reason)
        self.code = code
        self.reason = reason


async def audio_ws(ws: WebSocket, session_id: str) -> None:
    """音频会话：auth → accepted → 二进制帧 → end/abort。

    无 bridge（未装配 Java 内部面）时 auth 即 1011 关闭——fail-closed，不以假成功占位。
    """
    bridge: Optional[Any] = getattr(ws.app.state, "bridge", None)
    await ws.accept()
    try:
        binding = await _authenticate(ws, bridge, session_id)
    except _Close as closed:
        await ws.close(code=closed.code, reason=closed.reason)
        return
    await ws.send_text(json.dumps(
        {"v": 1, "type": "accepted", "turnId": binding.turn_id, "turnEpoch": binding.turn_epoch}))
    collector = PcmCollector()
    pending = 0
    try:
        async for message in _idle_limited(ws):
            if isinstance(message, bytes):
                frame = parse_frame(message, collector.expected_sequence)
                pending += max(1, int(frame.sample_count // 320))
                if queue_exceeded(pending):
                    raise AudioFrameError("dh_capacity_full")
                collector.add(frame)
                continue
            try:
                payload = json.loads(message)
            except (TypeError, ValueError):
                raise AudioFrameError("dh_audio_invalid")
            if is_abort(payload):
                collector.mark_discarded()
                await ws.close(code=1000, reason="aborted")
                return
            if payload.get("type") == "end":
                EndFrame.parse(payload, collector)
                await _dispatch_stt(ws, bridge, session_id, binding, collector)
                return
            raise AudioFrameError("dh_audio_invalid")
    except AudioFrameError as frame_error:
        await ws.close(code=close_code_for(frame_error.code), reason=frame_error.code)
    except WebSocketDisconnect:
        # 异常断开且未 end：abort 语义——turn 零 STT 派发（不因正常关闭误取消已受理转写）。
        collector.mark_discarded()
    except asyncio.TimeoutError:
        await ws.close(code=4408, reason="dh_idle_timeout")


async def _authenticate(ws: WebSocket, bridge: Optional[Any], session_id: str):
    if bridge is None:
        raise _Close(1011, "dh_runtime_unavailable")
    raw = await asyncio.wait_for(ws.receive_text(), timeout=IDLE_TIMEOUT_SECONDS)
    if len(raw.encode("utf-8")) > AUTH_MAX_JSON_BYTES:
        raise _Close(4401, "dh_auth_required")
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError):
        raise _Close(4401, "dh_auth_required")
    auth = parse_auth(payload)
    if auth is None:
        raise _Close(4401, "dh_auth_required")
    origin = ws.headers.get("origin")
    try:
        return await consume_grant(
            bridge,
            grant=auth["grant"],
            session_id=session_id,
            lease_epoch=auth["leaseEpoch"],
            origin=origin,
            request_id=auth["requestId"],
            format=auth["format"],
            sample_rate=auth["sampleRate"],
            channels=auth["channels"],
        )
    except GrantRejected as rejected:
        raise _Close(close_code_for(rejected.code), rejected.code)
    except asyncio.TimeoutError as timeout:
        raise _Close(4408, "dh_idle_timeout") from timeout


async def _idle_limited(ws: WebSocket) -> AsyncIterator[bytes | str]:
    """每帧 5 秒 idle 上限（超时抛 asyncio.TimeoutError → 4408）；透传 bytes/str。"""
    while True:
        message = await asyncio.wait_for(ws.receive(), timeout=IDLE_TIMEOUT_SECONDS)
        if message.get("type") == "websocket.disconnect":
            raise WebSocketDisconnect(message.get("code", 1000))
        data = message.get("bytes") if message.get("bytes") is not None else message.get("text")
        if data is None:
            raise AudioFrameError("dh_runtime_unavailable")
        yield data


async def _dispatch_stt(ws: WebSocket, bridge: Any, session_id: str, binding, collector: PcmCollector) -> None:
    """end 后移交唯一 STT 任务：WAV 只在内存。Java 执行面（INTERNAL08 STT multipart）随 C105D-05
    接线——未接通前明确 1011 关闭，且不创建任何经济键（确定性失败不烧凭据/积分）。"""
    try:
        meta = {"v": 1, "stage": "stt", "session_id": session_id, "turn_id": binding.turn_id,
                "duration_ms": collector.duration_ms, "pcm_sha256": None}
        async for _frame in bridge.execute("pending-stt-dispatch", "", json.dumps(meta).encode("utf-8")):
            break  # pragma: no cover — Java 面接通前不会到达
    except Exception:
        await ws.close(code=1011, reason="dh_runtime_unavailable")
        return
    await ws.close(code=1011, reason="dh_runtime_unavailable")
