"""草场 #105A-01：数字人上游固定与独立依赖环境引导脚本。

仅使用 Python 标准库。三种模式（互斥）：

  --fetch         仅下载固定提交的上游源码归档（只允许 github/codeload 主机），
                  安全解压到 vendor/opentalking；首次生成 upstream.lock.json 的
                  upstream 小节；已有 lock 时与归档摘要比对，不匹配即失败，
                  绝不重写已存在的 upstream 小节（禁止用重新生成 hash 绕过漂移）。
  --verify-only   只读校验：vendor/opentalking 逐文件摘要、patches/ 补丁摘要、
                  uv.lock 摘要（dependencyLockSha256）与 upstream.lock.json 一致；
                  零写入；漂移时逐项点名并以非零退出。
  --resolve-lock  仅解析本项目依赖：调用 uv lock（固定 --python 3.11），成功后把
                  uv.lock 的 sha256 记录进 upstream.lock.json，并刷新依赖清单
                  （版本来自 uv.lock，许可证来自已安装 .venv 的 METADATA，缺失记 null）。

本脚本永不执行上游 quickstart/doctor 等安装脚本、永不下载模型权重、永不启动
任何 provider；--resolve-lock 仅执行 PATH 上的 uv 二进制（固定参数）。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

UPSTREAM_REPOSITORY = "datascale-ai/opentalking"
UPSTREAM_COMMIT = "8c739a5a6f114daf71aeace832a668c3ad60f536"
ALLOWED_DOWNLOAD_HOSTS = frozenset({"codeload.github.com", "github.com"})
ARCHIVE_URL = f"https://codeload.github.com/{UPSTREAM_REPOSITORY}/tar.gz/{UPSTREAM_COMMIT}"
LOCK_VERSION = 1

PROJECT_ROOT = Path(__file__).resolve().parent.parent
VENDOR_DIR = "vendor"
VENDOR_PACKAGE_DIR = "opentalking"
LOCK_FILE_NAME = "upstream.lock.json"
DEP_LOCK_NAME = "uv.lock"
STAGING_DIR_NAME = ".fetch-staging"


# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _project_file(name: str) -> Path:
    return PROJECT_ROOT / name


def load_lock(root: Path) -> dict[str, object]:
    return json.loads((root / LOCK_FILE_NAME).read_text(encoding="utf-8"))


def write_lock(root: Path, lock: dict[str, object]) -> None:
    target = root / LOCK_FILE_NAME
    target.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def snapshot_tree(root: Path) -> list[dict[str, str]]:
    """遍历 root，记录每个常规文件的相对路径（posix）与 sha256，按路径排序。"""
    entries: list[dict[str, str]] = []
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        rel = path.relative_to(root).as_posix()
        entries.append({"path": rel, "sha256": sha256_file(path)})
    return entries


# ---------------------------------------------------------------------------
# 归档下载与安全解压
# ---------------------------------------------------------------------------


def download_archive(url: str = ARCHIVE_URL) -> bytes:
    """下载固定提交归档；主机不在白名单直接拒绝（测试通过替换本函数注入合成归档）。"""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS:
        raise RuntimeError(f"download host not allowed: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "grassland-dh-bootstrap/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def _strip_prefix_and_validate(members: list[tarfile.TarInfo]) -> tuple[list[tarfile.TarInfo], str]:
    """校验归档全部成员并统一去掉单一顶层目录前缀；任何穿越立即抛错。返回(成员, 前缀)。"""
    for member in members:
        name = member.name
        if name.startswith("/") or name.startswith("\\") or "\\" in name:
            raise RuntimeError(f"absolute or backslash archive member path: {name!r}")
    names = [member.name[2:] if member.name.startswith("./") else member.name for member in members]
    if not names:
        raise RuntimeError("archive is empty")
    top_levels = {name.split("/", 1)[0] for name in names if name}
    if len(top_levels) != 1:
        raise RuntimeError(f"archive has multiple top-level directories: {sorted(top_levels)}")
    prefix = next(iter(top_levels))
    for member, name in zip(members, names):
        if not name or name == prefix:
            member.name = ""  # 顶层目录标记：解压循环直接跳过
            continue
        rel = name[len(prefix) + 1 :]
        parts = rel.split("/")
        if rel.startswith("/") or ".." in parts or not parts[-1]:
            raise RuntimeError(f"unsafe archive member path: {member.name!r}")
        member.name = rel  # 后续解压使用清洗后的相对名
    return members, prefix


def _resolve_link_target(member: tarfile.TarInfo, prefix: str) -> str:
    """把符号/硬链接目标解析为解压根内的相对路径；出根或绝对目标抛错。"""
    target = member.linkname
    if member.issym():
        # 符号链接目标相对成员所在目录
        candidate = os.path.normpath(os.path.join(os.path.dirname(member.name), target))
    else:
        # 硬链接目标本身是归档内路径（含顶层前缀时先去掉）
        cleaned = target.lstrip("./")
        if cleaned == prefix:
            cleaned = ""
        elif cleaned.startswith(prefix + "/"):
            cleaned = cleaned[len(prefix) + 1 :]
        candidate = os.path.normpath(cleaned)
    parts = candidate.split("/")
    if candidate.startswith("/") or candidate == ".." or ".." in parts:
        raise RuntimeError(f"archive link escapes extraction root: {member.name!r} -> {target!r}")
    return candidate


def _extract_archive(data: bytes, dest: Path) -> None:
    """安全解压：先整体校验，再逐成员落盘；出根软/硬链接整体拒绝，根内链接物化为文件副本。"""
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        members, prefix = _strip_prefix_and_validate(tar.getmembers())
        pending_links: list[tarfile.TarInfo] = []
        for member in members:
            rel = member.name.lstrip("./")
            if not rel:
                continue  # 顶层目录标记
            if member.isdir():
                (dest / rel).mkdir(parents=True, exist_ok=True)
            elif member.isreg():
                target_path = dest / rel
                target_path.parent.mkdir(parents=True, exist_ok=True)
                source = tar.extractfile(member)
                if source is None:
                    raise RuntimeError(f"cannot read archive member: {member.name!r}")
                with target_path.open("wb") as handle:
                    shutil.copyfileobj(source, handle)
            elif member.issym() or member.islnk():
                pending_links.append(member)
            else:
                raise RuntimeError(f"unsupported archive member type: {member.name!r}")
        # 链接统一后置处理：目标必须在解压根内，物化为常规文件副本（摘要以内容为准）
        for member in pending_links:
            resolved = _resolve_link_target(member, prefix)
            target_path = dest / resolved
            link_path = dest / member.name
            if not target_path.is_file():
                raise RuntimeError(
                    f"archive link target missing or outside root: {member.name!r} -> {resolved!r}"
                )
            link_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target_path, link_path)


# ---------------------------------------------------------------------------
# 漂移校验
# ---------------------------------------------------------------------------


def verify_source(root: Path, lock: dict[str, object]) -> list[str]:
    """只读比对 vendor 源码、补丁、uv.lock 与 upstream.lock.json；返回具体漂移项，不修复。"""
    problems: list[str] = []
    upstream = lock.get("upstream")
    if not isinstance(upstream, dict):
        return ["upstream.lock.json missing 'upstream' section"]
    if upstream.get("commit") != UPSTREAM_COMMIT:
        problems.append(
            f"upstream commit drift: lock={upstream.get('commit')!r} expected={UPSTREAM_COMMIT!r}"
        )

    vendor_dir = root / VENDOR_DIR / VENDOR_PACKAGE_DIR
    if not vendor_dir.is_dir():
        problems.append(f"vendor source missing: {VENDOR_DIR}/{VENDOR_PACKAGE_DIR} not found")
        expected_files: list[dict[str, str]] = []
        actual_paths: set[str] = set()
    else:
        expected_files = list(upstream.get("files", []))  # type: ignore[arg-type]
        actual = {entry["path"]: entry["sha256"] for entry in snapshot_tree(vendor_dir)}
        actual_paths = set(actual)
        for entry in expected_files:
            rel = entry["path"]
            if rel not in actual:
                problems.append(f"vendor file missing: {rel}")
            elif actual[rel] != entry["sha256"]:
                problems.append(
                    f"vendor file digest mismatch: {rel} "
                    f"(expected {entry['sha256'][:12]}, got {actual[rel][:12]})"
                )
        expected_paths = {entry["path"] for entry in expected_files}
        for extra in sorted(actual_paths - expected_paths):
            problems.append(f"vendor unexpected local file: {extra}")

    patches = lock.get("patches")
    if not isinstance(patches, list):
        problems.append("upstream.lock.json missing 'patches' list")
    else:
        for patch in patches:  # type: ignore[union-attr]
            name = patch.get("name")  # type: ignore[union-attr]
            digest = patch.get("sha256")  # type: ignore[union-attr]
            patch_path = root / "patches" / str(name)
            if not patch_path.is_file():
                problems.append(f"patch missing: {name}")
            elif sha256_file(patch_path) != digest:
                problems.append(f"patch digest mismatch: {name}")

    dep_sha = lock.get("dependencyLockSha256")
    if dep_sha is not None:
        uv_lock = root / DEP_LOCK_NAME
        if not uv_lock.is_file():
            problems.append(f"dependency lock missing: {DEP_LOCK_NAME} not found")
        else:
            actual_sha = sha256_file(uv_lock)
            if actual_sha != dep_sha:
                problems.append(
                    f"uv.lock digest mismatch: expected dependencyLockSha256={str(dep_sha)[:12]}, "
                    f"actual={actual_sha[:12]}"
                )
    return problems


# ---------------------------------------------------------------------------
# 依赖解析
# ---------------------------------------------------------------------------


def _run_uv_lock(root: Path) -> int:
    uv_bin = shutil.which("uv")
    if uv_bin is None:
        print("bootstrap: uv not found on PATH", file=sys.stderr)
        return 1
    result = subprocess.run(
        [uv_bin, "lock", "--project", str(root), "--python", "3.11"],
        check=False,
    )
    return result.returncode


def _license_from_metadata(venv: Path) -> dict[str, str | None]:
    licenses: dict[str, str | None] = {}
    site_packages = venv / "lib" / "python3.11" / "site-packages"
    if not site_packages.is_dir():
        return licenses
    for dist_info in sorted(site_packages.glob("*.dist-info")):
        name = version = license_value = None
        for line in (dist_info / "METADATA").read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("Name:") and name is None:
                name = line.split(":", 1)[1].strip()
            elif line.startswith("Version:") and version is None:
                version = line.split(":", 1)[1].strip()
            elif line.startswith("License:") and license_value is None:
                candidate = line.split(":", 1)[1].strip()
                if candidate and "UNKNOWN" not in candidate.upper():
                    license_value = candidate
            elif line.startswith("License-Expression:") and license_value is None:
                license_value = line.split(":", 1)[1].strip() or None
        if name:
            licenses[f"{name}=={version}"] = license_value
    return licenses


def _dependency_inventory(root: Path) -> list[dict[str, object]]:
    try:
        import tomllib
    except ModuleNotFoundError:  # pragma: no cover - Python 3.11+ 均有 tomllib
        return []
    uv_lock = root / DEP_LOCK_NAME
    if not uv_lock.is_file():
        return []
    lock_data = tomllib.loads(uv_lock.read_text(encoding="utf-8"))
    license_map = _license_from_metadata(root / ".venv")
    inventory: list[dict[str, object]] = []
    for package in lock_data.get("package", []):
        name = package.get("name", "")
        version = str(package.get("version", ""))
        source = package.get("source", {})
        source_kind = source.get("registry") if isinstance(source, dict) else None
        origin = "pypi" if source_kind else str(source.get("virtual", "project") if isinstance(source, dict) else "project")
        inventory.append(
            {
                "name": name,
                "version": version,
                "license": license_map.get(f"{name}=={version}"),
                "source": origin,
            }
        )
    inventory.sort(key=lambda item: str(item["name"]).lower())
    return inventory


# ---------------------------------------------------------------------------
# fetch 主流程
# ---------------------------------------------------------------------------


def _fetch(root: Path) -> int:
    vendor_dir = root / VENDOR_DIR / VENDOR_PACKAGE_DIR
    staging = root / VENDOR_DIR / STAGING_DIR_NAME
    lock_path = root / LOCK_FILE_NAME

    existing_lock: dict[str, object] | None = load_lock(root) if lock_path.is_file() else None

    try:
        archive = download_archive()
        if staging.exists():
            shutil.rmtree(staging)
        staging.parent.mkdir(parents=True, exist_ok=True)
        _extract_archive(archive, staging)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    staged_snapshot = snapshot_tree(staging)

    if existing_lock is None:
        lock: dict[str, object] = {
            "version": LOCK_VERSION,
            "upstream": {
                "repository": UPSTREAM_REPOSITORY,
                "commit": UPSTREAM_COMMIT,
                "archiveUrl": ARCHIVE_URL,
                "files": staged_snapshot,
            },
            "patches": [],
            "dependencyLockSha256": None,
            "dependencies": [],
        }
        if vendor_dir.exists():
            shutil.rmtree(vendor_dir)
        staging.rename(vendor_dir)
        write_lock(root, lock)
        print(f"bootstrap: fetched {UPSTREAM_REPOSITORY}@{UPSTREAM_COMMIT[:12]} "
              f"({len(staged_snapshot)} files); wrote {LOCK_FILE_NAME}")
        return 0

    upstream = existing_lock.get("upstream")
    if not isinstance(upstream, dict) or upstream.get("commit") != UPSTREAM_COMMIT:
        shutil.rmtree(staging, ignore_errors=True)
        print("bootstrap: existing lock commit mismatch; refusing to overwrite lock", file=sys.stderr)
        return 1

    staged = {entry["path"]: entry["sha256"] for entry in staged_snapshot}
    expected = {entry["path"]: entry["sha256"] for entry in upstream.get("files", [])}  # type: ignore[union-attr]
    if staged != expected:
        shutil.rmtree(staging, ignore_errors=True)
        diffs = [path for path in sorted(set(staged) | set(expected))
                 if staged.get(path) != expected.get(path)]
        preview = "; ".join(diffs[:5]) + (" ..." if len(diffs) > 5 else "")
        print(f"bootstrap: downloaded archive does not match locked digests "
              f"({len(diffs)} files differ: {preview})", file=sys.stderr)
        return 1

    if vendor_dir.is_dir():
        # vendor 已存在：绝不覆盖本地未知修改；仅提示漂移，由 --verify-only 点名
        local = {entry["path"]: entry["sha256"] for entry in snapshot_tree(vendor_dir)}
        drift = [path for path in sorted(set(local) | set(expected))
                 if local.get(path) != expected.get(path)]
        shutil.rmtree(staging, ignore_errors=True)
        if drift:
            print(f"bootstrap: vendor has local modifications ({len(drift)} files); left untouched")
        else:
            print(f"bootstrap: vendor already at {UPSTREAM_COMMIT[:12]}; nothing to do")
        return 0

    staging.rename(vendor_dir)
    print(f"bootstrap: installed pinned source into {VENDOR_DIR}/{VENDOR_PACKAGE_DIR} "
          f"({len(staged_snapshot)} files)")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="grassland digital-human stage-A bootstrap")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--fetch", action="store_true", help="download pinned upstream source only")
    modes.add_argument("--verify-only", action="store_true", help="read-only consistency check")
    modes.add_argument("--resolve-lock", action="store_true", help="run uv lock and record digests")
    args = parser.parse_args(argv)

    root = PROJECT_ROOT

    if args.fetch:
        try:
            return _fetch(root)
        except RuntimeError as error:
            print(f"bootstrap: fetch FAILED: {error}", file=sys.stderr)
            return 1

    if args.verify_only:
        lock_path = root / LOCK_FILE_NAME
        if not lock_path.is_file():
            print(f"bootstrap: {LOCK_FILE_NAME} not found; run --fetch first", file=sys.stderr)
            return 1
        problems = verify_source(root, load_lock(root))
        if problems:
            for problem in problems:
                print(f"bootstrap: DRIFT {problem}", file=sys.stderr)
            print(f"bootstrap: verify FAILED with {len(problems)} problem(s)", file=sys.stderr)
            return 1
        file_count = len(load_lock(root)["upstream"]["files"])  # type: ignore[index]
        print(f"bootstrap: verify OK (upstream files={file_count}, commit={UPSTREAM_COMMIT[:12]})")
        return 0

    if args.resolve_lock:
        code = _run_uv_lock(root)
        if code != 0:
            return code
        lock = load_lock(root)
        lock["dependencyLockSha256"] = sha256_file(root / DEP_LOCK_NAME)
        lock["dependencies"] = _dependency_inventory(root)
        write_lock(root, lock)
        print(f"bootstrap: dependency lock sha256 recorded "
              f"({len(lock['dependencies'])} packages)")  # type: ignore[arg-type]
        return 0

    parser.error("exactly one mode is required")  # pragma: no cover
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
