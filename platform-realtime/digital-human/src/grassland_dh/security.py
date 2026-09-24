"""wrapper 入口认证（任务书 #105D C105D-02 / 共享契约 K07、K13.2）。

auth 首帧之前的任何音频/provider 调用都被禁止：authenticate_origin 先本地精确比对批准
AI origin（不触网），consume_grant 再经 mTLS 内部通道核销票据（原子单次）。grant 从不进
query/subprotocol/日志；核销失败不回 owner。
"""

from __future__ import annotations

from typing import Sequence

from grassland_dh.bindings import GrantBinding


class GrantRejected(Exception):
    """票据/来源/参数被拒绝。code 为 Java 侧稳定错误码（或本地 fail-closed 码）。"""

    def __init__(self, code: str, status: int = 403) -> None:
        super().__init__(f"grant rejected: {code}")
        self.code = code
        self.status = status


def authenticate_origin(origin: str | None, allowed: Sequence[str]) -> bool:
    """Origin 精确比对（大小写敏感、无后缀通配、无 scheme 归一）。空 origin 拒绝。"""
    if not origin:
        return False
    return any(origin == candidate for candidate in allowed)


async def consume_grant(
    bridge,
    *,
    grant: str,
    session_id: str,
    lease_epoch: int,
    origin: str | None,
    request_id: str,
    format: str = "pcm_s16le",
    sample_rate: int = 16000,
    channels: int = 1,
) -> GrantBinding:
    """核销连接资格并接受 audio turn。

    顺序即安全边界：本地 origin 先拒（不占渲染槽、不产生任何上游调用），格式先验，然后才
    经 bridge（固定 authority、mTLS）核销；票据单次，重放即 409。
    """
    if not authenticate_origin(origin, bridge.allowed_origins):
        raise GrantRejected("dh_origin_rejected", 403)
    if format != "pcm_s16le" or sample_rate != 16000 or channels != 1:
        raise GrantRejected("dh_invalid_input", 422)
    if not grant or len(grant) > 128:
        raise GrantRejected("dh_grant_invalid", 409)
    if not request_id:
        raise GrantRejected("dh_invalid_input", 422)
    from grassland_dh.bridge import BridgeError

    try:
        return await bridge.consume_grant(
            grant=grant,
            session_id=session_id,
            lease_epoch=lease_epoch,
            origin=origin,
            request_id=request_id,
            format=format,
            sample_rate=sample_rate,
            channels=channels,
        )
    except BridgeError as failure:
        # Java 侧稳定错误码透传（dh_grant_invalid/dh_grant_expired/…）；网络类归并 fail-closed。
        code = failure.code if failure.code.startswith("dh_") else "dh_runtime_unavailable"
        raise GrantRejected(code, failure.status or 409) from failure
