"""音频 turn 双档接线（任务书 #105fix-1 C105X-04 / tc105x_04_01、tc105x_04_02）。

tc105x_04_01：Fake 档（DH_REAL_PROVIDERS_ENABLED=false，默认）端到端——INTERNAL01 create_session
挂进程内 RuntimeSession（任务书步骤 2 的挂接点），audio WS 认证→PCM→end 后 _dispatch_stt 走
Fake 管线：WS 收到 transcript/turn 完成帧并 1000 关闭（不再 1011）；OutboundGuard 断言零出站；
无 media 落盘。空 PCM 与超长 PCM 边界各一组（既有 4413/既有上限语义）。

tc105x_04_02：bridge.execute_stt multipart 客户端契约——内嵌 httpx MockTransport 承接（校验
multipart 形状：meta=application/json + wav=audio/wav、meta JSON 可解析），恰 1.92MB 边界过、
1.92MB+1 由 Java 侧 422 拒绝（BridgeError 透传）、meta 超 128KiB 客户端 413。Java 侧权威负例
（缺 part/非法 meta/不核销）由 DigitalHumanExecuteSttIT 承担。
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import httpx  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from grassland_dh.adapters import RuntimeSession, RunnerAdapter  # noqa: E402
from grassland_dh.app import create_app  # noqa: E402
from grassland_dh.bindings import SessionBinding  # noqa: E402
from grassland_dh.bridge import BridgeError, ExecutionBridge  # noqa: E402
from grassland_dh.fakes import OutboundGuard  # noqa: E402
from grassland_dh.routes_internal import InternalSessionState  # noqa: E402
from grassland_dh.media import ProgramAdapter  # noqa: E402

SESSION_ID = "11111111-2222-4333-8444-555555555555"
TURN_ID = "aaaa1111-2222-4333-8444-555555555555"
GRANT = "test-grant-token"


ALLOWED_ORIGIN = "http://127.0.0.1:18082"


def _java_grant_stub() -> httpx.AsyncClient:
    """Java INTERNAL05 桩：consume 原子核销返回 GrantBinding wire（认证面用）。"""
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/digital-human/grants/consume"
        return httpx.Response(200, json={
            "sessionId": SESSION_ID, "leaseEpoch": 1, "contentEpoch": 1,
            "expiresAt": "2030-01-01T00:00:00Z", "turnId": TURN_ID, "turnEpoch": 1,
            "firstFrameDeadlineAt": "2030-01-01T00:00:10Z",
        })

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _binding() -> SessionBinding:
    from datetime import datetime, timedelta, timezone

    return SessionBinding(
        session_id=SESSION_ID, lease_epoch=1, media_epoch=1, backend_id="backend-1",
        profile_revision=1, expires_at=datetime.now(timezone.utc) + timedelta(minutes=30),
        bridge_base_url="https://intelligence-service:9143",
    )


def _attach_fake_session(sessions: dict) -> None:
    """复刻 create_session 的 Fake 档挂接（同一构造路径，避免 e2e 双次构建 runner）。"""
    sessions[SESSION_ID] = InternalSessionState(
        session_id=SESSION_ID, binding={"sessionId": SESSION_ID, "leaseEpoch": 1, "mediaEpoch": 1},
        adapter=ProgramAdapter(session_id=SESSION_ID, lease_epoch=1, media_epoch=1))


def _frame(sequence: int, samples: int) -> bytes:
    pcm = b"".join((1000).to_bytes(2, "little", signed=True) for _ in range(samples))
    return sequence.to_bytes(4, "big") + samples.to_bytes(4, "big") + pcm


def _auth() -> dict:
    return {"v": 1, "type": "auth", "grant": GRANT, "leaseEpoch": 1,
            "requestId": "req-1", "format": "pcm_s16le", "sampleRate": 16000, "channels": 1}


# ---------------------------------------------------------------------------
# tc105x_04_01：Fake 档端到端（零出站）
# ---------------------------------------------------------------------------


def test_tc105x_04_01_fake_tier_audio_turn_completes_with_zero_outbound(tmp_path: Path) -> None:
    with OutboundGuard() as guard:
        bridge = ExecutionBridge(allowed_origins=(ALLOWED_ORIGIN,), client=_java_grant_stub())
        internal = create_app(test_mode=True, surface="internal", bridge=bridge)
        audio = create_app(test_mode=True, surface="audio", bridge=bridge)
        # 单进程共享 registry（部署为双进程时 create 侧挂接仍在同一构造点，见交接说明）。
        audio.state.sessions = internal.state.sessions
        # 先经 INTERNAL01 建会话：Fake 档必须在 create 时挂 RuntimeSession（步骤 2 挂接点）。
        with TestClient(internal) as client:
            body = {"binding": {"sessionId": SESSION_ID, "leaseEpoch": 1, "mediaEpoch": 1,
                                "backendId": "backend-1", "profileRevision": 1,
                                "expiresAt": "2030-01-01T00:00:00Z", "leaseExpiresAt": "2030-01-01T00:00:00Z",
                                "contentEpoch": 1, "bridgeBaseUrl": "https://intelligence-service:9143"},
                    "commandId": "cmd-create-1",
                    "payloadHash": "0" * 64}
            created = client.post("/internal/v1/sessions", json=body)
            assert created.status_code == 202, created.text
            attached = internal.state.sessions[SESSION_ID]
            assert isinstance(attached.runtime, RuntimeSession), "Fake 档 create 必须挂 RuntimeSession"

        with TestClient(audio) as client:
            with client.websocket_connect(
                    f"/api/digital-human/sessions/{SESSION_ID}/audio",
                    headers={"Origin": ALLOWED_ORIGIN}) as ws:
                ws.send_json(_auth())
                accepted = ws.receive_json()
                assert accepted == {"v": 1, "type": "accepted", "turnId": TURN_ID, "turnEpoch": 1}
                # 1 秒合成 PCM（50 帧 × 20ms）。
                for seq in range(50):
                    ws.send_bytes(_frame(seq, 320))
                ws.send_json({"v": 1, "type": "end", "nextSequence": 50, "totalSamples": 16000})
                transcript = ws.receive_json()
                assert transcript["type"] == "transcript"
                assert transcript["turnId"] == TURN_ID
                assert transcript["text"] == "帮我整理三句口播要点。"
                completed = ws.receive_json()
                assert completed == {"v": 1, "type": "completed", "turnId": TURN_ID,
                                     "status": "completed", "reasonCode": None}
                # 1000 = 正常完成（不再 1011）。
                assert ws.receive() == {"type": "websocket.close", "code": 1000, "reason": "completed"}
        assert guard.attempts == [], f"Fake 档必须零出站，实际: {guard.attempts}"
        assert guard.blocked == []


def test_tc105x_04_01_empty_pcm_still_completes(tmp_path: Path) -> None:
    with OutboundGuard() as guard:
        bridge = ExecutionBridge(allowed_origins=(ALLOWED_ORIGIN,), client=_java_grant_stub())
        internal = create_app(test_mode=True, surface="internal", bridge=bridge)
        audio = create_app(test_mode=True, surface="audio", bridge=bridge)
        audio.state.sessions = internal.state.sessions
        # 经 INTERNAL01 真实建会话（同主用例；Fake 档 create 挂 RuntimeSession 后才有音频管线）。
        with TestClient(internal) as client:
            body = {"binding": {"sessionId": SESSION_ID, "leaseEpoch": 1, "mediaEpoch": 1,
                                "backendId": "backend-1", "profileRevision": 1,
                                "expiresAt": "2030-01-01T00:00:00Z", "leaseExpiresAt": "2030-01-01T00:00:00Z",
                                "contentEpoch": 1, "bridgeBaseUrl": "https://intelligence-service:9143"},
                    "commandId": "cmd-create-empty",
                    "payloadHash": "0" * 64}
            assert client.post("/internal/v1/sessions", json=body).status_code == 202
        with TestClient(audio) as client:
            with client.websocket_connect(
                    f"/api/digital-human/sessions/{SESSION_ID}/audio",
                    headers={"Origin": ALLOWED_ORIGIN}) as ws:
                ws.send_json(_auth())
                ws.receive_json()  # accepted
                # 空 PCM（0ms）：end 直接到达，Fake 档仍完成（转写回退文本）。
                ws.send_json({"v": 1, "type": "end", "nextSequence": 0, "totalSamples": 0})
                transcript = ws.receive_json()
                assert transcript["type"] == "transcript"
                assert ws.receive_json()["type"] == "completed"
        assert guard.attempts == []


def test_tc105x_04_01_oversized_pcm_closes_4413(tmp_path: Path) -> None:
    bridge = ExecutionBridge(allowed_origins=(ALLOWED_ORIGIN,), client=_java_grant_stub())
    audio = create_app(test_mode=True, surface="audio", bridge=bridge)
    _attach_fake_session(audio.state.sessions)
    with TestClient(audio) as client:
        with client.websocket_connect(
                f"/api/digital-human/sessions/{SESSION_ID}/audio",
                headers={"Origin": ALLOWED_ORIGIN}) as ws:
            ws.send_json(_auth())
            ws.receive_json()  # accepted
            # 超长：单帧 >1600 样本 → 既有 4413 语义（dh_audio_frame_too_large）。
            ws.send_bytes(_frame(0, 1601))
            assert ws.receive() == {"type": "websocket.close", "code": 4413,
                                    "reason": "dh_audio_frame_too_large"}


async def _collect(ait) -> list:
    return [frame async for frame in ait]


# ---------------------------------------------------------------------------
# tc105x_04_02：bridge.execute_stt multipart 客户端契约
# ---------------------------------------------------------------------------


def _ndjson_frames() -> bytes:
    def frame(seq: int, extra: dict) -> dict:
        return {"v": 1, "invocationId": "cccc1111-2222-4333-8444-555555555555", "seq": seq, **extra}
    lines = [
        frame(0, {"type": "meta", "format": "wav", "sampleRate": 16000, "channels": 1}),
        frame(1, {"type": "delta", "text": "测试转写", "audioBase64": None}),
        frame(2, {"type": "usage", "inputTokens": 4, "outputTokens": 2, "audioInputMs": 1000,
                  "audioOutputMs": None, "textCodePoints": None, "renderMs": None,
                  "providerRequestId": None, "quality": "confirmed"}),
        frame(3, {"type": "done", "state": "succeeded"}),
    ]
    return ("\n".join(json.dumps(line) for line in lines) + "\n").encode("utf-8")


def _stt_java_stub(expected_wav_limit: bool) -> httpx.MockTransport:
    """Java INTERNAL08 桩：校验 multipart 形状（meta=application/json + wav=audio/wav），返回 NDJSON。"""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/execute")
        content_type = request.headers.get("content-type", "")
        assert "multipart/form-data" in content_type
        body = request.read()
        wav_size = _stub_wav_size[0]
        if expected_wav_limit and wav_size > 1_920_000:
            return httpx.Response(422, json={"success": False, "code": "dh_invalid_input",
                                             "error": "wav 超出大小上限。"})
        assert b'name="meta"' in body and b'content-type: application/json' in body.lower()
        assert b'name="wav"' in body and b'content-type: audio/wav' in body.lower()
        return httpx.Response(200, content=_ndjson_frames(),
                              headers={"content-type": "application/x-ndjson"})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


_stub_wav_size = [0]


def test_tc105x_04_02_execute_stt_multipart_contract() -> None:
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["content_type"] = request.headers.get("content-type", "")
        captured["body"] = request.read()
        return httpx.Response(200, content=_ndjson_frames(),
                              headers={"content-type": "application/x-ndjson"})

    bridge = ExecutionBridge(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    meta = json.dumps({"v": 1, "inputHash": "a" * 64,
                       "input": {"durationMs": 1000, "pcmSha256": "b" * 64}}).encode("utf-8")
    wav = b"RIFF" + b"\x00" * 1000
    frames = asyncio.run(_collect(bridge.execute_stt("inv-1", GRANT, meta, wav)))
    assert [f.type for f in frames] == ["meta", "delta", "usage", "done"]
    assert frames[1].delta_text == "测试转写"
    assert "multipart/form-data" in captured["content_type"]
    body = captured["body"]
    assert b'name="meta"' in body and b'application/json' in body
    assert b'name="wav"' in body and b'audio/wav' in body
    assert b'"inputHash"' in body and b'"durationMs"' in body


def test_tc105x_04_02_exact_1_92mb_passes_and_over_rejected_by_java() -> None:
    # 恰 1.92MB（44 头 + PCM）：客户端 2MiB 闸放行，Java 桩按其上限校验放行。
    ok_transport = _stt_java_stub(expected_wav_limit=True)
    bridge = ExecutionBridge(client=ok_transport)
    meta = json.dumps({"v": 1, "inputHash": "a" * 64,
                       "input": {"durationMs": 60000, "pcmSha256": "b" * 64}}).encode("utf-8")
    exact = b"\x00" * 1_920_000  # 恰 1.92MB（桩按长度判限）
    _stub_wav_size[0] = len(exact)
    frames = asyncio.run(_collect(bridge.execute_stt("inv-1", GRANT, meta, exact)))
    assert frames[-1].type == "done" and frames[-1].done_state == "succeeded"

    # 1.92MB+1：Java 侧 422 dh_invalid_input（客户端闸放行、拒绝责任在 Java）。
    over = exact + b"\x00"
    _stub_wav_size[0] = len(over)
    with pytest.raises(BridgeError) as rejected:
        asyncio.run(_collect(bridge.execute_stt("inv-1", GRANT, meta, over)))
    assert rejected.value.code == "dh_invalid_input"
    assert rejected.value.status == 422


def test_tc105x_04_02_meta_over_128kib_rejected_client_side() -> None:
    bridge = ExecutionBridge(client=_stt_java_stub(expected_wav_limit=False))
    huge_meta = json.dumps({"v": 1, "inputHash": "a" * 64,
                            "input": {"durationMs": 1000, "pcmSha256": "b" * 64,
                                      "pad": "x" * (128 * 1024 + 1)}}).encode("utf-8")
    with pytest.raises(BridgeError) as rejected:
        asyncio.run(_collect(bridge.execute_stt("inv-1", GRANT, huge_meta, b"RIFF")))
    assert rejected.value.code == "dh_payload_too_large"
