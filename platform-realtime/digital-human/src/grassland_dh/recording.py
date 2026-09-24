"""输出AV录制分支与分段编码（任务书 #105F C105F-02 / K07.3、K09）。

- RecordingBranch：会话输出的独立录制分支——只接 Program 输出音视频（adapter 的
  emit_output_* 回调），<b>永不接用户 mic</b>（无任何 mic 输入面）；每段独立文件与
  0 点（start_program_ms），第二段不清第一段。
- SegmentWriter：单段 MP4 编码（libx264/yuv420p + AAC/48000 + faststart），入队
  非阻塞（max 2 秒媒体缓冲）；队列满/时长 300s/字节 200MiB 超限即停该段并标
  partial，不阻塞对话；编码器崩溃如实 failed 并可清理。
- PTS 映射：画面与音频都按程序钟减段起点（0 点=该段起点）；程序钟跨轮次连续，
  中断/peer 重建不 reset 段时钟。
"""

from __future__ import annotations

import asyncio
import hashlib
import shutil
import uuid as uuid_module
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Any, Optional

from grassland_dh.subtitles import SpeechSegment, build_srt

MAX_SEGMENT_DURATION_MS = 300_000
MAX_SEGMENT_BYTES = 200 * 1024 * 1024
QUEUE_MEDIA_SECONDS = 2.0
# 2 秒 ×（视频 10fps + 音频 50fps）= 120 项；×2 余量上限仍受 2 秒媒体语义约束（溢出即 partial）。
QUEUE_CAPACITY = 240
OUTPUT_AUDIO_RATE = 48_000
OUTPUT_VIDEO_FPS = 10.0
FINALIZE_TIMEOUT_SECONDS = 10.0

# partial（合法部分保留）与 failed（无可信产物）的分界：队列溢出/上限属于 partial；
# 编码器崩溃/写盘失败属于 failed。
PARTIAL_REASONS = frozenset({"dh_recording_overflow", "duration_cap", "size_cap"})


class RecordingError(Exception):
    """录制域错误（code 契约见 K00/K09）。"""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


def object_ref_for(recording_id: str, key: str) -> str:
    """INTERNAL12 无斜线句柄：dhr-{recordingId}-{key}（key 不含点/斜线）。"""
    return f"dhr-{recording_id}-{key.replace('.', '_')}"


def recording_dir(root: Path, recording_id: str) -> Path:
    """录制沙箱：root/recording-{recordingId}（段文件与字幕独立于其它段）。"""
    return Path(root) / f"recording-{recording_id}"


@dataclass
class RecordingFileInfo:
    object_ref: str
    relative_path: str
    sha256: str
    size_bytes: int
    content_type: str


@dataclass
class VideoInfo:
    object_ref: str
    sha256: str
    size_bytes: int
    duration_ms: int
    width: int
    height: int
    fps: float
    video_codec: str
    audio_codec: str
    audio_sample_rate: int

    def to_wire(self) -> dict[str, Any]:
        return {
            "objectRef": self.object_ref,
            "sha256": self.sha256,
            "sizeBytes": self.size_bytes,
            "durationMs": self.duration_ms,
            "width": self.width,
            "height": self.height,
            "fps": self.fps,
            "videoCodec": self.video_codec,
            "audioCodec": self.audio_codec,
            "audioSampleRate": self.audio_sample_rate,
        }


@dataclass
class RecordingManifest:
    """K05 RecordingManifest（wire 字段一一对应；subtitle 无则 null）。"""

    recording_id: str
    start_program_ms: int
    end_program_ms: int
    partial: bool
    video: Optional[VideoInfo] = None
    subtitle: Optional[RecordingFileInfo] = None
    error_code: Optional[str] = None

    def to_wire(self) -> dict[str, Any]:
        return {
            "recordingId": self.recording_id,
            "startProgramMs": self.start_program_ms,
            "endProgramMs": self.end_program_ms,
            "partial": self.partial,
            "video": self.video.to_wire() if self.video is not None else None,
            "subtitle": ({"objectRef": self.subtitle.object_ref, "sha256": self.subtitle.sha256,
                          "sizeBytes": self.subtitle.size_bytes}
                         if self.subtitle is not None else None),
            "errorCode": self.error_code,
        }


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _file_info(path: Path, object_ref: str, content_type: str) -> RecordingFileInfo:
    return RecordingFileInfo(object_ref=object_ref, relative_path=path.name, sha256=_sha256_file(path),
                             size_bytes=path.stat().st_size, content_type=content_type)


class SegmentWriter:
    """单段 MP4 编码器（PyAV=libav；libx264/AAC 来自既有多媒体依赖，无新增包）。

    线模型：``offer`` 全程非阻塞（put_nowait；队满即溢出标记，不等待消费者）；
    消费协程逐帧重采样/编码/封装。Fake 栈帧极小（64×36），真机高分辨率随 H 阶段
    实测再移线程池——2 秒有界队列是「不阻塞对话」的结构保证。
    """

    def __init__(self, recording_id: str, start_program_ms: int, out_path: Path, *,
                 max_duration_ms: int = MAX_SEGMENT_DURATION_MS,
                 max_bytes: int = MAX_SEGMENT_BYTES,
                 queue_capacity: int = QUEUE_CAPACITY) -> None:
        self.recording_id = recording_id
        self.start_program_ms = start_program_ms
        self.out_path = out_path
        self.max_duration_ms = max_duration_ms
        self.max_bytes = max_bytes
        self._queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=queue_capacity)
        self._task: Optional[asyncio.Task[None]] = None
        self._done = asyncio.Event()
        self._sentinel_sent = False
        # 溢出/上限：partial 依据（K09 dh_recording_overflow 等）；error：failed 依据。
        self.stop_reason: Optional[str] = None
        self.error_code: Optional[str] = None
        self.last_program_ms = start_program_ms
        self.video_frames = 0
        self.audio_frames = 0
        # 已封装字节（packet 尺寸累计）：mp4 容器缓冲写盘，文件 stat 在编码中不可见，
        # 字节上限以实际产出的媒体字节为准（终文件 ≥ 该值）。
        self.encoded_bytes = 0
        self.first_video_size: Optional[tuple[int, int]] = None

    # ---------- 生产面（非阻塞；overflow 后丢弃并不再接受） ----------

    def offer(self, kind: str, frame: Any, program_ms: int) -> None:
        if self._sentinel_sent or self._task is None:
            return
        if self.stop_reason is not None:
            return  # 已判定 partial 收尾：后续帧丢弃（该段停止，不影响对话）。
        if program_ms - self.start_program_ms > self.max_duration_ms:
            self.stop_reason = "duration_cap"
            return
        if self.encoded_bytes > self.max_bytes:
            self.stop_reason = "size_cap"
            return
        try:
            self._queue.put_nowait((kind, frame, program_ms))
        except asyncio.QueueFull:
            self.stop_reason = "dh_recording_overflow"
            return
        self.last_program_ms = max(self.last_program_ms, program_ms)

    # ---------- 消费面 ----------

    async def start(self) -> None:
        self._task = asyncio.get_running_loop().create_task(self._run())

    async def _run(self) -> None:
        import av

        container = None
        try:
            self.out_path.parent.mkdir(parents=True, exist_ok=True)
            container = av.open(str(self.out_path), mode="w", format="mp4",
                                options={"movflags": "+faststart"})
            video_stream = container.add_stream("libx264", rate=Fraction(int(OUTPUT_VIDEO_FPS), 1))
            video_stream.pix_fmt = "yuv420p"
            video_stream.options = {"preset": "ultrafast", "tune": "zerolatency"}
            video_stream.time_base = Fraction(1, 1000)
            audio_stream = container.add_stream("aac", rate=OUTPUT_AUDIO_RATE)
            audio_stream.layout = "mono"
            audio_stream.time_base = Fraction(1, OUTPUT_AUDIO_RATE)
            resampler = av.AudioResampler(format="fltp", layout="mono", rate=OUTPUT_AUDIO_RATE)
            saw_video = False
            last_video_pts_ms = None
            last_audio_pts = None
            video_cadence_ms = int(round(1000 / OUTPUT_VIDEO_FPS))
            while True:
                item = await self._queue.get()
                if item is None:
                    break
                kind, frame, program_ms = item
                relative_ms = max(0, program_ms - self.start_program_ms)
                if kind == "video":
                    if not saw_video:
                        video_stream.width = frame.width
                        video_stream.height = frame.height
                        self.first_video_size = (frame.width, frame.height)
                        saw_video = True
                    encoded = frame.reformat(format="yuv420p")
                    # PTS=程序钟映射，但不回退也不与前帧重叠（快喂时钟重复时按帧自然时长推进）。
                    if last_video_pts_ms is None:
                        pts_ms = int(relative_ms)
                    else:
                        pts_ms = max(int(relative_ms), last_video_pts_ms + video_cadence_ms)
                    last_video_pts_ms = pts_ms
                    encoded.pts = pts_ms
                    encoded.time_base = Fraction(1, 1000)
                    for packet in video_stream.encode(encoded):
                        container.mux(packet)
                        self.encoded_bytes += packet.size
                    self.video_frames += 1
                else:
                    frame.sample_rate = getattr(frame, "sample_rate", None) or 16000
                    resampled = resampler.resample(frame)
                    chunks = resampled if isinstance(resampled, list) else [resampled]
                    for chunk in chunks:
                        program_pts = int(relative_ms) * OUTPUT_AUDIO_RATE // 1000
                        if last_audio_pts is None:
                            pts = program_pts
                        else:
                            pts = max(program_pts, last_audio_pts + chunk.samples)
                        last_audio_pts = pts
                        chunk.pts = pts
                        chunk.time_base = Fraction(1, OUTPUT_AUDIO_RATE)
                        for packet in audio_stream.encode(chunk):
                            container.mux(packet)
                            self.encoded_bytes += packet.size
                    self.audio_frames += 1
                if self.stop_reason is not None and self._queue.empty():
                    break  # 已判 partial：排空在途即封口，不继续等新帧。
            for packet in video_stream.encode(None):
                container.mux(packet)
            for packet in audio_stream.encode(None):
                container.mux(packet)
            container.close()
            container = None
            if self.video_frames == 0 or self.audio_frames == 0:
                # 无可解码音画不能保存（K09）：静默丢弃产物文件。
                self.error_code = "dh_recording_failed"
                self._discard_output()
        except Exception:
            self.error_code = "dh_recording_failed"
            self._discard_output()
            if container is not None:
                try:
                    container.close()
                except Exception:
                    pass
        finally:
            self._done.set()

    def _discard_output(self) -> None:
        try:
            if self.out_path.exists():
                self.out_path.unlink()
        except OSError:
            pass

    async def finalize(self) -> None:
        """排空并封口：尽力 kill/wait 编码（超时取消），结果状态如实返回。"""
        if self._task is None:
            raise RecordingError("dh_invalid_input", "writer 未启动")
        if not self._sentinel_sent:
            self._sentinel_sent = True
            try:
                self._queue.put_nowait(None)
            except asyncio.QueueFull:
                # 队列满且已溢出：直接等待消费侧按 stop_reason 封口。
                pass
        try:
            await asyncio.wait_for(self._done.wait(), timeout=FINALIZE_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
            if self.error_code is None:
                self.error_code = "dh_recording_failed"
            self._discard_output()

    @property
    def ok(self) -> bool:
        return self.error_code is None and self.out_path.exists() and self.video_frames > 0


class RecordingBranch:
    """一段录制（recordingId 即段标识）：start 绑 0 点，stop 返回 manifest。

    输入面只有 ``on_video``/``on_audio``（Program 输出）与 ``record_speech``（已审文本
    窗口）；不存在 mic 输入方法——用户麦克风不经录制分支（K09 红线，结构性排除）。
    """

    def __init__(self, recording_id: str, media_root: Path, *,
                 max_duration_ms: int = MAX_SEGMENT_DURATION_MS,
                 max_bytes: int = MAX_SEGMENT_BYTES,
                 queue_capacity: int = QUEUE_CAPACITY) -> None:
        self.recording_id = recording_id
        self.media_root = Path(media_root)
        self.max_duration_ms = max_duration_ms
        self.max_bytes = max_bytes
        self.queue_capacity = queue_capacity
        self.state = "idle"
        self.manifest: Optional[RecordingManifest] = None
        self._writer: Optional[SegmentWriter] = None
        self._speech_segments: list[SpeechSegment] = []
        self._start_program_ms: Optional[int] = None

    async def start(self, start_program_ms: int) -> None:
        if self.state != "idle":
            raise RecordingError("dh_state_conflict", "录制分支已启动")
        if start_program_ms < 0:
            raise RecordingError("dh_invalid_input", "startProgramMs 非负")
        try:
            str(uuid_module.UUID(self.recording_id))
        except ValueError as invalid:
            raise RecordingError("dh_invalid_input", "recordingId 不是合法 UUID") from invalid
        segment_path = recording_dir(self.media_root, self.recording_id) / "segment.mp4"
        self._writer = SegmentWriter(self.recording_id, start_program_ms, segment_path,
                                     max_duration_ms=min(self.max_duration_ms, MAX_SEGMENT_DURATION_MS),
                                     max_bytes=min(self.max_bytes, MAX_SEGMENT_BYTES),
                                     queue_capacity=self.queue_capacity)
        self._start_program_ms = start_program_ms
        await self._writer.start()
        self.state = "recording"

    # ---------- Program 输出入口（adapter.emit_output_* 调用；异常不外抛） ----------

    def on_video(self, frame: Any, program_ms: int) -> None:
        if self.state == "recording" and self._writer is not None:
            try:
                self._writer.offer("video", frame, program_ms)
            except Exception:
                self.state = "failed"

    def on_audio(self, frame: Any, program_ms: int) -> None:
        if self.state == "recording" and self._writer is not None:
            try:
                self._writer.offer("audio", frame, program_ms)
            except Exception:
                self.state = "failed"

    def record_speech(self, text: str, start_ms: int, end_ms: int) -> None:
        """登记一段实际输出语音的已审文本窗口（程序钟毫秒）。"""
        self._speech_segments.append(SpeechSegment(text=text, start_ms=start_ms, end_ms=end_ms))

    # ---------- 收口 ----------

    async def stop(self, reason: str = "user") -> RecordingManifest:
        if self.state not in ("recording", "failed"):
            raise RecordingError("dh_state_conflict", f"录制段状态 {self.state} 不可停止")
        self.state = "finalizing"
        writer = self._writer
        if writer is not None:
            await writer.finalize()
        end_program_ms = writer.last_program_ms if writer is not None else int(self._start_program_ms or 0)
        duration_ms = max(0, end_program_ms - int(self._start_program_ms or 0))
        segment_dir = recording_dir(self.media_root, self.recording_id)
        segment_path = segment_dir / "segment.mp4"

        partial = (writer.stop_reason in PARTIAL_REASONS) if writer is not None else False
        overflow_reason = writer.stop_reason if (writer is not None and writer.stop_reason) else None
        error_code = writer.error_code if writer is not None else None

        video: Optional[VideoInfo] = None
        if writer is not None and writer.ok:
            size = segment_path.stat().st_size
            width, height = writer.first_video_size or (0, 0)
            video = VideoInfo(
                object_ref=object_ref_for(self.recording_id, "mp4"),
                sha256=_sha256_file(segment_path),
                size_bytes=size,
                duration_ms=duration_ms,
                width=width,
                height=height,
                fps=round(writer.video_frames * 1000.0 / max(1, duration_ms), 3),
                video_codec="h264",
                audio_codec="aac",
                audio_sample_rate=OUTPUT_AUDIO_RATE,
            )

        subtitle: Optional[RecordingFileInfo] = None
        if video is not None and self._speech_segments:
            srt_bytes = build_srt(self._speech_segments, int(self._start_program_ms or 0), end_program_ms)
            if srt_bytes:
                srt_path = segment_dir / "subtitle.srt"
                srt_path.write_bytes(srt_bytes)
                subtitle = _file_info(srt_path, object_ref_for(self.recording_id, "srt"),
                                      "application/x-subrip")

        if video is None:
            # 无可解码音画：failed（不产生可下载产物；孤儿文件已由 writer 丢弃/可再清）。
            self.state = "failed"
            self.manifest = RecordingManifest(
                recording_id=self.recording_id,
                start_program_ms=int(self._start_program_ms or 0),
                end_program_ms=end_program_ms,
                partial=False,
                video=None,
                subtitle=None,
                error_code=error_code or overflow_reason or "dh_recording_failed",
            )
        else:
            self.state = "ready"
            self.manifest = RecordingManifest(
                recording_id=self.recording_id,
                start_program_ms=int(self._start_program_ms or 0),
                end_program_ms=end_program_ms,
                partial=partial,
                video=video,
                subtitle=subtitle,
                error_code=None,
            )
        return self.manifest

    def object_files(self) -> list[tuple[str, Path]]:
        """该段已写对象的（object_ref, 路径）清单（INTERNAL13 清理与 INTERNAL12 读取用）。"""
        files: list[tuple[str, Path]] = []
        if self.manifest is None:
            return files
        segment_dir = recording_dir(self.media_root, self.recording_id)
        if self.manifest.video is not None:
            files.append((self.manifest.video.object_ref, segment_dir / "segment.mp4"))
        if self.manifest.subtitle is not None:
            files.append((self.manifest.subtitle.object_ref, segment_dir / "subtitle.srt"))
        return files


def delete_recording_tree(media_root: Path, recording_id: str) -> tuple[bool, list[str]]:
    """INTERNAL13 kind=recording：整目录收口；失败返回剩余句柄，不报假零。

    纯阻塞 rmtree（与 avatar delete_avatar_tree 同型，经 asyncio.to_thread 调用）。
    """
    base = recording_dir(media_root, recording_id)
    if not base.exists():
        return True, []
    remaining: list[str] = []
    for item in sorted(base.rglob("*")):
        if item.is_file():
            remaining.append(item.name)
    try:
        shutil.rmtree(base)
    except Exception:
        return False, remaining
    return True, []
