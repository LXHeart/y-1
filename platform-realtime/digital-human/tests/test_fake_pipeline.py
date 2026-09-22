"""TC105A-03-01/02/04：全链 Fake 管线（#105A-03）。

外层网络以 OutboundGuard 全量拦截（socket.connect 非回环即拒并记录）；上游 runner、
Binding 代次校验、媒体对象、用量与清理全部走真实实现，不 mock 被测逻辑。
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, AsyncIterator

import numpy as np
import pytest

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.adapters import RunnerAdapter, ensure_runtime_overlay  # noqa: E402
from grassland_dh.bindings import SessionBinding, TurnBinding  # noqa: E402
from grassland_dh.fakes import FakeLlm, OutboundGuard  # noqa: E402

UTC = timezone.utc
SENSITIVE_MARKER = "GRASSLAND-SENSITIVE-MARKER-9527"


def make_binding(session_id: str, lease: int = 1, media: int = 1, revision: int = 3) -> SessionBinding:
    return SessionBinding(
        session_id=session_id,
        lease_epoch=lease,
        media_epoch=media,
        backend_id="mock",
        profile_revision=revision,
        expires_at=datetime.now(UTC) + timedelta(minutes=10),
        bridge_base_url="https://internal.invalid/dh-bridge",
    )


def make_turn(binding: SessionBinding, turn_epoch: int = 1, media_epoch: int | None = None,
              lease_epoch: int | None = None) -> TurnBinding:
    return TurnBinding(
        session_id=binding.session_id,
        turn_id=f"aaaaaaaa-1aaa-41aa-81aa-aaaaaaaaaa{turn_epoch:02d}",
        turn_epoch=turn_epoch,
        lease_epoch=binding.lease_epoch if lease_epoch is None else lease_epoch,
        media_epoch=binding.media_epoch if media_epoch is None else media_epoch,
        content_epoch=1,
        request_id=f"22222222-2222-4222-8222-2222222222{turn_epoch:02d}",
        deadline_at=datetime.now(UTC) + timedelta(seconds=30),
    )


class GatedFakeLlm(FakeLlm):
    """闸门 LLM：chat_stream 在闸门上等待，供打断时序确定性控制。"""

    def __init__(self, gate: asyncio.Event) -> None:
        super().__init__()
        self.gate = gate
        self.arrived = False

    async def chat_stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        self.arrived = True
        self.calls += 1
        self.last_messages = list(messages)
        self.input_tokens = max(1, sum(len(m.get("content", "")) for m in messages) // 2)
        await self.gate.wait()
        for sentence in self.reply_sentences:
            self.output_tokens += max(1, len(sentence) // 2)
            yield sentence


@pytest.fixture(scope="module", autouse=True)
def _overlay() -> None:
    ensure_runtime_overlay()


class TestFakePipeline:
    """tc105a_03_01/02/04：全链无外网、两会话隔离、peer 重建与内容日志。"""

    # ------------------------------------------------------------------
    # TC105A-03-01 全链无外网
    # ------------------------------------------------------------------

    @pytest.mark.parametrize("mode", ["text", "audio"])
    @pytest.mark.asyncio
    async def test_tc105a_03_01_full_pipeline_zero_outbound(
        self, tmp_path: Path, mode: str
    ) -> None:
        with OutboundGuard() as guard:
            adapter = RunnerAdapter(test_mode=True)
            binding = make_binding("55555555-5555-4555-8555-555555555555")
            session = await adapter.create(binding, persona_text="草场测试人设A")
            turn = make_turn(binding)
            if mode == "text":
                artifacts = await session.start_turn(turn, "帮我把产品卖点改成三句口播。")
            else:
                pcm = _sine_pcm(milliseconds=900)
                artifacts = await session.start_audio_turn(turn, pcm)
                assert artifacts.usage["stt"]["audioInputMs"] > 0
            # 副作用断言在 close 前收集完媒体对象后进行
            await session.close()

        # 真实媒体对象，不只 success JSON
        assert artifacts.status == "completed"
        assert artifacts.pcm_parts, "必须产出 PCM"
        pcm_all = np.concatenate([np.asarray(p, dtype=np.int16) for p in artifacts.pcm_parts])
        assert pcm_all.size >= 320 and int(np.abs(pcm_all).max()) > 1000, "PCM 必须非静音"
        assert artifacts.video_frames, "必须产出视频帧"
        arr = np.asarray(artifacts.video_frames[0].data)
        assert arr.shape == (256, 256, 3) and arr.dtype == np.uint8
        assert artifacts.subtitle_texts, "必须产出字幕事件文本"
        assert any("段" in t for t in artifacts.subtitle_texts)
        # 用量非零且 confirmed
        usage = artifacts.usage
        assert usage["llm"]["inputTokens"] > 0 and usage["llm"]["outputTokens"] > 0
        assert usage["tts"]["audioOutputMs"] > 0
        assert usage["render"]["renderMs"] > 0
        assert all(stage["quality"] == "confirmed" for stage in usage.values() if isinstance(stage, dict))
        # 外部请求计数 0（拦截器无任何尝试记录）
        assert guard.attempts == [], f"fake 全链不得发起任何 socket 连接：{guard.attempts}"
        # 原始 mic/PCM 不落盘：临时目录无音频类文件
        audio_like = [p for p in _new_files(tmp_path.parent) if p.suffix.lower() in {".wav", ".pcm", ".raw", ".mp4"}]
        assert not audio_like, f"原始音频落盘：{audio_like}"

    # ------------------------------------------------------------------
    # TC105A-03-02 两会话隔离（P0）
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_tc105a_03_02_two_sessions_isolated(self, tmp_path: Path) -> None:
        with OutboundGuard() as guard:
            adapter = RunnerAdapter(test_mode=True, avatars_root=tmp_path / "avatars")
            binding_a = make_binding("55555555-5555-4555-8555-555555555555", revision=3)
            binding_b = make_binding("88888888-8888-4888-8888-888888888888", revision=7)
            session_a = await adapter.create(binding_a, persona_text="人设A：口播教练")
            session_b = await adapter.create(binding_b, persona_text="人设B：品牌顾问")
            assert binding_a.grant_provider("audio") != binding_b.grant_provider("audio")

            # 交错：A 在闸门上停在生成中，B 完整跑一轮
            gate = asyncio.Event()
            gated_llm = GatedFakeLlm(gate)
            session_a.runner.llm = gated_llm
            task_a = asyncio.create_task(session_a.start_turn(make_turn(binding_a, 1), "A的第一轮内容。"))
            for _ in range(200):
                if gated_llm.arrived:
                    break
                await asyncio.sleep(0)
            assert gated_llm.arrived, "A 轮必须已进入生成"
            assert session_a.active_turn is not None

            artifacts_b = await session_b.start_turn(make_turn(binding_b, 1), "B的独立一轮。")
            b_frames = len(artifacts_b.video_frames)
            b_pcm = len(artifacts_b.pcm_parts)

            # A 取消：旧 turn 输出归零（打断在闸门释放前生效）
            assert await session_a.interrupt(turn_epoch=1) is True
            gate.set()
            artifacts_a = await task_a
            assert artifacts_a.status == "interrupted"
            assert artifacts_a.pcm_parts == [] and artifacts_a.video_frames == []
            assert artifacts_a.subtitle_texts == []

            # B 配置/音画不受 A 打断影响
            assert session_b.runner._closed is False
            assert len(artifacts_b.video_frames) == b_frames
            assert len(artifacts_b.pcm_parts) == b_pcm
            assert session_b.llm.calls == 1

            # prompt 不共享：两会话 system prompt 各自独立（A 侧读闸门 LLM 记录）
            msgs_a = gated_llm.last_messages
            msgs_b = session_b.llm.last_messages
            assert any("人设A" in m["content"] for m in msgs_a if m["role"] == "system")
            assert any("人设B" in m["content"] for m in msgs_b if m["role"] == "system")
            assert not any("人设B" in m["content"] for m in msgs_a)
            assert not any("人设A" in m["content"] for m in msgs_b)

            # 旧 lease epoch 迟到请求拒绝（K04）
            stale = make_turn(binding_b, turn_epoch=99, lease_epoch=binding_b.lease_epoch - 1,
                              media_epoch=session_b.media_epoch)
            with pytest.raises(PermissionError):
                await session_b.start_turn(stale, "旧租约迟到请求")

            await session_a.close()
            await session_b.close()
        assert guard.attempts == []

    # ------------------------------------------------------------------
    # TC105A-03-04 peer 重建与内容日志
    # ------------------------------------------------------------------

    @pytest.mark.asyncio
    async def test_tc105a_03_04_media_reset_and_no_content_logs(self, tmp_path: Path) -> None:
        marker = SENSITIVE_MARKER
        collector = _LogCollector()
        logging.getLogger().addHandler(collector)
        try:
            with OutboundGuard() as guard:
                adapter = RunnerAdapter(test_mode=True, avatars_root=tmp_path / "avatars")
                binding = make_binding("55555555-5555-4555-8555-555555555555")
                session = await adapter.create(binding, persona_text="草场内容脱敏人设")

                # 主动 media reset：旧 peer 丢弃、epoch+1，session 保留可重协商
                old_epoch = session.media_epoch
                new_epoch = await session.playback_reset()
                assert new_epoch == old_epoch + 1
                assert session.runner._closed is False

                # 原生断连事件经补丁交由 adapter lease：不终结 session
                await session.runner._grassland_peer_closed("disconnected")
                assert session.peer_close_states == ["disconnected"]
                assert session.runner._closed is False
                assert session.media_epoch == new_epoch + 1

                # 敏感标记入轮：日志/落盘均不得出现标记与音频
                turn = make_turn(binding, turn_epoch=1, media_epoch=session.media_epoch)
                artifacts = await session.start_turn(turn, f"请保密这串标记 {marker} 并整理成口播。")
                assert artifacts.status == "completed"
                await session.close()
            assert guard.attempts == []
        finally:
            logging.getLogger().removeHandler(collector)

        for record in collector.records:
            assert marker not in record.getMessage(), f"日志泄露标记：{record.getMessage()!r}"
        audio_like = [p for p in _new_files(tmp_path.parent)
                      if p.suffix.lower() in {".wav", ".pcm", ".raw", ".mp4", ".aac"}]
        assert not audio_like, f"音频/媒体落盘：{audio_like}"
        for path in _new_files(tmp_path.parent):
            if path.is_file() and path.stat().st_size < 64 * 1024:
                content = path.read_bytes()
                assert marker.encode() not in content, f"落盘文件含敏感标记：{path}"
                assert content[:4] != b"RIFF", f"疑似 WAV 落盘：{path}"


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------


class _LogCollector(logging.Handler):
    def __init__(self) -> None:
        super().__init__(level=logging.DEBUG)
        self.records: list[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


def _new_files(base: Path) -> list[Path]:
    """收尾时扫描临时树中的媒体类新文件（用例自身 avatars 根在 tmp 下，一并覆盖）。"""
    if not base.exists():
        return []
    return [p for p in base.rglob("*") if p.is_file()]


def _sine_pcm(milliseconds: int = 600, freq: int = 330, sample_rate: int = 16000) -> bytes:
    total = int(sample_rate * milliseconds / 1000)
    t = np.arange(total, dtype=np.float64) / sample_rate
    wave = (np.sin(2 * np.pi * freq * t) * 9000).astype(np.int16)
    return wave.tobytes()
