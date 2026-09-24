"""自有形象无模型规范化测试（任务书 #105F C105F-01 / TC105F-01-02、TC105F-01-04）。

TC105F-01-02（P0）：伪 mime、炸弹尺寸、10MiB+1、损坏/截断一律拒绝且无可运行 bundle；
路径逃逸（../、绝对路径、符号链接）拒绝。0/多脸的远端检测断言在 Java DigitalHumanAvatarIT
（跨语言同 TC 各有子断言——runtime 无模型不做本地人脸检测，K14）。
TC105F-01-04（P1）：写第二个 cache 文件失败 → 如实上报已写文件（部分 manifest+errorCode），
恢复 worker 后可追踪清理全部已写对象，不误删原 shared media。
"""

from __future__ import annotations

import base64
import hashlib
import io as io_module
import json
import sys
import uuid as uuid_module
from pathlib import Path
from typing import Any, Dict, List

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from fastapi import FastAPI
from fastapi.testclient import TestClient

from grassland_dh.avatar import (  # noqa: E402
    MAX_SOURCE_BYTES,
    AvatarManifest,
    AvatarPrepareError,
    delete_avatar_tree,
    object_ref_for,
    prepare_avatar,
    sandbox_dir,
    validate_relative_path,
)
from grassland_dh.routes_internal import AvatarSurface, create_avatar_router  # noqa: E402


def _png_bytes(width: int, height: int) -> bytes:
    from PIL import Image

    buffer = io_module.BytesIO()
    Image.new("RGB", (width, height), color=(120, 90, 200)).save(buffer, format="PNG")
    return buffer.getvalue()


def _jpeg_bytes(width: int, height: int) -> bytes:
    from PIL import Image

    buffer = io_module.BytesIO()
    Image.new("RGB", (width, height), color=(30, 160, 90)).save(buffer, format="JPEG", quality=90)
    return buffer.getvalue()


GOOD_AVATAR = "2f0b1c1a-1111-4111-8111-2f0b1c1a0001"


class RecordingBridge:
    """受控内部通道 fake：只记录 INTERNAL11 上报（最外层网络依赖替身，K12）。"""

    def __init__(self) -> None:
        self.reports: List[Dict[str, Any]] = []

    async def _post_json(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        self.reports.append({"path": path, "body": body})
        return {"accepted": True, "reason": "ready"}


# ---------- TC105F-01-02：坏图/超限/路径（参数化） ----------


def _gif_two_frame_bytes() -> bytes:
    from PIL import Image

    buffer = io_module.BytesIO()
    first = Image.new("RGB", (32, 32), color=(9, 9, 9))
    second = Image.new("RGB", (32, 32), color=(200, 200, 0))
    first.save(buffer, format="GIF", save_all=True, append_images=[second], duration=[100, 100], loop=0)
    return buffer.getvalue()


@pytest.mark.parametrize("label,payload,code", [
    ("fake_mime_no_magic", b"definitely not an image payload 0123456789", "dh_image_rejected"),
    ("fake_mime_png_header_only", b"\x89PNG\r\n\x1a\n" + b"\x00" * 32, "dh_image_rejected"),
    ("truncated_png", _png_bytes(512, 512)[:200], "dh_image_rejected"),
    ("oversize_10mib_plus_1", b"\x89PNG\r\n\x1a\n" + b"\x00" * MAX_SOURCE_BYTES, "dh_image_rejected"),
    ("bomb_dimension_5000px", _png_bytes(5000, 4), "dh_image_rejected"),
    ("bomb_pixels", _png_bytes(4096, 4097), "dh_image_rejected"),
    ("gif_frame_not_allowed", _gif_two_frame_bytes(), "dh_image_rejected"),
])
def test_tc105f_01_02_rejects_invalid_images_without_bundle(tmp_path: Path, label: str, payload: bytes,
                                                            code: str) -> None:
    with pytest.raises(AvatarPrepareError) as caught:
        prepare_avatar(payload, GOOD_AVATAR, 1, tmp_path)
    assert caught.value.code == code, f"{label} 应拒绝为 {code}"
    # 无可运行 bundle：沙箱内不存在该 avatar 的任何派生目录/文件。
    assert not (tmp_path / f"avatar-{GOOD_AVATAR}").exists(), f"{label} 不得留下可运行 bundle"


@pytest.mark.parametrize("bad_path", ["../escape.png", "/etc/passwd", "a/../../b.png", "", "a/./../b.png",
                                      "C:\\windows\\system32\\x.png"])
def test_tc105f_01_02_rejects_path_escape(bad_path: str) -> None:
    with pytest.raises(AvatarPrepareError) as caught:
        validate_relative_path(bad_path)
    assert caught.value.code == "dh_invalid_input"


def test_tc105f_01_02_prepare_never_writes_outside_sandbox(tmp_path: Path) -> None:
    original = tmp_path / "shared-original.png"
    original.write_bytes(b"original shared media bytes")
    before = {p for p in tmp_path.rglob("*")}
    prepare_avatar(_png_bytes(64, 64), GOOD_AVATAR, 1, tmp_path)
    added = {p for p in tmp_path.rglob("*")} - before
    # 新增文件只允许出现在服务端派生的沙箱目录内；沙箱外零写入。
    sandbox = tmp_path / f"avatar-{GOOD_AVATAR}"
    assert added and all(p == sandbox or sandbox in p.parents for p in added), f"沙箱外出现写入: {added}"
    assert original.read_bytes() == b"original shared media bytes"


# ---------- 合法图片与缓存（TC105F-01-01 的 runtime 子断言） ----------


def test_prepare_avatar_writes_manifest_with_fixed_keys_and_sha(tmp_path: Path) -> None:
    manifest = prepare_avatar(_png_bytes(320, 240), GOOD_AVATAR, 1, tmp_path, backend_id="backend-1")
    assert [f.relative_path for f in manifest.files] == ["normalized.png", "preview.jpg"]
    for entry in manifest.files:
        data = (sandbox_dir(tmp_path, GOOD_AVATAR, 1) / entry.relative_path).read_bytes()
        assert hashlib.sha256(data).hexdigest() == entry.sha256
        assert entry.size_bytes == len(data)
        assert "/" not in entry.object_ref
    assert manifest.to_wire()["avatarId"] == GOOD_AVATAR
    assert manifest.to_wire()["backendId"] == "backend-1"


def test_reprepare_same_revision_is_cache_hit(tmp_path: Path) -> None:
    first = prepare_avatar(_jpeg_bytes(200, 200), GOOD_AVATAR, 1, tmp_path)
    first_signature = {f.sha256 for f in first.files}
    second = prepare_avatar(_jpeg_bytes(200, 200), GOOD_AVATAR, 1, tmp_path)
    assert {f.sha256 for f in second.files} == first_signature


# ---------- TC105F-01-04：部分派生失败 → 可追踪清理，不误删原 shared media ----------


def test_tc105f_01_04_partial_write_failure_reports_written_files_only(tmp_path: Path) -> None:
    original_media = tmp_path / "shared-original.png"
    original_media.write_bytes(b"original shared media bytes")
    # 第二个 cache 目标被占位为目录 → 写 preview.jpg 必然失败（确定性失败注入，无 mock）。
    revision_dir = sandbox_dir(tmp_path, GOOD_AVATAR, 1)
    revision_dir.mkdir(parents=True)
    (revision_dir / "preview.jpg").mkdir()

    with pytest.raises(AvatarPrepareError) as caught:
        prepare_avatar(_png_bytes(128, 128), GOOD_AVATAR, 1, tmp_path)
    assert caught.value.code == "dh_media_write_failed"
    written = caught.value.written
    assert [f.relative_path for f in written] == ["normalized.png"], "已写文件必须如实上报"
    assert (revision_dir / "normalized.png").is_file()

    # 恢复 worker：清理登记内的对象全部可删；原 shared media 不被误删。
    complete, remaining = delete_avatar_tree(tmp_path, GOOD_AVATAR)
    assert complete is True and remaining == []
    assert not (tmp_path / f"avatar-{GOOD_AVATAR}").exists()
    assert original_media.read_bytes() == b"original shared media bytes", "原 shared media 不得被清理波及"


def test_delete_avatar_tree_failure_reports_remaining_not_fake_zero(tmp_path: Path,
                                                                    monkeypatch: pytest.MonkeyPatch) -> None:
    prepare_avatar(_png_bytes(64, 64), GOOD_AVATAR, 2, tmp_path)
    import shutil as shutil_module

    def failing_rmtree(path: Any, **kwargs: Any) -> None:
        raise OSError("disk simulated failure")

    monkeypatch.setattr(shutil_module, "rmtree", failing_rmtree)
    complete, remaining = delete_avatar_tree(tmp_path, GOOD_AVATAR)
    assert complete is False, "删除失败不得报假零（K07.2 INTERNAL13）"
    assert remaining, "残留句柄必须如实列出供有界重试"


# ---------- INTERNAL10/12/13 路由合同（真 handler + fake bridge；DB/owner 在 Java IT） ----------


def _mount_avatar_app(surface: AvatarSurface) -> FastAPI:
    app = FastAPI()
    router = create_avatar_router(surface, enabled_check=lambda: True)
    app.add_api_route("/internal/v1/avatars/{avatar_id}/prepare", router["avatar_prepare"], methods=["POST"])
    app.add_api_route("/internal/v1/artifacts/{resource_id}/{object_ref}", router["artifact_read"],
                      methods=["GET"])
    app.add_api_route("/internal/v1/resources/{resource_id}/delete", router["resource_delete"], methods=["POST"])
    return app


def _prepare_body(source: bytes, revision: int = 1) -> Dict[str, Any]:
    return {
        "commandId": str(uuid_module.uuid4()),
        "payloadHash": hashlib.sha256(source).hexdigest(),
        "avatarRevision": revision,
        "sourceBytesBase64": base64.b64encode(source).decode("ascii"),
        "sourceSha256": hashlib.sha256(source).hexdigest(),
        "backendId": "backend-1",
    }


def _await_job(surface: AvatarSurface, avatar_id: str, revision: int, bridge: RecordingBridge,
               expected_reports: int, timeout: float = 5.0) -> None:
    """有界等待后台规范化任务完成（等待内存标志位/回执计数，不做无界 sleep 换绿灯）。"""
    import time

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if len([r for r in bridge.reports if r["path"] == "/internal/digital-human/artifacts"]) >= expected_reports \
                and surface.jobs.get((avatar_id, revision)) is not None \
                and surface.jobs[(avatar_id, revision)].state != "processing":
            return
        time.sleep(0.02)
    job = surface.jobs.get((avatar_id, revision))
    raise AssertionError(f"后台任务未收口: state={getattr(job, 'state', None)}, reports={len(bridge.reports)}")


def test_internal10_reports_manifest_via_bridge_and_reads_back(tmp_path: Path) -> None:
    bridge = RecordingBridge()
    surface = AvatarSurface(bridge=bridge, media_root=tmp_path)
    source = _png_bytes(96, 96)

    with TestClient(_mount_avatar_app(surface)) as client:
        first = client.post(f"/internal/v1/avatars/{GOOD_AVATAR}/prepare", json=_prepare_body(source))
        assert first.status_code == 202
        assert first.json()["state"] == "processing"
        _await_job(surface, GOOD_AVATAR, 1, bridge, 1)

        # 同 avatar+revision 重放 = 幂等缓存（返回原受理，不二次处理/上报）。
        replay = client.post(f"/internal/v1/avatars/{GOOD_AVATAR}/prepare", json=_prepare_body(source))
        assert replay.status_code == 202
        assert replay.json()["jobId"] == first.json()["jobId"]

        reports = [r for r in bridge.reports if r["path"] == "/internal/digital-human/artifacts"]
        assert len(reports) == 1, "INTERNAL11 恰一次（幂等缓存不重复上报）"
        body = reports[0]["body"]
        assert body["kind"] == "avatar" and body["resourceId"] == GOOD_AVATAR
        assert body["errorCode"] is None
        object_refs = [f["objectRef"] for f in body["manifest"]["files"]]
        assert object_refs == [object_ref_for(GOOD_AVATAR, 1, "normalized.png"),
                               object_ref_for(GOOD_AVATAR, 1, "preview.jpg")]

        # INTERNAL12：受控读取（sha 校验 + 只认已登记句柄）。
        read = client.get(f"/internal/v1/artifacts/{GOOD_AVATAR}/{object_refs[0]}")
        assert read.status_code == 200
        assert hashlib.sha256(read.content).hexdigest() == body["manifest"]["files"][0]["sha256"]
        assert client.get(f"/internal/v1/artifacts/{GOOD_AVATAR}/dhav-not-registered").status_code == 404

        # INTERNAL13：整资源删除收口后 manifest 失效。
        delete = client.post(f"/internal/v1/resources/{GOOD_AVATAR}/delete",
                             json={"commandId": str(uuid_module.uuid4()), "payloadHash": "h", "kind": "avatar",
                                   "revision": 1})
        assert delete.status_code == 200
        assert delete.json() == {"complete": True, "remainingHandles": []}
        assert client.get(f"/internal/v1/artifacts/{GOOD_AVATAR}/{object_refs[0]}").status_code == 404


def test_internal10_bad_image_reports_error_without_bundle(tmp_path: Path) -> None:
    bridge = RecordingBridge()
    surface = AvatarSurface(bridge=bridge, media_root=tmp_path)
    body = _prepare_body(b"not-an-image-at-all" * 4)

    with TestClient(_mount_avatar_app(surface)) as client:
        response = client.post(f"/internal/v1/avatars/{GOOD_AVATAR}/prepare", json=body)
        assert response.status_code == 202, "受理即 202，失败经 INTERNAL11 回执（异步状态合同）"
        _await_job(surface, GOOD_AVATAR, 1, bridge, 1)
        reports = [r for r in bridge.reports if r["path"] == "/internal/digital-human/artifacts"]
        assert len(reports) == 1
        assert reports[0]["body"]["errorCode"] == "dh_image_rejected"
        assert reports[0]["body"]["manifest"] is None
        assert not (tmp_path / f"avatar-{GOOD_AVATAR}").exists()


def test_internal10_sha_mismatch_rejected_before_job(tmp_path: Path) -> None:
    bridge = RecordingBridge()
    surface = AvatarSurface(bridge=bridge, media_root=tmp_path)
    client = TestClient(_mount_avatar_app(surface))
    body = _prepare_body(_png_bytes(48, 48))
    body["sourceSha256"] = "0" * 64
    response = client.post(f"/internal/v1/avatars/{GOOD_AVATAR}/prepare", json=body)
    assert response.status_code == 422
    assert response.json()["code"] == "dh_invalid_input"
    assert bridge.reports == [], "受理前拒绝不得产生回执"


def test_internal13_recording_kind_now_real_contract(tmp_path: Path) -> None:
    # C105F-02 起 recording 删除已实现：未知段如实 404（不返回成功占位）；session 类仍明确 409。
    surface = AvatarSurface(bridge=RecordingBridge(), media_root=tmp_path)
    client = TestClient(_mount_avatar_app(surface))
    recording = client.post(f"/internal/v1/resources/{str(uuid_module.uuid4())}/delete",
                            json={"commandId": str(uuid_module.uuid4()), "payloadHash": "h", "kind": "recording",
                                  "revision": 1})
    assert recording.status_code == 404, "未知录制段如实 404"
    session = client.post(f"/internal/v1/resources/{str(uuid_module.uuid4())}/delete",
                          json={"commandId": str(uuid_module.uuid4()), "payloadHash": "h", "kind": "session",
                                "revision": 1})
    assert session.status_code == 409, "session 资源删除随 C105F-05/G 落地"


def test_app_surface_avatar_prepare_fail_closed_without_bridge(monkeypatch: pytest.MonkeyPatch) -> None:
    from grassland_dh.app import create_app

    app = create_app(test_mode=True, surface="internal")
    client = TestClient(app)
    response = client.post(f"/internal/v1/avatars/{GOOD_AVATAR}/prepare", json=_prepare_body(_png_bytes(32, 32)))
    assert response.status_code == 503
    assert response.json()["code"] == "dh_runtime_unavailable", "无受控内部通道不得假装受理"


def test_manifest_wire_json_roundtrip() -> None:
    manifest = AvatarManifest(avatar_id=GOOD_AVATAR, revision=1, backend_id=None, files=())
    wire = manifest.to_wire()
    encoded = json.dumps(wire, ensure_ascii=False)
    assert "avatarId" in encoded and "files" in encoded
