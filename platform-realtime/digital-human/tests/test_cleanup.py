"""文件失败与孤儿 / 路径与共享引用（任务书 #105G C105G-02 / TC105G-02-03、TC105G-02-04）。

Java 侧重试账本（dh_cleanup 行级 1/5/30 分钟、上限 10 次）与远端确认在
DigitalHumanErasureIT/DigitalHumanCleanupRaceIT 断言；本文件证明 runtime 文件面——
- 重启扫描只认受控前缀 + 过期（进程被 kill 后无任何内存注册表，仅凭文件系统判定）；
- 删除失败（含驱动侧 503/IO 失败等同为「删除不确认」）保持 failed 并逐 key 列残留，
  重试后收口，不报假零；
- 符号链接不跟随、``../`` 与非规范 id 拒绝、共享 asset 引用保留——无出根、无误删。

纯文件系统 + monkeypatch 注入的删除失败；无网络、无 vendor。
"""

from __future__ import annotations

import os
import sys
import uuid as uuid_module
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh import cleanup  # noqa: E402


NOW = datetime(2026, 9, 24, 12, 0, 0, tzinfo=timezone.utc)


def _make_recording(root: Path, recording_id: str, *, files: list[str],
                    age: timedelta | None = None) -> Path:
    base = root / f"recording-{recording_id}"
    base.mkdir(parents=True, exist_ok=True)
    for name in files:
        (base / name).write_bytes(b"payload-" + name.encode())
    if age is not None:
        stamp = (NOW - age).timestamp()
        os.utime(base, (stamp, stamp))
    return base


def _make_avatar(root: Path, avatar_id: str, *, files: list[str], age: timedelta | None = None) -> Path:
    base = root / f"avatar-{avatar_id}"
    (base / "r1").mkdir(parents=True, exist_ok=True)
    for name in files:
        (base / "r1" / name).write_bytes(b"payload-" + name.encode())
    if age is not None:
        stamp = (NOW - age).timestamp()
        os.utime(base, (stamp, stamp))
    return base


class TestOrphanScanRetries:
    """tc105g_02_03：对象删除失败（503/kill 同为不确认）→ failed 保留证据；重试后逐 key 收口。"""

    def test_tc105g_02_03_orphan_scan_failure_then_retry_recovers(self, tmp_path: Path,
                                                                   monkeypatch: pytest.MonkeyPatch) -> None:
        root = tmp_path / "media"
        root.mkdir()
        old_recording = str(uuid_module.uuid4())
        fresh_recording = str(uuid_module.uuid4())
        old_avatar = str(uuid_module.uuid4())
        expired = _make_recording(root, old_recording, files=["segment_mp4", "subtitle_srt"], age=timedelta(hours=25))
        fresh = _make_recording(root, fresh_recording, files=["segment_mp4"], age=timedelta(hours=1))
        avatar_dir = _make_avatar(root, old_avatar, files=["normalized_png"], age=timedelta(hours=30))
        (root / "note.txt").write_text("运行时旁路文件")
        (root / "logs").mkdir()
        (root / "logs" / "app.log").write_text("日志不属于受控清理面")

        # —— 第一轮：删除全部失败（模拟对象删除 503/IO 失败——不确认不得当成功）。 ——
        real_rmtree = cleanup.shutil.rmtree

        def failing_rmtree(path, *args, **kwargs):  # noqa: ANN001, ANN002, ANN003
            raise OSError("simulated delete failure (503-equivalent)")

        monkeypatch.setattr(cleanup.shutil, "rmtree", failing_rmtree)
        first = cleanup.scan_orphans(root, NOW)
        assert set(first.failed_names) == {f"recording-{old_recording}", f"avatar-{old_avatar}"}
        failed_by_name = {o.name: o for o in first.failed}
        assert failed_by_name[f"recording-{old_recording}"].remaining_handles == (
            "segment_mp4", "subtitle_srt")
        assert failed_by_name[f"avatar-{old_avatar}"].remaining_handles == ("normalized_png",)
        assert first.deleted == ()
        # 未完成保持原状（failed 的目录与新目录都不动）。
        assert expired.exists() and avatar_dir.exists() and fresh.exists()

        # —— 重启语义：进程注册表为空，扫描仅凭文件系统（无内存状态可依赖）。 ——
        monkeypatch.setattr(cleanup.shutil, "rmtree", real_rmtree)
        second = cleanup.scan_orphans(root, NOW)
        assert set(second.deleted) == {f"recording-{old_recording}", f"avatar-{old_avatar}"}
        assert second.failed == ()
        # 成功后逐 key 核验：残留句柄对应文件全部消失。
        assert not expired.exists() and not avatar_dir.exists()
        # 未过期与非受控名不受影响。
        assert fresh.exists()
        assert (root / "note.txt").exists() and (root / "logs" / "app.log").exists()
        fresh_report = next(o for o in second.outcomes if o.name == f"recording-{fresh_recording}")
        assert fresh_report.outcome == "retained_fresh"
        ignored = {o.name: o.reason for o in second.outcomes if o.outcome == "ignored"}
        assert ignored == {"note.txt": "uncontrolled_name", "logs": "uncontrolled_name"}


class TestDangerousPathsAndSharedRefs:
    """tc105g_02_04：manifest 含 ../、软链或共享 asset 引用 → 拒绝危险路径并告警，无出根/误删。"""

    def test_tc105g_02_04_rejects_traversal_symlink_and_keeps_shared(self, tmp_path: Path) -> None:
        root = tmp_path / "media"
        root.mkdir()
        victim = tmp_path / "victim"
        victim.mkdir()
        secret = victim / "secret.txt"
        secret.write_text("敏感内容")

        # 受控名符号链接指向根外（不跟随、不删除、告警）。
        linked_avatar = str(uuid_module.uuid4())
        (root / f"avatar-{linked_avatar}").symlink_to(victim)

        # 共享引用（已转 asset）：过期也不删。
        shared_id = str(uuid_module.uuid4())
        shared_dir = _make_recording(root, shared_id, files=["segment_mp4"], age=timedelta(hours=48))

        # 过期且可删的目录，内部含指向根外文件的符号链接：目录删除、链接目标不删。
        deletable_id = str(uuid_module.uuid4())
        deletable = _make_recording(root, deletable_id, files=["segment_mp4"], age=timedelta(hours=48))
        (deletable / "outside-link").symlink_to(secret)
        # 建链会刷新目录 mtime——重打过期时间戳（固定时钟，不 sleep）。
        stale = (NOW - timedelta(hours=48)).timestamp()
        os.utime(deletable, (stale, stale))

        report = cleanup.scan_orphans(root, NOW, shared_refs={f"recording-{shared_id}"})
        assert report.deleted == (f"recording-{deletable_id}",)
        assert not deletable.exists()
        # 链接目标未被跟随删除（无出根）。
        assert secret.read_text() == "敏感内容"
        # 共享引用保留。
        assert shared_dir.exists() and (shared_dir / "segment_mp4").exists()
        shared_outcome = next(o for o in report.outcomes if o.name == f"recording-{shared_id}")
        assert shared_outcome.outcome == "retained_shared"
        # 符号链接：rejected + 告警，本体保留（不误删链接目标，也不悄悄删链接）。
        rejected = {o.name: o.reason for o in report.rejected}
        assert rejected == {f"avatar-{linked_avatar}": "symlink_not_followed"}
        assert (root / f"avatar-{linked_avatar}").is_symlink()
        assert victim.exists() and secret.exists()
        assert report.warnings() == [
            f"dh_cleanup_rejected: avatar-{linked_avatar}: symlink_not_followed"
        ]

    def test_tc105g_02_04_cleanup_session_rejects_traversal_ids(self, tmp_path: Path) -> None:
        root = tmp_path / "media"
        root.mkdir()
        keep = _make_recording(root, str(uuid_module.uuid4()), files=["segment_mp4"])
        victim = tmp_path / "outside"
        victim.mkdir()
        (victim / "pwned.txt").write_text("不得出根")

        # 含 ../、斜线、非 UUID 的 id 全部拒绝且不触碰文件系统。
        report = cleanup.cleanup_session(
            root,
            ["../../outside", "recording-../../etc", "not-a-uuid", "", "../media"],
        )
        assert all(o.outcome == "rejected" for o in report.outcomes)
        assert {o.reason for o in report.outcomes} == {"invalid_recording_id"}
        assert keep.exists()
        assert (victim / "pwned.txt").read_text() == "不得出根"

        # 目标是符号链接：拒绝（不跟随）。
        target = str(uuid_module.uuid4())
        (root / f"recording-{target}").symlink_to(victim)
        link_report = cleanup.cleanup_session(root, [target])
        assert link_report.rejected[0].reason == "symlink_not_followed"
        assert (root / f"recording-{target}").is_symlink()

        # 正常收口 + 幂等：第二次同 id 视为已收口（404 不当失败）。
        first_pass = cleanup.cleanup_session(root, [keep.name.removeprefix("recording-")])
        assert first_pass.deleted == (keep.name,)
        assert not keep.exists()
        second_pass = cleanup.cleanup_session(root, [keep.name.removeprefix("recording-")])
        assert second_pass.deleted == (keep.name,)
        assert next(o for o in second_pass.outcomes if o.name == keep.name).reason == "already_absent"
