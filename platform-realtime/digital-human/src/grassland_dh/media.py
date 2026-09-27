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
import os
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional


def _ice_servers_from_env() -> list[Any]:
    """K07 TURN 注入（部署拓扑差异面）：容器网络对浏览器不可路由（Docker Desktop 等
    VM 边界）时，runtime 经 TURN 中继取得宿主可达 candidate。三 env 齐备才启用；
    缺省空=直接 ICE（既有行为，生产同宿主可路由部署不需要 TURN）。"""
    uri = os.environ.get("DH_TURN_URI", "").strip()
    username = os.environ.get("DH_TURN_USERNAME", "").strip()
    credential = os.environ.get("DH_TURN_CREDENTIAL", "").strip()
    if not uri or not username or not credential:
        return []
    from aiortc import RTCIceServer

    return [RTCIceServer(urls=uri, username=username, credential=credential)]


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


class PcmAudioQueue:
    """下行 PCM 缓冲（runtime-static 形态）：turn 合成产物喂入，20ms 一取；空窗输出静音。

    feed 接受 int16 ndarray（vendor webrtc 队列元素形态）或 bytes（16k mono s16）；
    有界 drop-oldest——消费方（音频轨）慢于生产方（说话管线）时丢最旧段，不阻塞管线。
    level 供视频轨做能量律动（快起慢落包络）。
    """

    SAMPLES_PER_TICK = 320  # 16k mono 20ms

    def __init__(self, max_chunks: int = 600) -> None:  # ~12s 上限
        import collections

        self._chunks: Any = collections.deque(maxlen=max_chunks)
        self._carry = b""
        self._level = 0.0
        self.fed_chunks = 0
        self.dropped_chunks = 0

    def feed(self, pcm: Any) -> None:
        import numpy as np

        if pcm is None:
            return
        data = pcm.tobytes() if isinstance(pcm, np.ndarray) else bytes(pcm)
        if not data:
            return
        if len(self._chunks) == self._chunks.maxlen:
            self.dropped_chunks += 1
        self._chunks.append(data)
        self.fed_chunks += 1

    def _track_level(self, window: bytes) -> None:
        import numpy as np

        usable = len(window) // 2 * 2
        if usable < 2:
            return
        samples = np.frombuffer(window[:usable], dtype="<i2").astype(np.float32)
        peak = float(np.max(np.abs(samples))) / 32768.0 if samples.size else 0.0
        self._level = max(peak, self._level * 0.82)

    def next_samples(self) -> bytes:
        need = self.SAMPLES_PER_TICK * 2
        out = bytearray()
        while len(out) < need and (self._carry or self._chunks):
            if not self._carry:
                self._carry = self._chunks.popleft()
            take = min(need - len(out), len(self._carry))
            out += self._carry[:take]
            self._carry = self._carry[take:]
        if not out:
            return b"\x00\x00" * self.SAMPLES_PER_TICK
        self._track_level(bytes(out))
        if len(out) < need:
            out += b"\x00\x00" * ((need - len(out)) // 2)
        return bytes(out)

    @property
    def level(self) -> float:
        return self._level

    def clear(self) -> None:
        self._chunks.clear()
        self._carry = b""


class AvatarFrameSource:
    """形象静态帧源（runtime-static）：基帧=形象 normalized 图，输出=能量律动视频帧。

    无 GPU/无渲染后端时的画面形态（opentalking mock 同构）：静止形象 + 说话能量
    亮度律动 + 呼吸微动。基帧缺失（形象未准备/文件被清）时 is_ready=False——
    RtcSession 回落既有假帧行为，轨道不断流。
    """

    def __init__(self, image_path: Any, width: int = 640, height: int = 360) -> None:
        self.path = image_path
        self.width = width
        self.height = height
        self.is_ready = False
        self.frames_rendered = 0
        try:
            import numpy as np
            from PIL import Image

            with Image.open(str(image_path)) as image:
                base = image.convert("RGB").resize((width, height))
                self._base = np.asarray(base, dtype=np.int16)
            self.is_ready = True
        except Exception:
            self._base = None

    def render(self, pts_ms: int, audio_level: float = 0.0) -> Any:
        import math

        import numpy as np
        from av import VideoFrame

        if not self.is_ready:
            return _fake_video_frame(pts_ms)
        breath = 0.5 + 0.5 * math.sin(2.0 * math.pi * pts_ms / 3400.0)
        gain = 1.0 + 0.10 * min(1.0, max(0.0, audio_level)) + 0.04 * breath
        pixels = np.clip(self._base * gain, 0, 255).astype(np.uint8)
        frame = VideoFrame.from_ndarray(pixels, format="rgb24")
        frame.pts = pts_ms
        frame.time_base = fractions_1_1000()
        self.frames_rendered += 1
        return frame


class RtcSession:
    """单 peer 封装：一次 offer→answer；关闭后不可复用（新 peer 由 ProgramAdapter 重建）。"""

    def __init__(self, media_epoch: int, clock: ProgramClock, video_fps: float = 10.0,
                 adapter: Optional["ProgramAdapter"] = None,
                 video_source: Optional[AvatarFrameSource] = None,
                 audio_source: Optional[PcmAudioQueue] = None) -> None:
        from aiortc import RTCPeerConnection
        from aiortc.mediastreams import MediaStreamTrack
        self.media_epoch = media_epoch
        self.clock = clock
        self.closed = False
        # 一次 offer→answer：已协商过的 peer 不可再收 offer（ICE 失败不 closed，但 track
        # 已挂 sender，重复 addTrack 会 InvalidAccessError）——上层据此重建新 peer。
        self.answered = False
        ice_servers = _ice_servers_from_env()
        if ice_servers:
            from aiortc import RTCConfiguration

            self.pc: Any = RTCPeerConnection(RTCConfiguration(iceServers=ice_servers))
        else:
            self.pc: Any = RTCPeerConnection()
        self.received_kinds: list[str] = []
        self._video_fps = video_fps
        self.adapter = adapter
        # runtime-static 源（可选）：None 时保持既有 Fake 帧行为（测试/旧档兼容）。
        self.video_source = video_source
        self.audio_source = audio_source

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
                frame = outer_self._session._render_video_frame()
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
                frame = session._render_audio_frame()
                if session.adapter is not None:
                    session.adapter.emit_output_audio(frame)
                return frame

        self.video_track = _VideoTrack(self)
        self.audio_track = _AudioTrack(self)
        self._audio_pts = 0

    def _render_video_frame(self) -> Any:
        source = self.video_source
        if source is not None and source.is_ready:
            level = self.audio_source.level if self.audio_source is not None else 0.0
            return source.render(self.clock.now_ms(), level)
        return _fake_video_frame(self.clock.now_ms())

    def _render_audio_frame(self) -> Any:
        if self.audio_source is not None:
            samples = self.audio_source.next_samples()
        else:
            samples = b"\x00\x20" * 320
        import fractions

        from av import AudioFrame

        frame = AudioFrame(format="s16", layout="mono", samples=320)
        frame.pts = self._audio_pts
        frame.sample_rate = 16_000
        frame.time_base = fractions.Fraction(1, 16_000)
        frame.planes[0].update(samples)
        self._audio_pts += 320
        return frame

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
        self.answered = True
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

    def __init__(self, session_id: str, lease_epoch: int, media_epoch: int,
                 frame_source: Optional[AvatarFrameSource] = None,
                 audio_queue: Optional[PcmAudioQueue] = None) -> None:
        self.session_id = session_id
        self.lease_epoch = lease_epoch
        self.media_epoch = media_epoch
        self.clock = ProgramClock()
        self.peer: Optional[RtcSession] = None
        self.dropped_stale_frames = 0
        self.discarded_epochs: set[int] = set()
        self.disconnect_states: list[str] = []
        # runtime-static 源（挂在 adapter 上——peer 重建/打断后新 peer 继续同一形象与音频缓冲）。
        self.frame_source = frame_source
        self.audio_queue = audio_queue
        # 输出AV录制分支（C105F-02）：挂在 adapter（非 peer）上——peer 重建（reset_media）
        # 不中断录制；ProgramClock 连续，段时钟不随轮次/peer 重置归零。
        self.recorder: Optional[Any] = None

    def new_peer(self, video_fps: float = 25.0) -> RtcSession:
        """带源 peer 工厂：reset_media 后重建的 peer 继续同一形象帧源与 PCM 缓冲。"""
        return RtcSession(self.media_epoch, self.clock, video_fps=video_fps, adapter=None,
                          video_source=self.frame_source, audio_source=self.audio_queue)

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
