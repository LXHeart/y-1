"""输出字幕 SRT 测试（任务书 #105F C105F-02 / TC105F-02-03 字幕子断言）。

中断（尚未输出的文本不在窗口内）、0 点映射（裁剪到录制起点）、序号从 1、严格
start < end 且非负、UTF-8 中文。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.subtitles import SpeechSegment, build_srt  # noqa: E402


def test_srt_numbers_from_one_and_zero_point_mapping():
    segments = [
        SpeechSegment("第一句", 5_000, 7_000),
        SpeechSegment("第二句", 7_500, 9_000),
    ]
    content = build_srt(segments, 5_000, 10_000).decode("utf-8")
    assert content.startswith("1\n00:00:00,000 --> 00:00:02,000\n第一句\n")
    assert "2\n00:00:02,500 --> 00:00:04,000\n第二句" in content


def test_srt_interrupt_drops_unspoken_text():
    # 中断：第三句窗口在录制终点之后（尚未输出）——不进字幕。
    segments = [
        SpeechSegment("已说", 1_000, 3_000),
        SpeechSegment("未说", 8_000, 9_500),
    ]
    content = build_srt(segments, 0, 5_000).decode("utf-8")
    assert "已说" in content
    assert "未说" not in content
    assert "2\n" not in content  # 只有 1 条


def test_srt_crops_segment_crossing_window_edges():
    segments = [SpeechSegment("跨界", 1_000, 9_000)]
    content = build_srt(segments, 2_000, 5_000).decode("utf-8")
    assert "00:00:00,000 --> 00:00:03,000" in content


@pytest.mark.parametrize("start_ms,end_ms", [(5_000, 5_000), (6_000, 5_000), (-1, 0), (10, 5)])
def test_srt_invalid_window_returns_empty(start_ms, end_ms):
    segments = [SpeechSegment("x", 0, 100)]
    assert build_srt(segments, start_ms, end_ms) == b""


def test_srt_drops_degenerate_and_blank_segments():
    segments = [
        SpeechSegment("", 100, 200),  # 空白文本
        SpeechSegment("退化", 500, 500),  # start == end
        SpeechSegment("有效", 600, 900),
    ]
    content = build_srt(segments, 0, 1_000).decode("utf-8")
    assert content.startswith("1\n")
    assert "有效" in content
    assert "退化" not in content


def test_srt_timestamps_never_negative_and_strictly_ordered():
    segments = [SpeechSegment("早于起点", 0, 2_000), SpeechSegment("正常", 3_000, 4_000)]
    content = build_srt(segments, 1_000, 5_000).decode("utf-8")
    assert "00:00:00,000 --> 00:00:01,000" in content  # 裁剪后非负
    for line in content.splitlines():
        if " --> " in line:
            left, right = line.split(" --> ")
            assert _to_ms(right) > _to_ms(left) >= 0


def test_srt_output_is_utf8_bytes():
    payload = build_srt([SpeechSegment("中文（UTF-8）", 0, 1_000)], 0, 2_000)
    assert isinstance(payload, bytes)
    payload.decode("utf-8")  # 不抛即 UTF-8


def _to_ms(stamp: str) -> int:
    clock, millis = stamp.split(",")
    hours, minutes, seconds = clock.split(":")
    return int(hours) * 3_600_000 + int(minutes) * 60_000 + int(seconds) * 1_000 + int(millis)
