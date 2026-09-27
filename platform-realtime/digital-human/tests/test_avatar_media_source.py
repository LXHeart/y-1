"""runtime-static 形象媒体源（AvatarFrameSource / PcmAudioQueue / 带源 RtcSession）。

画面形态 = 形象静态帧 + 能量律动（opentalking mock 同构）；音频 = Fake 管线 PCM 经
PcmAudioQueue 进 WebRTC 轨。无源时回落既有假帧行为（旧档/测试兼容零变化）。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from grassland_dh.media import (  # noqa: E402
    AvatarFrameSource,
    PcmAudioQueue,
    ProgramAdapter,
    RtcSession,
    _fake_video_frame,
)


def _make_png(path: Path, color: tuple[int, int, int] = (200, 120, 40)) -> None:
    from PIL import Image

    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (320, 180), color).save(path, format="PNG")


def test_pcm_queue_feeds_ordered_samples_and_silent_gap() -> None:
    import numpy as np

    queue = PcmAudioQueue()
    # 1.5 个 tick 的 int16 ndarray（vendor 队列元素形态）
    chunk = np.arange(480, dtype=np.int16)
    queue.feed(chunk)
    first = queue.next_samples()
    second = queue.next_samples()
    assert len(first) == 320 * 2
    assert np.frombuffer(first, dtype="<i2").tolist() == list(range(320))
    # 第二取只余 160 样本 → 静音补齐
    tail = np.frombuffer(second, dtype="<i2").tolist()
    assert tail[:160] == list(range(320, 480))
    assert tail[160:] == [0] * 160
    # 空窗：全静音、level 衰减不为负
    silent = queue.next_samples()
    assert silent == b"\x00\x00" * 320
    assert 0.0 <= queue.level <= 1.0


def test_pcm_queue_level_rises_on_loud_feed_and_decays() -> None:
    import numpy as np

    queue = PcmAudioQueue()
    loud = np.full(320, 16000, dtype=np.int16)
    queue.feed(loud)
    before = queue.level
    queue.next_samples()
    assert queue.level >= before  # 响度起来
    peak = queue.level
    queue.clear()
    queue.next_samples()  # 静音窗
    assert queue.level <= peak  # 慢落不增


def test_avatar_frame_source_renders_image_with_motion(tmp_path: Path) -> None:
    image = tmp_path / "normalized.png"
    _make_png(image)
    source = AvatarFrameSource(image, width=160, height=90)
    assert source.is_ready is True
    quiet = source.render(0, audio_level=0.0)
    loud = source.render(100, audio_level=1.0)
    assert quiet.width == 160 and quiet.height == 90
    quiet_px = quiet.to_ndarray()
    loud_px = loud.to_ndarray()
    assert int(loud_px.max()) >= int(quiet_px.max())  # 能量律动提亮
    assert source.frames_rendered == 2


def test_avatar_frame_source_missing_file_degrades_not_ready(tmp_path: Path) -> None:
    source = AvatarFrameSource(tmp_path / "absent.png")
    assert source.is_ready is False
    # 渲染回落假帧（轨道不断流）
    frame = source.render(5)
    assert frame.width == 64 and frame.height == 36


@pytest.mark.asyncio
async def test_rtc_session_uses_sources_and_falls_back_without() -> None:
    import tempfile

    from PIL import Image

    from grassland_dh.media import ProgramClock

    # 无源会话 = 既有 Fake 假帧行为
    plain = RtcSession(1, ProgramClock())
    frame = plain._render_video_frame()
    assert (frame.width, frame.height) == (64, 36)

    # 带源会话：帧来自形象图
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "normalized.png"
        Image.new("RGB", (200, 200), (10, 200, 30)).save(path, format="PNG")
        source = AvatarFrameSource(path, width=320, height=180)
    queue = PcmAudioQueue()
    avatared = RtcSession(1, ProgramClock(), video_source=source, audio_source=queue)
    assert avatared._render_video_frame().width == 320
    audio_frame = avatared._render_audio_frame()
    assert audio_frame.samples == 320  # 16k mono s16 每 20ms 一取


@pytest.mark.asyncio
async def test_program_adapter_new_peer_keeps_sources_across_reset() -> None:
    import tempfile

    from PIL import Image

    adapter = ProgramAdapter(session_id="s1", lease_epoch=1, media_epoch=1)
    assert adapter.frame_source is None and adapter.audio_queue is None
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "normalized.png"
        Image.new("RGB", (64, 64), (0, 0, 255)).save(path, format="PNG")
        adapter.frame_source = AvatarFrameSource(path, width=64, height=64)
    adapter.audio_queue = PcmAudioQueue()
    peer = adapter.new_peer()
    assert peer.video_source is adapter.frame_source
    assert peer.audio_source is adapter.audio_queue
    # reset 后新 peer 继续同一源（同一形象与音频缓冲）
    await adapter.reset_media(2)
    peer2 = adapter.new_peer()
    assert peer2.video_source is adapter.frame_source
    assert peer2.media_epoch == 2
