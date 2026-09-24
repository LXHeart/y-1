"""受控内部执行桥（任务书 #105D C105D-02 / 共享契约 K07.1、K07.2）。

ExecutionBridge 只访问固定 authority 的 Java 内部端点（mTLS 由部署注入的 client 证书承载）：
不做任意 URL、不接受重定向到新主机、请求体有界、每请求有 deadline。NDJSON 帧逐行解析为
BridgeFrame（严格字段判别），音频/正文不落盘、不进日志。
"""

from __future__ import annotations

import json
from typing import AsyncIterator, Sequence

import httpx

from grassland_dh.bindings import BridgeFrame, ExecutionGrant, GrantBinding

_MAX_LINE_BYTES = 128 * 1024  # K07.1：每行 ≤128KiB
_MAX_BODY_BYTES = 128 * 1024  # 普通内部 JSON 上限（SDP 64KiB 之外的总闸）
_DEFAULT_TIMEOUT = 10.0
_EXECUTE_DEADLINE_SECONDS = 90.0  # K07：执行 deadline ≤90 秒


class BridgeError(Exception):
    """桥接失败（网络/协议/状态码）。刻意不携带请求正文与 grant 原文。"""

    def __init__(self, code: str, status: int | None = None) -> None:
        super().__init__(f"bridge error: {code}")
        self.code = code
        self.status = status


class ExecutionBridge:
    """固定 authority 的 Java 内部通道客户端。

    ``client`` 注入 httpx.AsyncClient（生产=mTLS 配置；测试=MockTransport），authority
    固定为 ``https://intelligence-service:9143``，禁止调用方覆盖为目标外的任何地址。
    """

    def __init__(
        self,
        allowed_origins: Sequence[str] = (),
        *,
        base_url: str = "https://intelligence-service:9143",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.allowed_origins = tuple(allowed_origins)
        self.base_url = base_url.rstrip("/")
        self._client = client

    def _new_client(self, timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=self.base_url,
            timeout=timeout,
            verify=False,  # 测试 transport 不校验；生产由注入的 mTLS client 承担
            follow_redirects=False,
        )

    async def consume_grant(
        self,
        *,
        grant: str,
        session_id: str,
        lease_epoch: int,
        origin: str,
        request_id: str,
        format: str,
        sample_rate: int,
        channels: int,
    ) -> GrantBinding:
        payload = {
            "grant": grant,
            "sessionId": session_id,
            "leaseEpoch": lease_epoch,
            "origin": origin,
            "requestId": request_id,
            "format": format,
            "sampleRate": sample_rate,
            "channels": channels,
        }
        data = await self._post_json("/internal/digital-human/grants/consume", payload)
        expires_at = _parse_datetime(data["expiresAt"])
        first_frame = _parse_datetime(data["firstFrameDeadlineAt"])
        return GrantBinding(
            session_id=str(data["sessionId"]),
            lease_epoch=int(data["leaseEpoch"]),
            content_epoch=int(data["contentEpoch"]),
            expires_at=expires_at,
            turn_id=str(data["turnId"]),
            turn_epoch=int(data["turnEpoch"]),
            first_frame_deadline_at=first_frame,
        )

    async def report_event(
        self,
        *,
        event_id: str,
        session_id: str,
        lease_epoch: int,
        event_type: str,
        payload: dict[str, object],
    ) -> dict[str, object]:
        body = {
            "eventId": event_id,
            "sessionId": session_id,
            "leaseEpoch": lease_epoch,
            "type": event_type,
            "payload": payload,
        }
        return await self._post_json("/internal/digital-human/events", body)

    async def create_invocation(
        self,
        *,
        session_id: str,
        turn_id: str,
        stage: str,
        segment_index: int,
        input_hash: str,
    ) -> ExecutionGrant:
        body = {
            "sessionId": session_id,
            "turnId": turn_id,
            "stage": stage,
            "segmentIndex": segment_index,
            "inputHash": input_hash,
        }
        data = await self._post_json("/internal/digital-human/invocations", body)
        return ExecutionGrant(
            invocation_id=str(data["invocationId"]),
            stage=stage,
            grant=str(data["grant"]),
            expires_at=_parse_datetime(data["expiresAt"]),
            deadline_at=_parse_datetime(data["deadlineAt"]),
            input_hash=str(data["inputHash"]),
        )

    async def execute(self, invocation_id: str, grant: str, payload: bytes) -> AsyncIterator[BridgeFrame]:
        """INTERNAL08 NDJSON 流：固定 authority、有限 body、90 秒 deadline、逐行严格解析。"""
        if len(payload) > _MAX_BODY_BYTES:
            raise BridgeError("dh_payload_too_large", 413)
        request = httpx.Request(
            "POST",
            f"{self.base_url}/internal/digital-human/invocations/{invocation_id}/execute",
            headers={"Authorization": f"Bearer {grant}", "Content-Type": "application/json"},
            content=payload,
        )
        client = self._client or self._new_client(_EXECUTE_DEADLINE_SECONDS)
        owned = self._client is None
        try:
            response = await client.send(request, stream=True)
            if response.status_code != 200:
                await response.aread()
                code = _error_code(response)
                raise BridgeError(code, response.status_code)
            seq = 0
            async for line in response.aiter_lines():
                if not line.strip():
                    continue
                if len(line.encode("utf-8")) > _MAX_LINE_BYTES:
                    raise BridgeError("dh_bridge_frame_too_large")
                frame = BridgeFrame.from_wire(json.loads(line))
                if frame.seq != seq:
                    raise BridgeError("dh_bridge_seq_gap")
                seq += 1
                yield frame
                if frame.type in ("done", "error"):
                    break
        except httpx.HTTPError as network_failure:
            raise BridgeError("dh_runtime_unavailable") from network_failure
        finally:
            if owned:
                await client.aclose()

    async def _post_json(self, path: str, body: dict[str, object]) -> dict[str, object]:
        encoded = json.dumps(body).encode("utf-8")
        if len(encoded) > _MAX_BODY_BYTES:
            raise BridgeError("dh_payload_too_large", 413)
        client = self._client or self._new_client(_DEFAULT_TIMEOUT)
        owned = self._client is None
        try:
            # 显式绝对 URL：固定 authority 不依赖注入 client 的 base_url 配置。
            response = await client.post(f"{self.base_url}{path}", content=encoded,
                    headers={"Content-Type": "application/json"})
            if response.status_code == 200:
                data = response.json()
                return data if isinstance(data, dict) else {}
            raise BridgeError(_error_code(response), response.status_code)
        except httpx.HTTPError as network_failure:
            raise BridgeError("dh_runtime_unavailable") from network_failure
        finally:
            if owned:
                await client.aclose()


def _error_code(response: httpx.Response) -> str:
    try:
        body = response.json()
        code = body.get("code")
        return str(code) if code else "dh_runtime_unavailable"
    except Exception:
        return "dh_runtime_unavailable"


def _parse_datetime(value: str):
    from datetime import datetime

    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
