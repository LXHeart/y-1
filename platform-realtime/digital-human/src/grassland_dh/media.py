"""媒体代次与连续输出钟（任务书 #105D C105D-05 / 共享契约 K06、K07、K14）。

- RtcSession：单次 peer 的受控封装——recvonly offer 校验、Fake AV 下行轨、mediaEpoch 标记；
  关闭即废，晚到 packet 只属于已废 peer。
- ProgramClock：跨轮次连续的节目时钟（ms，单调），媒体 0 音量或 idle 仍按时钟推进；
  真实 fps 从实际视频帧计，不从配置常量显示。
- ProgramAdapter：会话级媒体编排——peer 重建（reset_media）清旧队列、保时钟与 Binding；
  旧代次帧一律丢弃；瞬时 disconnected 交由租约超时，不 close 业务 session。
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional


class MediaStateError(Exception):
    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


@dataclass
class RtcAnswer:
    """K07.1：answer SDP + mediaEpoch（iceServers 由 Java 追加后给浏览器）。"""

    sdp: str
    type: str = "answer"
    media_epoch: int = 0


class ProgramClock:
    """连续节目时钟：单调毫秒；跨轮次/跨 peer 重建不归零（录制段内更不得归零）。"""

    def __init__(self) -> None:
        self._origin = time.monotonic()
        self._frames = 0

    def now_ms(self) -> int:
        return int((time.monotonic() - self._origin) * 1000)

    def tick_video_frame(self) -> None:
        self._frames += 1

    @property
    def video_frames(self) -> int:
        return self._frames

    def real_fps(self, window_seconds: float) -> Optional[float]:
        """真实 fps 从实际帧数计（不显示配置常量）。"""
        if window_seconds <= 0:
            return None
        return self._frames / window_seconds


class _Ticker:
    """按固定间隔产生帧事件的源（Fake AV 轨道用；不加载任何模型）。"""

    def __init__(self, interval: float) -> None:
        self.interval = interval
        self._queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=120)
        self._task: Optional[asyncio.Task[None]] = None
        self._stopped = asyncio.Event()

    async def _run(self, producer: Any) -> None:
        while not self._stopped.is_set():
            frame = producer()
            try:
                self._queue.put_nowait(frame)
            except asyncio.QueueFull:
                try:
                    self._queue.get_nowait()
                    self._queue.put_nowait(frame)
                except asyncio.QueueEmpty:
                    pass
            await asyncio.sleep(self.interval)

    def start(self, producer: Any) -> None:
        self._stopped.clear()
        self._task = asyncio.get_event_loop().create_task(self._run(producer))

    async def stop(self) -> None:
        self._stopped.set()
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, timeout=2.0)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()
            self._task = None


class RtcSession:
    """单 peer 封装：一次 offer→answer；关闭后不可复用（新 peer 由 ProgramAdapter 重建）。"""

    def __init__(self, media_epoch: int, clock: ProgramClock, video_fps: float = 10.0,
                 adapter: Optional["ProgramAdapter"] = None) -> None:
        from aiortc import RTCPeerConnection
        from aiortc.mediastreams import MediaStreamTrack
        self.media_epoch = media_epoch
        self.clock = clock
        self.closed = False
        self.pc: Any = RTCPeerConnection()
        self.received_kinds: list[str] = []
        self._video_fps = video_fps
        self.adapter = adapter

        class _VideoTrack(MediaStreamTrack):  # type: ignore[misc]
            kind = "video"

            def __init__(outer_self, session: "RtcSession") -> None:
                super().__init__()
                outer_self._session = session

            async def recv(outer_self) -> Any:
                if outer_self._session.closed:
                    raise MediaStateError("media_closed")
                await asyncio.sleep(1.0 / outer_self._session._video_fps)
                outer_self._session.clock.tick_video_frame()
                frame = _fake_video_frame(outer_self._session.clock.now_ms())
                if outer_self._session.adapter is not None:
                    # 输出AV录制分支（C105F-02）：同一帧经 adapter 复制给录制（不改变轨道输出）。
                    outer_self._session.adapter.emit_output_video(frame)
                return frame

        class _AudioTrack(MediaStreamTrack):  # type: ignore[misc]
            kind = "audio"

            def __init__(outer_self, session: "RtcSession") -> None:
                super().__init__()
                outer_self._session = session

            async def recv(outer_self) -> Any:
                session = outer_self._session
                if session.closed:
                    raise MediaStateError("media_closed")
                await asyncio.sleep(0.02)  # 20ms
                frame = _fake_audio_frame(session._audio_pts)
                session._audio_pts += 320
                if session.adapter is not None:
                    session.adapter.emit_output_audio(frame)
                return frame

        self.video_track = _VideoTrack(self)
        self.audio_track = _AudioTrack(self)
        self._audio_pts = 0

    async def offer(self, sdp: str, lease_epoch: int, media_epoch: int) -> RtcAnswer:
        """recvonly 合法 offer 才接受；media/lease 代次不符拒绝；完整 ICE（非 trickle）。"""
        if self.closed:
            raise MediaStateError("media_peer_closed")
        if media_epoch != self.media_epoch:
            raise MediaStateError("dh_media_epoch_stale")
        if "sendrecv" in sdp or "sendonly" in sdp:
            raise MediaStateError("dh_offer_not_recvonly", "RTC 只下行音视频")
        if "a=ice-options:trickle" in sdp:
            raise MediaStateError("dh_offer_trickle", "需要完整 ICE")
        self.pc.addTrack(self.video_track)
        self.pc.addTrack(self.audio_track)
        from aiortc import RTCSessionDescription

        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="offer"))
        # recvonly 校验后不再上传：接收轨仅登记（aiortc 无 per-receiver 事件面，上行由 SDP 方向拒绝）。
        self.received_kinds = [t.kind for t in (r.track for r in self.pc.getReceivers()) if t is not None]
        answer = await self.pc.createAnswer()
        await self.pc.setLocalDescription(answer)
        # aiortc 内建完整 ICE 协商（非 trickle）；localDescription 已含全部候选。
        return RtcAnswer(sdp=self.pc.localDescription.sdp, media_epoch=self.media_epoch)

    async def close(self) -> None:
        if not self.closed:
            self.closed = True
            self.video_track.stop()
            self.audio_track.stop()
            await self.pc.close()

    def negotiated_receiver_kinds(self) -> list[str]:
        """协商出的接收轨（recvonly 场景应为空/仅登记——浏览器不经 RTC 上传麦克风）。"""
        return list(self.received_kinds)


class ProgramAdapter:
    """会话级媒体编排：peer 生命周期 + 代次隔离 + 连续时钟。

    - reset_media(new_epoch)：关旧 peer、清旧队列、标记旧代次作废；<b>保留</b> ProgramClock 与
      会话 Binding（业务 session 不因 peer 重建而关闭）。
    - 帧门（accept_frame）：旧代次帧一律丢弃（晚到 packet 只属于已废 peer）。
    - 瞬时 disconnected 不 close 业务 session（交由租约超时）；持续 failed 才收尾。
    """

    def __init__(self, session_id: str, lease_epoch: int, media_epoch: int) -> None:
        self.session_id = session_id
        self.lease_epoch = lease_epoch
        self.media_epoch = media_epoch
        self.clock = ProgramClock()
        self.peer: Optional[RtcSession] = None
        self.dropped_stale_frames = 0
        self.discarded_epochs: set[int] = set()
        self.disconnect_states: list[str] = []
        # 输出AV录制分支（C105F-02）：挂在 adapter（非 peer）上——peer 重建（reset_media）
        # 不中断录制；ProgramClock 连续，段时钟不随轮次/peer 重置归零。
        self.recorder: Optional[Any] = None

    def require_peer(self) -> RtcSession:
        if self.peer is None or self.peer.closed:
            raise MediaStateError("media_peer_closed", "媒体未就绪或已重建，请重新协商")
        return self.peer

    async def attach_peer(self, peer: RtcSession) -> None:
        old = self.peer
        peer.adapter = self
        self.peer = peer
        if old is not None and not old.closed:
            await old.close()

    # ---------- 录制分支出口（只接 Program 输出；异常一律吞掉，不阻塞对话） ----------

    def emit_output_video(self, frame: Any) -> None:
        if self.recorder is None:
            return
        try:
            self.recorder.on_video(frame, self.clock.now_ms())
        except Exception:
            pass

    def emit_output_audio(self, frame: Any) -> None:
        if self.recorder is None:
            return
        try:
            self.recorder.on_audio(frame, self.clock.now_ms())
        except Exception:
            pass

    async def offer(self, sdp: str, lease_epoch: int, media_epoch: int) -> RtcAnswer:
        if lease_epoch != self.lease_epoch:
            raise MediaStateError("dh_lease_stale")
        peer = self.require_peer()
        return await peer.offer(sdp, lease_epoch, media_epoch)

    async def reset_media(self, new_epoch: int) -> int:
        """打断/播放重置：先递增代次（Java 侧已定序），再关旧 peer、清旧队列。"""
        if new_epoch <= self.media_epoch:
            raise MediaStateError("dh_media_epoch_stale", "媒体代次必须递增")
        self.media_epoch = new_epoch
        if self.peer is not None:
            self.discarded_epochs.add(self.peer.media_epoch)
            await self.peer.close()
            self.peer = None
        return self.media_epoch

    def accept_frame(self, frame_epoch: int) -> bool:
        """帧门：已废代次的帧丢弃并计数（旧 peer 音画不再进入输出队列）。"""
        if frame_epoch in self.discarded_epochs or frame_epoch != self.media_epoch:
            self.dropped_stale_frames += 1
            return False
        return True

    def note_disconnect(self, state: str) -> bool:
        """原生 connectionstatechange 回调：瞬时 disconnected 只记录（session 保留）；
        持续 failed 返回 True（由调用方收尾）。"""
        self.disconnect_states.append(state)
        if state == "failed":
            return True
        return False


def _fake_video_frame(pts_ms: int) -> Any:
    from av import VideoFrame

    frame = VideoFrame(width=64, height=36, format="yuv420p")
    frame.pts = pts_ms
    frame.time_base = fractions_1_1000()
    return frame


def _fake_audio_frame(pts: int) -> Any:
    """16k mono s16、20ms=320 样本（显式 sample_rate——aiortc 编码器按帧属性读采样率）。"""
    import fractions

    from av import AudioFrame

    frame = AudioFrame(format="s16", layout="mono", samples=320)
    frame.pts = pts
    frame.sample_rate = 16_000
    frame.time_base = fractions.Fraction(1, 16_000)
    frame.planes[0].update(b"\x00\x20" * 320)
    return frame


def fractions_1_1000() -> Any:
    import fractions

    return fractions.Fraction(1, 1000)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
