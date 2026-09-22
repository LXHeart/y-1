"""C105A-01 上游固定与独立依赖环境测试（tc105a_01_01～tc105a_01_04）。

外层网络（download_archive）以合成归档替换；文件摘要、解压安全、幂等与漂移
检测全部走 bootstrap 真实实现，不 mock 被测逻辑本身。
"""

from __future__ import annotations

import importlib
import io
import os
import socket
import subprocess
import sys
import tarfile
import tomllib
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

import bootstrap  # noqa: E402

ARCHIVE_PREFIX = f"opentalking-{bootstrap.UPSTREAM_COMMIT[:8]}"
SYNTHETIC_FILES = {
    "pyproject.toml": b"[project]\nname = 'opentalking'\n",
    "opentalking/__init__.py": b"# synthetic upstream package\n",
    "opentalking/runtime/task_consumer.py": b"def _create_runner():\n    return None\n",
}


def make_archive_bytes(
    files: dict[str, bytes],
    prefix: str = ARCHIVE_PREFIX,
    symlinks: dict[str, str] | None = None,
) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for rel, content in files.items():
            info = tarfile.TarInfo(f"{prefix}/{rel}")
            info.size = len(content)
            info.mtime = 0
            tar.addfile(info, io.BytesIO(content))
        for rel, target in (symlinks or {}).items():
            info = tarfile.TarInfo(f"{prefix}/{rel}")
            info.type = tarfile.SYMTYPE
            info.linkname = target
            info.mtime = 0
            tar.addfile(info)
    return buffer.getvalue()


def make_raw_archive(entries: list[tuple[str, bytes]], types: list[str] | None = None) -> bytes:
    """构造带原始成员名（含穿越/绝对路径）的恶意归档。"""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for index, (name, content) in enumerate(entries):
            info = tarfile.TarInfo(name)
            member_type = (types[index] if types else tarfile.REGTYPE) or tarfile.REGTYPE
            info.type = member_type
            info.size = len(content)
            info.mtime = 0
            tar.addfile(info, io.BytesIO(content))
    return buffer.getvalue()


@pytest.fixture
def project_root(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    root = tmp_path / "digital-human"
    root.mkdir()
    monkeypatch.setattr(bootstrap, "PROJECT_ROOT", root)
    return root


@pytest.fixture
def patch_download(monkeypatch: pytest.MonkeyPatch):
    def _install(data: bytes) -> None:
        monkeypatch.setattr(bootstrap, "download_archive", lambda url=None: data)

    return _install


@pytest.fixture
def no_exec(monkeypatch: pytest.MonkeyPatch) -> None:
    def _fail(*args: object, **kwargs: object) -> None:
        raise AssertionError("bootstrap must not execute external processes in fetch/verify")

    for name in ("run", "Popen", "call", "check_call", "check_output"):
        monkeypatch.setattr(subprocess, name, _fail)
    monkeypatch.setattr(os, "system", _fail)
    monkeypatch.setattr(os, "popen", _fail)


def snapshot_dir_tree(base: Path) -> dict[str, int]:
    return {
        path.relative_to(base).as_posix(): path.stat().st_size
        for path in sorted(base.rglob("*"))
        if path.is_file()
    }


def run_mode(mode_args: list[str]) -> int:
    return bootstrap.main(mode_args)


class BootstrapTest:
    """tc105a_01_01～04：固定提交复现、恶意归档、环境隔离、冻结漂移。"""

    # ------------------------------------------------------------------
    # TC105A-01-01 固定提交复现
    # ------------------------------------------------------------------

    @pytest.mark.parametrize("modification", ["modify-existing", "unknown-file"])
    def test_tc105a_01_01_pinned_fetch_twice_and_verify(
        self, project_root: Path, patch_download, no_exec, capsys, modification: str
    ) -> None:
        archive = make_archive_bytes(SYNTHETIC_FILES)
        patch_download(archive)

        assert run_mode(["--fetch"]) == 0
        lock_path = project_root / "upstream.lock.json"
        assert lock_path.is_file()
        lock_bytes_after_first = lock_path.read_bytes()
        import json

        lock = json.loads(lock_bytes_after_first)
        assert lock["upstream"]["commit"] == bootstrap.UPSTREAM_COMMIT
        assert lock["upstream"]["repository"] == bootstrap.UPSTREAM_REPOSITORY
        assert {entry["path"] for entry in lock["upstream"]["files"]} == set(SYNTHETIC_FILES)

        vendor = project_root / "vendor" / "opentalking"
        for rel, content in SYNTHETIC_FILES.items():
            assert (vendor / rel).read_bytes() == content

        assert run_mode(["--verify-only"]) == 0

        # 本地未知修改：改既有文件 或 新增未知文件
        if modification == "modify-existing":
            target = vendor / "opentalking" / "__init__.py"
            target.write_bytes(b"# local unknown edit\n")
            drift_marker = "opentalking/__init__.py"
        else:
            target = vendor / "opentalking" / "unknown_local.py"
            target.write_bytes(b"# stray local file\n")
            drift_marker = "opentalking/unknown_local.py"

        # 第二次 fetch：不覆盖/不删除本地未知修改
        assert run_mode(["--fetch"]) == 0
        assert target.exists()
        if modification == "modify-existing":
            assert target.read_bytes() == b"# local unknown edit\n"

        # lock 未被第二次 fetch 重写（同 commit/源摘要，不可重新生成 hash 绕过）
        assert lock_path.read_bytes() == lock_bytes_after_first

        # verify-only 点名具体漂移文件
        assert run_mode(["--verify-only"]) == 1
        stderr = capsys.readouterr().err
        assert drift_marker in stderr

    # ------------------------------------------------------------------
    # TC105A-01-02 错误源与穿越
    # ------------------------------------------------------------------

    @pytest.mark.parametrize(
        "archive_kind",
        ["dotdot-escape", "absolute-path", "symlink-escape", "digest-mismatch"],
    )
    def test_tc105a_01_02_unsafe_or_mismatched_archive_rejected(
        self, project_root: Path, tmp_path: Path, patch_download, no_exec, capsys, archive_kind: str
    ) -> None:
        if archive_kind == "dotdot-escape":
            archive = make_raw_archive(
                [(f"{ARCHIVE_PREFIX}/../escape.sh", b"#!/bin/sh\necho pwned\n")]
            )
        elif archive_kind == "absolute-path":
            archive = make_raw_archive([("/tmp/grassland-escape.py", b"print('escaped')\n")])
        elif archive_kind == "symlink-escape":
            archive = make_archive_bytes(
                {"inner.py": b"x = 1\n"},
                symlinks={"link.py": "../../../outside_root.txt"},
            )
        else:  # digest-mismatch：先正常 fetch 建锁，再喂篡改归档
            good = make_archive_bytes(SYNTHETIC_FILES)
            patch_download(good)
            assert run_mode(["--fetch"]) == 0
            vendor_file = project_root / "vendor" / "opentalking" / "pyproject.toml"
            original_bytes = vendor_file.read_bytes()
            tampered = dict(SYNTHETIC_FILES)
            tampered["pyproject.toml"] = b"[project]\nname = 'tampered'\n"
            patch_download(make_archive_bytes(tampered))
            outside_before = snapshot_dir_tree(tmp_path)
            assert run_mode(["--fetch"]) != 0
            stderr = capsys.readouterr().err
            assert "digest" in stderr or "mismatch" in stderr.lower()
            assert vendor_file.read_bytes() == original_bytes
            return

        patch_download(archive)
        outside_before = snapshot_dir_tree(tmp_path)
        code = run_mode(["--fetch"])
        assert code != 0
        # 无 workspace 外写入：目录树仅在 project_root 子树内可能变化
        outside_after = snapshot_dir_tree(tmp_path)
        subtree = project_root.name + "/"
        leaked = {
            rel
            for rel in set(outside_before) | set(outside_after)
            if not rel.startswith(subtree) and outside_before.get(rel) != outside_after.get(rel)
        }
        assert not leaked, f"files changed outside project workspace: {sorted(leaked)}"
        # 无脚本执行由 no_exec fixture 保证（任何 subprocess/os.system 都会 AssertionError）
        assert not (project_root / "vendor" / "opentalking").exists() or archive_kind == "digest-mismatch"

    # ------------------------------------------------------------------
    # TC105A-01-03 环境隔离
    # ------------------------------------------------------------------

    def test_tc105a_01_03_isolated_python311_no_provider(self, monkeypatch: pytest.MonkeyPatch) -> None:
        real_root = Path(bootstrap.__file__).resolve().parent.parent

        # 项目 python 固定 3.11（本机默认 3.14 不满足目标，uv 项目内独立）
        assert (real_root / ".python-version").read_text(encoding="utf-8").strip() == "3.11"
        assert sys.version_info[:2] == (3, 11)
        assert sys.prefix != sys.base_prefix, "tests must run inside the project venv"
        venv = real_root / ".venv"
        # 不做 resolve()：venv 内 python 是指向系统解释器的符号链接，解析会跳出 venv
        executable = Path(sys.executable)
        assert executable.is_relative_to(venv), f"project interpreter {executable} outside {venv}"

        # 系统默认 python 未被替换：沿 PATH 跳过 venv bin 找系统 python3，仍保持 3.14（本书 FACT）
        import shutil

        venv_bin = (Path(sys.prefix) / "bin").resolve()
        system_python_path = None
        for entry in os.environ.get("PATH", "").split(os.pathsep):
            if not entry:
                continue
            candidate_dir = Path(entry)
            try:
                if candidate_dir.resolve() == venv_bin:
                    continue
            except OSError:
                continue
            candidate = candidate_dir / "python3"
            if candidate.is_file() and os.access(candidate, os.X_OK):
                system_python_path = str(candidate)
                break
        assert system_python_path is not None, "system python3 not found on PATH outside venv"
        assert not Path(system_python_path).resolve().is_relative_to(venv)
        system_python = subprocess.run(
            [system_python_path, "--version"],
            capture_output=True,
            text=True,
            check=True,
        )
        words = (system_python.stdout + system_python.stderr).split()
        assert len(words) >= 2 and words[0] == "Python" and words[1].startswith(
            "3.14"
        ), f"system python changed: {system_python.stdout!r}"

        # pyproject 锁定 3.11 且为独立项目
        pyproject = tomllib.loads((real_root / "pyproject.toml").read_text(encoding="utf-8"))
        requires = pyproject["project"]["requires-python"]
        assert "3.11" in requires and "<3.12" in requires
        assert pyproject["project"]["name"] == "grassland-dh"

        # import 无副作用、verify 零网络零执行（socket/进程全被禁止仍成功）
        def _no_network(*args: object, **kwargs: object) -> None:
            raise AssertionError("bootstrap must not open sockets on import/verify")

        monkeypatch.setattr(socket, "socket", _no_network)
        monkeypatch.setattr(socket, "create_connection", _no_network)

        def _no_exec(*args: object, **kwargs: object) -> None:
            raise AssertionError("bootstrap must not execute processes on import/verify")

        monkeypatch.setattr(subprocess, "run", _no_exec)
        monkeypatch.setattr(subprocess, "Popen", _no_exec)
        monkeypatch.setattr(os, "system", _no_exec)

        monkeypatch.setattr(bootstrap, "PROJECT_ROOT", real_root)
        reloaded = importlib.reload(bootstrap)
        assert reloaded.UPSTREAM_COMMIT == bootstrap.UPSTREAM_COMMIT
        assert bootstrap.main(["--verify-only"]) == 0

        # bootstrap 仅标准库（无第三方 import → 无 provider/模型 SDK 被引入）
        source = (real_root / "scripts" / "bootstrap.py").read_text(encoding="utf-8")
        import ast as ast_module

        tree = ast_module.parse(source)
        imported: set[str] = set()
        for node in ast_module.walk(tree):
            if isinstance(node, ast_module.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast_module.ImportFrom) and node.module:
                imported.add(node.module.split(".")[0])
        non_stdlib = imported - set(sys.stdlib_module_names)
        assert not non_stdlib, f"bootstrap.py imports non-stdlib modules: {sorted(non_stdlib)}"

    # ------------------------------------------------------------------
    # TC105A-01-04 冻结依赖漂移
    # ------------------------------------------------------------------

    @pytest.fixture
    def drifted_root(
        self, project_root: Path, patch_download
    ) -> tuple[Path, Path, Path]:
        """已 fetch 且依赖锁/补丁齐备的基线环境：uv.lock、patches/0001 与 lock 一致。"""
        import hashlib
        import json

        patch_download(make_archive_bytes(SYNTHETIC_FILES))
        assert run_mode(["--fetch"]) == 0
        uv_lock = project_root / "uv.lock"
        uv_lock.write_bytes(b"# synthetic frozen uv lock v1\n")
        patch_file = project_root / "patches" / "0001-injected-runtime-bindings.patch"
        patch_file.parent.mkdir(parents=True, exist_ok=True)
        patch_bytes = b"--- a\n+++ b\n@@\n"
        patch_file.write_bytes(patch_bytes)
        lock_path = project_root / "upstream.lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        lock["dependencyLockSha256"] = hashlib.sha256(uv_lock.read_bytes()).hexdigest()
        lock["patches"] = [
            {
                "name": "0001-injected-runtime-bindings.patch",
                "sha256": hashlib.sha256(patch_bytes).hexdigest(),
                "appliesTo": ["opentalking/runtime/task_consumer.py"],
            }
        ]
        lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        assert run_mode(["--verify-only"]) == 0
        return project_root, uv_lock, patch_file

    @pytest.mark.parametrize(
        "tamper_kind",
        ["vendor-file", "vendor-missing", "uv-lock", "patch", "unexpected-vendor-file"],
    )
    def test_tc105a_01_04_verify_reports_specific_drift_without_updating_lock(
        self, drifted_root, capsys, tamper_kind: str
    ) -> None:
        import json

        project_root, uv_lock, patch_file = drifted_root
        lock_path = project_root / "upstream.lock.json"
        lock_before = lock_path.read_bytes()
        vendor = project_root / "vendor" / "opentalking"

        if tamper_kind == "vendor-file":
            (vendor / "pyproject.toml").write_bytes(b"[project]\nname = 'drifted'\n")
            expected_marker = "vendor file digest mismatch: pyproject.toml"
        elif tamper_kind == "vendor-missing":
            (vendor / "opentalking" / "runtime" / "task_consumer.py").unlink()
            expected_marker = "vendor file missing: opentalking/runtime/task_consumer.py"
        elif tamper_kind == "unexpected-vendor-file":
            (vendor / "opentalking" / "extra_local.py").write_bytes(b"x = 2\n")
            expected_marker = "vendor unexpected local file: opentalking/extra_local.py"
        elif tamper_kind == "uv-lock":
            uv_lock.write_bytes(b"# synthetic frozen uv lock v2 (tampered)\n")
            expected_marker = "uv.lock digest mismatch"
        else:
            patch_file.write_bytes(b"--- a\n+++ b\n@@\n- changed\n")
            expected_marker = "patch digest mismatch: 0001-injected-runtime-bindings.patch"

        assert run_mode(["--verify-only"]) == 1
        stderr = capsys.readouterr().err
        assert expected_marker in stderr, f"drift {tamper_kind} not named precisely in: {stderr}"
        # 不能悄悄更新锁：lock 文件字节不变
        assert lock_path.read_bytes() == lock_before

    def test_tc105a_01_04_verify_source_pure_function_drift_items(
        self, project_root: Path, patch_download
    ) -> None:
        """verify_source 直接以合成 lock 调用：漂移项逐条具体、只读。"""
        import json

        patch_download(make_archive_bytes(SYNTHETIC_FILES))
        assert run_mode(["--fetch"]) == 0
        lock = json.loads((project_root / "upstream.lock.json").read_text(encoding="utf-8"))
        problems = bootstrap.verify_source(project_root, lock)
        assert problems == []

        (project_root / "vendor" / "opentalking" / "pyproject.toml").write_bytes(b"tampered\n")
        problems = bootstrap.verify_source(project_root, lock)
        assert len(problems) == 1
        assert problems[0].startswith("vendor file digest mismatch: pyproject.toml")


def test_symlink_members_are_materialized_as_files(project_root: Path, patch_download) -> None:
    """根内软链接物化为常规文件副本（摘要以内容为准），不出根不保留链接。"""
    archive = make_archive_bytes(
        {"real.py": b"VALUE = 1\n"}, symlinks={"alias.py": "real.py"}
    )
    patch_download(archive)
    assert bootstrap.main(["--fetch"]) == 0
    alias = project_root / "vendor" / "opentalking" / "alias.py"
    assert alias.read_bytes() == b"VALUE = 1\n"
    assert not alias.is_symlink(), "根内链接必须物化为常规文件副本，不得保留符号链接"


def test_download_host_allowlist() -> None:
    """下载仅允许 github/codeload：其他主机直接拒绝。"""
    with pytest.raises(RuntimeError, match="host not allowed"):
        bootstrap.download_archive("https://example.com/opentalking.tar.gz")
    with pytest.raises(RuntimeError, match="host not allowed"):
        bootstrap.download_archive("http://codeload.github.com/opentalking.tar.gz")
