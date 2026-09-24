"""输出AV录制分支与分段时钟测试（任务书 #105F C105F-02 / TC105F-02-01～04）。

TC105F-02-01（P0）：彩色帧 + 1kHz 输出音频（mic 的 2kHz 不进分支——录制面只有
on_video/on_audio 两个 Program 输出入口，结构性排除），真编码后 ffprobe 验 H264+AAC、
1kHz 在 2kHz 不在、时长同步。
TC105F-02-02：同会话先后两段独立文件/0 点/manifest，第二段不清第一段。
TC105F-02-03：中断未输出文本不进字幕；录制队列 ≤2 秒满即 partial（dh_recording_overflow），
生产面全程非阻塞（对话不受队列影响）。
TC105F-02-04：300 秒/字节上限 partial 收口、编码器崩溃 failed 且孤儿文件可清。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import subprocess
import sys
import uuid as uuid_module
from pathlib import Path
from typing import Any, Dict, List

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from grassland_dh.media import ProgramAdapter  # noqa: E402
from grassland_dh.recording import (  # noqa: E402
    MAX_SEGMENT_BYTES,
    MAX_SEGMENT_DURATION_MS,
    RecordingBranch,
    delete_recording_tree,
    object_ref_for,
    recording_dir,
)
from grassland_dh.routes_internal import create_internal_router  # noqa: E402

SAMPLE_RATE = 16_000


def _video_frame(program_ms: int, y: int = 120, u: int = 90, v: int = 200) -> Any:
    import fractions

    import numpy as np
    from av import VideoFrame

    frame = VideoFrame(width=64, height=36, format="yuv420p")
    frame.planes[0].update(np.full((36, 64), y, dtype="uint8").tobytes())
    frame.planes[1].update(np.full((18, 32), u, dtype="uint8").tobytes())
    frame.planes[2].update(np.full((18, 32), v, dtype="uint8").tobytes())
    frame.pts = program_ms
    frame.time_base = fractions.Fraction(1, 1000)
    return frame


def _audio_frame(samples: int, freq_hz: float, phase: float, pts: int) -> Any:
    import fractions

    import numpy as np
    from av import AudioFrame

    frame = AudioFrame(format="s16", layout="mono", samples=samples)
    t = (np.arange(samples) + phase) / SAMPLE_RATE
    wave = (np.sin(2 * math.pi * freq_hz * t) * 9000).astype("<i2")
    frame.planes[0].update(wave.tobytes())
    frame.sample_rate = SAMPLE_RATE
    frame.pts = pts
    frame.time_base = fractions.Fraction(1, SAMPLE_RATE)
    return frame


async def _feed_segment(branch: RecordingBranch, start_ms: int, end_ms: int, *,
                        freq_hz: float = 1_000.0, yield_every: int = 10) -> int:
    """按程序钟喂 Program 输出：视频 10fps（100ms）、音频 20ms/帧——与 fake 轨道节拍一致。

    periodic yield 让消费协程排水；喂帧不等待真实墙钟（媒体时间由 program_ms 显式给出）。
    """
    fed = 0
    phase = 0.0
    pending_video = start_ms
    for program_ms in range(start_ms, end_ms, 20):
        while pending_video <= program_ms:
            branch.on_video(_video_frame(pending_video), pending_video)
            pending_video += 100
            fed += 1
        branch.on_audio(_audio_frame(320, freq_hz, phase, 0), program_ms)
        phase = (phase + 320) % SAMPLE_RATE
        fed += 1
        if fed % yield_every == 0:
            await asyncio.sleep(0)
    return fed


def _ffprobe(path: Path) -> Dict[str, Any]:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", str(path)],
        capture_output=True, text=True, timeout=30, check=True)
    return json.loads(result.stdout)


def _decode_audio_spectrum(path: Path) -> Dict[int, float]:
    """解码输出音频并给出指定频点的相对幅值（1 归一）。"""
    import numpy as np
    import av

    samples: List[np.ndarray] = []
    with av.open(str(path)) as container:
        for frame in container.decode(audio=0):
            array = frame.to_ndarray().reshape(-1).astype("float64")
            samples.append(array)
    joined = np.concatenate(samples) if samples else np.zeros(1)
    spectrum = np.abs(np.fft.rfft(joined))
    peak = spectrum.max() or 1.0
    def at(freq: int) -> float:
        bin_index = int(round(freq * len(joined) / 48_000))
        return spectrum[max(0, bin_index - 2):bin_index + 3].max() / peak
    return {freq: at(freq) for freq in (500, 1_000, 2_000, 4_000)}


# ---------- TC105F-02-01：有声音真 MP4（P0） ----------


@pytest.mark.asyncio
async def test_tc105f_02_01_real_mp4_h264_acc_program_audio_only(tmp_path: Path) -> None:
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await branch.start(0)
    assert branch.state == "recording"
    # 录制分支不存在任何 mic 输入面（K09 红线：结构性排除，非靠约定）。
    assert not hasattr(branch, "on_mic") and not hasattr(branch, "on_microphone")
    await _feed_segment(branch, 0, 5_000, freq_hz=1_000.0)
    manifest = await branch.stop("user")

    assert manifest.partial is False
    assert manifest.video is not None
    segment = recording_dir(tmp_path, manifest.recording_id) / "segment.mp4"
    assert segment.exists()

    probe = _ffprobe(segment)
    streams = probe["streams"]
    video = next(s for s in streams if s["codec_type"] == "video")
    audio = next(s for s in streams if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264"
    assert video["pix_fmt"] == "yuv420p"
    assert video["width"] == 64 and video["height"] == 36
    assert audio["codec_name"] == "aac"
    assert int(audio["sample_rate"]) == 48_000

    duration = float(probe["format"]["duration"])
    assert 4.4 <= duration <= 5.6, f"时长应约 5 秒，实际 {duration}"
    video_duration = float(video.get("duration") or duration)
    audio_duration = float(audio.get("duration") or duration)
    assert abs(video_duration - audio_duration) < 0.4, "画面与音频时长同步"

    spectrum = _decode_audio_spectrum(segment)
    assert spectrum[1_000] > 0.8, f"输出音频应含 1kHz：{spectrum}"
    assert spectrum[2_000] < 0.1, f"用户 mic 的 2kHz 不得混入录制：{spectrum}"
    assert manifest.video.duration_ms == pytest.approx(5_000, abs=600)
    assert manifest.video.sha256 == hashlib.sha256(segment.read_bytes()).hexdigest()
    assert manifest.video.object_ref == object_ref_for(manifest.recording_id, "mp4")


@pytest.mark.asyncio
async def test_tc105f_02_01_empty_media_fails_without_artifact(tmp_path: Path) -> None:
    # 无可解码音画：只喂视频不喂音频 → failed，不产可下载产物（K09）。
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await branch.start(0)
    for program_ms in range(0, 1_000, 100):
        branch.on_video(_video_frame(program_ms), program_ms)
        await asyncio.sleep(0)
    manifest = await branch.stop("user")
    assert branch.state == "failed"
    assert manifest.video is None
    assert manifest.error_code == "dh_recording_failed"
    assert not (recording_dir(tmp_path, manifest.recording_id) / "segment.mp4").exists()


# ---------- TC105F-02-02：两段不中覆盖 ----------


@pytest.mark.asyncio
async def test_tc105f_02_02_two_segments_independent_zero_points(tmp_path: Path) -> None:
    first = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await first.start(0)
    await _feed_segment(first, 0, 2_000, freq_hz=1_000.0)
    manifest_one = await first.stop("user")

    first_path = recording_dir(tmp_path, manifest_one.recording_id) / "segment.mp4"
    first_hash = hashlib.sha256(first_path.read_bytes()).hexdigest()

    # 第二段：程序钟已推进到 3000（多轮对话后），独立 recordingId、独立 0 点。
    second = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await second.start(3_000)
    await _feed_segment(second, 3_000, 5_000, freq_hz=1_200.0)
    manifest_two = await second.stop("user")

    assert manifest_one.recording_id != manifest_two.recording_id
    # 首段文件与 hash 不变（第二段不清第一段）。
    assert hashlib.sha256(first_path.read_bytes()).hexdigest() == first_hash
    assert manifest_one.start_program_ms == 0
    assert manifest_two.start_program_ms == 3_000
    # 各自 0 点：两段时长都约 2 秒（第二段不是从会话起点累计的 5 秒）。
    assert manifest_one.video.duration_ms == pytest.approx(2_000, abs=400)
    assert manifest_two.video.duration_ms == pytest.approx(2_000, abs=400)

    second_path = recording_dir(tmp_path, manifest_two.recording_id) / "segment.mp4"
    probe = _ffprobe(second_path)
    duration = float(probe["format"]["duration"])
    assert 1.4 <= duration <= 2.6, f"第二段应为独立 2 秒段，实际 {duration}"


# ---------- TC105F-02-03：中断与队列溢出 ----------


@pytest.mark.asyncio
async def test_tc105f_02_03_interrupt_drops_unspoken_text_from_subtitle(tmp_path: Path) -> None:
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await branch.start(0)
    await _feed_segment(branch, 0, 1_000)
    branch.record_speech("已实际输出", 100, 500)
    branch.record_speech("中断未输出", 1_500, 1_800)  # 晚于最后输出帧（~980ms）：尚未输出
    manifest = await branch.stop("interrupted")
    assert manifest.subtitle is not None
    content = (recording_dir(tmp_path, manifest.recording_id) / "subtitle.srt").read_text("utf-8")
    assert "已实际输出" in content
    assert "中断未输出" not in content
    assert manifest.subtitle.object_ref == object_ref_for(manifest.recording_id, "srt")


@pytest.mark.asyncio
async def test_tc105f_02_03_queue_overflow_marks_partial_without_blocking(tmp_path: Path) -> None:
    # 2 秒队列上限的微缩等价：容量 4 的有界队列，生产面同步灌 12 帧不 await。
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path, queue_capacity=4)
    await branch.start(0)
    for index in range(12):
        branch.on_video(_video_frame(index * 100), index * 100)
        branch.on_audio(_audio_frame(320, 1_000.0, 0, 0), index * 100)
    # 生产面全程非阻塞（对话不被录制队列阻塞）：上述灌帧即时完成且分支进入溢出收尾。
    assert branch._writer is not None
    assert branch._writer.stop_reason == "dh_recording_overflow"
    manifest = await branch.stop("overflow")
    assert manifest.partial is True
    assert manifest.error_code is None  # partial 不是 failed：合法部分保留
    assert manifest.video is not None
    probe = _ffprobe(recording_dir(tmp_path, manifest.recording_id) / "segment.mp4")
    assert probe["streams"], "溢出前已编码帧保留为 partial 产物"


@pytest.mark.asyncio
async def test_tc105f_02_03_adapter_emission_survives_recorder_failures() -> None:
    # adapter 出口吞掉录制分支异常：录制失败不影响节目输出（对话不阻塞）。
    adapter = ProgramAdapter(session_id="s", lease_epoch=1, media_epoch=1)

    class Boom:
        def on_video(self, frame: Any, program_ms: int) -> None:
            raise RuntimeError("recorder exploded")

        def on_audio(self, frame: Any, program_ms: int) -> None:
            raise RuntimeError("recorder exploded")

    adapter.recorder = Boom()
    adapter.emit_output_video(_video_frame(0))
    adapter.emit_output_audio(_audio_frame(320, 1_000.0, 0, 0))


# ---------- TC105F-02-04：边界与进程退出 ----------


@pytest.mark.asyncio
async def test_tc105f_02_04_duration_cap_stops_partial(tmp_path: Path) -> None:
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path, max_duration_ms=1_000)
    await branch.start(0)
    await _feed_segment(branch, 0, 2_500)
    manifest = await branch.stop("user")
    assert branch._writer is not None and branch._writer.stop_reason == "duration_cap"
    assert manifest.partial is True
    assert manifest.video is not None
    assert manifest.video.duration_ms <= 1_200


@pytest.mark.asyncio
async def test_tc105f_02_04_size_cap_stops_partial(tmp_path: Path) -> None:
    # 微缩上限：首个 mp4 头+数据包写盘后即超 1KB → size_cap 收口 partial。
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path, max_bytes=1_024)
    await branch.start(0)
    program_ms = 0
    while program_ms < 5_000:
        branch.on_video(_video_frame(program_ms), program_ms)
        branch.on_audio(_audio_frame(320, 1_000.0, 0, 0), program_ms)
        await asyncio.sleep(0)
        program_ms += 20
        if branch._writer is not None and branch._writer.stop_reason is not None:
            break
    assert branch._writer is not None and branch._writer.stop_reason == "size_cap"
    manifest = await branch.stop("user")
    assert manifest.partial is True
    assert manifest.video is not None


@pytest.mark.asyncio
async def test_tc105f_02_04_encoder_crash_fails_and_orphans_cleanable(tmp_path: Path,
                                                                      monkeypatch: pytest.MonkeyPatch) -> None:
    import av

    def broken_open(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("encoder boom")

    monkeypatch.setattr(av, "open", broken_open)
    branch = RecordingBranch(str(uuid_module.uuid4()), tmp_path)
    await branch.start(0)
    await _feed_segment(branch, 0, 400)
    manifest = await branch.stop("user")
    assert branch.state == "failed"
    assert manifest.video is None
    assert manifest.error_code == "dh_recording_failed"
    monkeypatch.undo()
    # 孤儿文件可清：INTERNAL13 整目录收口、不报假零。
    stray = recording_dir(tmp_path, manifest.recording_id)
    stray.mkdir(parents=True, exist_ok=True)
    (stray / "segment.mp4.partial").write_bytes(b"orphan")
    complete, remaining = await asyncio.to_thread(delete_recording_tree, tmp_path,
                                                  manifest.recording_id)
    assert complete is True and remaining == []
    assert not stray.exists()


def test_tc105f_02_04_hard_caps_are_contract_limits() -> None:
    # 构建具备 codec（许可证见 license-manifest.json：PyAV wheel 捆绑 ffmpeg/libx264）。
    import av

    assert av.codec.Codec("libx264", "w").name == "libx264"
    assert av.codec.Codec("aac", "w").name == "aac"
    assert MAX_SEGMENT_DURATION_MS == 300_000
    assert MAX_SEGMENT_BYTES == 200 * 1024 * 1024


# ---------- INTERNAL02 命令面（路由级；AsyncClient+ASGITransport 与录制分支同 loop） ----------


class RecordingBridge:
    """受控内部通道 fake：只记录 INTERNAL11 上报（最外层网络依赖替身，K12）。"""

    def __init__(self) -> None:
        self.reports: List[Dict[str, Any]] = []

    async def _post_json(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        self.reports.append({"path": path, "body": body})
        return {"accepted": True}


def _mount_internal(bridge: Any) -> FastAPI:
    app = FastAPI()
    app.state.sessions = {}
    app.state.bridge = bridge
    router = create_internal_router(lambda session_id: app.state.sessions.get(session_id),
                                    enabled_check=lambda: True)
    app.add_api_route("/internal/v1/sessions", router["create_session"], methods=["POST"])
    app.add_api_route("/internal/v1/sessions/{session_id}/commands", router["session_commands"],
                      methods=["POST"])
    return app


def _command_body(command: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "commandId": str(uuid_module.uuid4()),
        "payloadHash": hashlib.sha256(
            json.dumps({"command": command, "payload": payload}, sort_keys=True).encode()).hexdigest(),
        "command": command, "payload": payload}


@pytest.mark.asyncio
async def test_tc105f_02_route_start_stop_reports_and_validates(tmp_path: Path,
                                                                monkeypatch: pytest.MonkeyPatch) -> None:
    import httpx

    monkeypatch.setenv("DH_MEDIA_ROOT", str(tmp_path))
    bridge = RecordingBridge()
    app = _mount_internal(bridge)
    session_id = str(uuid_module.uuid4())
    recording_id = str(uuid_module.uuid4())
    start_payload = {"recordingId": recording_id, "maxDurationMs": 300_000,
                     "maxBytes": 200 * 1024 * 1024}

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                 base_url="http://dh-internal") as client:
        created = await client.post("/internal/v1/sessions", json={
            "commandId": str(uuid_module.uuid4()), "payloadHash": "boot",
            "binding": {"sessionId": session_id, "leaseEpoch": 1, "mediaEpoch": 1}})
        assert created.status_code == 202
        state = app.state.sessions[session_id]

        started = await client.post(f"/internal/v1/sessions/{session_id}/commands",
                                    json=_command_body("startRecording", start_payload))
        assert started.status_code == 202
        assert state.recording is not None

        # 并发第二段 → 409；载荷缺字段/超硬顶 → 422；eraseContent 仍明确不支持。
        second = await client.post(f"/internal/v1/sessions/{session_id}/commands", json=_command_body(
            "startRecording", {"recordingId": str(uuid_module.uuid4()), "maxDurationMs": 300_000,
                               "maxBytes": 100}))
        assert second.status_code == 409
        missing = await client.post(f"/internal/v1/sessions/{session_id}/commands",
                                    json=_command_body("startRecording", {"maxDurationMs": 300_000}))
        assert missing.status_code == 422
        over_cap = await client.post(f"/internal/v1/sessions/{session_id}/commands", json=_command_body(
            "startRecording", {"recordingId": recording_id, "maxDurationMs": 400_000, "maxBytes": 100}))
        assert over_cap.status_code == 422
        erase = await client.post(f"/internal/v1/sessions/{session_id}/commands", json={
            "commandId": str(uuid_module.uuid4()), "payloadHash": "x", "command": "eraseContent",
            "payload": {"contentEpoch": 2}})
        assert erase.status_code == 409

        # 喂 Program 输出（adapter 出口=轨道帧复制路径；同 loop 排水）。
        for program_ms in range(0, 600, 20):
            state.adapter.emit_output_video(_video_frame(program_ms))
            state.adapter.emit_output_audio(_audio_frame(320, 1_000.0, 0, 0))
            await asyncio.sleep(0)

        stopped = await client.post(f"/internal/v1/sessions/{session_id}/commands",
                                    json=_command_body("stopRecording",
                                                       {"recordingId": recording_id, "reasonCode": "user"}))
        assert stopped.status_code == 202
        assert state.recording is None
        assert recording_id in state.recording_archive

        reports = [r for r in bridge.reports if r["path"] == "/internal/digital-human/artifacts"]
        assert len(reports) == 1, "INTERNAL11 恰一次"
        body = reports[0]["body"]
        assert body["kind"] == "recording" and body["resourceId"] == recording_id
        assert body["manifest"]["video"]["objectRef"] == object_ref_for(recording_id, "mp4")

        # 未知段停止 → 404；同 id 再 start → 409（同 id 不再新建段）。
        unknown = await client.post(f"/internal/v1/sessions/{session_id}/commands", json=_command_body(
            "stopRecording", {"recordingId": str(uuid_module.uuid4()), "reasonCode": "user"}))
        assert unknown.status_code == 404
        restart = await client.post(f"/internal/v1/sessions/{session_id}/commands",
                                    json=_command_body("startRecording", start_payload))
        assert restart.status_code == 409


@pytest.mark.asyncio
async def test_tc105f_02_route_without_bridge_fails_closed(tmp_path: Path,
                                                           monkeypatch: pytest.MonkeyPatch) -> None:
    import httpx

    monkeypatch.setenv("DH_MEDIA_ROOT", str(tmp_path))
    app = _mount_internal(None)
    session_id = str(uuid_module.uuid4())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                 base_url="http://dh-internal") as client:
        created = await client.post("/internal/v1/sessions", json={
            "commandId": str(uuid_module.uuid4()), "payloadHash": "boot",
            "binding": {"sessionId": session_id, "leaseEpoch": 1, "mediaEpoch": 1}})
        assert created.status_code == 202
        response = await client.post(f"/internal/v1/sessions/{session_id}/commands", json=_command_body(
            "startRecording", {"recordingId": str(uuid_module.uuid4()), "maxDurationMs": 300_000,
                               "maxBytes": 100}))
        assert response.status_code == 503
        assert response.json()["code"] == "dh_runtime_unavailable"
