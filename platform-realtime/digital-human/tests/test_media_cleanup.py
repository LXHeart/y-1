"""录制文件生命周期：到期清理、重启孤儿目录、删除失败重试（任务书 #105F C105F-05 / TC105F-05-02）。

架构分工如实注明：24h 到期判定与有界重试在 Java 侧（dh_recording.expires_at 驱动清理
worker，PersonalDataObjectCleanup MAX_OBJECT_ATTEMPTS=8 重试环）；运行时侧本文件经真实
internal 路由（INTERNAL01/02/12/13）证明文件面合同——
- 到期清理按删除命令逐对象收口：临时段删净、未收到删除命令的已存 asset 文件原样保留且仍可读；
- 删除失败不报假零：残留句柄逐对象列出，驱动侧重试后收口；
- 重启（进程内注册表清空）：不产生陈旧读取（磁盘文件在但未登记→404），孤儿目录仍可按资源路径清理；
- 进行中段先停后删（409），不得绕过停止直接删活动段。

全部走真实 FastAPI app + 真编码文件（H264/AAC），无 route mock；唯一替身是受控内部通道
fake bridge（最外层网络依赖，K12）。
"""

from __future__ import annotations

import hashlib
import math
import os
import sys
import time
import uuid as uuid_module
from pathlib import Path
from typing import Any, Dict, List

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from grassland_dh.app import create_app  # noqa: E402
from grassland_dh.recording import object_ref_for, recording_dir  # noqa: E402

SAMPLE_RATE = 16_000
MAX_SEGMENT_BYTES = 200 * 1024 * 1024


class FakeBridge:
    """受控内部通道 fake：只记录 INTERNAL11 上报（最外层网络依赖替身，K12）。"""

    def __init__(self) -> None:
        self.reports: List[Dict[str, Any]] = []

    async def _post_json(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        self.reports.append({"path": path, "body": body})
        return {"accepted": True, "reason": "ready"}

    def recording_receipts(self) -> List[Dict[str, Any]]:
        return [r["body"] for r in self.reports
                if r["path"] == "/internal/digital-human/artifacts" and r["body"].get("kind") == "recording"]


def _video_frame(program_ms: int) -> Any:
    import fractions

    import numpy as np
    from av import VideoFrame

    frame = VideoFrame(width=64, height=36, format="yuv420p")
    frame.planes[0].update(np.full((36, 64), 120, dtype="uint8").tobytes())
    frame.planes[1].update(np.full((18, 32), 90, dtype="uint8").tobytes())
    frame.planes[2].update(np.full((18, 32), 200, dtype="uint8").tobytes())
    frame.pts = program_ms
    frame.time_base = fractions.Fraction(1, 1000)
    return frame


def _audio_frame(samples: int, freq_hz: float, phase: float) -> Any:
    import fractions

    import numpy as np
    from av import AudioFrame

    frame = AudioFrame(format="s16", layout="mono", samples=samples)
    t = (np.arange(samples) + phase) / SAMPLE_RATE
    wave = (np.sin(2 * math.pi * freq_hz * t) * 9000).astype("<i2")
    frame.planes[0].update(wave.tobytes())
    frame.sample_rate = SAMPLE_RATE
    frame.pts = 0
    frame.time_base = fractions.Fraction(1, SAMPLE_RATE)
    return frame


def _make_client(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, bridge: FakeBridge) -> FastAPI:
    monkeypatch.setenv("DH_MEDIA_ROOT", str(tmp_path / "media"))
    app = create_app(test_mode=True, bridge=bridge)
    app.state._test_media_root = tmp_path / "media"
    return app


def _create_session(client: TestClient, session_id: str) -> None:
    response = client.post("/internal/v1/sessions", json={
        "commandId": str(uuid_module.uuid4()), "sessionId": session_id,
        "leaseEpoch": 1, "mediaEpoch": 1})
    assert response.status_code == 202, response.text


def _command(client: TestClient, session_id: str, command: str, payload: Dict[str, Any]) -> Any:
    response = client.post(f"/internal/v1/sessions/{session_id}/commands", json={
        "commandId": str(uuid_module.uuid4()), "payloadHash": hashlib.sha256(
            command.encode()).hexdigest()[:8], "leaseEpoch": 1, "command": command, "payload": payload})
    return response


def _start_recording(client: TestClient, session_id: str, recording_id: str) -> None:
    response = _command(client, session_id, "startRecording", {
        "recordingId": recording_id, "maxDurationMs": 60_000, "maxBytes": MAX_SEGMENT_BYTES})
    assert response.status_code == 202, response.text


def _feed_program(app: FastAPI, session_id: str, *, seconds: float) -> None:
    """经 Program 输出入口喂帧（与 fake 轨道同节拍：视频 100ms、音频 20ms）。

    帧生产必须在编码任务所在的同一事件环上调度（run_coroutine_threadsafe 唤醒环；
    从测试线程裸 put_nowait 的 call_soon 不写自管道、唤不醒停驻在 queue.get() 的环）。
    环内有界排水：等消费者吃到已喂帧（内存计数 + 截止期，超时即失败——不 sleep 换绿灯）。
    """
    import asyncio

    state = app.state.sessions[session_id]
    branch = state.recording
    assert branch is not None and branch.state == "recording", "录制分支应在场"
    writer = branch._writer
    loop = writer._task.get_loop()

    async def feeder() -> None:
        fed = 0
        phase = 0.0
        pending_video = 0
        for program_ms in range(0, int(seconds * 1000), 20):
            while pending_video <= program_ms:
                branch.on_video(_video_frame(pending_video), pending_video)
                pending_video += 100
                fed += 1
            branch.on_audio(_audio_frame(320, 1_000.0, phase), program_ms)
            phase = (phase + 320) % SAMPLE_RATE
            fed += 1
            if fed % 40 == 0:
                deadline = time.monotonic() + 30.0
                while writer.video_frames + writer.audio_frames < fed - 8:
                    if time.monotonic() > deadline:
                        raise AssertionError(f"编码消费停滞：喂 {fed}，消费 "
                                             f"{writer.video_frames + writer.audio_frames}")
                    await asyncio.sleep(0.005)

    asyncio.run_coroutine_threadsafe(feeder(), loop).result(timeout=120)


def _stop_recording(client: TestClient, app: FastAPI, session_id: str, recording_id: str,
                    *, speech: bool = True) -> Dict[str, Any]:
    state = app.state.sessions[session_id]
    if speech and state.recording is not None:
        state.recording.record_speech("已实际输出的文本", 200, 1_500)
    response = _command(client, session_id, "stopRecording",
                        {"recordingId": recording_id, "reasonCode": "user"})
    assert response.status_code == 202, response.text
    manifest = state.recording_archive[recording_id]["manifest"]
    assert manifest.video is not None, "真编码段应产出 video 产物"
    return manifest


def _manifest_video_sha(manifest: Any) -> str:
    return manifest.video.sha256


def _delete(client: TestClient, recording_id: str) -> Any:
    return client.post(f"/internal/v1/resources/{recording_id}/delete", json={
        "commandId": str(uuid_module.uuid4()), "payloadHash": "h", "kind": "recording", "revision": 1})


def _read(client: TestClient, recording_id: str, key: str) -> Any:
    return client.get(f"/internal/v1/artifacts/{recording_id}/{object_ref_for(recording_id, key)}")


# ---------- TC105F-05-02 主线：临时段到期删除、已存 asset 保留 ----------


def test_tc105f_05_02_expired_temp_deleted_saved_asset_retained(tmp_path: Path,
                                                                monkeypatch: pytest.MonkeyPatch) -> None:
    bridge = FakeBridge()
    app = _make_client(monkeypatch, tmp_path, bridge)
    session_id = str(uuid_module.uuid4())
    temp_id, saved_id = str(uuid_module.uuid4()), str(uuid_module.uuid4())
    with TestClient(app) as client:
        _create_session(client, session_id)
        _start_recording(client, session_id, temp_id)
        _feed_program(app, session_id, seconds=2.0)
        temp_manifest = _stop_recording(client, app, session_id, temp_id)
        _start_recording(client, session_id, saved_id)
        _feed_program(app, session_id, seconds=2.0)
        saved_manifest = _stop_recording(client, app, session_id, saved_id)

        media_root = Path(app.state._test_media_root)
        temp_dir, saved_dir = recording_dir(media_root, temp_id), recording_dir(media_root, saved_id)
        assert (temp_dir / "segment.mp4").is_file() and (temp_dir / "subtitle.srt").is_file()
        assert (saved_dir / "segment.mp4").is_file()
        saved_before = (saved_dir / "segment.mp4").read_bytes()

        # 时间推进 25h（文件 mtime 一律拨回）：运行时无 mtime 扫描器——删除只由命令驱动，
        # 到期判定在 Java 侧。此刻临时段仍在下载窗口内（INTERNAL12 可读、sha 对得上）。
        stale = time.time() - 25 * 3600
        for item in temp_dir.rglob("*"):
            os.utime(item, (stale, stale))
        read = _read(client, temp_id, "mp4")
        assert read.status_code == 200
        assert hashlib.sha256(read.content).hexdigest() == _manifest_video_sha(temp_manifest)

        # Java 清理 worker 只对到期临时段下发 INTERNAL13（已存 asset 由引用保护不下发）：
        result = _delete(client, temp_id).json()
        assert result == {"complete": True, "remainingHandles": []}
        # 逐对象证据（不能只看 DB state）：两份产物文件都已消失。
        assert not temp_dir.exists(), f"临时段目录应整体删除，残留 {list(temp_dir.rglob('*')) if temp_dir.exists() else []}"
        # 已存 asset 文件原样保留、字节一致、仍可读。
        assert (saved_dir / "segment.mp4").read_bytes() == saved_before
        assert _read(client, saved_id, "mp4").status_code == 200
        assert hashlib.sha256(_read(client, saved_id, "mp4").content).hexdigest() \
            == _manifest_video_sha(saved_manifest)

        # 删除后不得复活：INTERNAL12 → 404；重复删除 → 404（驱动侧按幂等收口）。
        assert _read(client, temp_id, "mp4").status_code == 404
        assert _read(client, temp_id, "srt").status_code == 404
        assert _delete(client, temp_id).status_code == 404

        # 副作用核对：两段各恰好一条 INTERNAL11 录制回执（无隐藏多余上报）。
        receipts = bridge.recording_receipts()
        assert len(receipts) == 2
        assert {r["resourceId"] for r in receipts} == {temp_id, saved_id}


def test_tc105f_05_02_active_segment_delete_rejected_until_stopped(tmp_path: Path,
                                                                    monkeypatch: pytest.MonkeyPatch) -> None:
    bridge = FakeBridge()
    app = _make_client(monkeypatch, tmp_path, bridge)
    session_id, recording_id = str(uuid_module.uuid4()), str(uuid_module.uuid4())
    with TestClient(app) as client:
        _create_session(client, session_id)
        _start_recording(client, session_id, recording_id)
        _feed_program(app, session_id, seconds=1.0)

        # 进行中段：先停后删（409），不得绕过停止直接删活动段（文件仍在）。
        conflict = _delete(client, recording_id)
        assert conflict.status_code == 409
        assert conflict.json()["code"] == "dh_state_conflict"
        assert (recording_dir(Path(app.state._test_media_root), recording_id) / "segment.mp4").is_file()

        _stop_recording(client, app, session_id, recording_id)
        assert _delete(client, recording_id).json()["complete"] is True
        assert not recording_dir(Path(app.state._test_media_root), recording_id).exists()


# ---------- TC105F-05-02：删除失败不报假零，重试后收口 ----------


def test_tc105f_05_02_delete_failure_reports_remaining_handles_and_retry_completes(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import shutil as shutil_module

    bridge = FakeBridge()
    app = _make_client(monkeypatch, tmp_path, bridge)
    session_id, recording_id = str(uuid_module.uuid4()), str(uuid_module.uuid4())
    real_rmtree = shutil_module.rmtree
    failures = {"count": 0}

    def flaky_rmtree(path: Any, *args: Any, **kwargs: Any) -> None:
        if failures["count"] > 0:
            return real_rmtree(path, *args, **kwargs)
        failures["count"] += 1
        raise OSError("simulated disk failure")

    monkeypatch.setattr(shutil_module, "rmtree", flaky_rmtree)
    with TestClient(app) as client:
        _create_session(client, session_id)
        _start_recording(client, session_id, recording_id)
        _feed_program(app, session_id, seconds=2.0)
        _stop_recording(client, app, session_id, recording_id)
        segment_dir = recording_dir(Path(app.state._test_media_root), recording_id)
        expected_objects = sorted(item.name for item in segment_dir.rglob("*") if item.is_file())
        assert expected_objects, "应至少有 mp4/srt 两个对象"

        # 首次删除失败：不报假零——逐对象列出残留句柄，文件仍在、仍可读（诚实中间态）。
        first = _delete(client, recording_id)
        assert first.status_code == 200
        assert first.json() == {"complete": False, "remainingHandles": expected_objects}
        assert segment_dir.is_dir() and (segment_dir / "segment.mp4").is_file()
        assert _read(client, recording_id, "mp4").status_code == 200

        # 驱动侧有界重试（Java MAX_OBJECT_ATTEMPTS=8 环）：同键重发 → 收口。
        retry = _delete(client, recording_id)
        assert retry.status_code == 200
        assert retry.json() == {"complete": True, "remainingHandles": []}
        assert not segment_dir.exists()
        assert _read(client, recording_id, "mp4").status_code == 404


# ---------- TC105F-05-02：重启扫描（注册表清空、孤儿目录、无陈旧读取） ----------


def test_tc105f_05_02_restart_clears_registry_orphans_cleanable_no_stale_reads(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    bridge = FakeBridge()
    media_root = tmp_path / "media"
    pre_restart_id, orphan_id = str(uuid_module.uuid4()), str(uuid_module.uuid4())
    app_one = _make_client(monkeypatch, tmp_path, bridge)
    with TestClient(app_one) as client:
        session_id = str(uuid_module.uuid4())
        _create_session(client, session_id)
        _start_recording(client, session_id, pre_restart_id)
        _feed_program(app_one, session_id, seconds=2.0)
        _stop_recording(client, app_one, session_id, pre_restart_id)
    assert (recording_dir(media_root, pre_restart_id) / "segment.mp4").is_file()

    # 崩溃残留：无登记的孤儿录制目录（另一资源 id，手工写入产物文件）。
    orphan_dir = recording_dir(media_root, orphan_id)
    orphan_dir.mkdir(parents=True)
    (orphan_dir / "segment.mp4").write_bytes(b"orphan-bytes-from-crashed-run")

    # 重启 = 全新进程（同 DH_MEDIA_ROOT）：进程内注册表为空。
    bridge_two = FakeBridge()
    app_two = _make_client(monkeypatch, tmp_path, bridge_two)
    assert app_two.state.sessions == {}
    with TestClient(app_two) as client:
        # 无陈旧读取：磁盘文件在、注册表无登记 → INTERNAL12 fail-closed 404（不复活旧数据）。
        stale = _read(client, pre_restart_id, "mp4")
        assert stale.status_code == 404
        assert stale.json()["code"] == "dh_not_found"

        # 重启后扫描清理（驱动侧重发 INTERNAL13）：孤儿目录按资源路径可清理——不依赖注册表。
        orphan_delete = _delete(client, orphan_id)
        assert orphan_delete.status_code == 200
        assert orphan_delete.json()["complete"] is True
        assert not orphan_dir.exists()

        # 重启前已收口的段同样可按路径清理；清理不波及相邻未知目录。
        guard_dir = media_root / "unrelated-not-a-recording"
        guard_dir.mkdir(parents=True)
        (guard_dir / "keep.txt").write_bytes(b"keep")
        pre = _delete(client, pre_restart_id)
        assert pre.json()["complete"] is True
        assert not recording_dir(media_root, pre_restart_id).exists()
        assert (guard_dir / "keep.txt").read_bytes() == b"keep"
