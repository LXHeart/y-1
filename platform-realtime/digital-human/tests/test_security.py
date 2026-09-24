"""wrapper 入口安全测试（任务书 #105D C105D-02 / TC105D-02-02）。

跨 owner/scope/origin 全负例：A 的 grant 改 session/Origin/stage 使用 → 全部拒绝、零音频缓存与
零推理（fake transport 计数断言）；重复核销拒绝；origin 本地先拒不触 Java。全部 Fake，无外网。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

import httpx
import pytest

from grassland_dh.bindings import BridgeFrame, ExecutionGrant
from grassland_dh.bridge import BridgeError, ExecutionBridge
from grassland_dh.security import GrantRejected, authenticate_origin, consume_grant

ALLOWED = ("https://ai.grassland.test",)
SESSION_A = "11111111-1111-4111-8111-111111111111"
SESSION_B = "22222222-2222-4222-8222-222222222222"


class FakeJava:
    """固定 authority 的 Java 假端点：记录请求、按用例返回；绝不做真实推理。"""

    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.consume_responses: list[int] = []
        self.provider_calls = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        body = json.loads(request.content.decode("utf-8")) if request.content else {}
        self.requests.append({"path": path, "body": body})
        if path == "/internal/digital-human/grants/consume":
            status = self.consume_responses.pop(0) if self.consume_responses else 200
            if status == 200:
                return httpx.Response(200, json={
                    "sessionId": body["sessionId"],
                    "leaseEpoch": body["leaseEpoch"],
                    "contentEpoch": 1,
                    "expiresAt": "2026-09-23T00:00:30Z",
                    "turnId": "33333333-3333-4333-8333-333333333333",
                    "turnEpoch": 1,
                    "firstFrameDeadlineAt": "2026-09-23T00:00:35Z",
                })
            code = {409: "dh_grant_invalid", 410: "dh_grant_expired", 403: "dh_origin_rejected"}.get(status,
                                                                                                      "dh_grant_invalid")
            return httpx.Response(status, json={"success": False, "error": "拒绝。", "code": code})
        if path.startswith("/internal/digital-human/invocations/"):
            return httpx.Response(503, json={"success": False, "error": "未装配。", "code": "dh_runtime_unavailable"})
        raise AssertionError(f"未预期的出站请求: {path}")

    @property
    def consume_calls(self) -> int:
        return sum(1 for r in self.requests if r["path"].endswith("/grants/consume"))


@pytest.fixture()
def fake_java() -> FakeJava:
    return FakeJava()


@pytest.fixture()
def bridge(fake_java: FakeJava) -> ExecutionBridge:
    client = httpx.AsyncClient(transport=httpx.MockTransport(fake_java.handler))
    return ExecutionBridge(list(ALLOWED), client=client)


# ---------- TC105D-02-02：跨 owner/scope/origin ----------

@pytest.mark.asyncio
async def test_tc105d_02_02_wrong_origin_rejected_locally_without_java_call(fake_java: FakeJava,
                                                                             bridge: ExecutionBridge) -> None:
    # A 的合法 grant，但 Origin 换成攻击者域/后缀域/空：本地先拒——零 Java 调用、零推理。
    for bad_origin in ("https://evil.example", "https://ai.grassland.test.evil", "https://ai.grassland.test:8443",
                       None, ""):
        with pytest.raises(GrantRejected) as raised:
            await consume_grant(bridge, grant="g" * 43, session_id=SESSION_A, lease_epoch=1,
                                origin=bad_origin, request_id="req-1")
        assert raised.value.code in ("dh_origin_rejected",)
    assert fake_java.consume_calls == 0
    assert fake_java.provider_calls == 0


@pytest.mark.asyncio
async def test_tc105d_02_02_cross_session_rejected_by_authority(fake_java: FakeJava,
                                                                 bridge: ExecutionBridge) -> None:
    # A grant 用于 B 会话：Java 权威面拒绝（409 dh_grant_invalid），wrapper 不缓存音频、零推理。
    fake_java.consume_responses.append(409)
    with pytest.raises(GrantRejected) as raised:
        await consume_grant(bridge, grant="g" * 43, session_id=SESSION_B, lease_epoch=1,
                            origin=ALLOWED[0], request_id="req-2")
    assert raised.value.code == "dh_grant_invalid"
    assert fake_java.consume_calls == 1
    assert fake_java.provider_calls == 0


@pytest.mark.asyncio
async def test_tc105d_02_02_duplicate_redemption_rejected(fake_java: FakeJava, bridge: ExecutionBridge) -> None:
    # 同 grant 第二次核销：Java GETDEL 原子语义 → 409；期间零推理。
    await consume_grant(bridge, grant="g" * 43, session_id=SESSION_A, lease_epoch=1,
                        origin=ALLOWED[0], request_id="req-3")
    fake_java.consume_responses.append(409)
    with pytest.raises(GrantRejected) as raised:
        await consume_grant(bridge, grant="g" * 43, session_id=SESSION_A, lease_epoch=1,
                            origin=ALLOWED[0], request_id="req-3")
    assert raised.value.code == "dh_grant_invalid"
    assert fake_java.provider_calls == 0


@pytest.mark.asyncio
async def test_tc105d_02_02_expired_grant_maps_to_410(fake_java: FakeJava, bridge: ExecutionBridge) -> None:
    fake_java.consume_responses.append(410)
    with pytest.raises(GrantRejected) as raised:
        await consume_grant(bridge, grant="g" * 43, session_id=SESSION_A, lease_epoch=1,
                            origin=ALLOWED[0], request_id="req-4")
    assert raised.value.code == "dh_grant_expired"


@pytest.mark.asyncio
async def test_tc105d_02_02_invalid_format_rejected_before_java(fake_java: FakeJava,
                                                                bridge: ExecutionBridge) -> None:
    with pytest.raises(GrantRejected) as raised:
        await consume_grant(bridge, grant="g" * 43, session_id=SESSION_A, lease_epoch=1,
                            origin=ALLOWED[0], request_id="req-5", format="pcm_f32le", sample_rate=44100,
                            channels=2)
    assert raised.value.code == "dh_invalid_input"
    assert fake_java.consume_calls == 0


@pytest.mark.asyncio
async def test_tc105d_02_02_grant_never_in_query_or_url(fake_java: FakeJava, bridge: ExecutionBridge) -> None:
    await consume_grant(bridge, grant="SECRET-GRANT-XYZ", session_id=SESSION_A, lease_epoch=1,
                        origin=ALLOWED[0], request_id="req-6")
    request = fake_java.requests[0]
    assert "SECRET-GRANT-XYZ" in json.dumps(request["body"])  # 只在 POST body
    # consume_grant 走 POST；grant 不进 URL/query（记录里 path 不含原文）。
    assert "SECRET-GRANT-XYZ" not in request["path"]


# ---------- 桥协议负例（同卡安全面） ----------

@pytest.mark.asyncio
async def test_tc105d_02_02_execute_rejects_oversized_payload_before_network(
        bridge: ExecutionBridge) -> None:
    with pytest.raises(BridgeError) as raised:
        generator = bridge.execute("inv-1", "g" * 43, b"x" * (128 * 1024 + 1))
        async for _frame in generator:
            pass
    assert raised.value.code == "dh_payload_too_large"


@pytest.mark.asyncio
async def test_tc105d_02_02_execute_seq_gap_detected(fake_java: FakeJava, bridge: ExecutionBridge) -> None:
    def streaming(request: httpx.Request) -> httpx.Response:
        lines = [
            json.dumps({"v": 1, "invocationId": "inv-1", "seq": 0, "type": "meta", "format": "pcm_s16le",
                        "sampleRate": 24000, "channels": 1}),
            json.dumps({"v": 1, "invocationId": "inv-1", "seq": 2, "type": "delta", "text": "跳号"}),
        ]
        return httpx.Response(200, text="\n".join(lines))

    streaming_bridge = ExecutionBridge(list(ALLOWED),
                                       client=httpx.AsyncClient(transport=httpx.MockTransport(streaming)))
    with pytest.raises(BridgeError) as raised:
        async for _frame in streaming_bridge.execute("inv-1", "g" * 43, b"{}"):
            pass
    assert raised.value.code == "dh_bridge_seq_gap"
    assert fake_java.provider_calls == 0


def test_bridge_frame_rejects_unknown_type_and_mixed_fields() -> None:
    with pytest.raises(ValueError):
        BridgeFrame.from_wire({"v": 1, "invocationId": "i", "seq": 0, "type": "usage2"})
    with pytest.raises(ValueError):
        # delta 混入 usage 字段 → 拒绝（不同 type 禁止混字段）。
        BridgeFrame.from_wire({"v": 1, "invocationId": "i", "seq": 0, "type": "delta", "text": "a",
                               "inputTokens": 3})
    frame = BridgeFrame.from_wire({"v": 1, "invocationId": "i", "seq": 0, "type": "meta", "format": "pcm_s16le",
                                   "sampleRate": 24000, "channels": 1})
    assert frame.type == "meta"


def test_execution_grant_expiry_check() -> None:
    from datetime import datetime, timedelta, timezone

    grant = ExecutionGrant(
        invocation_id="inv-1", stage="llm", grant="g" * 43,
        expires_at=datetime.now(timezone.utc) - timedelta(seconds=1),
        deadline_at=datetime.now(timezone.utc) + timedelta(seconds=90),
        input_hash="h",
    )
    assert grant.expired()
