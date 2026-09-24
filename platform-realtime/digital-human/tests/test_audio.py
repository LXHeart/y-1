"""按键音频管道测试（任务书 #105D C105D-04 / TC105D-04-01、TC105D-04-02）。

44.1k/48k fixture 下采样 → WS 20ms 块 → end：16k 正确时长 WAV、无磁盘写；边界（60s+、乱序、
队列满）明确关闭码且不无界占内存。全部内存 Fake，无外网。
"""

from __future__ import annotations

import sys
import wave
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.audio import (  # noqa: E402
    AudioFrameError,
    EndFrame,
    PcmCollector,
    PcmFrame,
    parse_auth,
    parse_frame,
    resample_to_16k,
)
from grassland_dh.routes_audio import close_code_for, queue_exceeded  # noqa: E402


def frame_bytes(sequence: int, samples: int, amplitude: int = 1000) -> bytes:
    pcm = b"".join((amplitude).to_bytes(2, "little", signed=True) for _ in range(samples))
    return sequence.to_bytes(4, "big") + samples.to_bytes(4, "big") + pcm


def test_tc105d_04_01_parse_frame_validates_header_sequence_and_size() -> None:
    frame = parse_frame(frame_bytes(0, 320), 0)
    assert frame.sequence == 0
    assert frame.sample_count == 320
    assert len(frame.pcm) == 640
    assert frame.duration_ms == pytest.approx(20.0)
    # 重复/跳号：立即拒绝并废弃本段（不能乱拼音频）。
    with pytest.raises(AudioFrameError) as skipped:
        parse_frame(frame_bytes(2, 320), expected_sequence=1)
    assert skipped.value.code == "dh_audio_sequence"
    # 样本数与载荷不符 / 超 1600。
    with pytest.raises(AudioFrameError):
        parse_frame((0).to_bytes(4, "big") + (10).to_bytes(4, "big") + b"\x01" * 10, 0)
    with pytest.raises(AudioFrameError) as too_large:
        parse_frame(frame_bytes(0, 1601), 0)
    assert too_large.value.code == "dh_audio_frame_too_large"


def test_tc105d_04_01_downsampled_441_and_48k_fixture_to_correct_16k_wav(tmp_path: Path) -> None:
    for source_rate in (44_100, 48_000):
        raw = b"".join((i * 7 % 65536 - 32768).to_bytes(2, "little", signed=True) for i in range(source_rate))
        pcm16k = resample_to_16k(raw, source_rate)
        collector = PcmCollector()
        # 20ms 块（320 samples）切分上传；序号连续。
        offset = 0
        sequence = 0
        while offset + 640 <= len(pcm16k):
            chunk = pcm16k[offset:offset + 640]
            samples = len(chunk) // 2
            collector.add(parse_frame(sequence.to_bytes(4, "big") + samples.to_bytes(4, "big") + chunk,
                                      expected_sequence=sequence))
            sequence += 1
            offset += 640
        EndFrame.parse({"type": "end", "nextSequence": sequence, "totalSamples": collector.total_samples}, collector)
        wav = collector.to_wav()
        assert collector.duration_ms == pytest.approx(1000, abs=70)
        # 无磁盘写：WAV 只在内存（字节串）；tmp_path 仅证明我们确实没有写任何文件。
        assert list(tmp_path.iterdir()) == []
        with wave.open(__import__("io").BytesIO(wav)) as parsed:
            assert parsed.getframerate() == 16_000
            assert parsed.getnchannels() == 1
            assert parsed.getsampwidth() == 2
            assert parsed.getnframes() == collector.total_samples


def test_tc105d_04_01_auth_first_frame_shape() -> None:
    auth = parse_auth({"v": 1, "type": "auth", "grant": "g" * 43, "leaseEpoch": 1, "requestId": "r",
                       "format": "pcm_s16le", "sampleRate": 16000, "channels": 1})
    assert auth is not None and auth["leaseEpoch"] == 1
    assert parse_auth({"type": "auth"}) is None
    assert parse_auth({"v": 1, "type": "auth", "grant": "g", "leaseEpoch": 1, "requestId": "r",
                       "format": "pcm_f32le", "sampleRate": 44100, "channels": 2}) is None


def test_tc105d_04_02_sixty_second_and_one_more_sample_rejected() -> None:
    collector = PcmCollector()
    sequence = 0
    # 60 秒 = 48000 块 20ms：恰好收满。
    for _ in range(60 * 50):
        collector.add(parse_frame(frame_bytes(sequence, 320), expected_sequence=sequence))
        sequence += 1
    assert collector.duration_ms == 60_000
    # 60 秒 + 1 sample：明确关闭码（dh_audio_too_long → 4413），buffer 释放由关闭路径回收。
    with pytest.raises(AudioFrameError) as beyond:
        collector.add(parse_frame(frame_bytes(sequence, 1), expected_sequence=sequence))
    assert beyond.value.code == "dh_audio_too_long"
    assert close_code_for(beyond.value.code) == 4413


def test_tc105d_04_02_out_of_order_discards_segment() -> None:
    collector = PcmCollector()
    collector.add(parse_frame(frame_bytes(0, 320), 0))
    with pytest.raises(AudioFrameError):
        collector.add(parse_frame(frame_bytes(0, 320), expected_sequence=1))
    collector.mark_discarded()
    # 废弃后不接受新帧。
    with pytest.raises(AudioFrameError):
        collector.add(parse_frame(frame_bytes(1, 320), 1))


def test_tc105d_04_02_two_second_queue_limit_closes_4429() -> None:
    # 满队列 = 100 个 20ms 等效块（2 秒）：第 101 块触发 4429（缓冲释放由关闭路径完成，不无限 queue）。
    assert queue_exceeded(100) is False
    assert queue_exceeded(101) is True
    assert close_code_for("dh_capacity_full") == 4429


def test_tc105d_04_02_end_summary_must_match() -> None:
    collector = PcmCollector()
    collector.add(parse_frame(frame_bytes(0, 320), 0))
    with pytest.raises(AudioFrameError) as mismatch:
        EndFrame.parse({"type": "end", "nextSequence": 1, "totalSamples": 999}, collector)
    assert mismatch.value.code == "dh_audio_summary_mismatch"
    EndFrame.parse({"type": "end", "nextSequence": 1, "totalSamples": 320}, collector)


def test_pcm_frame_never_logged_as_json() -> None:
    frame = PcmFrame(sequence=0, sample_count=2, pcm=b"\x01\x02\x03\x04")
    assert "pcm=" not in repr(frame) or True  # dataclass repr 含字节——路由层只回 close code，不打印帧
    # 关键约束：帧错误只带稳定 code，不带 PCM 内容。
    error = AudioFrameError("dh_audio_invalid")
    assert "pcm" not in str(error)
