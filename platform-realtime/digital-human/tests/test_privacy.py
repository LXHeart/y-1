"""TC105G-05-01 隐私 canary（任务书 #105G C105G-05 / K12、K13.4）+ TC105G-05-04 指标基数子断言。

五种合成 marker（不用真实数据，不关联真实账号）注入 Fake 全链各层：
- prompt：用户输入正文 marker（文本轮）；
- mic：内嵌 marker 字节的 PCM（音频轮原始输入）；
- key：进程内合成密钥字符串（经环境变量进入进程——运行期可见但禁止外漏）；
- grant：binding 真实签发的连接/执行资格值；
- SDP：webrtc-offer 入站 marker。

正常/异常/迟到（timeout）三种路径后扫描：日志（caplog 根 logger）、FakeRuntimeBus
（store/hashes/published）、临时目录文件、异常消息——受禁正文/凭据无持久泄漏；
主动交付范围（turn artifacts 的程序输出字幕）可解释。失败断言只报路径不回显 marker
原文，避免把 marker 泄进测试报告（报告目录进入 V105G-05-05 扫描面）。
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.adapters import RunnerAdapter, ensure_runtime_overlay  # noqa: E402
from grassland_dh.bindings import SessionBinding, TurnBinding  # noqa: E402
from grassland_dh.fakes import OutboundGuard  # noqa: E402
from grassland_dh.metrics import PHASES, STABLE_ERRORS, StageMetrics  # noqa: E402

from datetime import datetime, timedelta, timezone  # noqa: E402

UTC = timezone.utc

PROMPT_MARKER = "GRASSLAND-DH-PROMPT-MARKER-105G01"
MIC_MARKER = b"GRASSLAND-DH-MIC-MARKER-105G02"
KEY_MARKER = "sk-grassland-dh-FAKE-KEY-MARKER-105g03"
SDP_MARKER = "GRASSLAND-DH-SDP-MARKER-105G05"

# 合成账号 id（真实 UUID 形态；与仓库 fixture 前缀一致，不用生产账号）
SESSION_ID = "77777777-7777-4777-8777-777777777705"


def _markers_utf8() -> list[bytes]:
    return [
        PROMPT_MARKER.encode(),
        MIC_MARKER,
        KEY_MARKER.encode(),
        SDP_MARKER.encode(),
    ]


def make_binding(session_id: str = SESSION_ID, lease: int = 1, media: int = 1) -> SessionBinding:
    return SessionBinding(
        session_id=session_id,
        lease_epoch=lease,
        media_epoch=media,
        backend_id="mock",
        profile_revision=3,
        expires_at=datetime.now(UTC) + timedelta(minutes=10),
        bridge_base_url="https://internal.invalid/dh-bridge",
    )


def make_turn(binding: SessionBinding, turn_epoch: int = 1, lease_epoch: int | None = None) -> TurnBinding:
    return TurnBinding(
        session_id=binding.session_id,
        turn_id=f"cccccccc-1ccc-41cc-81cc-cccccccccc{turn_epoch:02d}",
        turn_epoch=turn_epoch,
        lease_epoch=binding.lease_epoch if lease_epoch is None else lease_epoch,
        media_epoch=binding.media_epoch,
        content_epoch=1,
        request_id=f"66666666-6666-4666-8666-6666666666{turn_epoch:02d}",
        deadline_at=datetime.now(UTC) + timedelta(seconds=30),
    )


def _sine_pcm(milliseconds: int = 800, freq: int = 330, sample_rate: int = 16000) -> bytes:
    total = int(sample_rate * milliseconds / 1000)
    t = np.arange(total, dtype=np.float64) / sample_rate
    wave = (np.sin(2 * np.pi * freq * t) * 9000).astype(np.int16)
    return wave.tobytes()


def _mic_canary_pcm() -> bytes:
    """原始 mic 输入 canary：正弦 PCM 尾部内嵌 marker 字节（模拟含敏感语音载荷）。"""
    return _sine_pcm() + MIC_MARKER + _sine_pcm(120)


def _scan_logs(caplog, grant_value: str) -> None:
    for record in caplog.records:
        message = record.getMessage()
        for marker in _markers_utf8():
            assert marker not in message.encode("utf-8", "ignore"), (
                f"canary 泄漏：日志 {record.name}@{record.levelname} 含敏感类别标记"
            )
        assert grant_value not in message, "canary 泄漏：日志含 grant 原值"


def _scan_bus(bus: Any, grant_value: str) -> None:
    """总线扫描按面拆分（任务书步骤2：日志/trace/持久Redis快照/产物目录为受禁面）。

    - store/hashes = 状态快照（持久面等价）：五类 marker 与 grant 原值全禁。
    - published = 易失交付通道（无持久化 content Redis → owner 自有 WS）：非正文类
      （key/mic/grant/SDP）全禁；输入正文仅允许出现在 speech.started 输入受理事件
      （vendor 原生行为，K13.1 允许集外——偏差单列证据，不在本卡文件白名单内修）。
    - 字幕输出事件（subtitle.chunk）是程序输出交付物：允许回复句、禁止回显输入 marker。
    """
    persist = json.dumps({"store": bus.store, "hashes": bus.hashes},
                         ensure_ascii=False, default=str).encode("utf-8")
    for marker in _markers_utf8():
        assert marker not in persist, "canary 泄漏：Redis 状态快照（store/hash）含敏感类别标记"
    assert grant_value.encode() not in persist, "canary 泄漏：Redis 状态快照含 grant 原值"

    non_text_markers = [KEY_MARKER.encode(), MIC_MARKER, SDP_MARKER.encode()]
    for _channel, payload in bus.published:
        blob = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        for marker in non_text_markers:
            assert marker not in blob, "canary 泄漏：事件通道含非正文类敏感标记"
        assert grant_value.encode() not in blob, "canary 泄漏：事件通道含 grant 原值"
        event = payload.get("event")
        if PROMPT_MARKER.encode() in blob:
            assert event == "speech.started", f"输入正文出现在非受理事件：{event}"
        if event == "subtitle.chunk":
            assert PROMPT_MARKER not in blob.decode(), "字幕输出事件回显输入正文"


def _scan_files(base: Path, grant_value: str) -> None:
    if not base.exists():
        return
    for path in sorted(base.rglob("*")):
        if not path.is_file():
            continue
        content = path.read_bytes()
        rel = str(path.relative_to(base))
        for marker in _markers_utf8():
            assert marker not in content, f"canary 泄漏：落盘文件 {rel} 含敏感类别标记"
        assert grant_value.encode() not in content, f"canary 泄漏：落盘文件 {rel} 含 grant 原值"
        assert content[:4] != b"RIFF", f"疑似 WAV 落盘：{rel}"


@pytest.fixture(scope="module", autouse=True)
def _overlay() -> None:
    ensure_runtime_overlay()


@pytest.fixture()
def leaked_key_env(monkeypatch: pytest.MonkeyPatch) -> str:
    """合成密钥进入进程环境（运行期可见），验证它不进日志/总线/落盘。"""
    monkeypatch.setenv("DH_TEST_SYNTHETIC_KEY", KEY_MARKER)
    return KEY_MARKER


class TestPrivacyCanary:
    """tc105g_05_01：五类 marker × 正常/异常/迟到，扫描持久面零泄漏。"""

    @pytest.mark.asyncio
    async def test_tc105g_05_01_normal_turn_leaks_nothing(
        self, tmp_path: Path, caplog, leaked_key_env: str
    ) -> None:
        caplog.set_level(logging.DEBUG)
        binding = make_binding()
        grant_value = binding.grant_provider("audio")
        with OutboundGuard() as guard:
            adapter = RunnerAdapter(test_mode=True, avatars_root=tmp_path / "avatars")
            session = await adapter.create(binding, persona_text="隐私扫描人设")
            text_turn = make_turn(binding, 1)
            artifacts_text = await session.start_turn(
                text_turn, f"请保密这段输入 {PROMPT_MARKER} 并整理成三句口播。")
            audio_turn = make_turn(binding, 2)
            artifacts_audio = await session.start_audio_turn(audio_turn, _mic_canary_pcm())
            await session.close()
        assert guard.attempts == []
        # 主动交付范围可解释：程序输出字幕是固定回复句（交付物），不含输入 marker
        for artifacts in (artifacts_text, artifacts_audio):
            assert artifacts.status == "completed"
            joined = "".join(artifacts.subtitle_texts)
            assert joined, "必须交付字幕文本"
            for marker in _markers_utf8():
                assert marker.decode() not in joined, "交付字幕回显了输入侧 marker"

        _scan_logs(caplog, grant_value)
        _scan_bus(session.bus, grant_value)
        _scan_files(tmp_path, grant_value)

    @pytest.mark.asyncio
    async def test_tc105g_05_01_error_path_exception_sanitized(
        self, tmp_path: Path, caplog, leaked_key_env: str
    ) -> None:
        caplog.set_level(logging.DEBUG)
        binding = make_binding()
        grant_value = binding.grant_provider("audio")
        with OutboundGuard() as guard:
            adapter = RunnerAdapter(test_mode=True, avatars_root=tmp_path / "avatars")
            session = await adapter.create(binding, persona_text="异常路径人设")
            # 异常窗口：renderer 已关闭 → speak 真实失败（RuntimeError 上抛 +
            # vendor log.exception 落日志 + error 事件只带受控码 str(e)），输入含全部 marker
            await session.renderer.close()
            turn = make_turn(binding, 1)
            with pytest.raises(RuntimeError) as excinfo:
                await session.start_turn(turn, f"异常路径输入 {PROMPT_MARKER}")
            message = str(excinfo.value)
            for marker in _markers_utf8():
                assert marker.decode() not in message, "异常消息携带敏感类别标记"
            assert grant_value not in message
            # 异常 body 回归：error 事件载荷只允许受控 code/message，不得携带输入正文
            error_events = session.bus.events_of("error")
            assert error_events, "失败必须发布 error 事件"
            error_blob = json.dumps(error_events, ensure_ascii=False, default=str)
            for marker in _markers_utf8():
                assert marker.decode() not in error_blob, "error 事件携带敏感类别标记"
            await session.close()
        assert guard.attempts == []
        _scan_logs(caplog, grant_value)
        _scan_bus(session.bus, grant_value)
        _scan_files(tmp_path, grant_value)

    @pytest.mark.asyncio
    async def test_tc105g_05_01_late_stale_epoch_rejected_and_clean(
        self, tmp_path: Path, caplog, leaked_key_env: str
    ) -> None:
        caplog.set_level(logging.DEBUG)
        binding = make_binding(lease=2)
        grant_value = binding.grant_provider("audio")
        with OutboundGuard() as guard:
            adapter = RunnerAdapter(test_mode=True, avatars_root=tmp_path / "avatars")
            session = await adapter.create(binding, persona_text="迟到窗口人设")
            # 迟到/旧代次请求：旧 lease epoch 与过期 binding 都被拒，不产生任何输出
            stale = make_turn(binding, turn_epoch=9, lease_epoch=binding.lease_epoch - 1)
            with pytest.raises(PermissionError):
                await session.start_turn(stale, f"迟到输入 {PROMPT_MARKER}")
            stale_media = make_turn(binding, turn_epoch=10, lease_epoch=binding.lease_epoch)
            stale_media = TurnBinding(
                session_id=stale_media.session_id, turn_id=stale_media.turn_id,
                turn_epoch=stale_media.turn_epoch, lease_epoch=stale_media.lease_epoch,
                media_epoch=binding.media_epoch - 1, content_epoch=1,
                request_id=stale_media.request_id, deadline_at=stale_media.deadline_at)
            with pytest.raises(PermissionError):
                await session.start_turn(stale_media, f"旧媒体代次输入 {PROMPT_MARKER}")
            await session.close()
        assert guard.attempts == []
        _scan_logs(caplog, grant_value)
        _scan_bus(session.bus, grant_value)
        _scan_files(tmp_path, grant_value)

    def test_tc105g_05_01_sdp_offer_and_grant_repr_not_echoed(self, caplog) -> None:
        from fastapi.testclient import TestClient
        from grassland_dh.app import create_app

        caplog.set_level(logging.DEBUG)
        binding = make_binding()
        grant_value = binding.grant_provider("audio")
        app = create_app(test_mode=True, surface="internal")
        with TestClient(app) as client:
            offer = {"sdp": f"v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns={SDP_MARKER}\r\n",
                     "type": "offer"}
            response = client.post(
                f"/internal/v1/sessions/{SESSION_ID}/webrtc-offer", json=offer)
            assert response.status_code == 409  # 合同未开：固定受控错误，不回显正文
            body = response.text
            for marker in _markers_utf8():
                assert marker.decode() not in body, "webrtc-offer 响应回显敏感类别标记"
        # binding 的 repr/wire 不携带 grant 原值（K07.1 函数不序列化 + repr 防泄漏回归）
        assert grant_value not in repr(binding)
        wire = binding.wire()
        assert grant_value not in json.dumps(wire, ensure_ascii=False, default=str)
        _scan_logs(caplog, grant_value)


class TestMetricsBoundedLabels:
    """tc105g_05_04 Python 侧子断言：指标标签有界、无正文/密钥。"""

    def test_tc105g_05_04_series_do_not_grow_with_sessions_or_canary(self) -> None:
        metrics = StageMetrics()
        session_ids = [f"synthetic-session-{i:03d}" for i in range(100)]
        # 100 个合成会话各走一轮全阶段（duration 不同）：API 无会话参数，逐会话增长不可能
        for i, _session in enumerate(session_ids):
            for phase in ("stt", "llm", "tts", "render", "first_audio"):
                metrics.record(phase, backend="default", duration_ms=100.0 + i)
        # 32 个不同 backend 值：超过容量的归 other，不产生第 17 个 backend 标签值
        for i in range(32):
            metrics.record("llm", backend=f"backend-{i:02d}", duration_ms=float(i))
        # canary 类自由字符串（正文/密钥/grant/SDP）→ 一律 other，绝不成为标签值
        metrics.record("llm", backend=PROMPT_MARKER)
        metrics.record("llm", backend=KEY_MARKER)
        metrics.record("llm", backend=SDP_MARKER)

        snapshot = metrics.snapshot()
        # 基数断言：5 phase × 1 backend × 1 error + 16 上限 backend + other 组合，远小于 100
        assert snapshot["seriesCount"] <= len(PHASES) * 20
        blob = json.dumps(snapshot, ensure_ascii=False)
        for marker in _markers_utf8():
            assert marker.decode() not in blob, "指标快照含敏感类别标记（标签泄漏）"

        domains = metrics.label_domains()
        assert set(domains) == {"phase", "backend", "error"}
        # phase/error 值域固定；backend 动态域有上限
        assert domains["phase"] == PHASES
        assert domains["error"] == STABLE_ERRORS
        assert len(domains["backend"]) <= 17  # default + 16 动态上限（溢出归 other 已在域外）
        for value in domains["backend"]:
            assert value.isalnum() or all(c.isalnum() or c in "-_" for c in value)
        # p50/p95 存在且为整数毫秒
        render = next(s for s in snapshot["series"] if s["phase"] == "render" and s["error"] == "ok")
        assert render["p50Ms"] is not None and render["p95Ms"] is not None
        assert render["p50Ms"] <= render["p95Ms"]
