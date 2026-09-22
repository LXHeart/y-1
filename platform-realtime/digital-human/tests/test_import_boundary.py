"""TC105A-03-03：默认关闭危险面（#105A-03）。

wrapper 启动后：原生 /sessions、runtime-config、memory、outputs、knowledge 路由
全部 404；无自动 agent/tool 调用；import 无副作用（不触网、不引 vendor、不加载模型）。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

NATIVE_PATHS = [
    "/sessions",
    "/sessions/55555555-5555-4555-8555-555555555555",
    "/runtime-config",
    "/memory",
    "/outputs",
    "/knowledge",
    "/api/sessions",
    "/avatars/runtime-config",
]


class TestImportBoundary:
    """tc105a_03_03：危险面 404 / 无自动调用 / import 边界。"""

    def test_tc105a_03_03_import_no_side_effects(self) -> None:
        # 同进程可能已被管线测试导入 vendor——以全新子进程验证 import 零副作用
        import subprocess

        src_root = str(Path(__file__).resolve().parent.parent / "src")
        code = (
            "import sys, socket\n"
            f"sys.path.insert(0, {src_root!r})\n"
            "def _blocked(*a, **k):\n"
            "    raise AssertionError('import must not open sockets')\n"
            "socket.socket.connect = _blocked\n"
            "import grassland_dh, grassland_dh.app, grassland_dh.adapters, grassland_dh.fakes\n"
            "assert 'opentalking' not in sys.modules, 'vendor imported at module import'\n"
            "assert not any(m.startswith('apps.') for m in sys.modules), 'upstream apps imported'\n"
            "app = grassland_dh.app.create_app(test_mode=True)\n"
            "assert app.state.surface == 'internal'\n"
            "print('IMPORT_BOUNDARY_OK')\n"
        )
        result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, f"import 边界失败：{result.stderr}"
        assert "IMPORT_BOUNDARY_OK" in result.stdout

    def test_tc105a_03_03_native_routes_absent_on_both_surfaces(self) -> None:
        from fastapi.testclient import TestClient

        from grassland_dh.app import create_app

        for surface in ("internal", "audio"):
            app = create_app(test_mode=True, surface=surface)  # type: ignore[arg-type]
            registered = {getattr(r, "path", None) for r in app.routes}
            for native in NATIVE_PATHS:
                assert native not in registered, f"{surface} 面不得注册原生路由 {native}"
            client = TestClient(app)
            response = client.get("/health")
            assert response.status_code == 200
            body = response.json()
            assert body["ok"] is True and body["surface"] == surface
            for native in NATIVE_PATHS:
                assert client.get(native).status_code == 404, f"{surface} 面 {native} 必须 404"
                assert client.post(native, json={}).status_code == 404, f"{surface} 面 POST {native} 必须 404"

        # 生产默认（test_mode=False）同样不暴露；internal 写路径 503 未启用
        prod = create_app(test_mode=False, surface="internal")
        client = TestClient(prod)
        assert client.get("/health").json()["enabled"] is False
        resp = client.post("/internal/v1/sessions", json={"binding": {}})
        assert resp.status_code == 503 and resp.json()["code"] == "dh_runtime_unavailable"

        # audio 面 WS：A03 无 C/D 认证合同，唯一安全默认=4401 关闭（connect 即被服务端关闭）
        from starlette.websockets import WebSocketDisconnect

        audio = create_app(test_mode=True, surface="audio")
        with pytest.raises(WebSocketDisconnect) as closed:
            with TestClient(audio).websocket_connect(
                "/api/digital-human/sessions/55555555-5555-4555-8555-555555555555/audio"
            ):
                pass
        assert closed.value.code == 4401

    def test_tc105a_03_03_patched_factory_forces_agent_off_and_binds_llm(self, tmp_path: Path) -> None:
        """补丁链路实证：_create_runner 经 binding 注入，agent/memory/knowledge 强制关。"""
        from grassland_dh.adapters import ensure_runtime_overlay
        from grassland_dh.fakes import FakeRuntimeBus

        ensure_runtime_overlay()
        # 清除可能存在的旧导入，强制从 overlay 重新解析
        for name in [m for m in sys.modules if m.startswith("opentalking")]:
            del sys.modules[name]
        sys.path.insert(0, str(Path(ensure_runtime_overlay())))
        try:
            task_consumer = __import__(
                "opentalking.runtime.task_consumer", fromlist=["_create_runner"]
            )
            bus = FakeRuntimeBus()
            task = {
                "model": "mock",
                "session_id": "55555555-5555-4555-8555-555555555555",
                "avatar_id": "fake-avatar",
                "agent_enabled": True,   # 上游默认开（OT02）——草场必须强制关
                "memory_enabled": True,
                "knowledge_enabled": True,
                "llm_system_prompt": "上游全局人设",
            }
            task_consumer.set_grassland_binding_hook(lambda t: {
                "llm_base_url": "https://grassland-fake.invalid/llm",
                "llm_api_key": "",
                "llm_model": "grassland-fake-llm",
                "system_prompt": "草场每会话人设",
            })
            runner = task_consumer._create_runner(task, bus, tmp_path, "cpu")
            assert runner.agent_config.agent_enabled is False
            assert runner.agent_config.memory_enabled is False
            assert runner.agent_config.knowledge_enabled is False
            assert runner.llm.base_url == "https://grassland-fake.invalid/llm"
            assert "草场每会话人设" in runner.conversation.get_messages()[0]["content"]
            # 未设置 hook 时上游行为不变（原生路径不受影响）
            task_consumer.set_grassland_binding_hook(None)
            runner_native = task_consumer._create_runner(dict(task), bus, tmp_path, "cpu")
            assert runner_native.agent_config.agent_enabled is True
        finally:
            sys.path.remove(str(Path(ensure_runtime_overlay())))
            for name in [m for m in sys.modules if m.startswith("opentalking")]:
                del sys.modules[name]

    def test_tc105a_03_03_patched_timing_has_no_text_preview(self) -> None:
        """补丁链路实证：SpeechTiming 无 text_preview 字段、payload 不含正文。"""
        from grassland_dh.adapters import ensure_runtime_overlay

        root = str(Path(ensure_runtime_overlay()))
        sys.path.insert(0, root)
        try:
            timing_mod = __import__("opentalking.runtime.timing", fromlist=["SpeechTiming"])
            timing = timing_mod.SpeechTiming(session_id="s1", model_type="mock")
            payload = timing.payload()
            assert "text_preview" not in payload
            with pytest.raises(TypeError):
                timing_mod.SpeechTiming(session_id="s1", model_type="mock", text_preview="正文")  # type: ignore[call-arg]
        finally:
            sys.path.remove(root)
            for name in [m for m in sys.modules if m.startswith("opentalking")]:
                del sys.modules[name]
