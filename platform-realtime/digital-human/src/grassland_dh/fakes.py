"""全链 Fake：STT/LLM/TTS/Renderer/事件总线，确定流、确定用量、零外部出站。

设计约束（任务书 #105A-03）：
- Fake STT 按 fixture 返回最终文本（整段转写，首期口径）；
- Fake LLM 分三段输出且 usage 非零；
- Fake TTS 生成确定 PCM 正弦波（16kHz int16，随文本确定频率/时长）；
- FakeRenderer 生成自有测试图（渐变+活动条纹，与参考图无关），输出真实媒体对象；
- 全部实现不 import 网络 SDK、不打开 socket、不写盘；不得用 Edge TTS 冒充。
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Callable

import numpy as np

SAMPLE_RATE = 16000


def _stable_int(seed: str, modulo: int) -> int:
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") % modulo


@dataclass
class FakeLlm:
    """chat_stream 逐段输出确定回复（三段），记录非零 usage 与收到的 messages。"""

    persona_seed: str = "grassland-fake-llm"
    reply_sentences: tuple[str, ...] = (
        "这是第一段确定回复。",
        "第二段说明口播结构。",
        "第三段给出行动建议。",
    )
    input_tokens: int = 0
    output_tokens: int = 0
    calls: int = 0
    last_messages: list[dict[str, str]] = field(default_factory=list)

    async def chat_stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        self.calls += 1
        self.last_messages = list(messages)
        self.input_tokens = max(1, sum(len(m.get("content", "")) for m in messages) // 2)
        for sentence in self.reply_sentences:
            self.output_tokens += max(1, len(sentence) // 2)
            yield sentence
        # 非零 usage 终态（K08：真实 usage 才可 confirmed；Fake 提供确定非零值）
        yield ""

    def usage(self) -> dict[str, Any]:
        return {
            "inputTokens": max(1, self.input_tokens),
            "outputTokens": max(1, self.output_tokens),
            "providerRequestId": f"fake-llm-{_stable_int(self.persona_seed, 10**9)}",
            "quality": "confirmed",
        }


@dataclass
class _TtsChunk:
    data: np.ndarray


@dataclass
class FakeTts:
    """synthesize_stream 产出确定正弦 PCM；文本决定频率与时长。"""

    sample_rate: int = SAMPLE_RATE
    chunk_ms: float = 400.0
    default_voice: str | None = None
    synthesized_ms: int = 0
    segments: list[str] = field(default_factory=list)
    closed: bool = False

    async def synthesize_stream(self, text: str) -> AsyncIterator[_TtsChunk]:
        if self.closed:
            raise RuntimeError("fake tts closed")
        self.segments.append(text)
        # 时长 = max(300ms, 每码点 45ms)，频率 = 200~600Hz 由文本确定
        duration_ms = max(300, len(text) * 45)
        freq = 200 + _stable_int(text, 400)
        total = int(self.sample_rate * duration_ms / 1000)
        t = np.arange(total, dtype=np.float64) / self.sample_rate
        wave = (np.sin(2 * math.pi * freq * t) * 12000).astype(np.int16)
        self.synthesized_ms += duration_ms
        per_chunk = int(self.sample_rate * self.chunk_ms / 1000)
        for start in range(0, total, per_chunk):
            yield _TtsChunk(data=wave[start:start + per_chunk].copy())

    async def aclose(self) -> None:
        self.closed = True

    def usage(self) -> dict[str, Any]:
        return {
            "audioOutputMs": max(1, self.synthesized_ms),
            "textCodePoints": sum(len(s) for s in self.segments),
            "providerRequestId": f"fake-tts-{_stable_int('|'.join(self.segments) or 'idle', 10**9)}",
            "quality": "confirmed",
        }


@dataclass
class FakeSpeech:
    """整段转写 Fake：按 fixture 表（或确定性回退）返回最终文本，不派发真实 STT。"""

    fixture: dict[bytes, str] = field(default_factory=dict)
    fallback_text: str = "帮我整理三句口播要点。"
    transcriptions: list[bytes] = field(default_factory=list)

    def transcribe(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> str:
        self.transcriptions.append(pcm)
        for key, value in self.fixture.items():
            if pcm == key:
                return value
        return self.fallback_text

    def usage(self, pcm: bytes, sample_rate: int = SAMPLE_RATE) -> dict[str, Any]:
        samples = len(pcm) // 2
        return {
            "audioInputMs": max(1, samples * 1000 // sample_rate),
            "providerRequestId": f"fake-stts-{_stable_int(hashlib.sha256(pcm).hexdigest(), 10**9)}",
            "quality": "confirmed",
        }


@dataclass
class FakeVideoFrame:
    """与上游 VideoFrameData 同构的媒体对象（BGR uint8）。"""

    data: np.ndarray
    width: int
    height: int
    timestamp_ms: float


@dataclass
class FakeRenderer:
    """Audio2VideoClient 面：connect/init_session/generate/close + 会话属性。

    生成自有测试图（与参考图无关）：纵向渐变底 + 随帧索引移动的亮条；
    generate 按音频时长产帧，绝不联网、绝不读写盘。
    """

    fps: int = 25
    width: int = 256
    height: int = 256
    sample_rate: int = SAMPLE_RATE
    frame_num: int = 25
    motion_frames_num: int = 5
    slice_len: int = 12
    inited: bool = False
    generated_pcm_samples: int = 0
    generated_frames: int = 0
    connect_calls: int = 0
    closed: bool = False

    @property
    def audio_chunk_samples(self) -> int:
        return self.slice_len * self.sample_rate // self.fps

    async def connect(self) -> None:
        self.connect_calls += 1

    async def init_session(self, **kwargs: Any) -> dict[str, Any]:
        self.inited = True
        return {"width": self.width, "height": self.height, "fps": self.fps}

    def _pattern(self, frame_index: int) -> np.ndarray:
        yy, xx = np.mgrid[0:self.height, 0:self.width]
        base = (xx * 255 // max(1, self.width - 1)).astype(np.uint8)
        bar_col = (frame_index * 7) % self.width
        bar = (np.abs(xx - bar_col) < 6).astype(np.uint8) * 200
        blue = (yy * 255 // max(1, self.height - 1)).astype(np.uint8)
        frame = np.stack([base, ((base // 2) + (bar // 2)).astype(np.uint8), blue], axis=-1)
        return np.ascontiguousarray(frame)

    async def generate(self, pcm: np.ndarray) -> list[FakeVideoFrame]:
        if self.closed:
            raise RuntimeError("fake renderer closed")
        samples = int(pcm.size)
        self.generated_pcm_samples += samples
        frames = max(1, samples * self.fps // self.sample_rate)
        out: list[FakeVideoFrame] = []
        for i in range(frames):
            self.generated_frames += 1
            out.append(FakeVideoFrame(
                data=self._pattern(self.generated_frames),
                width=self.width, height=self.height,
                timestamp_ms=(self.generated_pcm_samples / self.sample_rate) * 1000.0,
            ))
        return out

    async def close(self) -> None:
        self.closed = True

    def usage(self) -> dict[str, Any]:
        return {
            "renderMs": max(1, self.generated_pcm_samples * 1000 // self.sample_rate),
            "providerRequestId": "fake-renderer",
            "quality": "confirmed",
        }


@dataclass
class FakeRuntimeBus:
    """redis 鸭子面：hset/hget/get/set/publish/expire/persist，全部内存态。

    published 事件序列供测试断言（字幕/状态事件），绝不写盘、绝不外发。
    """

    store: dict[str, str] = field(default_factory=dict)
    hashes: dict[str, dict[str, str]] = field(default_factory=dict)
    published: list[tuple[str, dict[str, Any]]] = field(default_factory=list)

    async def get(self, key: str) -> str | None:
        return self.store.get(key)

    async def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.store[key] = value

    async def delete(self, key: str) -> None:
        self.store.pop(key, None)

    async def hset(self, key: str, mapping: dict[str, Any] | None = None, **kw: Any) -> None:
        self.hashes.setdefault(key, {}).update({str(k): str(v) for k, v in (mapping or {}).items()})

    async def hget(self, key: str, field: str) -> str | None:
        return self.hashes.get(key, {}).get(field)

    async def expire(self, key: str, ttl: int) -> None:
        return None

    async def persist(self, key: str) -> None:
        return None

    async def publish(self, channel: str, payload: str) -> None:
        try:
            parsed = json.loads(payload)
        except json.JSONDecodeError:
            parsed = {"raw": payload}
        self.published.append((channel, parsed))

    def events_of(self, name: str) -> list[dict[str, Any]]:
        return [data for _ch, data in self.published if data.get("event") == name]


class OutboundGuard:
    """上下文管理器：拦截一切到非回环地址的 TCP 连接并计数（供「外网请求=0」断言）。

    只 monkeypatch socket.socket.connect 族；bind/listen（本机服务）不受影响。
    """

    ALLOWED_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})

    def __init__(self) -> None:
        self.attempts: list[tuple[str, int]] = []
        self.blocked: list[tuple[str, int]] = []

    def __enter__(self) -> "OutboundGuard":
        import socket

        self._orig_connect = socket.socket.connect

        def guarded_connect(sock: Any, address: Any) -> None:
            host = address[0] if isinstance(address, tuple) else str(address)
            port = address[1] if isinstance(address, tuple) else 0
            self.attempts.append((str(host), int(port)))
            if str(host) not in self.ALLOWED_HOSTS:
                self.blocked.append((str(host), int(port)))
                raise ConnectionRefusedError(f"grassland fake test: outbound blocked {host}:{port}")
            return self._orig_connect(sock, address)

        socket.socket.connect = guarded_connect  # type: ignore[method-assign]
        return self

    def __exit__(self, *exc: Any) -> None:
        import socket

        socket.socket.connect = self._orig_connect  # type: ignore[method-assign]


def make_test_reference_png(path: Any) -> None:
    """生成自有测试参考图（纯代码，无真实人像），PIL 写 PNG。"""
    from PIL import Image

    yy, xx = np.mgrid[0:256, 0:256]
    arr = np.stack([
        (xx).astype(np.uint8),
        (yy).astype(np.uint8),
        ((xx + yy) // 2).astype(np.uint8),
    ], axis=-1)
    Image.fromarray(arr, mode="RGB").save(path, format="PNG")


FAKE_GRANT_PROVIDER: Callable[[str], str] = lambda kind: f"fake-grant-{kind}-{secrets.token_urlsafe(16)}"
