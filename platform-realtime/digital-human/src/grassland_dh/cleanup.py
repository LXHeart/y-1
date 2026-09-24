"""数字人运行时文件清理（任务书 #105G C105G-02 / K05、K09、§9.1）。

职责分工如实注明：dh_cleanup 登记、1/5/30 分钟有界重试账本与远端确认在 Java 侧
（DigitalHumanCleanupWorker / PersonalDataErasureService）；本模块只做 runtime 文件面——

- ``scan_orphans``：重启兜底扫描（进程被 kill 后注册表为空，仅凭文件系统判定）。只对受控
  前缀（``recording-<uuid>`` / ``avatar-<uuid>``）且超过 TTL 的目录整目录删除；符号链接
  一律不跟随、不删除（拒绝+告警）；非受控名一概不动（无出根、无误删）；删除失败不报
  假零——逐 key 列出残留文件名供驱动侧重试（与 INTERNAL13 remainingHandles 同口径）。
- ``cleanup_session``：会话终止/租约失效后按 recording id 收口段目录；已转 asset 的共享
  引用保留；目标不存在视为已收口（幂等，404 不当失败——K05「不把 404 之外异常当成功」）。

纯文件系统操作（无网络、无 vendor 依赖）；不读取文件内容（日志/正文不落地原则）。
"""

from __future__ import annotations

import re
import shutil
import uuid as uuid_module
from collections.abc import Collection, Iterable
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 受控目录名：recording-/avatar- + 规范小写 UUID（其余一概视为非受控，不动）。
_CONTROLLED_DIR_RE = re.compile(
    r"^(?:recording|avatar)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$"
)

#: 孤儿兜底 TTL：与 dh_recording 到期清理（24h）对齐；Java 侧 INTERNAL13 正常先收口，
#: 这里只兜「登记方已不可达/进程死亡」的残留。
DEFAULT_ORPHAN_TTL = timedelta(hours=24)

#: 受控行状态（与 dh_cleanup 行状态语义对齐：deleted/retained/failed/rejected/ignored）。
OUTCOME_DELETED = "deleted"
OUTCOME_RETAINED_FRESH = "retained_fresh"
OUTCOME_RETAINED_SHARED = "retained_shared"
OUTCOME_REJECTED = "rejected"
OUTCOME_FAILED = "failed"
OUTCOME_IGNORED = "ignored"


def is_controlled_dir_name(name: str) -> bool:
    """仅受控前缀 + 规范 UUID 视为可清理目录（含 ``..``、斜线、大写或畸形 UUID 一律不匹配）。"""
    return _CONTROLLED_DIR_RE.match(name) is not None


@dataclass(frozen=True)
class DirOutcome:
    """单个目录的清理结论（逐 key 证据：失败时 remaining_handles 为目录内实际残留文件名）。"""

    name: str
    outcome: str
    reason: str = ""
    remaining_handles: tuple[str, ...] = ()


@dataclass(frozen=True)
class CleanupReport:
    """一次清理扫描/收口的完整报告（不报假零：failed 由调用方按有界重试驱动）。"""

    scanned: tuple[str, ...] = ()
    outcomes: tuple[DirOutcome, ...] = field(default=())

    def of(self, outcome: str) -> tuple[DirOutcome, ...]:
        return tuple(o for o in self.outcomes if o.outcome == outcome)

    @property
    def deleted(self) -> tuple[str, ...]:
        return tuple(o.name for o in self.of(OUTCOME_DELETED))

    @property
    def failed(self) -> tuple[DirOutcome, ...]:
        return self.of(OUTCOME_FAILED)

    @property
    def failed_names(self) -> tuple[str, ...]:
        return tuple(o.name for o in self.of(OUTCOME_FAILED))

    @property
    def rejected(self) -> tuple[DirOutcome, ...]:
        return self.of(OUTCOME_REJECTED)

    def warnings(self) -> list[str]:
        """拒绝项告警（危险路径/符号链接）：治理台与日志消费的明文口径。"""
        return [f"dh_cleanup_rejected: {o.name}: {o.reason}" for o in self.rejected]


def _now_utc(now: datetime) -> datetime:
    return now if now.tzinfo is not None else now.replace(tzinfo=timezone.utc)


def _delete_controlled(base: Path) -> DirOutcome:
    """整目录删除（先逐 key 清点，失败如实列出残留；rmtree 不跟随符号链接）。"""
    remaining = sorted(
        p.name for p in base.rglob("*") if p.is_file() and not p.is_symlink()
    )
    try:
        shutil.rmtree(base)
    except OSError:
        return DirOutcome(base.name, OUTCOME_FAILED, "rmtree_error", tuple(remaining))
    return DirOutcome(base.name, OUTCOME_DELETED)


def scan_orphans(
    root: Path,
    now: datetime,
    *,
    ttl: timedelta = DEFAULT_ORPHAN_TTL,
    shared_refs: Collection[str] = frozenset(),
) -> CleanupReport:
    """重启兜底扫描：仅删除「受控前缀 + 已过 TTL」的目录。

    - 符号链接（即便名字受控）：rejected，不跟随、不删除；
    - 非受控名/非目录：ignored，原样保留（无出根、无误删）；
    - shared_refs 中的受控目录名：retained_shared（已转共享引用，不因孤儿扫描误删）；
    - 删除失败：failed + remaining_handles（逐 key），由调用方重试——本函数单轮，不内部自旋。
    """
    shared = frozenset(shared_refs)
    scanned: list[str] = []
    outcomes: list[DirOutcome] = []
    root_path = Path(root)
    if not root_path.exists():
        return CleanupReport()
    resolved_root = root_path.resolve()
    now_value = _now_utc(now)
    for entry in sorted(root_path.iterdir(), key=lambda p: p.name):
        name = entry.name
        scanned.append(name)
        if entry.is_symlink():
            outcomes.append(DirOutcome(name, OUTCOME_REJECTED, "symlink_not_followed"))
            continue
        if not is_controlled_dir_name(name):
            outcomes.append(DirOutcome(name, OUTCOME_IGNORED, "uncontrolled_name"))
            continue
        if not entry.is_dir():
            outcomes.append(DirOutcome(name, OUTCOME_IGNORED, "not_a_directory"))
            continue
        if name in shared:
            outcomes.append(DirOutcome(name, OUTCOME_RETAINED_SHARED, "shared_reference"))
            continue
        try:
            entry.resolve().relative_to(resolved_root)
        except ValueError:
            outcomes.append(DirOutcome(name, OUTCOME_REJECTED, "escapes_root"))
            continue
        modified_at = datetime.fromtimestamp(entry.stat().st_mtime, tz=timezone.utc)
        if now_value - modified_at < ttl:
            outcomes.append(DirOutcome(name, OUTCOME_RETAINED_FRESH, "within_ttl"))
            continue
        outcomes.append(_delete_controlled(entry))
    return CleanupReport(tuple(scanned), tuple(outcomes))


def cleanup_session(
    media_root: Path,
    recording_ids: Iterable[str],
    *,
    shared_refs: Collection[str] = frozenset(),
) -> CleanupReport:
    """会话终止（含租约失效/重启）后按 recording id 收口段目录。

    - id 必须是规范 UUID（含 ``../``、斜线、非 UUID 一律 rejected，不出根）；
    - 目标是符号链接：rejected（不跟随）；目标不存在：视为已收口（幂等）；
    - shared_refs 中的 recording id：retained_shared（已存 asset 引用保留，K09）；
    - 删除失败：failed + remaining_handles 逐 key（不报假零）。
    """
    shared = frozenset(shared_refs)
    scanned: list[str] = []
    outcomes: list[DirOutcome] = []
    root_path = Path(media_root)
    resolved_root = root_path.resolve() if root_path.exists() else None
    for raw in recording_ids:
        try:
            recording_id = str(uuid_module.UUID(str(raw)))
        except (ValueError, AttributeError, TypeError):
            outcomes.append(DirOutcome(str(raw), OUTCOME_REJECTED, "invalid_recording_id"))
            continue
        name = f"recording-{recording_id}"
        scanned.append(name)
        if recording_id in shared or name in shared:
            outcomes.append(DirOutcome(name, OUTCOME_RETAINED_SHARED, "shared_reference"))
            continue
        base = root_path / name
        if base.is_symlink():
            outcomes.append(DirOutcome(name, OUTCOME_REJECTED, "symlink_not_followed"))
            continue
        if not base.exists():
            outcomes.append(DirOutcome(name, OUTCOME_DELETED, "already_absent"))
            continue
        if resolved_root is not None:
            try:
                base.resolve().relative_to(resolved_root)
            except ValueError:
                outcomes.append(DirOutcome(name, OUTCOME_REJECTED, "escapes_root"))
                continue
        outcomes.append(_delete_controlled(base))
    return CleanupReport(tuple(scanned), tuple(outcomes))
