"""按键音频管道（任务书 #105D C105D-04 / 共享契约 K07）。

二进制帧 = 8 字节头（uint32 BE sequence、uint32 BE sampleCount）+ little-endian int16 单声道
PCM；sampleCount≤1600（100ms），默认 20ms=320 samples。序号从 0 连续递增；重复/跳号立即拒绝并
废弃本段。原音频只内存（≤1.92MB/60 秒），不落盘、不进素材库/日志；end 后移交唯一 STT 任务。
"""

from __future__ import annotations

import audioop
from dataclasses import dataclass, field
from typing import Optional


class AudioFrameError(Exception):
    """帧非法（立即关闭，close code 由 routes_audio 映射）。code 为稳定错误码。"""

    def __init__(self, code: str) -> None:
        super().__init__(f"audio frame error: {code}")
        self.code = code


@dataclass(frozen=True)
class PcmFrame:
    """K07.1：pcm 是内存二进制，不能 JSON 打印/落日志。"""

    sequence: int
    sample_count: int
    pcm: bytes

    @property
    def duration_ms(self) -> float:
        return self.sample_count / 16.0  # 16000Hz → ms


MAX_SAMPLES_PER_FRAME = 1600  # 100ms
MAX_TOTAL_BYTES = 1_920_000  # 1.92MB / 60 秒双检查
MAX_TOTAL_SAMPLES = 16_000 * 60


def parse_frame(raw: bytes, expected_sequence: int) -> PcmFrame:
    """校验 header/字节序/序号/样本数；非法立即拒绝（dh_audio_invalid → 4413/4400）。"""
    if not isinstance(raw, (bytes, bytearray)) or len(raw) < 8:
        raise AudioFrameError("dh_audio_invalid")
    header = bytes(raw[:8])
    sequence = int.from_bytes(header[:4], "big")
    sample_count = int.from_bytes(header[4:8], "big")
    pcm = bytes(raw[8:])
    if sample_count > MAX_SAMPLES_PER_FRAME:
        raise AudioFrameError("dh_audio_frame_too_large")
    if len(pcm) != sample_count * 2:
        raise AudioFrameError("dh_audio_invalid")
    if sample_count > 0 and len(pcm) % 2 != 0:
        raise AudioFrameError("dh_audio_invalid")
    if sequence != expected_sequence:
        raise AudioFrameError("dh_audio_sequence")
    return PcmFrame(sequence=sequence, sample_count=sample_count, pcm=pcm)


def resample_to_16k(pcm: bytes, source_rate: int) -> bytes:
    """44.1k/48k → 16k 受控重采样（fixture 与自校验用；浏览器 AudioWorklet 是第一道）。"""
    if source_rate == 16_000:
        return pcm
    if source_rate not in (44_100, 48_000):
        raise AudioFrameError("dh_audio_invalid")
    return audioop.ratecv(pcm, 2, 1, source_rate, 16_000, None)[0]


@dataclass
class PcmCollector:
    """累积端到端音频：总量/时长双检查；重复或跳号即废弃（不乱拼音频）。

    ``total_samples`` 是重采样到 16k 后的口径（Java 收到的 WAV 即此）。
    """

    expected_sequence: int = 0
    total_samples: int = 0
    buffer: bytearray = field(default_factory=bytearray)
    discarded: bool = False

    def add(self, frame: PcmFrame) -> None:
        if self.discarded:
            raise AudioFrameError("dh_audio_sequence")
        self.total_samples += frame.sample_count
        if self.total_samples > MAX_TOTAL_SAMPLES or len(self.buffer) + len(frame.pcm) > MAX_TOTAL_BYTES:
            raise AudioFrameError("dh_audio_too_long")
        self.buffer.extend(frame.pcm)
        self.expected_sequence = frame.sequence + 1

    def mark_discarded(self) -> None:
        self.discarded = True

    @property
    def duration_ms(self) -> int:
        return self.total_samples * 1000 // 16_000

    def to_wav(self) -> bytes:
        """44 字节头 + PCM（16k mono s16le）——直接给 Java 内部 STT multipart，不落盘。"""
        pcm = bytes(self.buffer)
        header = b"RIFF" + (36 + len(pcm)).to_bytes(4, "little") + b"WAVE"
        header += b"fmt " + (16).to_bytes(4, "little") + (1).to_bytes(2, "little") + (1).to_bytes(2, "little")
        header += (16_000).to_bytes(4, "little") + (32_000).to_bytes(4, "little")
        header += (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
        header += b"data" + len(pcm).to_bytes(4, "little")
        return header + pcm


@dataclass(frozen=True)
class EndFrame:
    """末帧 {v:1,type:'end',nextSequence,totalSamples}：与累积不一致即拒绝。"""

    next_sequence: int
    total_samples: int

    @staticmethod
    def parse(payload: dict[str, object], collector: PcmCollector) -> "EndFrame":
        if payload.get("type") != "end":
            raise AudioFrameError("dh_audio_invalid")
        next_sequence = payload.get("nextSequence")
        total_samples = payload.get("totalSamples")
        if not isinstance(next_sequence, int) or not isinstance(total_samples, int):
            raise AudioFrameError("dh_audio_invalid")
        if next_sequence != collector.expected_sequence or total_samples != collector.total_samples:
            raise AudioFrameError("dh_audio_summary_mismatch")
        return EndFrame(next_sequence=next_sequence, total_samples=total_samples)


def is_abort(payload: dict[str, object]) -> bool:
    return payload.get("type") == "abort"


def parse_auth(payload: Optional[dict[str, object]]) -> Optional[dict[str, object]]:
    """首帧 JSON auth（≤4096 bytes，5 秒内）：返回核销所需字段；其它形态 → None（关闭 4401）。"""
    if not isinstance(payload, dict) or payload.get("type") != "auth" or payload.get("v") != 1:
        return None
    grant = payload.get("grant")
    lease_epoch = payload.get("leaseEpoch")
    request_id = payload.get("requestId")
    if not isinstance(grant, str) or not isinstance(lease_epoch, int) or not isinstance(request_id, str):
        return None
    if payload.get("format") != "pcm_s16le" or payload.get("sampleRate") != 16000 or payload.get("channels") != 1:
        return None
    return {"grant": grant, "leaseEpoch": lease_epoch, "requestId": request_id,
            "format": "pcm_s16le", "sampleRate": 16000, "channels": 1}
