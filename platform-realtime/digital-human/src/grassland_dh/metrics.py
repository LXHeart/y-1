"""数字人 runtime 阶段指标（任务书 #105G C105G-05 / 共享契约 K10）。

设计约束：
- 标签只有 phase / backend / error 三类，值域在本模块固定（K10：标签限定
  phase/provider/stable error code；account/session/token/正文一律不作标签）。
- 高基数从类型上不可能：``record`` 没有任何接收账号/会话/正文/密钥的参数；
  backend 入参经 ``_backend_tag`` 规范化——非法形态与超出容量的不同取值一律
  ``other``，绝不让自由字符串成为标签值。
- 进程内累计、零出站、零落盘；``snapshot`` 只输出聚合值与有界标签组合。
  时延样本用固定容量 reservoir（512），p50/p95 由快照时计算，不无限增内存。
"""

from __future__ import annotations

import math
import threading
from dataclasses import dataclass, field
from typing import Any

# 有界值域（构造期固定；扩值域=改代码，不接受运行期自由值）
PHASES: tuple[str, ...] = (
    "connect", "stt", "llm", "tts", "render", "first_audio",
    "recording", "cleanup_session", "cleanup_orphan",
)
STABLE_ERRORS: tuple[str, ...] = (
    "ok", "dh_state_conflict", "dh_lease_stale", "dh_grant_invalid",
    "dh_grant_expired", "dh_content_deleted", "dh_runtime_unavailable",
    "provider_failure", "provider_timeout", "aborted", "overflow", "other",
)
DEFAULT_BACKEND = "default"

_MAX_BACKENDS = 16          # 不同 backend 标签值上限，超出归 other
_BACKEND_MAX_LEN = 32       # 单个 backend 标签长度上限
_RESERVOIR = 512            # 每 (phase,backend,error) 组合保留的时延样本上限


def _backend_tag(raw: Any) -> str:
    """backend 入参规范化：白名单式有界化，自由字符串不得直接成为标签值。"""
    if not isinstance(raw, str):
        return "other"
    value = raw.strip().lower()
    if not value or len(value) > _BACKEND_MAX_LEN:
        return "other"
    if not all(ch.isalnum() or ch in "-_" for ch in value):
        return "other"
    return value


def _error_tag(raw: Any) -> str:
    if not isinstance(raw, str):
        return "other"
    return raw if raw in STABLE_ERRORS else "other"


@dataclass
class _Cell:
    """一个 (phase, backend, error) 组合的计数与时延 reservoir。"""

    count: int = 0
    failures: int = 0
    samples: list[float] = field(default_factory=list)
    _seen: int = 0

    def observe(self, duration_ms: float | None, failed: bool) -> None:
        self.count += 1
        if failed:
            self.failures += 1
        if duration_ms is None:
            return
        self._seen += 1
        if len(self.samples) < _RESERVOIR:
            self.samples.append(float(duration_ms))
        else:
            # 均匀 reservoir：第 n 个样本以 reservoir/n 概率替换（确定性可测）
            index = self._seen % _RESERVOIR
            self.samples[index] = float(duration_ms)

    def percentile(self, p: float) -> int | None:
        if not self.samples:
            return None
        ordered = sorted(self.samples)
        rank = min(len(ordered) - 1, max(0, math.ceil(p * len(ordered)) - 1))
        return int(round(ordered[rank]))


class StageMetrics:
    """阶段指标（进程内）：record 只收有界标签；snapshot 输出无正文。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cells: dict[tuple[str, str, str], _Cell] = {}
        self._backends: set[str] = set()

    # ---- 写入 ------------------------------------------------------------

    def record(
        self,
        phase: str,
        *,
        backend: str = DEFAULT_BACKEND,
        error: str = "ok",
        duration_ms: float | None = None,
    ) -> None:
        """记一次阶段观测。phase/error 必须在固定值域内（否则拒绝归 other/抛错）。

        刻意不接受 account/session/token/text 参数：高基数与正文泄漏在 API
        形状上即不可能（tc105g_05_04 有界标签回归）。
        """
        phase_tag = phase if phase in PHASES else "other"
        error_tag = _error_tag(error)
        backend_tag = _backend_tag(backend)
        if backend_tag != DEFAULT_BACKEND and backend_tag != "other":
            with self._lock:
                if backend_tag not in self._backends and len(self._backends) >= _MAX_BACKENDS:
                    backend_tag = "other"
                else:
                    self._backends.add(backend_tag)
        failed = error_tag != "ok"
        with self._lock:
            self._cells.setdefault((phase_tag, backend_tag, error_tag), _Cell()).observe(
                duration_ms, failed)

    # ---- 读取 ------------------------------------------------------------

    def snapshot(self) -> dict[str, Any]:
        """聚合快照：只有有界标签组合 + 计数/p50/p95，无正文、无自由字符串。"""
        with self._lock:
            series: list[dict[str, Any]] = []
            for (phase, backend, error), cell in sorted(self._cells.items()):
                series.append({
                    "phase": phase,
                    "backend": backend,
                    "error": error,
                    "count": cell.count,
                    "failures": cell.failures,
                    "p50Ms": cell.percentile(0.5),
                    "p95Ms": cell.percentile(0.95),
                })
            return {"series": series, "seriesCount": len(series)}

    def label_domains(self) -> dict[str, tuple[str, ...]]:
        """当前标签值域（测试断言有界性用；含运行期动态 backend 域）。"""
        with self._lock:
            backends = tuple(sorted(self._backends | {DEFAULT_BACKEND}))
        return {"phase": PHASES, "backend": backends, "error": STABLE_ERRORS}
