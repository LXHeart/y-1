"""每会话 Binding（K07.1）：Python 实时域唯一身份/租约载体。

SessionBinding 冻结不可变；无长期 provider key。grant_provider 是服务端注入的
短期资格签发函数（不序列化、不落日志）。旧 epoch 的 Binding 一律视为无效：
适配层在每次动作前核对 lease/media/turn epoch，乱序即拒绝。
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class SessionBinding:
    """跨语言内部类型 SessionBinding 的 Python 形态（wire 仍 camelCase）。"""

    session_id: str
    lease_epoch: int
    media_epoch: int
    backend_id: str
    profile_revision: int
    expires_at: datetime
    bridge_base_url: str
    grant_provider: Callable[[str], str] = field(default=lambda kind: secrets.token_urlsafe(32), repr=False)

    def lease_valid(self, lease_epoch: int) -> bool:
        return lease_epoch == self.lease_epoch

    def wire(self) -> dict[str, object]:
        """不含 grant_provider 函数的 wire 形态（K07.1：函数不序列化）。"""
        return {
            "sessionId": self.session_id,
            "leaseEpoch": self.lease_epoch,
            "mediaEpoch": self.media_epoch,
            "backendId": self.backend_id,
            "profileRevision": self.profile_revision,
            "expiresAt": self.expires_at.isoformat().replace("+00:00", "Z"),
            "bridgeBaseUrl": self.bridge_base_url,
        }


@dataclass(frozen=True)
class TurnBinding:
    """单轮绑定：turnEpoch 控制生成代次；输入正文只内存随命令传输，不进 receipt。"""

    session_id: str
    turn_id: str
    turn_epoch: int
    lease_epoch: int
    media_epoch: int
    content_epoch: int
    request_id: str
    deadline_at: datetime


@dataclass(frozen=True)
class ExecutionGrant:
    """INTERNAL07 wire 的执行资格（K07.1）：scope 固定 stage+invocation+inputHash，30 秒核销。"""

    invocation_id: str
    stage: str
    grant: str
    expires_at: datetime
    deadline_at: datetime
    input_hash: str

    def expired(self, now: datetime | None = None) -> bool:
        return (now or utc_now()) >= self.expires_at


@dataclass(frozen=True)
class GrantBinding:
    """INTERNAL05 核销成功返回（K07.2）：wrapper 侧只认这些字段，无 owner。"""

    session_id: str
    lease_epoch: int
    content_epoch: int
    expires_at: datetime
    turn_id: str
    turn_epoch: int
    first_frame_deadline_at: datetime


_ALLOWED_FRAME_TYPES = ("meta", "delta", "usage", "done", "error")
_FRAME_FIELDS = {
    "meta": {"format", "sampleRate", "channels"},
    "delta": {"text", "audioBase64"},
    "usage": {"inputTokens", "outputTokens", "audioInputMs", "audioOutputMs", "textCodePoints", "renderMs",
              "providerRequestId", "quality"},
    "done": {"state"},
    "error": {"code", "retryable"},
}


@dataclass(frozen=True)
class BridgeFrame:
    """K07.1 NDJSON 帧：不同 type 禁止混字段；构造即校验。"""

    type: str
    seq: int
    meta: dict[str, object] | None = None
    delta_text: str | None = None
    delta_audio_base64: str | None = None
    usage: dict[str, object] | None = None
    done_state: str | None = None
    error_code: str | None = None
    error_retryable: bool | None = None

    def __post_init__(self) -> None:
        if self.type not in _ALLOWED_FRAME_TYPES:
            raise ValueError(f"bridge frame type 非法: {self.type}")

    @staticmethod
    def from_wire(payload: dict[str, object]) -> "BridgeFrame":
        """从 NDJSON 行解析：字段集合按 type 严格判别，多余/缺失即拒绝（不猜语义）。"""
        frame_type = payload.get("type")
        if frame_type not in _ALLOWED_FRAME_TYPES:
            raise ValueError(f"bridge frame type 非法: {frame_type!r}")
        allowed = {"v", "invocationId", "seq", "type"} | _FRAME_FIELDS[frame_type]
        unexpected = set(payload) - allowed
        if unexpected:
            raise ValueError(f"bridge frame {frame_type} 混入未声明字段: {sorted(unexpected)}")
        seq = payload.get("seq")
        if not isinstance(seq, int) or seq < 0:
            raise ValueError("bridge frame seq 必须是非负整数")
        missing = _FRAME_FIELDS[frame_type] - set(payload)
        # 各 type 只要求其「至少一个」载荷字段存在（如 delta 至少有 text 或 audioBase64）。
        if missing == _FRAME_FIELDS[frame_type]:
            raise ValueError(f"bridge frame {frame_type} 缺少载荷字段")
        return BridgeFrame(
            type=frame_type,
            seq=seq,
            meta=payload if frame_type == "meta" else None,
            delta_text=payload.get("text") if frame_type == "delta" else None,
            delta_audio_base64=payload.get("audioBase64") if frame_type == "delta" else None,
            usage=payload if frame_type == "usage" else None,
            done_state=payload.get("state") if frame_type == "done" else None,
            error_code=payload.get("code") if frame_type == "error" else None,
            error_retryable=payload.get("retryable") if frame_type == "error" else None,
        )

    def to_wire(self, invocation_id: str) -> dict[str, object]:
        wire: dict[str, object] = {"v": 1, "invocationId": invocation_id, "seq": self.seq, "type": self.type}
        if self.type == "meta":
            assert self.meta is not None
            wire.update({k: self.meta[k] for k in ("format", "sampleRate", "channels")})
        elif self.type == "delta":
            if self.delta_text is not None:
                wire["text"] = self.delta_text
            if self.delta_audio_base64 is not None:
                wire["audioBase64"] = self.delta_audio_base64
        elif self.type == "usage":
            assert self.usage is not None
            wire.update({k: self.usage[k] for k in _FRAME_FIELDS["usage"] if k in self.usage})
        elif self.type == "done":
            wire["state"] = self.done_state
        else:
            wire["code"] = self.error_code
            wire["retryable"] = bool(self.error_retryable)
        return wire
