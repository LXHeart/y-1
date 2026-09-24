"""自有形象无模型规范化（任务书 #105F C105F-01 / 共享契约 K09、K07.1 AvatarManifest、K07.2 INTERNAL10～13）。

本模块只做本地解码/尺寸/格式检查与派生 bundle 写入——人脸检测与形象准备由 Java 编排的第三方
服务完成（K14：本地不加载任何检测/驱动模型；上游源码的 local 能力不是实施选项）。沙箱路径由
服务端从 (avatar_id, revision) 派生，拒绝任何外部路径输入；objectRef 为服务器发放的不含斜线
句柄。部分写入失败如实上报已写文件（TC105F-01-04：可追踪清理全部已写对象，不误删原 shared media）。
"""

from __future__ import annotations

import base64
import hashlib
import json
import uuid as uuid_module
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

MAX_SOURCE_BYTES = 10 * 1024 * 1024  # K09：原图 ≤10MiB
MAX_JSON_BYTES = 14 * 1024 * 1024  # K07.2 INTERNAL10：总 JSON ≤14MiB
MAX_DIMENSION = 4096
MAX_PIXELS = MAX_DIMENSION * MAX_DIMENSION
PREVIEW_EDGE = 256

# 固定 relative keys（K09：manifest 固定 relative keys/sha256/size/contentType）。
NORMALIZED_KEY = "normalized.png"
PREVIEW_KEY = "preview.jpg"


class AvatarPrepareError(Exception):
    """规范化失败（code 稳定；written=已成功写入沙箱的文件清单，供部分失败上报与清理）。"""

    def __init__(self, code: str, message: str = "", written: tuple["AvatarFile", ...] = ()) -> None:
        super().__init__(message or code)
        self.code = code
        self.written = tuple(written)


@dataclass(frozen=True)
class AvatarFile:
    object_ref: str
    relative_path: str
    sha256: str
    size_bytes: int
    content_type: str


@dataclass(frozen=True)
class AvatarManifest:
    avatar_id: str
    revision: int
    backend_id: Optional[str]
    files: tuple[AvatarFile, ...]

    def to_wire(self) -> dict[str, Any]:
        return {
            "avatarId": self.avatar_id,
            "revision": self.revision,
            "backendId": self.backend_id,
            "files": [
                {
                    "objectRef": f.object_ref,
                    "relativePath": f.relative_path,
                    "sha256": f.sha256,
                    "sizeBytes": f.size_bytes,
                    "contentType": f.content_type,
                }
                for f in self.files
            ],
        }


def object_ref_for(avatar_id: str, revision: int, relative_path: str) -> str:
    """服务器发放句柄：不含斜线（INTERNAL12 只接受无斜线 handle），确定性派生自 avatar/revision/key。"""
    safe_key = relative_path.replace(".", "_")
    return f"dhav-{avatar_id}-r{revision}-{safe_key}"


def validate_relative_path(path: str) -> None:
    """拒绝绝对路径/../盘符等词法逃逸（不接受用户提供本机路径，K09）。

    写入侧的物理防逃逸由 sandbox_dir 的服务端派生 + 拒绝符号链接复制承担；本函数是
    manifest/输入层的独立词法闸（相对路径且不含 ..）。
    """
    if not path or path.startswith("/") or path.startswith("\\") or "\\" in path:
        raise AvatarPrepareError("dh_invalid_input", f"非法 relativePath: {path!r}")
    target = Path(path)
    if target.is_absolute() or target.drive or target.root:
        raise AvatarPrepareError("dh_invalid_input", f"非法 relativePath: {path!r}")
    for part in target.parts:
        if part == "..":
            raise AvatarPrepareError("dh_invalid_input", f"非法 relativePath: {path!r}")


def sandbox_dir(root: Path, avatar_id: str, revision: int) -> Path:
    """沙箱目录：服务端派生（每 avatar/revision 独立；删除按资源整目录收口）。"""
    try:
        parsed = str(uuid_module.UUID(avatar_id))
    except ValueError as invalid:
        raise AvatarPrepareError("dh_invalid_input", "avatarId 不是合法 UUID") from invalid
    if int(revision) < 1:
        raise AvatarPrepareError("dh_invalid_input", "revision 必须 ≥1")
    return root / f"avatar-{parsed}" / f"r{int(revision)}"


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _decode_still(source: bytes):
    """PIL 真实解码（魔数/完整性/尺寸/炸弹防护；无模型）。"""
    from PIL import Image

    if len(source) < 8:
        raise AvatarPrepareError("dh_image_rejected", "图片内容为空")
    if len(source) > MAX_SOURCE_BYTES:
        raise AvatarPrepareError("dh_image_rejected", "图片超过 10MiB 上限")
    try:
        image = Image.open(__import__("io").BytesIO(source))
        fmt = (image.format or "").upper()
        if fmt not in ("JPEG", "PNG"):
            raise AvatarPrepareError("dh_image_rejected", f"仅支持 JPEG/PNG（实际 {fmt or '未知'}）")
        image.verify()  # 完整性（截断/损坏在此暴露）
        image = Image.open(__import__("io").BytesIO(source)).convert("RGB")
    except AvatarPrepareError:
        raise
    except Exception as failure:
        raise AvatarPrepareError("dh_image_rejected", "图片内容损坏或被截断") from failure
    width, height = image.size
    if width <= 0 or height <= 0 or width > MAX_DIMENSION or height > MAX_DIMENSION or width * height > MAX_PIXELS:
        raise AvatarPrepareError("dh_image_rejected", "图片尺寸需在 4096×4096 以内")
    return image


def prepare_avatar(source: bytes, avatar_id: str, revision: int, root: Path,
                   *, backend_id: Optional[str] = None) -> AvatarManifest:
    """无模型规范化：解码校验 → 写 normalized.png + preview.jpg → 逐 file manifest。

    部分写入失败抛 AvatarPrepareError（written=已写文件）——调用方据实上报 errorCode+部分 manifest，
    已写对象仍可被清理（先登记后删，TC105F-01-04）。
    """
    image = _decode_still(source)
    directory = sandbox_dir(root, avatar_id, revision)
    directory.mkdir(parents=True, exist_ok=True)
    written: list[AvatarFile] = []
    try:
        normalized_path = directory / NORMALIZED_KEY
        _write_image(image, normalized_path, "PNG")
        written.append(_file_entry(avatar_id, revision, NORMALIZED_KEY, "image/png", normalized_path))

        preview = image.copy()
        preview.thumbnail((PREVIEW_EDGE, PREVIEW_EDGE))
        preview_path = directory / PREVIEW_KEY
        _write_image(preview, preview_path, "JPEG")
        written.append(_file_entry(avatar_id, revision, PREVIEW_KEY, "image/jpeg", preview_path))
    except AvatarPrepareError:
        raise
    except Exception as failure:
        raise AvatarPrepareError("dh_media_write_failed", "派生文件写入失败", written=tuple(written)) from failure
    return AvatarManifest(avatar_id=avatar_id, revision=int(revision), backend_id=backend_id,
                          files=tuple(written))


def _write_image(image, target: Path, fmt: str) -> None:
    import io

    buffer = io.BytesIO()
    if fmt == "JPEG":
        image.save(buffer, format="JPEG", quality=88)
    else:
        image.save(buffer, format=fmt)
    target.write_bytes(buffer.getvalue())


def _file_entry(avatar_id: str, revision: int, relative_key: str, content_type: str, path: Path) -> AvatarFile:
    data = path.read_bytes()
    return AvatarFile(object_ref=object_ref_for(avatar_id, revision, relative_key), relative_path=relative_key,
                      sha256=_sha256_bytes(data), size_bytes=len(data), content_type=content_type)


def delete_avatar_tree(root: Path, avatar_id: str) -> tuple[bool, list[str]]:
    """INTERNAL13（kind=avatar）：删除该 avatar 全部派生目录。返回 (complete, remainingHandles)——
    删除失败不报假零（K07.2），残留句柄如实列出供有界重试。"""
    try:
        parsed = str(uuid_module.UUID(avatar_id))
    except ValueError as invalid:
        raise AvatarPrepareError("dh_invalid_input", "avatarId 不是合法 UUID") from invalid
    base = root / f"avatar-{parsed}"
    if not base.exists():
        return True, []
    remaining: list[str] = []
    import shutil

    for revision_dir in sorted(base.iterdir()):
        if not revision_dir.is_dir():
            continue
        for item in sorted(revision_dir.rglob("*")):
            if item.is_file() and not item.is_symlink():
                remaining.append(item.name)
    try:
        shutil.rmtree(base)
    except Exception:
        return False, remaining
    return True, []


def read_artifact(root: Path, object_ref: str, manifests: dict[tuple[str, int], AvatarManifest]) -> Optional[bytes]:
    """INTERNAL12：只按已登记 manifest 的 objectRef 取字节（不接受任意路径/URL）。"""
    if not object_ref or "/" in object_ref or "\\" in object_ref or ".." in object_ref:
        return None
    for (avatar_id, revision), manifest in manifests.items():
        for entry in manifest.files:
            if entry.object_ref == object_ref:
                target = sandbox_dir(root, avatar_id, revision) / entry.relative_path
                try:
                    data = target.read_bytes()
                except OSError:
                    return None
                if _sha256_bytes(data) != entry.sha256:
                    return None
                return data
    return None


@dataclass
class AvatarJob:
    """INTERNAL10 受理记录（幂等缓存：同 avatar+revision 返回原结果，不重写 bundle）。"""

    job_id: str
    avatar_id: str
    revision: int
    state: str = "processing"
    manifest: Optional[AvatarManifest] = None
    error_code: Optional[str] = None
    written: tuple[AvatarFile, ...] = field(default_factory=tuple)

    def wire_state(self) -> dict[str, Any]:
        return {"jobId": self.job_id, "state": self.state}


async def report_artifact_via_bridge(bridge: Any, payload: dict[str, Any]) -> dict[str, Any]:
    """INTERNAL11 上报：复用受控 ExecutionBridge 的固定 authority 与 mTLS 客户端。

    bridge.py 未列入本卡白名单，经其 _post_json 复用同一内部通道（128KiB 总闸沿 bridge 约束；
    avatar manifest 两文件远小于该上限）。bridge 缺席时明确失败——回执不可达不能假装完成。
    """
    post = getattr(bridge, "_post_json", None)
    if post is None:
        raise AvatarPrepareError("dh_runtime_unavailable", "缺少受控内部通道")
    return await post("/internal/digital-human/artifacts", payload)


def artifact_payload(event_id: str, kind: str, resource_id: str, revision: int,
                     manifest: Optional[AvatarManifest], error_code: Optional[str]) -> dict[str, Any]:
    """INTERNAL11 body（K07.2）：manifest 与 errorCode 可同时携带（部分失败=已写文件+错误码）。"""
    payload: dict[str, Any] = {
        "eventId": event_id,
        "kind": kind,
        "resourceId": resource_id,
        "revision": int(revision),
        "manifest": manifest.to_wire() if manifest is not None else None,
        "errorCode": error_code,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > 128 * 1024:
        raise AvatarPrepareError("dh_payload_too_large", "回执超过内部通道上限")
    return payload


def decode_prepare_body(body: dict[str, Any]) -> tuple[bytes, str, int, Optional[str], str]:
    """INTERNAL10 body 解码与上限校验（原图 ≤10MiB、总 JSON ≤14MiB、sha 校验）。"""
    raw = body.get("sourceBytesBase64")
    revision = body.get("avatarRevision")
    command_id = body.get("commandId")
    payload_hash = body.get("payloadHash")
    if not isinstance(raw, str) or not raw:
        raise AvatarPrepareError("dh_invalid_input", "sourceBytesBase64 必填")
    if not isinstance(revision, int) or revision < 1:
        raise AvatarPrepareError("dh_invalid_input", "avatarRevision 必须 ≥1")
    if not command_id or not payload_hash:
        raise AvatarPrepareError("dh_invalid_input", "commandId/payloadHash 必填")
    if len(raw) > MAX_JSON_BYTES:
        raise AvatarPrepareError("dh_payload_too_large", "INTERNAL10 总 JSON 超 14MiB")
    try:
        source = base64.b64decode(raw, validate=True)
    except Exception as invalid:
        raise AvatarPrepareError("dh_invalid_input", "sourceBytesBase64 不是合法 base64") from invalid
    if len(source) > MAX_SOURCE_BYTES:
        raise AvatarPrepareError("dh_image_rejected", "图片超过 10MiB 上限")
    expected = body.get("sourceSha256")
    if expected and _sha256_bytes(source) != expected:
        raise AvatarPrepareError("dh_invalid_input", "sourceSha256 与字节不符")
    backend_id = body.get("backendId")
    return source, str(command_id), int(revision), backend_id if isinstance(backend_id, str) else None, str(payload_hash)
