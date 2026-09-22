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
