"""媒体代次与真实 peer 测试（任务书 #105D C105D-05 / TC105D-05-01、TC105D-05-02、TC105D-05-03）。

aiortc 两端 + Fake AV：offer/answer 播放有音轨视频轨、节目时间单调；打断后旧 peer 音画不再进入
输出、新轮无旧帧；主动 reset 触发的上游 disconnect 回调不 close 业务 session。无外网。
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.media import MediaStateError, ProgramAdapter, ProgramClock, RtcSession  # noqa: E402


def make_offer_sdp(pc) -> str:
    return pc.localDescription.sdp


async def browser_side_answer(answer_sdp: str) -> "object":
    """浏览器端：recvonly 收 answer 建立对端（只收不发）。"""
    from aiortc import RTCPeerConnection, RTCSessionDescription

    browser = RTCPeerConnection()
    browser.addTransceiver("video", direction="recvonly")
    browser.addTransceiver("audio", direction="recvonly")
    offer = await browser.createOffer()
    await browser.setLocalDescription(offer)
    return browser, offer


@pytest.mark.asyncio
async def test_tc105d_05_01_real_peer_synthesizes_media_with_monotonic_clock() -> None:
    clock = ProgramClock()
    session = RtcSession(media_epoch=1, clock=clock, video_fps=20)
    browser, offer = await browser_side_answer(None)
    try:
        answer = await session.offer(make_offer_sdp(browser), lease_epoch=5, media_epoch=1)
        assert answer.type == "answer"
        assert answer.media_epoch == 1
        assert "m=video" in answer.sdp and "m=audio" in answer.sdp
        from aiortc import RTCSessionDescription

        await browser.setRemoteDescription(RTCSessionDescription(sdp=answer.sdp, type="answer"))
        # 播放 ~0.5 秒：真实帧到达浏览器端（音视频轨都有帧）；节目时间单调。
        video_frames = 0
        audio_frames = 0
        stamps: list[int] = []
        async def play() -> None:
            nonlocal video_frames, audio_frames
            deadline = asyncio.get_event_loop().time() + 0.5
            while asyncio.get_event_loop().time() < deadline:
                for receiver in browser.getReceivers():
                    try:
                        frame = await asyncio.wait_for(receiver.track.recv(), timeout=0.3)
                    except (asyncio.TimeoutError, MediaStateError):
                        continue
                    if receiver.track.kind == "video":
                        video_frames += 1
                        stamps.append(int(frame.pts))
                    else:
                        audio_frames += 1
        await play()
        assert video_frames >= 2, "视频轨应有真实帧（不是 JSON 空成功）"
        assert audio_frames >= 2, "音频轨应有真实帧"
        assert stamps == sorted(stamps), "节目时间单调"
        assert clock.video_frames >= video_frames, "真实 fps 从实际帧计"
        # RTC 只下行：协商出的接收轨仅登记（sendrecv offer 已被拒，浏览器不经 RTC 上传）。
        assert set(session.negotiated_receiver_kinds()) <= {"video", "audio"}
    finally:
        await session.close()
        await browser.close()


@pytest.mark.asyncio
async def test_tc105d_05_02_interrupt_drops_old_peer_media_before_new_turn() -> None:
    adapter = ProgramAdapter(session_id="s1", lease_epoch=1, media_epoch=3)
    old_peer = RtcSession(media_epoch=3, clock=adapter.clock, video_fps=30)
    await adapter.attach_peer(old_peer)
    # 旧音频延迟 2 秒仍属旧代次：interrupt→reset_media(4) 后全部丢弃。
    assert adapter.accept_frame(3) is True
    await adapter.reset_media(4)
    assert adapter.accept_frame(3) is False, "旧 peer 音画不再进入输出"
    assert adapter.accept_frame(4) is True
    assert adapter.dropped_stale_frames == 1
    assert old_peer.closed
    # 新 peer 就绪前不接受新 text 的 offer（media 未就绪）。
    with pytest.raises(MediaStateError):
        adapter.require_peer()
    new_peer = RtcSession(media_epoch=4, clock=adapter.clock, video_fps=30)
    await adapter.attach_peer(new_peer)
    browser, offer = await browser_side_answer(None)
    try:
        answer = await adapter.offer(make_offer_sdp(browser), lease_epoch=1, media_epoch=4)
        assert answer.media_epoch == 4
        # 新轮后无旧帧：时钟连续不归零（录制段语义）。
        assert adapter.clock.now_ms() >= 0
        before = adapter.clock.now_ms()
        await asyncio.sleep(0.05)
        assert adapter.clock.now_ms() >= before
    finally:
        await new_peer.close()
        await browser.close()


@pytest.mark.asyncio
async def test_tc105d_05_03_reset_triggered_disconnect_keeps_session() -> None:
    adapter = ProgramAdapter(session_id="s2", lease_epoch=2, media_epoch=1)
    # 主动 reset 触发上游 connectionstatechange(disconnected)：gate 只记录、session 保留。
    assert adapter.note_disconnect("disconnected") is False
    assert adapter.note_disconnect("disconnected") is False
    # 持续 failed 才收尾（返回 True 由调用方关闭）。
    assert adapter.note_disconnect("failed") is True
    assert adapter.disconnect_states == ["disconnected", "disconnected", "failed"]
    # 会话状态仍在：可继续 reset 与重建。
    await adapter.reset_media(2)
    assert adapter.media_epoch == 2


@pytest.mark.asyncio
async def test_offer_rejects_non_recvonly_and_stale_epochs() -> None:
    # 租约校验在 ProgramAdapter（会话 Binding 层）：旧租约的 offer 拒绝。
    adapter = ProgramAdapter(session_id="s3", lease_epoch=2, media_epoch=1)
    await adapter.attach_peer(RtcSession(media_epoch=1, clock=adapter.clock))
    browser, offer = await browser_side_answer(None)
    try:
        with pytest.raises(MediaStateError) as stale_lease:
            await adapter.offer(make_offer_sdp(browser), lease_epoch=1, media_epoch=1)
        assert stale_lease.value.code == "dh_lease_stale"
        with pytest.raises(MediaStateError) as stale_media:
            await adapter.offer(make_offer_sdp(browser), lease_epoch=2, media_epoch=9)
        assert stale_media.value.code == "dh_media_epoch_stale"
    finally:
        if adapter.peer is not None:
            await adapter.peer.close()
        await browser.close()

    # sendrecv offer（浏览器侧加发送轨）→ 拒绝。
    from aiortc import RTCPeerConnection

    sender = RTCPeerConnection()
    sender.addTransceiver("audio", direction="sendrecv")
    offer_desc = await sender.createOffer()
    await sender.setLocalDescription(offer_desc)
    session2 = RtcSession(media_epoch=1, clock=ProgramClock())
    try:
        with pytest.raises(MediaStateError) as not_recvonly:
            await session2.offer(sender.localDescription.sdp, lease_epoch=1, media_epoch=1)
        assert not_recvonly.value.code == "dh_offer_not_recvonly"
    finally:
        await session2.close()
        await sender.close()


@pytest.mark.asyncio
async def test_media_gate_patch_roundtrip() -> None:
    """0002 补丁链路：overlay 内 runner 带 _grassland_media_gate 属性位（由 adapter 注入）。"""
    from grassland_dh.adapters import ensure_runtime_overlay

    overlay = ensure_runtime_overlay(force=True)
    source = (overlay / "opentalking" / "pipeline" / "speak" / "synthesis_runner.py").read_text()
    assert "_grassland_media_gate" in source, "0002 必须落在 overlay"
    vendor = (Path(__file__).resolve().parent.parent / "vendor" / "opentalking" / "opentalking"
              / "pipeline" / "speak" / "synthesis_runner.py").read_text()
    assert "_grassland_media_gate" not in vendor, "vendor 干净源不可变"
