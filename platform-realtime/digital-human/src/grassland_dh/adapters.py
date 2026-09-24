"""受控 wrapper：固定上游适配入口（任务书 #105A-03）。

- ensure_runtime_overlay()：以 vendor 干净源为基线全量拷贝到 vendor/.runtime-overlay，
  再应用 patches/0001-injected-runtime-bindings.patch（受限适配：每会话 Binding 注入、
  agent/memory/knowledge 强制关闭、peer 断连交由 adapter lease、内容日志去除）。
  vendor 本体永不修改（--verify-only 始终对干净源）。
- import_runtime()：把 overlay 根插入 sys.path 头部（opentalking 以 overlay 优先解析）。
- RunnerAdapter.create(binding) -> RuntimeSession：直接持有上游 FlashTalkRunner，
  以 Fake LLM/TTS/Renderer 组装全链，绝不启动 apps.api.main。
"""

from __future__ import annotations

import importlib
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from grassland_dh.media import ProgramAdapter

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
VENDOR_PACKAGE = PROJECT_ROOT / "vendor" / "opentalking"
OVERLAY_ROOT = PROJECT_ROOT / "vendor" / ".runtime-overlay"
PATCH_FILES = (
    PROJECT_ROOT / "patches" / "0001-injected-runtime-bindings.patch",
    PROJECT_ROOT / "patches" / "0002-media-epoch-and-output-hooks.patch",
)
OVERLAY_MARKER = OVERLAY_ROOT / ".grassland-patch-sha"
PATCHED_FILES = (
    "opentalking/runtime/task_consumer.py",
    "opentalking/pipeline/speak/synthesis_runner.py",
    "opentalking/runtime/timing.py",
    "opentalking/pipeline/session/runner.py",
)

_runtime_imported = False


# ---------------------------------------------------------------------------
# 受限 unified-diff 应用器（仅支持本仓库补丁生成器产出的标准上下文格式）
# ---------------------------------------------------------------------------


def apply_unified_diff(original: str, patch_text: str) -> str:
    lines = original.splitlines(keepends=True)
    out: list[str] = list(lines)
    offset = 0
    hunk_header: bool = False
    hunk_lines: list[str] = []
    hunks: list[tuple[int, int, list[str]]] = []

    for raw in patch_text.splitlines(keepends=True):
        if raw.startswith("--- ") or raw.startswith("+++ "):
            continue
        if raw.startswith("@@"):
            if hunk_lines:
                hunks.append((hunk_header_start, hunk_header_count, hunk_lines))  # type: ignore[misc]
            body = raw.strip().split("@@", 2)[1]
            old_part = body.split("+")[0].strip().lstrip("-")
            start = int(old_part.split(",")[0])
            count = int(old_part.split(",")[1]) if "," in old_part else 1
            hunk_header_start, hunk_header_count = start, count
            hunk_lines = []
            hunk_header = True
            continue
        if hunk_header:
            hunk_lines.append(raw)
    if hunk_lines:
        hunks.append((hunk_header_start, hunk_header_count, hunk_lines))  # type: ignore[misc]

    for start, count, hunk in hunks:
        pos = offset + start - 1
        consumed = 0
        produced: list[str] = []
        for line in hunk:
            if line.startswith("\n") or line == "":
                produced.append("\n")
                consumed += 1
                continue
            tag, content = line[0], line[1:]
            if tag == "-":
                actual = out[pos + consumed] if pos + consumed < len(out) else ""
                if actual.rstrip("\n") != content.rstrip("\n"):
                    raise RuntimeError(
                        f"patch context mismatch at line {pos + consumed + 1}: "
                        f"expected {content.rstrip()!r}, got {actual.rstrip()!r}"
                    )
                consumed += 1
            elif tag == "+":
                produced.append(content if content.endswith("\n") else content + "\n")
            else:  # 上下文行
                actual = out[pos + consumed] if pos + consumed < len(out) else ""
                if actual.rstrip("\n") != content.rstrip("\n"):
                    raise RuntimeError(
                        f"patch context mismatch at line {pos + consumed + 1}: "
                        f"expected {content.rstrip()!r}, got {actual.rstrip()!r}"
                    )
                produced.append(actual)
                consumed += 1
        out[pos:pos + consumed] = produced
        offset += len(produced) - consumed
    return "".join(out)


def _sha256(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure_runtime_overlay(force: bool = False) -> Path:
    """构建/复用运行时 overlay；vendor 干净源不可变。幂等：补丁链合并 sha 不变则跳过。

    补丁按声明顺序依次应用（0002 的上下文基于 0001 的产物）；任何一段上下文失配即中止——
    不带病运行（上游漂移在 CI 的 --verify-only 与此处双重拦截）。
    """
    patch_shas = [_sha256(path) for path in PATCH_FILES]
    combined = "|".join(patch_shas)
    if not force and OVERLAY_MARKER.is_file() and OVERLAY_MARKER.read_text().strip() == combined:
        return OVERLAY_ROOT
    if not VENDOR_PACKAGE.is_dir():
        raise RuntimeError(f"vendor 源码缺失：{VENDOR_PACKAGE}（先运行 bootstrap --fetch）")
    if OVERLAY_ROOT.exists():
        shutil.rmtree(OVERLAY_ROOT)
    # 只拷贝 opentalking 包本体（apps/web 等上游应用不入运行时 overlay）
    shutil.copytree(VENDOR_PACKAGE / "opentalking", OVERLAY_ROOT / "opentalking")
    for patch_path in PATCH_FILES:
        patch_text = patch_path.read_text(encoding="utf-8")
        by_file: dict[str, str] = {}
        current: str | None = None
        for raw in patch_text.splitlines(keepends=True):
            if raw.startswith("--- a/"):
                current = raw[len("--- a/"):].rstrip("\n")
                by_file.setdefault(current, "")
            elif raw.startswith("+++ b/"):
                continue
            elif current is not None:
                by_file[current] += raw
        for rel, file_patch in by_file.items():
            target = OVERLAY_ROOT / rel
            original = target.read_text(encoding="utf-8")
            target.write_text(apply_unified_diff(original, file_patch), encoding="utf-8")
    OVERLAY_MARKER.parent.mkdir(parents=True, exist_ok=True)
    OVERLAY_MARKER.write_text(combined)
    return OVERLAY_ROOT


def import_runtime() -> None:
    """把 overlay 插到 sys.path 最前（幂等）；opentalking 全部自 overlay 解析。"""
    global _runtime_imported
    root = str(ensure_runtime_overlay())
    if root not in sys.path:
        sys.path.insert(0, root)
    _runtime_imported = True


def runtime_module(name: str) -> Any:
    import_runtime()
    return importlib.import_module(name)


# ---------------------------------------------------------------------------
# RunnerAdapter / RuntimeSession
# ---------------------------------------------------------------------------


@dataclass
class TurnArtifacts:
    turn_id: str
    turn_epoch: int
    status: str = "accepted"  # accepted/speaking/completed/interrupted/failed
    pcm_parts: list[Any] = field(default_factory=list)
    video_frames: list[Any] = field(default_factory=list)
    subtitle_texts: list[str] = field(default_factory=list)
    usage: dict[str, Any] = field(default_factory=dict)


class RuntimeSession:
    """包装单个上游 FlashTalkRunner：媒体产物收集、租约/代次校验、peer 重建。"""

    def __init__(self, binding: Any, persona_text: str, avatars_root: Path) -> None:
        from grassland_dh.fakes import (
            FakeLlm, FakeRenderer, FakeRuntimeBus, FakeSpeech, FakeTts, make_test_reference_png,
        )

        self.binding = binding
        self.persona_text = persona_text
        self.bus = FakeRuntimeBus()
        self.renderer = FakeRenderer()
        self.llm = FakeLlm(persona_seed=f"{binding.session_id}:{binding.profile_revision}")
        self.speech = FakeSpeech()
        self._last_tts: Any = None
        self.artifacts: dict[str, TurnArtifacts] = {}
        self.active_turn: TurnArtifacts | None = None
        self.media_epoch = binding.media_epoch
        self.peer_close_states: list[str] = []
        self.closed = False

        def _tts_factory(**kw: Any) -> Any:
            tts = FakeTts(
                sample_rate=kw.get("sample_rate", 16000), chunk_ms=kw.get("chunk_ms", 400.0),
                default_voice=kw.get("default_voice"),
            )
            self._last_tts = tts
            return tts

        self.tts_factory = _tts_factory

        avatar_dir = avatars_root / "fake-avatar"
        avatar_dir.mkdir(parents=True, exist_ok=True)
        ref = avatar_dir / "reference.png"
        if not ref.exists():
            make_test_reference_png(ref)

        runner_cls = runtime_module("opentalking.pipeline.speak.synthesis_runner").FlashTalkRunner
        self.runner = runner_cls(
            session_id=binding.session_id,
            avatar_id="fake-avatar",
            avatars_root=avatars_root,
            redis=self.bus,
            audio2video_client=self.renderer,
            llm_base_url="",
            llm_api_key="",
            llm_model="grassland-fake-llm",
            system_prompt=persona_text,
            model_type="mock",
            agent_enabled=False,
            memory_enabled=False,
            knowledge_enabled=False,
        )
        # 程序化注入（补丁提供的钩子，不走任何全局 env/密钥）
        self.runner.llm = self.llm
        self.runner._grassland_tts_factory = self.tts_factory
        self.runner._grassland_peer_closed = self._on_peer_closed
        self.runner._grassland_media_gate = self._media_gate
        self.program = ProgramAdapter(session_id=binding.session_id, lease_epoch=binding.lease_epoch,
                                      media_epoch=binding.media_epoch)
        self._prepared = False

    async def prepare(self) -> None:
        if not self._prepared:
            await self.runner.prepare()
            self._prepared = True

    # ---- K04/K07 代次校验 -------------------------------------------------

    def _check_lease(self, lease_epoch: int) -> None:
        if lease_epoch != self.binding.lease_epoch:
            raise PermissionError(
                f"stale lease epoch {lease_epoch} != {self.binding.lease_epoch} (dh_lease_stale)"
            )

    # ---- 媒体产物收集 -----------------------------------------------------

    async def _drain_while(self, artifacts: TurnArtifacts, task: Any) -> None:
        import asyncio

        webrtc = self.runner.webrtc
        while not task.done():
            await asyncio.sleep(0)
            self._drain_queues(artifacts)
        await asyncio.wait_for(task, timeout=30.0)
        self._drain_queues(artifacts)

    def _drain_queues(self, artifacts: TurnArtifacts) -> None:
        webrtc = self.runner.webrtc
        if not webrtc:
            return
        while True:
            try:
                artifacts.video_frames.append(webrtc.video._queue.get_nowait())
            except Exception:
                break
        while True:
            try:
                artifacts.pcm_parts.append(webrtc.audio._queue.get_nowait())
            except Exception:
                break

    # ---- 对外动作 ---------------------------------------------------------

    async def start_turn(self, turn: Any, text: str) -> TurnArtifacts:
        import asyncio

        if self.closed:
            raise RuntimeError("session closed")
        self._check_lease(turn.lease_epoch)
        if turn.media_epoch != self.media_epoch:
            raise PermissionError(
                f"stale media epoch {turn.media_epoch} != {self.media_epoch} (media.reset pending)"
            )
        await self.prepare()
        subtitles_before = len(self.bus.events_of("subtitle.chunk"))
        artifacts = TurnArtifacts(turn_id=turn.turn_id, turn_epoch=turn.turn_epoch, status="speaking")
        self.artifacts[turn.turn_id] = artifacts
        self.active_turn = artifacts
        try:
            speak_task = asyncio.create_task(self.runner.speak(text))
            await self._drain_while(artifacts, speak_task)
            artifacts.status = "interrupted" if self.runner._interrupt.is_set() else "completed"
        except Exception:
            artifacts.status = "failed"
            raise
        finally:
            for event in self.bus.events_of("subtitle.chunk")[subtitles_before:]:
                payload = event.get("data", {})
                text_value = payload.get("text")
                if text_value:
                    artifacts.subtitle_texts.append(str(text_value))
            artifacts.usage = self.collect_usage()
            self.active_turn = None
        return artifacts

    async def start_audio_turn(self, turn: Any, pcm: bytes) -> TurnArtifacts:
        """首期口径：按键提交后内存整段转写（Fake STT），再走同一 speak 管线。"""
        text = self.speech.transcribe(pcm)
        artifacts = await self.start_turn(turn, text)
        artifacts.usage["stt"] = self.speech.usage(pcm)
        return artifacts

    async def interrupt(self, turn_epoch: int) -> bool:
        """打断：旧 turn 输出归零；新 turn 必须用更大 epoch（K04）。"""
        target = self.active_turn or next(
            (a for a in reversed(list(self.artifacts.values())) if a.status == "speaking"), None
        )
        await self.runner.interrupt()
        if target is not None:
            if turn_epoch < target.turn_epoch:
                return False  # 旧代次不得打断新轮
            target.status = "interrupted"
            target.pcm_parts.clear()
            target.video_frames.clear()
            target.subtitle_texts.clear()
        return True

    async def playback_reset(self) -> int:
        """主动换 peer：旧队列丢弃、media_epoch+1；session 保留可重协商（时钟与 Binding 不归零）。"""
        self.media_epoch = await self.program.reset_media(self.media_epoch + 1)
        webrtc = self.runner.webrtc
        if webrtc:
            webrtc.clear_media_queues()
        return self.media_epoch

    async def _on_peer_closed(self, state: str) -> None:
        """补丁钩子：原生断连自动 close 交由 wrapper；此处仅记录并保留 session。"""
        self.peer_close_states.append(state)
        self.media_epoch += 1

    def _media_gate(self, state: str) -> bool:
        """补丁 0002 钩子：瞬时 disconnected 不关业务 session（租约决定）；持续 failed 才收尾。"""
        return self.program.note_disconnect(state)

    async def close(self) -> None:
        if not self.closed:
            self.closed = True
            await self.runner.close()

    # ---- 用量 -------------------------------------------------------------

    def collect_usage(self) -> dict[str, Any]:
        usage: dict[str, Any] = {
            "llm": dict(self.llm.usage()),
            "render": self.renderer.usage(),
        }
        tts = getattr(self, "_last_tts", None)
        if tts is not None:
            usage["tts"] = tts.usage()
        return usage


class RunnerAdapter:
    """固定上游适配入口：test_mode 下全 Fake；生产路径须 DH_ENABLED 且 approved backend。"""

    def __init__(self, test_mode: bool = False, avatars_root: Path | None = None) -> None:
        self.test_mode = test_mode
        self._avatars_root = avatars_root

    async def create(self, binding: Any, persona_text: str = "") -> RuntimeSession:
        if not self.test_mode:
            # 生产默认关闭（K10 DH_ENABLED=false）；真实后端属 C/D/H 阶段合同
            import os

            if os.environ.get("DH_ENABLED", "false").lower() != "true":
                raise RuntimeError("dh_runtime_unavailable: DH_ENABLED=false（生产路径未开放）")
        import tempfile

        root = self._avatars_root or Path(tempfile.mkdtemp(prefix="dh-avatars-"))
        persona = persona_text or f"你是草场合成数字人（persona rev={binding.profile_revision}）。"
        session = RuntimeSession(binding, persona, root)
        await session.prepare()
        return session
