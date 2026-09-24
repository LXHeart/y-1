"""输出字幕 SRT 生成（任务书 #105F C105F-02 / K09）。

以段为单位对齐实际输出音频区间（不声称字级对齐）：输入为程序钟窗口内的已审文本段，
``build_srt`` 裁剪到 [start_ms, end_ms]、映射到该段 0 点（录制起点）、序号从 1、严格
start < end 且非负、UTF-8。中断尚未输出的文本不在窗口内，自然被裁剪——不消费未来内容。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class SpeechSegment:
    """一段实际输出语音的已审文本（start/end 为程序钟毫秒，0 ≤ start < end）。"""

    text: str
    start_ms: int
    end_ms: int


def _format_timestamp(total_ms: int) -> str:
    if total_ms < 0:
        raise ValueError("SRT 时间戳不能为负")
    hours, rest = divmod(total_ms, 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    seconds, millis = divmod(rest, 1_000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def build_srt(segments: list[SpeechSegment], start_ms: int, end_ms: int) -> bytes:
    """生成 SRT 字节：裁剪到录制窗口、相对 0 点、序号从 1、严格递增区间。

    - 区间交集为空或退化（裁剪后 end ≤ start）的段丢弃（中断未输出的文本不进字幕）；
    - 空白文本丢弃；
    - 输出条目仍按原始顺序编号（从 1 起，不重排不合并）。
    """
    if end_ms <= start_ms:
        return b""
    cues: list[tuple[int, int, str]] = []
    for segment in segments:
        clipped_start = max(segment.start_ms, start_ms)
        clipped_end = min(segment.end_ms, end_ms)
        if clipped_end <= clipped_start:
            continue
        text = segment.text.strip()
        if not text:
            continue
        rel_start = clipped_start - start_ms
        rel_end = clipped_end - start_ms
        if rel_start < 0 or rel_end <= rel_start:
            continue
        cues.append((rel_start, rel_end, text))
    blocks: list[str] = []
    for index, (rel_start, rel_end, text) in enumerate(cues, start=1):
        blocks.append(f"{index}\n{_format_timestamp(rel_start)} --> {_format_timestamp(rel_end)}\n{text}\n")
    return "\n".join(blocks).encode("utf-8")
