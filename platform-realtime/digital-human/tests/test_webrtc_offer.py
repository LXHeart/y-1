"""INTERNAL03（webrtc-offer）接线验收（任务书 #105fix-1 C105X-03 / TC105X-03-01）。

此前 app.py 挂内联 409 占位、真实 handler 不被路由——本组用例证明真实 handler 已挂载且
commandId/payloadHash 校验、代次校验、recvonly/trickle 判别全部在线。进程内 ASGI 客户端 +
进程内 aiortc 浏览器端 peer（无网络出站；fake 档语义；全程同一事件循环，可干净收尾）。
"""

from __future__ import annotations

import sys
from pathlib import Path
from uuid import UUID, uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import httpx
import pytest
from httpx import ASGITransport, AsyncClient

from grassland_dh.app import create_app
from grassland_dh.routes_internal import _payload_hash


def _hash_without(body: dict) -> str:
    wire = {key: value for key, value in body.items() if key != "payloadHash"}
    return _payload_hash(wire)


async def _browser_offer_sdp(trickle: bool = False, no_candidates: bool = False, recvonly: bool = True) -> str:
    """进程内浏览器端 peer 生成完整 offer SDP（aiortc 内建完整 ICE）。

    trickle=True：附加 a=ice-options:trickle（chromium 实际行为——能力宣告，候选仍完整携带）。
    no_candidates=True：剥掉全部候选行（真 trickle 依赖 offer——候选靠后续信令补发）。
    """
    from aiortc import RTCPeerConnection

    browser = RTCPeerConnection()
    direction = "recvonly" if recvonly else "sendrecv"
    browser.addTransceiver("video", direction=direction)
    browser.addTransceiver("audio", direction=direction)
    offer = await browser.createOffer()
    await browser.setLocalDescription(offer)
    sdp = browser.localDescription.sdp
    if trickle:
        sdp = sdp + "a=ice-options:trickle\r\n"
    if no_candidates:
        sdp = "\r\n".join(line for line in sdp.splitlines()
                           if not line.startswith("a=candidate:") and line != "a=end-of-candidates") + "\r\n"
    await browser.close()
    return sdp


async def _seed_session(client: AsyncClient, session_id: str, lease_epoch: int = 1, media_epoch: int = 1) -> None:
    body = {
        "commandId": "bootstrap",
        "binding": {
            "sessionId": session_id,
            "backendId": "dh-it-backend",
            "leaseEpoch": lease_epoch,
            "mediaEpoch": media_epoch,
        },
    }
    body["payloadHash"] = _hash_without(body)
    response = await client.post("/internal/v1/sessions", json=body)
    assert response.status_code == 202, response.text


def _offer_body(session_id: str, sdp: str, lease_epoch: int = 1, media_epoch: int = 1,
                command_id: str = "11111111-1111-4111-8111-111111111101") -> dict:
    body = {
        "commandId": command_id,
        "leaseEpoch": lease_epoch,
        "mediaEpoch": media_epoch,
        "sdp": sdp,
        "type": "offer",
    }
    body["payloadHash"] = _hash_without(body)
    return body


@pytest.mark.asyncio
async def test_tc105x_03_01_offer_wiring_validates_and_answers():
    app = create_app(test_mode=True, surface="internal")
    session_id = "22222222-2222-4222-8222-222222222201"
    try:
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://dh-it-internal") as client:
            await _seed_session(client, session_id)
            sdp = await _browser_offer_sdp()

            # 完整 body → 200 answer（sdp/type=answer/mediaEpoch）。
            body = _offer_body(session_id, sdp)
            ok = await client.post(f"/internal/v1/sessions/{session_id}/webrtc-offer", json=body)
            assert ok.status_code == 200, ok.text
            answer = ok.json()
            assert answer["type"] == "answer"
            assert "m=video" in answer["sdp"] and "m=audio" in answer["sdp"]
            assert answer["mediaEpoch"] == 1

            # peer 复用：二次 offer（同 commandId+hash）幂等 200——answer 重放由 receipt 缓存承担
            # （RtcSession 一次 offer→answer，§4.3 peer 复用语义落在命令幂等层）。
            again = await client.post(f"/internal/v1/sessions/{session_id}/webrtc-offer",
                                      json=_offer_body(session_id, sdp))
            assert again.status_code == 200, again.text
            assert again.json()["type"] == "answer"
            assert again.json()["sdp"] == answer["sdp"]

            # 缺 commandId → 422 dh_invalid_input。
            missing = _offer_body(session_id, sdp)
            del missing["commandId"]
            missing["payloadHash"] = _hash_without(missing)
            response = await client.post(f"/internal/v1/sessions/{session_id}/webrtc-offer", json=missing)
            assert response.status_code == 422
            assert response.json()["code"] == "dh_invalid_input"

            # payloadHash 错（与去 hash 字段后的 body 重算不符）→ 422 dh_invalid_input。
            wrong_hash = _offer_body(session_id, sdp)
            wrong_hash["payloadHash"] = "0" * 64
            response = await client.post(f"/internal/v1/sessions/{session_id}/webrtc-offer", json=wrong_hash)
            assert response.status_code == 422
            assert response.json()["code"] == "dh_invalid_input"

            # hash 大小写敏感：正确 hash 的大写十六进制同样拒绝（hex 规范小写比对）。
            upper = _offer_body(session_id, sdp)
            upper["payloadHash"] = upper["payloadHash"].upper()
            response = await client.post(f"/internal/v1/sessions/{session_id}/webrtc-offer", json=upper)
            assert response.status_code == 422
            assert response.json()["code"] == "dh_invalid_input"

            # 不存在会话 → 404 dh_not_found。
            body = _offer_body("33333333-3333-4333-8333-333333333301", sdp)
            response = await client.post(
                "/internal/v1/sessions/33333333-3333-4333-8333-333333333301/webrtc-offer", json=body)
            assert response.status_code == 404
            assert response.json()["code"] == "dh_not_found"

            # 非 recvonly SDP → 422 dh_offer_not_recvonly（既有码；独立 commandId 避开 receipt）。
            sendrecv = await _browser_offer_sdp(recvonly=False)
            response = await client.post(
                f"/internal/v1/sessions/{session_id}/webrtc-offer",
                json=_offer_body(session_id, sendrecv, command_id="11111111-1111-4111-8111-111111111111"))
            assert response.status_code == 422
            assert response.json()["code"] == "dh_offer_not_recvonly"

            # 浏览器能力宣告（chromium 恒带 a=ice-options:trickle，候选完整）→ 边界剥离后正常 200。
            # 用独立会话：RtcSession 一次 offer→answer，同会话二次真 offer 会撞 aiortc 单 sender 限制。
            advertised_session = str(uuid4())
            await _seed_session(client, advertised_session)
            advertised = await _browser_offer_sdp(trickle=True)
            response = await client.post(
                f"/internal/v1/sessions/{advertised_session}/webrtc-offer",
                json=_offer_body(advertised_session, advertised, command_id="11111111-1111-4111-8111-111111111113"))
            assert response.status_code == 200, response.text

            # 真 trickle 依赖（SDP 无任何候选）→ 422 dh_offer_trickle（既有码）。
            trickle = await _browser_offer_sdp(no_candidates=True)
            response = await client.post(
                f"/internal/v1/sessions/{session_id}/webrtc-offer",
                json=_offer_body(session_id, trickle, command_id="11111111-1111-4111-8111-111111111112"))
            assert response.status_code == 422
            assert response.json()["code"] == "dh_offer_trickle"
    finally:
        # 收尾：关闭 peer（同一事件循环内干净收尾）。
        for session in list(app.state.sessions.values()):
            adapter = getattr(session, "adapter", None)
            peer = getattr(adapter, "peer", None) if adapter is not None else None
            if peer is not None and not peer.closed:
                await peer.close()
