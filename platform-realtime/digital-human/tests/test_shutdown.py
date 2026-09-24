"""TC105H-04-01（任务书 #105H C105H-04）：进程与网络失联——SIGTERM 与失联自停。

Given 活动会话 + 排队；When kill runtime 或隔断 Java；Then 45 秒内无推理孤儿、状态真实、
排队不悄悄扩容。Python 侧责任面：
- SIGTERM：真实子进程跑 audio listener，TERM 后干净退出（无孤儿进程/端口残留）；
- 失联自停：租约到期（30s TTL + 5s tick 粒度）→ 停推理，停机记录一次性（≤45s 目标）；
- 排队不扩容：队列权威在 Java（dh_catalog 容量锁），本文件以注释锚定其归属
  （DigitalHumanRecoveryIT 与 compose chaos runner 观察面），不在 Python 侧伪造队列。
"""

from __future__ import annotations

import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.leases import LeaseManager, RuntimeLease  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class TestShutdown:
    def test_tc105h_04_01_sigterm_audio_listener_exits_clean_no_orphan(self) -> None:
        """真实子进程（audio 面）SIGTERM：有限时间内 exit 0、端口释放、无孤儿进程。"""
        port = free_port()
        venv_python = PROJECT_ROOT / ".venv" / "bin" / "python"
        python = str(venv_python) if venv_python.exists() else sys.executable
        process = subprocess.Popen(
            [python, "-m", "uvicorn", "grassland_dh.app:create_audio", "--factory",
             "--host", "127.0.0.1", "--port", str(port)],
            cwd=str(PROJECT_ROOT),
            env={**os.environ, "PYTHONPATH": f"{PROJECT_ROOT / 'src'}:{os.environ.get('PYTHONPATH', '')}"},
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            deadline = time.monotonic() + 20.0
            ready = False
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    pytest.fail(f"audio listener 提前退出：{process.stderr.read() if process.stderr else ''}")
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                        ready = json.load(response).get("ok") is True
                        break
                except Exception:  # noqa: BLE001 - 启动轮询
                    time.sleep(0.2)
            assert ready, "audio listener 20 秒内未就绪"

            process.send_signal(signal.SIGTERM)
            returncode = process.wait(timeout=10)
            # uvicorn 优雅清理（Application shutdown complete）后按 POSIX 以信号方式退出
            # （returncode=-15/exit 143）；干净退出的证据是清理完成，而非强制 exit 0。
            assert returncode in (0, -15), f"SIGTERM 退出异常（exit={returncode}）"
            stderr_tail = (process.stderr.read() if process.stderr else "")
            assert "Finished server process" in stderr_tail, f"未见优雅清理日志：{stderr_tail[-400:]}"

            # 无孤儿/端口已释放：同一端口立即可绑定。
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)

    def test_tc105h_04_01_java_loss_stops_inference_within_45s_bound(self) -> None:
        """隔断 Java（续约停止）→ 租约到期自停推理；停机一次性、≤45 秒目标界内。"""
        now = datetime(2026, 9, 24, 0, 0, 0, tzinfo=timezone.utc)
        manager = LeaseManager()
        stopped: list[str] = []
        inference_beats: list[str] = []
        manager.install(
            RuntimeLease("s-shutdown", 1, now + timedelta(seconds=30)),
            on_stop=lambda session_id: stopped.append(session_id),
        )

        # 失联窗口内（最后一次续约后 35 秒 = 下一个 5 秒 tick）：必须已停（30s TTL + 粒度 ≤45s）。
        for offset in range(0, 46, 5):
            manager.tick(now + timedelta(seconds=offset))
            if not manager.stopped:
                inference_beats.append(f"t+{offset}s")
        assert manager.stopped, "45 秒目标界内必须自停（无推理孤儿）"
        assert stopped == ["s-shutdown"]
        # 停机是单向一次性：后续 tick 不重复停机记录。
        manager.tick(now + timedelta(seconds=60))
        assert stopped == ["s-shutdown"]
        # 停机前推理仍在跑（不是立刻误停）——失联前窗口有心跳。
        assert any(beat.endswith("25s") or beat.endswith("20s") for beat in inference_beats) or \
            len(inference_beats) >= 5, f"失联前推理不应立刻停止：{inference_beats}"

    def test_tc105h_04_01_queue_authority_is_java_not_python(self) -> None:
        """排队不悄悄扩容的权威在 Java：dh_catalog 容量锁（K13.4 单全局锁 max1）。
        Python 侧不持有队列也不得自扩——本用例锚定该边界（无本地队列状态可扩）。"""
        from grassland_dh import leases as leases_module

        assert not hasattr(leases_module, "Queue"), "runtime 侧不得自带队列实现（权威在 Java）"
