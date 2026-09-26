"""内部控制面路由（任务书 #105D C105D-05 / 共享契约 K07.2 INTERNAL01～04；#105F 扩 INTERNAL10/12/13）。

幂等控制处理：相同 session/commandId 返回已有 receipt（不开第二 runner）；命令严格按 K07.3
判别；startTurn 走会话 Binding 与代次校验；playback_reset/reset_media 先递增代次再关旧 peer；
webrtc-offer 经 ProgramAdapter（mediaEpoch 标记 + recvonly 校验）。

F01 形象面：INTERNAL10 prepare（无模型规范化 + 后台任务经 INTERNAL11 回执 Java）、INTERNAL12
受控产物读取（只按已登记 manifest 的无斜线句柄）、INTERNAL13 资源删除（整目录收口、不报假零）。

F02 录制面：INTERNAL02 startRecording/stopRecording（段分支挂 adapter、连续程序钟、产物经
INTERNAL11 回执；回执不可达保留归档按原键重报）；INTERNAL12 增录制段 mp4/srt 读取；INTERNAL13
增 recording 整目录删除（活动段 409 先停后删）。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import uuid as uuid_module
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from fastapi import Request
from fastapi.responses import JSONResponse, Response

from grassland_dh.avatar import (
    AvatarJob,
    AvatarManifest,
    AvatarPrepareError,
    artifact_payload,
    decode_prepare_body,
    delete_avatar_tree,
    prepare_avatar,
    report_artifact_via_bridge,
    sandbox_dir,
)
from grassland_dh.media import MediaStateError, ProgramAdapter, RtcSession
# C105X-04：双档开关唯一定义在 routes_audio（分派语义所在模块）；本处仅消费。
from grassland_dh.routes_audio import real_providers_enabled
from grassland_dh.recording import (
    MAX_SEGMENT_BYTES,
    MAX_SEGMENT_DURATION_MS,
    RecordingBranch,
    delete_recording_tree,
    recording_dir,
)

UNSUPPORTED_COMMANDS = ("eraseContent",)


@dataclass
class CommandReceipt:
    command_id: str
    payload_hash: str
    result: dict[str, Any]
    lease_epoch: int


@dataclass
class InternalSessionState:
    session_id: str
    binding: dict[str, Any]
    adapter: ProgramAdapter
    worker_id: str = "wrapper-1"
    active_turn_id: Optional[str] = None
    state: str = "ready"
    receipts: dict[str, CommandReceipt] = field(default_factory=dict)
    # C105F-02：活动录制分支（挂在 adapter，peer 重建不中断）与已收口段归档（INTERNAL12/13）。
    recording: Optional[RecordingBranch] = None
    recording_archive: dict[str, Any] = field(default_factory=dict)
    # C105X-04：Fake 档（DH_REAL_PROVIDERS_ENABLED=false）create 时挂进程内 RuntimeSession；
    # 真实档保持 None（音频执行走 bridge INTERNAL07/08）。默认 None 不影响既有面。
    runtime: Optional[Any] = None


def _recording_media_root() -> Path:
    """录制沙箱根（与 AvatarSurface 同一 DH_MEDIA_ROOT 约定；env 注入，路径不承载密文）。"""
    return Path(os.environ.get("DH_MEDIA_ROOT", "data/dh-media"))


async def _fake_runtime_session(wire: dict[str, Any]) -> Optional[Any]:
    """Fake 档 create 时构造进程内 RuntimeSession（RunnerAdapter 全 Fake 链，零出站）。

    构造失败（vendor overlay 缺失等）降级为 None 并 WARN：create 本身不因此失败，音频后续 1011。
    """
    import sys
    from datetime import datetime, timedelta, timezone

    from grassland_dh.adapters import RunnerAdapter
    from grassland_dh.bindings import SessionBinding

    try:
        expires_at = datetime.fromisoformat(str(wire.get("expiresAt")).replace("Z", "+00:00")) \
            if wire.get("expiresAt") else datetime.now(timezone.utc) + timedelta(minutes=30)
        binding = SessionBinding(
            session_id=str(wire["sessionId"]),
            lease_epoch=int(wire["leaseEpoch"]),
            media_epoch=int(wire["mediaEpoch"]),
            backend_id=str(wire.get("backendId", "wrapper-1")),
            profile_revision=int(wire.get("profileRevision", 0)),
            expires_at=expires_at,
            bridge_base_url=str(wire.get("bridgeBaseUrl", "")),
        )
        adapter = RunnerAdapter(test_mode=True, avatars_root=_recording_media_root() / "avatars")
        return await adapter.create(binding, persona_text="")
    except Exception as failure:  # pragma: no cover — vendor 缺失等环境降级
        print(f"dh runtime: fake RuntimeSession construction degraded: {failure!r}", file=sys.stderr)
        return None


def _payload_hash(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"success": False, "error": message, "code": code}, status_code=status)


def create_internal_router(get_session: Any, *, enabled_check: Any = None) -> Any:
    """构建 internal 控制面路由处理器集合；由 app.py 挂载到 internal surface。

    ``get_session``：session_id -> InternalSessionState | None（app.state 持有）。
    """

    async def create_session(request: Request) -> JSONResponse:
        if enabled_check is not None and not enabled_check():
            return _error(503, "dh_runtime_unavailable", "数字人运行时未启用。")
        body = await request.json()
        wire = body.get("binding", body)
        command_id = str(body.get("commandId", ""))
        payload_hash = _payload_hash(wire)
        session_id = str(wire["sessionId"])
        existing = get_session(session_id)
        if existing is not None:
            # INTERNAL01：相同 session 返回已有（命令幂等），不开第二 runner。
            state = _runtime_state(existing)
            return JSONResponse(state, status_code=202)
        adapter = ProgramAdapter(session_id=session_id, lease_epoch=int(wire["leaseEpoch"]),
                                 media_epoch=int(wire["mediaEpoch"]))
        session = InternalSessionState(session_id=session_id, binding=wire, adapter=adapter)
        if not real_providers_enabled():
            # C105X-04：Fake 档在 create 时挂进程内 RuntimeSession（音频 turn 走 Fake 管线，零出站）。
            session.runtime = await _fake_runtime_session(wire)
        session.receipts[command_id or "bootstrap"] = CommandReceipt(
            command_id=command_id or "bootstrap", payload_hash=payload_hash, result={}, lease_epoch=0)
        request.app.state.sessions[session_id] = session
        return JSONResponse(_runtime_state(session), status_code=202)

    async def session_commands(request: Request) -> JSONResponse:
        session = get_session(request.path_params["session_id"])
        if session is None:
            return _error(404, "dh_not_found", "会话不存在。")
        body = await request.json()
        command_id = str(body.get("commandId", ""))
        payload_hash = str(body.get("payloadHash", ""))
        lease_epoch = int(body.get("leaseEpoch", session.binding["leaseEpoch"]))
        command = str(body.get("command", ""))
        if not command_id or not payload_hash:
            return _error(422, "dh_invalid_input", "commandId/payloadHash 必填。")
        # 幂等 receipt：同 commandId+hash 重放原结果；同 commandId 异 hash 拒绝。
        receipt = session.receipts.get(command_id)
        if receipt is not None:
            if receipt.payload_hash != payload_hash:
                return _error(409, "dh_request_conflict", "同命令号已受理其它内容。")
            return JSONResponse(_runtime_state(session), status_code=202)
        if lease_epoch != session.binding["leaseEpoch"]:
            return _error(409, "dh_lease_stale", "会话控制权已变化。")
        if command in UNSUPPORTED_COMMANDS:
            return _error(409, "dh_state_conflict", f"命令 {command} 在本阶段明确不支持（F 前无占位成功）。")
        if command == "startTurn":
            payload = body.get("payload", {})
            turn = (payload.get("binding") or {})
            session.active_turn_id = str(turn.get("turnId"))
            session.state = "responding"
        elif command == "interrupt":
            payload = body.get("payload", {})
            next_media_epoch = int(payload.get("nextMediaEpoch", 0))
            await session.adapter.reset_media(max(next_media_epoch, session.adapter.media_epoch + 1))
            session.active_turn_id = None
            session.state = "ready"
        elif command == "pause":
            payload = body.get("payload", {})
            next_media_epoch = int(payload.get("nextMediaEpoch", 0))
            await session.adapter.reset_media(max(next_media_epoch, session.adapter.media_epoch + 1))
            session.state = "paused"
        elif command == "resume":
            session.state = "ready"
        elif command == "end":
            await session.adapter.reset_media(session.adapter.media_epoch + 1)
            session.state = "ended"
        elif command in ("startRecording", "stopRecording"):
            bridge = getattr(request.app.state, "bridge", None)
            if command == "startRecording":
                stop_error = _validate_start_recording(session, body.get("payload", {}), bridge)
            else:
                stop_error = _validate_stop_recording(session, body.get("payload", {}))
            if stop_error is not None:
                return stop_error
            if command == "startRecording":
                payload = body.get("payload", {})
                branch = RecordingBranch(str(payload["recordingId"]), _recording_media_root(),
                                         max_duration_ms=int(payload["maxDurationMs"]),
                                         max_bytes=int(payload["maxBytes"]))
                await branch.start(session.adapter.clock.now_ms())
                session.adapter.recorder = branch
                session.recording = branch
            else:
                payload = body.get("payload", {})
                recording_id = str(payload["recordingId"])
                branch = session.recording
                if branch is None:
                    # 同键重试（Java 未收到回执重发 stop）：段已收口，从归档重报 INTERNAL11。
                    manifest = session.recording_archive[recording_id]["manifest"]
                    try:
                        await report_artifact_via_bridge(bridge, artifact_payload(
                            str(uuid_module.uuid4()), "recording", recording_id, 1, manifest, None))
                    except Exception:
                        return _error(503, "dh_runtime_unavailable", "录制产物回执不可达，请按原键重试。")
                else:
                    reason = str(payload.get("reasonCode") or "user")
                    manifest = await branch.stop(reason)
                    session.recording = None
                    session.adapter.recorder = None
                    session.recording_archive[branch.recording_id] = {"manifest": manifest, "branch": branch}
                    try:
                        await report_artifact_via_bridge(bridge, artifact_payload(
                            str(uuid_module.uuid4()), "recording", branch.recording_id, 1, manifest, None))
                    except Exception:
                        # 段已收口并归档；回执不可达不二次 stop，重试同键从归档重报。
                        return _error(503, "dh_runtime_unavailable", "录制产物回执不可达，请按原键重试。")
        else:
            return _error(422, "dh_invalid_input", f"未知命令 {command}。")
        session.receipts[command_id] = CommandReceipt(command_id=command_id, payload_hash=payload_hash,
                                                      result={}, lease_epoch=lease_epoch)
        return JSONResponse(_runtime_state(session), status_code=202)

    async def webrtc_offer(request: Request) -> JSONResponse:
        # C105X-03（任务书 #105fix-1）：INTERNAL03 首次接通——commandId/payloadHash 必填校验照
        # session_commands 的 INTERNAL02 receipt 模式（L124-125 同款）；hash 按去 payloadHash 字段
        # 后的 body 重算比对（_payload_hash 同算法），不符 → 422 dh_invalid_input（K07.2）。
        session = get_session(request.path_params["session_id"])
        if session is None:
            return _error(404, "dh_not_found", "会话不存在。")
        body = await request.json()
        command_id = str(body.get("commandId", ""))
        payload_hash = str(body.get("payloadHash", ""))
        if not command_id or not payload_hash:
            return _error(422, "dh_invalid_input", "commandId/payloadHash 必填。")
        hash_wire = {key: value for key, value in body.items() if key != "payloadHash"}
        if _payload_hash(hash_wire) != payload_hash:
            return _error(422, "dh_invalid_input", "payloadHash 与请求内容不符。")
        lease_epoch = int(body.get("leaseEpoch", session.binding["leaseEpoch"]))
        media_epoch = int(body.get("mediaEpoch", session.adapter.media_epoch))
        sdp = str(body.get("sdp", ""))
        if not sdp or body.get("type") != "offer":
            return _error(422, "dh_invalid_input", "需要完整 offer SDP。")
        # 浏览器（chromium/firefox/webkit）恒在 offer 中宣告 trickle 能力（a=ice-options:trickle），
        # 与「依赖后续 trickle 候选」无关；runtime 为完整 ICE 语义（RtcSession 内建全部候选协商），
        # 该能力宣告行在本边界剥离。chromium 默认还把 host 候选全部 mDNS 化（<name>.local）——
        # 容器内无 mDNS 解析面（组播 socket ENODEV），这些行同样剥离：对端可达性由 ICE
        # peer-reflexive 学习承担（浏览器侧主动连 runtime 的 answer 候选）。firefox 另在会话层
        # 放 a=sendrecv（m 行级才是权威方向）——方向子串判别只认 m 行，会话级行剥离。
        # 真正的 trickle 依赖 = SDP 内无任何 a=candidate → 既有码 422 拒绝（剥离前判定）。
        if "a=candidate:" not in sdp:
            return _error(422, "dh_offer_trickle", "需要完整 ICE")
        offer_lines = sdp.splitlines()
        first_media = next((i for i, line in enumerate(offer_lines) if line.startswith("m=")), len(offer_lines))
        session_scope_directions = {"a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive"}
        sdp = "\r\n".join(
            line for i, line in enumerate(offer_lines)
            if line.strip() and not line.startswith("a=ice-options:trickle")
            and not (line.startswith("a=candidate:") and ".local" in line)
            and not (i < first_media and line.strip() in session_scope_directions)) + "\r\n"
        # 幂等 receipt（INTERNAL02 同款）：RtcSession 一次 offer→answer，二次 offer 的 answer 重放由
        # 本层缓存承担（同 commandId+hash 返回原 answer；同 commandId 异 hash → 409 dh_request_conflict）。
        receipt = session.receipts.get(command_id)
        if receipt is not None:
            if receipt.payload_hash != payload_hash:
                return _error(409, "dh_request_conflict", "同命令号已受理其它内容。")
            return JSONResponse(receipt.result, status_code=200)
        if session.adapter.peer is None or session.adapter.peer.closed:
            await session.adapter.attach_peer(RtcSession(session.adapter.media_epoch, session.adapter.clock))
        try:
            answer = await session.adapter.offer(sdp, lease_epoch, media_epoch)
        except MediaStateError as media_error:
            status = {"dh_lease_stale": 409, "dh_media_epoch_stale": 409, "dh_offer_not_recvonly": 422,
                      "dh_offer_trickle": 422, "media_peer_closed": 409}.get(media_error.code, 422)
            return _error(status, media_error.code, str(media_error) or media_error.code)
        result = {"sdp": answer.sdp, "type": "answer", "mediaEpoch": answer.media_epoch}
        session.receipts[command_id] = CommandReceipt(command_id=command_id, payload_hash=payload_hash,
                                                      result=result, lease_epoch=lease_epoch)
        return JSONResponse(result)

    async def session_state(request: Request) -> JSONResponse:
        session = get_session(request.path_params["session_id"])
        if session is None:
            return _error(404, "dh_not_found", "会话不存在。")
        return JSONResponse(_runtime_state(session))

    return {"create_session": create_session, "session_commands": session_commands,
            "webrtc_offer": webrtc_offer, "session_state": session_state}


def _runtime_state(session: InternalSessionState) -> dict[str, Any]:
    return {
        "sessionId": session.session_id,
        "workerId": session.worker_id,
        "leaseEpoch": session.binding["leaseEpoch"],
        "mediaEpoch": session.adapter.media_epoch,
        "state": session.state,
        "activeTurnId": session.active_turn_id,
        "leaseExpiresAt": None,
        "mediaReady": session.adapter.peer is not None and not session.adapter.peer.closed,
        "cleanupPending": False,
    }


# ---------- #105F C105F-02：录制命令校验（严格判别，先验证再副作用） ----------


def _validate_start_recording(session: InternalSessionState, payload: Any, bridge: Any) -> Optional[JSONResponse]:
    if bridge is None:
        return _error(503, "dh_runtime_unavailable", "缺少受控内部通道，录制产物无法回执。")
    if session.state not in ("ready", "responding"):
        return _error(409, "dh_state_conflict", f"会话状态 {session.state} 不可开始录制。")
    recording_id = payload.get("recordingId") if isinstance(payload, dict) else None
    try:
        recording_id = str(uuid_module.UUID(str(recording_id)))
    except (ValueError, TypeError):
        return _error(422, "dh_invalid_input", "recordingId 不是合法 UUID。")
    max_duration_ms = payload.get("maxDurationMs")
    max_bytes = payload.get("maxBytes")
    if not isinstance(max_duration_ms, int) or isinstance(max_duration_ms, bool) or max_duration_ms <= 0:
        return _error(422, "dh_invalid_input", "maxDurationMs 必须为正整数。")
    if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or max_bytes <= 0:
        return _error(422, "dh_invalid_input", "maxBytes 必须为正整数。")
    if max_duration_ms > MAX_SEGMENT_DURATION_MS or max_bytes > MAX_SEGMENT_BYTES:
        return _error(422, "dh_invalid_input", "录制上限超出 300 秒 / 200MiB 硬顶。")
    if session.recording is not None:
        return _error(409, "dh_state_conflict", "已有进行中的录制段。")
    if recording_id in session.recording_archive:
        return _error(409, "dh_request_conflict", "该录制段已收口，同 id 不再新建段。")
    if len(session.recording_archive) >= 2:
        return _error(409, "dh_state_conflict", "同会话录制段已达上限（2 段）。")
    return None


def _validate_stop_recording(session: InternalSessionState, payload: Any) -> Optional[JSONResponse]:
    recording_id = payload.get("recordingId") if isinstance(payload, dict) else None
    try:
        recording_id = str(uuid_module.UUID(str(recording_id)))
    except (ValueError, TypeError):
        return _error(422, "dh_invalid_input", "recordingId 不是合法 UUID。")
    reason = payload.get("reasonCode") if isinstance(payload, dict) else None
    if not isinstance(reason, str) or not reason:
        return _error(422, "dh_invalid_input", "reasonCode 必填。")
    if session.recording is None and recording_id not in session.recording_archive:
        return _error(404, "dh_not_found", "录制段不存在。")
    if session.recording is not None and session.recording.recording_id != recording_id:
        return _error(409, "dh_state_conflict", "只能停止当前进行中的录制段。")
    return None


# ---------- #105F C105F-01：INTERNAL10/12/13 形象面 ----------


@dataclass
class AvatarSurface:
    """形象任务/清单/沙箱根（app.state 持有；bridge 缺席时 prepare 明确失败）。"""

    jobs: dict[tuple[str, int], AvatarJob] = field(default_factory=dict)
    manifests: dict[tuple[str, int], AvatarManifest] = field(default_factory=dict)
    bridge: Any = None
    media_root: Path = field(default_factory=lambda: Path(
        os.environ.get("DH_MEDIA_ROOT", "data/dh-media")))


def avatar_error(error: AvatarPrepareError) -> JSONResponse:
    status = {"dh_image_rejected": 422, "dh_invalid_input": 422, "dh_payload_too_large": 413}.get(error.code, 422)
    return _error(status, error.code, str(error) or error.code)


def create_avatar_router(surface: AvatarSurface, *, enabled_check: Any = None) -> Any:
    """INTERNAL10/12/13 处理器集合（由 app.py 挂到 internal surface）。"""

    async def avatar_prepare(request: Request) -> JSONResponse:
        if enabled_check is not None and not enabled_check():
            return _error(503, "dh_runtime_unavailable", "数字人运行时未启用。")
        avatar_id = request.path_params["avatar_id"]
        try:
            str(uuid_module.UUID(avatar_id))
        except ValueError:
            return _error(422, "dh_invalid_input", "avatarId 不是合法 UUID。")
        try:
            body = await request.json()
        except Exception:
            return _error(422, "dh_invalid_input", "请求体格式不正确。")
        try:
            source, _command_id, revision, backend_id, _payload_hash = decode_prepare_body(body)
        except AvatarPrepareError as invalid:
            return avatar_error(invalid)
        existing = surface.jobs.get((avatar_id, revision))
        if existing is not None:
            # 幂等缓存：同 avatar+revision 返回原受理（不重写 bundle，K09 缓存可重建但不重复处理）。
            return JSONResponse(existing.wire_state(), status_code=202)
        if surface.bridge is None:
            return _error(503, "dh_runtime_unavailable", "缺少受控内部通道，无法回执处理结果。")
        job = AvatarJob(job_id=str(uuid_module.uuid4()), avatar_id=avatar_id, revision=revision)
        surface.jobs[(avatar_id, revision)] = job
        surface.media_root.mkdir(parents=True, exist_ok=True)

        async def run_prepare() -> None:
            try:
                manifest = await asyncio.to_thread(prepare_avatar, source, avatar_id, revision,
                                                    surface.media_root, backend_id=backend_id)
                await report_artifact_via_bridge(surface.bridge,
                                                 artifact_payload(str(uuid_module.uuid4()), "avatar", avatar_id,
                                                                  revision, manifest, None))
                job.manifest = manifest
                job.state = "ready"
                surface.manifests[(avatar_id, revision)] = manifest
            except AvatarPrepareError as failure:
                partial = (AvatarManifest(avatar_id, revision, backend_id, failure.written)
                           if failure.written else None)
                try:
                    await report_artifact_via_bridge(surface.bridge,
                                                     artifact_payload(str(uuid_module.uuid4()), "avatar",
                                                                      avatar_id, revision, partial, failure.code))
                except Exception:
                    job.error_code = "dh_runtime_unavailable"
                    job.state = "failed"
                    return
                job.error_code = failure.code
                job.state = "failed"
                if partial is not None:
                    # 已写文件如实登记（INTERNAL12/13 可控读取与清理，TC105F-01-04）。
                    surface.manifests[(avatar_id, revision)] = partial
            except Exception:
                try:
                    await report_artifact_via_bridge(
                        surface.bridge,
                        artifact_payload(str(uuid_module.uuid4()), "avatar", avatar_id, revision, None,
                                         "dh_runtime_unavailable"))
                except Exception:
                    pass
                job.error_code = "dh_runtime_unavailable"
                job.state = "failed"

        asyncio.create_task(run_prepare())
        return JSONResponse(job.wire_state(), status_code=202)

    async def artifact_read(request: Request) -> Response:
        object_ref = request.path_params["object_ref"]
        try:
            resource_id = str(uuid_module.UUID(request.path_params["resource_id"]))
        except ValueError:
            return _error(422, "dh_invalid_input", "resourceId 不是合法 UUID。")
        if "/" in object_ref or "\\" in object_ref or ".." in object_ref:
            return _error(422, "dh_invalid_input", "objectRef 必须为服务器发放的无斜线句柄。")
        if object_ref.startswith("dhr-"):
            # C105F-02：录制段产物（mp4/srt）按已登记 manifest 读取，sha 校验同口径。
            # VideoInfo 无 relative_path/content_type（契约字段为媒体元数据）——路径与类型按
            # 固定产物名映射：mp4 → segment.mp4/video/mp4；srt 用 RecordingFileInfo 自带字段。
            sessions = getattr(request.app.state, "sessions", {})
            for session in sessions.values():
                archived = session.recording_archive.get(resource_id)
                if archived is None:
                    continue
                manifest = archived["manifest"]
                entries = []
                if manifest.video is not None:
                    entries.append((manifest.video, "segment.mp4", "video/mp4"))
                if manifest.subtitle is not None:
                    entries.append((manifest.subtitle, manifest.subtitle.relative_path,
                                    manifest.subtitle.content_type))
                for info, relative_path, content_type in entries:
                    if info.object_ref == object_ref:
                        target = recording_dir(_recording_media_root(), resource_id) / relative_path
                        try:
                            data = target.read_bytes()
                        except OSError:
                            return _error(404, "dh_not_found", "产物不存在。")
                        if hashlib.sha256(data).hexdigest() != info.sha256:
                            return _error(404, "dh_not_found", "产物校验不符。")
                        return Response(content=data, media_type=content_type)
            return _error(404, "dh_not_found", "产物不存在。")
        found: Optional[tuple[AvatarManifest, Any]] = None
        for (avatar_id, revision), manifest in surface.manifests.items():
            if avatar_id != resource_id:
                continue
            for entry in manifest.files:
                if entry.object_ref == object_ref:
                    found = (manifest, entry)
                    break
            if found is not None:
                break
        if found is None:
            return _error(404, "dh_not_found", "产物不存在。")
        manifest, entry = found
        target = sandbox_dir(surface.media_root, resource_id, manifest.revision) / entry.relative_path
        try:
            data = target.read_bytes()
        except OSError:
            return _error(404, "dh_not_found", "产物不存在。")
        if hashlib.sha256(data).hexdigest() != entry.sha256:
            return _error(404, "dh_not_found", "产物校验不符。")
        return Response(content=data, media_type=entry.content_type)

    async def resource_delete(request: Request) -> JSONResponse:
        body = await request.json()
        kind = str(body.get("kind", ""))
        if kind not in ("avatar", "recording", "session"):
            return _error(422, "dh_invalid_input", "未知资源类型。")
        if kind == "session":
            return _error(409, "dh_state_conflict", "session 资源删除随 C105F-05/G 阶段落地。")
        try:
            resource_id = str(uuid_module.UUID(request.path_params["resource_id"]))
        except ValueError:
            return _error(422, "dh_invalid_input", "resourceId 不是合法 UUID。")
        if not body.get("commandId") or not body.get("payloadHash"):
            return _error(422, "dh_invalid_input", "commandId/payloadHash 必填。")
        revision = body.get("revision")
        if not isinstance(revision, int) or revision < 1:
            return _error(422, "dh_invalid_input", "revision 必须 ≥1。")
        if kind == "recording":
            sessions = getattr(request.app.state, "sessions", {})
            for session in sessions.values():
                if session.recording is not None and session.recording.recording_id == resource_id:
                    return _error(409, "dh_state_conflict", "录制段进行中，先停止再删除。")
            known = any(resource_id in session.recording_archive for session in sessions.values())
            if not known and not recording_dir(_recording_media_root(), resource_id).exists():
                return _error(404, "dh_not_found", "录制段不存在。")
            complete, remaining = await asyncio.to_thread(delete_recording_tree, _recording_media_root(),
                                                          resource_id)
            if complete:
                for session in sessions.values():
                    session.recording_archive.pop(resource_id, None)
            return JSONResponse({"complete": complete, "remainingHandles": remaining})
        try:
            complete, remaining = await asyncio.to_thread(delete_avatar_tree, surface.media_root, resource_id)
        except AvatarPrepareError as invalid:
            return avatar_error(invalid)
        if complete:
            for key in [k for k in surface.jobs if k[0] == resource_id]:
                surface.jobs.pop(key, None)
            for key in [k for k in surface.manifests if k[0] == resource_id]:
                surface.manifests.pop(key, None)
        return JSONResponse({"complete": complete, "remainingHandles": remaining})

    return {"avatar_prepare": avatar_prepare, "artifact_read": artifact_read,
            "resource_delete": resource_delete}
