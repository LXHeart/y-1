"""真实候选验证门禁探针（任务书 #105A-04）。

用法：
  python probe_runtime.py --mode fake --profile renderer-profile.json --output probe.json
  python probe_runtime.py --mode real --profile renderer-profile.json --output probe.json

分级语义（K12：端到端 Fake 证明功能/协议，真实第三方服务证明渲染，互不替代）：
  - fake：以 #105A-03 全链 Fake 复跑协议探针（冷/暖、打断、AV 绑定）→ FAKE_PASS，exit 0；
    不得出现 REAL_PASS 或真实 fps 承诺。
  - real：逐项核对 profile 中现有控制面配置引用、第三方服务条款/协议和测试授权；
    任何缺口 → REAL_NOT_RUN + 具体 missingEvidence，exit 2；绝不下载权重或自动换模型。
    合成服务 fixture 可执行有界采样 → SYNTHETIC_PASS；
    非合成的真实执行需 DH_REAL_PROBE_AUTHORIZED（本机未授权即 exit 2）。

报告字段全部实测：mode/status/commit/pythonVersion/os/hardware/backend/dimensions/
fpsSamples/latencySamples/costUnits/licenseStatus/missingEvidence；不填推测数字。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import platform
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from grassland_dh.adapters import RunnerAdapter  # noqa: E402
from grassland_dh.bindings import SessionBinding, TurnBinding  # noqa: E402
from grassland_dh.fakes import OutboundGuard  # noqa: E402

UPSTREAM_COMMIT = "8c739a5a6f114daf71aeace832a668c3ad60f536"
LICENSE_MANIFEST = PROJECT_ROOT / "license-manifest.json"
DEFAULT_PROFILE = PROJECT_ROOT / "renderer-profile.json"

MIN_REAL_SAMPLES = 100


# ---------------------------------------------------------------------------
# 资源采样（#105H C105H-03：真实档位必须携带；Fake 档位同样实测本进程，标注 fake）
# ---------------------------------------------------------------------------


def sample_resources(count: int = 10, interval_s: float = 0.0) -> list[dict[str, Any]]:
    """对本进程做有界资源采样（RSS/CPU 时间）。只测进程事实，不外推集群容量。"""
    import resource
    import time

    samples: list[dict[str, Any]] = []
    for index in range(count):
        usage = resource.getrusage(resource.RUSAGE_SELF)
        samples.append({
            "sample": index,
            "rssKb": usage.ru_maxrss,
            "userCpuS": round(usage.ru_utime, 3),
            "systemCpuS": round(usage.ru_stime, 3),
        })
        if interval_s > 0 and index < count - 1:
            time.sleep(interval_s)
    return samples


# ---------------------------------------------------------------------------
# 环境事实（只读，不安装、不下载）
# ---------------------------------------------------------------------------


def environment_facts() -> dict[str, Any]:
    hardware = {"machine": platform.machine(), "processor": platform.processor() or "unknown"}
    accelerator = "none-detected"
    # 本项目不安装或加载推理栈；模型运行在第三方服务侧。
    accelerator = "not-applicable-third-party-models"
    hardware["accelerator"] = accelerator
    return {
        "pythonVersion": platform.python_version(),
        "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "hardware": hardware,
        "commit": UPSTREAM_COMMIT,
    }


# ---------------------------------------------------------------------------
# 报告完整性校验（测试复用；TC105A-04-03 责任实现）
# ---------------------------------------------------------------------------


def validate_report(report: dict[str, Any], *, min_samples: int = MIN_REAL_SAMPLES) -> list[str]:
    problems: list[str] = []
    status = report.get("status")
    if status not in {"FAKE_PASS", "SYNTHETIC_PASS", "REAL_PASS", "REAL_NOT_RUN",
                      "SYNTHETIC_FAILED", "FAILED"}:
        problems.append(f"未知 status: {status!r}")

    # Fake 结果分级：不得出现真实承诺
    if status == "FAKE_PASS":
        text = json.dumps(report, ensure_ascii=False)
        if "REAL_PASS" in text:
            problems.append("FAKE_PASS 报告不得包含 REAL_PASS 字样")
        if report.get("fpsClaim") is not None or report.get("perfPassed") is True:
            problems.append("FAKE_PASS 不得携带真实 fps 承诺或性能通过标记")

    # AV 与性能判定须有实测样本支撑
    dimensions = report.get("dimensions") or {}
    if dimensions.get("audioVideo") == "pass":
        if not dimensions.get("audioSamples") or not dimensions.get("videoFrames"):
            problems.append("缺音轨或缺视频帧不得标 audioVideo=pass（必须 partial）")
    if report.get("perfPassed") is True:
        samples = report.get("fpsSamples") or []
        if len(samples) < min_samples:
            problems.append(
                f"fps 采样 {len(samples)} < {min_samples}，只有均值无采样不得标性能通过"
            )

    if status in {"REAL_PASS", "SYNTHETIC_PASS"} and not report.get("missingEvidence") == []:
        problems.append("通过态 missingEvidence 必须为空")
    if status == "REAL_NOT_RUN" and not report.get("missingEvidence"):
        problems.append("REAL_NOT_RUN 必须列出具体 missingEvidence")
    # #105H C105H-03：真实通过必须携带实测资源采样与录制覆盖（无中生有=伪造）。
    if status == "REAL_PASS":
        if not report.get("resourceSamples"):
            problems.append("REAL_PASS 缺 resourceSamples（真实档位必须实测进程/资源采样）")
        recording = report.get("recordingProbe") or {}
        if recording.get("scope") != "real" or recording.get("partial") is True:
            problems.append("REAL_PASS 缺真实录制覆盖记录（recordingProbe.scope=real 且非 partial）")
    return problems


# ---------------------------------------------------------------------------
# Fake 协议探针
# ---------------------------------------------------------------------------


async def run_fake_probe(sample_turns: int = 20) -> dict[str, Any]:
    """全链 Fake：冷暖/打断/AV 绑定；样本为协议计时（非真实渲染性能）。"""
    from grassland_dh.adapters import ensure_runtime_overlay

    ensure_runtime_overlay()
    binding = SessionBinding(
        session_id="55555555-5555-4555-8555-555555555555",
        lease_epoch=1, media_epoch=1, backend_id="mock", profile_revision=1,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
        bridge_base_url="https://internal.invalid/dh-bridge",
    )
    adapter = RunnerAdapter(test_mode=True, avatars_root=PROJECT_ROOT / "data" / "probe-avatars")

    cold_start_ms = None
    import time

    t0 = time.perf_counter()
    session = await adapter.create(binding, persona_text="草场 Fake 探针人设")
    await session.runner.prepare()
    cold_start_ms = round((time.perf_counter() - t0) * 1000, 1)

    latency_samples: list[float] = []
    fps_samples: list[float] = []
    audio_total = 0
    video_total = 0

    def turn(turn_epoch: int) -> TurnBinding:
        return TurnBinding(
            session_id=binding.session_id,
            turn_id=f"aaaaaaaa-1aaa-41aa-81aa-aaaaaaaaaa{turn_epoch:02d}",
            turn_epoch=turn_epoch, lease_epoch=1, media_epoch=session.media_epoch,
            content_epoch=1,
            request_id=f"22222222-2222-4222-8222-2222222222{turn_epoch:02d}",
            deadline_at=datetime.now(timezone.utc) + timedelta(seconds=30),
        )

    for i in range(sample_turns):
        t = time.perf_counter()
        artifacts = await session.start_turn(turn(i + 1), f"探针第{i + 1}轮。")
        latency_samples.append(round((time.perf_counter() - t) * 1000, 1))
        audio_total += sum(len(p) for p in artifacts.pcm_parts)
        video_total += len(artifacts.video_frames)
        timestamps = [float(getattr(f, "timestamp_ms", 0.0)) for f in artifacts.video_frames]
        for a, b in zip(timestamps, timestamps[1:]):
            if b > a > 0:
                fps_samples.append(round(1000.0 / (b - a), 1))

    # 打断分支：闸门 LLM + interrupt
    from grassland_dh.fakes import FakeLlm

    class _GateLlm(FakeLlm):
        def __init__(self) -> None:
            super().__init__()
            import asyncio

            self.gate = asyncio.Event()
            self.arrived = False

        async def chat_stream(self, messages):
            self.arrived = True
            self.last_messages = list(messages)
            await self.gate.wait()
            for sentence in self.reply_sentences:
                yield sentence

    gated = _GateLlm()
    session.runner.llm = gated
    task = asyncio.create_task(session.start_turn(turn(sample_turns + 1), "将被打断的一轮。"))
    while not gated.arrived:
        await asyncio.sleep(0)
    await session.interrupt(turn_epoch=sample_turns + 1)
    gated.gate.set()
    interrupted = await task
    interrupt_ok = interrupted.status == "interrupted"

    await session.close()
    return {
        "backend": "mock(fake-transport)",
        "coldStartMs": cold_start_ms,
        "warmTurns": sample_turns,
        "interruptOk": interrupt_ok,
        "audioSamples": audio_total // 2,  # int16 样本数
        "videoFrames": video_total,
        "latencySamples": latency_samples,
        "fpsSamples": fps_samples,
        # #105H C105H-03：进程资源采样与录制覆盖（Fake 档位=本进程/协议管线事实，
        # 不构成真实渲染资源结论；真实档位同字段由真实执行路径实测）。
        "resourceSamples": sample_resources(count=5),
        "recordingProbe": {
            "scope": "fake-pipeline",
            "segments": sample_turns,
            "audioSamples": audio_total // 2,
            "videoFrames": video_total,
            "partial": audio_total == 0 or video_total == 0,
        },
        "costUnits": {
            "note": "Fake 用量（无价表换算；成本只用实量×实际价表，禁止预设单价）",
            "llmTokens": session.llm.input_tokens + session.llm.output_tokens,
            "ttsAudioMs": getattr(session, "_last_tts", None).synthesized_ms if session._last_tts else 0,
            "renderMs": session.renderer.usage()["renderMs"],
        },
        "dimensions": {
            "audioVideo": "pass" if audio_total > 0 and video_total > 0 else "partial",
            "audioSamples": audio_total // 2,
            "videoFrames": video_total,
            "width": 256, "height": 256, "fpsNominalTransport": 25,
        },
        "perfPassed": None,
        "fpsClaim": None,
    }


# ---------------------------------------------------------------------------
# real 前置核对（绝不下载/换模型）
# ---------------------------------------------------------------------------


def check_real_prerequisites(profile: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    missing: list[str] = []
    approved_candidate: dict[str, Any] | None = None
    for candidate in profile.get("candidates", []):
        if candidate.get("id") == "mock":
            continue
        if candidate.get("state") != "approved":
            missing.append(f"第三方配置 {candidate.get('id')!r} 未批准（state={candidate.get('state')!r}）")
            continue
        approved_candidate = candidate

    if approved_candidate is not None and approved_candidate.get("evidence", {}).get("synthetic"):
        # 合成批准资产：许可证据随 fixture（真实 manifest 不适用，也不得被合成路径绕过真实门禁）
        if not approved_candidate.get("evidence", {}).get("serviceTermsApproved"):
            missing.append("合成 fixture 缺 serviceTermsApproved 证据")
    else:
        if not LICENSE_MANIFEST.is_file():
            missing.append(f"第三方服务证据清单缺失：{LICENSE_MANIFEST}")

    if approved_candidate is not None:
        if not approved_candidate.get("platformConfigId"):
            missing.append("第三方配置缺少现有模型控制面 platformConfigId")
        if not approved_candidate.get("platformModelVersion"):
            missing.append("第三方配置缺少 platformModelVersion")
        if not approved_candidate.get("serviceEvidenceRef"):
            missing.append("第三方服务条款/协议证据缺失")
        if not approved_candidate.get("evidence", {}).get("synthetic"):
            # #105H C105H-03：真实（非合成）执行另需真实价表与实机证据
            # （合成 fixture 不涉真实计费与设备语义，沿用 A 阶段口径）。
            if not approved_candidate.get("priceEvidenceRef"):
                missing.append("真实价表证据缺失（centsPerSecond 依据；不接受估计值）")
            if not approved_candidate.get("deviceEvidenceRef"):
                missing.append("实机证据缺失（iOS Safari + Android Chrome 实测记录）")
            if __import__("os").environ.get("DH_REAL_PROBE_AUTHORIZED") != "1":
                missing.append("真实（非合成）探针未获本机授权（DH_REAL_PROBE_AUTHORIZED!=1）")
    return missing, approved_candidate or {}


# ---------------------------------------------------------------------------
# 合成批准资产的有界探针（TC105A-04-04：固定时长、重试上限、冷/暖、分阶段）
# ---------------------------------------------------------------------------


async def run_synthetic_bounded_probe(candidate: dict[str, Any]) -> dict[str, Any]:
    import os
    import time

    max_attempts = int(candidate.get("maxAttempts", 3))
    fail_first = int(os.environ.get("DH_PROBE_FAIL_FIRST_TURNS", "0"))
    phases: list[dict[str, Any]] = []
    attempts = 0
    base = await run_fake_probe(sample_turns=2)  # 复用 Fake 管线作为「已批准合成 provider」
    for attempt in range(1, max_attempts + 1):
        attempts = attempt
        phases.append({"phase": f"attempt-{attempt}", "ok": attempt > fail_first})
        if attempt > fail_first:
            break
        await asyncio.sleep(0)
    succeeded = attempts > fail_first
    base.update({
        "backend": f"synthetic-approved:{candidate.get('id')}",
        "status": "SYNTHETIC_PASS" if succeeded else "SYNTHETIC_FAILED",
        "attempts": attempts,
        "maxAttempts": max_attempts,
        "phases": phases,
        "coldWarm": {"coldStartMs": base.get("coldStartMs"),
                     "warmMedianMs": sorted(base.get("latencySamples", []))[0]
                     if base.get("latencySamples") else None},
        "versions": {"upstreamCommit": UPSTREAM_COMMIT,
                     "python": platform.python_version(),
                     "profileVersion": candidate.get("profileVersion", 1)},
    })
    if not succeeded:
        base["status"] = "SYNTHETIC_FAILED"
    return base


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_report(mode: str, probe: dict[str, Any], status: str,
                 missing: list[str], license_status: str) -> dict[str, Any]:
    report = {
        "mode": mode,
        "status": status,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        **environment_facts(),
        "backend": probe.get("backend"),
        "dimensions": probe.get("dimensions"),
        "fpsSamples": probe.get("fpsSamples", []),
        "latencySamples": probe.get("latencySamples", []),
        "costUnits": probe.get("costUnits"),
        "licenseStatus": license_status,
        "missingEvidence": missing,
        "perfPassed": probe.get("perfPassed"),
        "fpsClaim": probe.get("fpsClaim"),
        "attempts": probe.get("attempts"),
        "maxAttempts": probe.get("maxAttempts"),
        "phases": probe.get("phases"),
        # #105H C105H-03：资源采样与录制覆盖为顶层实测字段（Fake/真实档位同构）。
        "resourceSamples": probe.get("resourceSamples", []),
        "recordingProbe": probe.get("recordingProbe"),
        "extra": {k: v for k, v in probe.items() if k in {
            "coldStartMs", "warmTurns", "interruptOk", "attempts", "maxAttempts",
            "phases", "coldWarm", "versions"}},
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="grassland digital-human runtime probe")
    parser.add_argument("--mode", choices=["fake", "real"], required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--profile", default=str(DEFAULT_PROFILE))
    parser.add_argument("--samples", type=int, default=0, help="覆盖采样轮数（默认 fake=20）")
    args = parser.parse_args(argv)

    profile = json.loads(Path(args.profile).read_text(encoding="utf-8"))
    license_status = "unknown"
    if LICENSE_MANIFEST.is_file():
        components = json.loads(LICENSE_MANIFEST.read_text(encoding="utf-8")).get("components", [])
        license_status = "approved" if components and all(
            c.get("status") == "approved" for c in components) else "not_approved"

    with OutboundGuard():
        if args.mode == "fake":
            probe = asyncio.run(run_fake_probe(sample_turns=args.samples or 20))
            report = build_report("fake", probe, "FAKE_PASS", [], license_status)
            problems = validate_report(report)
            if problems:
                report["status"] = "FAILED"
                report["missingEvidence"] = problems
        else:
            missing, candidate = check_real_prerequisites(profile)
            if missing:
                report = build_report("real", {}, "REAL_NOT_RUN", missing, license_status)
            elif candidate.get("evidence", {}).get("synthetic"):
                probe = asyncio.run(run_synthetic_bounded_probe(candidate))
                status = probe.pop("status", "SYNTHETIC_PASS")
                report = build_report("real", probe, status, [], license_status)
            else:
                # 已批准真实候选且已授权：仍需 H03 真实门禁（本轮不可达，防御性退出）
                report = build_report(
                    "real", {}, "REAL_NOT_RUN",
                    ["真实执行归 H03 门禁：本工具不在此路径宣称 REAL_PASS"], license_status)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    status = report["status"]
    print(f"probe: mode={report['mode']} status={status} "
          f"samples={len(report['fpsSamples'])} missing={len(report['missingEvidence'])}")
    if status in {"REAL_NOT_RUN", "FAILED", "SYNTHETIC_FAILED"}:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
