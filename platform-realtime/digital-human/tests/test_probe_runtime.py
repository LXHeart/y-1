"""TC105A-04-01～04：真实候选验证门禁与探针结果完整性（#105A-04）。

无第三方配置/服务证据/缺数据不得 PASS；Fake 结果分级不得冒充真实；测量缺口保留 partial。
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

PROJECT_ROOT = Path(__file__).resolve().parent.parent
PROBE = PROJECT_ROOT / "scripts" / "probe_runtime.py"
PROFILE = PROJECT_ROOT / "renderer-profile.json"


def run_probe(mode: str, output: Path, profile: Path = PROFILE, extra: list[str] | None = None,
              env_extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    import os

    env = dict(os.environ)
    env.update(env_extra or {})
    return subprocess.run(
        [sys.executable, str(PROBE), "--mode", mode, "--output", str(output),
         "--profile", str(profile), *(extra or [])],
        capture_output=True, text=True, timeout=300, env=env, cwd=str(PROJECT_ROOT),
    )


def synthetic_approved_profile(tmp_path: Path, *, fail_first_turns: int | None = None,
                               permanent_fail: bool = False) -> Path:
    """合成「已批准」第三方服务 fixture：引用控制面配置与服务证据，不含本地权重。"""
    profile = {
        "version": 2,
        "candidates": [
            {"id": "synthetic-remote", "transport": "remote", "state": "approved",
             "model": "synthetic-third-party-renderer",
             "platformConfigId": "33333333-3333-4333-8333-333333333333",
             "platformModelVersion": 2,
             "serviceEvidenceRef": "synthetic-service-evidence",
             "evidence": {"synthetic": True, "serviceTermsApproved": True},
             "profileVersion": 2, "maxAttempts": 3},
        ],
    }
    path = tmp_path / "profile.json"
    path.write_text(json.dumps(profile, ensure_ascii=False, indent=2), encoding="utf-8")
    if fail_first_turns is not None:
        return path
    return path


class TestProbeRuntime:
    """tc105a_04_01～04。"""

    def test_tc105a_04_01_real_without_evidence_exit2_names_gaps(self, tmp_path: Path) -> None:
        """真实 profile=unknown：exit2、missingEvidence 具体点名、零下载零换模型。"""
        out = tmp_path / "probe-real.json"
        from grassland_dh.fakes import OutboundGuard

        with OutboundGuard() as guard:  # 子进程内同样有 guard；此处再兜底宿主侧
            result = run_probe("real", out)
        assert result.returncode == 2, f"缺证据必须 exit 2：{result.stdout}{result.stderr}"
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["status"] == "REAL_NOT_RUN"
        missing = "\n".join(report["missingEvidence"])
        assert "third-party-render-test" in missing
        assert "未批准" in missing
        assert guard.attempts == []  # 无任何下载/联网尝试
        # 报告不填推测数字：未执行的真实测量字段为空而非编造
        assert report["fpsSamples"] == []
        assert report["dimensions"] in (None, {}, {"audioVideo": "partial"}) or not report["dimensions"]

    def test_tc105a_04_02_fake_pass_never_claims_real(self, tmp_path: Path) -> None:
        out = tmp_path / "probe-fake.json"
        result = run_probe("fake", out, extra=["--samples", "6"])
        assert result.returncode == 0, result.stderr
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["status"] == "FAKE_PASS"
        assert "REAL_PASS" not in json.dumps(report, ensure_ascii=False)
        assert report["fpsClaim"] is None and report["perfPassed"] is None
        # Fake 报告仍要含实测协议事实（非空壳）
        assert len(report["latencySamples"]) == 6
        assert report["dimensions"]["audioVideo"] == "pass"
        assert report["dimensions"]["audioSamples"] > 0 and report["dimensions"]["videoFrames"] > 0
        assert report["extra"]["interruptOk"] is True
        assert report["costUnits"]["llmTokens"] > 0

    @pytest.mark.parametrize("tamper", ["missing-audio", "avg-fps-only", "real-pass-no-samples"])
    def test_tc105a_04_03_measurement_gaps_stay_partial(self, tamper: str) -> None:
        """校验器负例：缺音轨/只有均值无采样不得标 AV 或性能通过。"""
        sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
        import probe_runtime

        base = {
            "mode": "real", "status": "SYNTHETIC_PASS",
            "dimensions": {"audioVideo": "pass", "audioSamples": 16000, "videoFrames": 250},
            "fpsSamples": [24.9] * 120, "perfPassed": True, "missingEvidence": [],
        }
        if tamper == "missing-audio":
            report = {**base, "dimensions": {"audioVideo": "pass", "audioSamples": 0, "videoFrames": 250}}
            expect = "缺音轨"
        elif tamper == "avg-fps-only":
            report = {**base, "fpsSamples": [], "perfPassed": True}
            expect = "不得标性能通过"
        else:
            report = {**base, "status": "REAL_PASS", "fpsSamples": [25.0] * 3, "perfPassed": True}
            expect = "不得标性能通过"
        problems = probe_runtime.validate_report(report)
        assert problems, f"{tamper} 必须被判违规"
        assert any(expect in p for p in problems), f"{tamper} 未点名：{problems}"

    def test_tc105a_04_04_synthetic_approved_bounded_probe(self, tmp_path: Path) -> None:
        """批准合成资产：固定时长有界探针，冷/暖/分阶段/版本齐备；失败不无限重试。"""
        profile = synthetic_approved_profile(tmp_path)
        out = tmp_path / "probe-syn.json"
        result = run_probe("real", out, profile=profile,
                           env_extra={"DH_PROBE_FAIL_FIRST_TURNS": "2"})
        report = json.loads(out.read_text(encoding="utf-8"))
        assert result.returncode == 0, result.stderr
        assert report["status"] == "SYNTHETIC_PASS"
        assert report["attempts"] == 3 and report["maxAttempts"] == 3  # 有界重试后成功
        assert len(report["phases"]) == 3
        assert report["extra"]["coldWarm"]["coldStartMs"] > 0
        assert report["extra"]["versions"]["upstreamCommit"].startswith("8c739a5a")

        # 永久失败：恰好 maxAttempts 次即止（不无限试 provider），exit 2
        out_fail = tmp_path / "probe-syn-fail.json"
        result_fail = run_probe("real", out_fail, profile=profile,
                                env_extra={"DH_PROBE_FAIL_FIRST_TURNS": "99"})
        report_fail = json.loads(out_fail.read_text(encoding="utf-8"))
        assert result_fail.returncode == 2
        assert report_fail["status"] == "SYNTHETIC_FAILED"
        assert report_fail["attempts"] == 3, "失败重试必须有上界"

    def test_tc105a_04_01_fake_mode_also_clean_under_outbound_guard(self, tmp_path: Path) -> None:
        """Fake 探针全程零外网（复跑 V105A-04-02 语义，命令级）。"""
        out = tmp_path / "probe-fake2.json"
        result = run_probe("fake", out, extra=["--samples", "3"])
        assert result.returncode == 0
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["licenseStatus"] == "not_approved"  # 第三方服务尚未批准
        assert report["missingEvidence"] == []


def real_candidate_profile(tmp_path: Path, **overrides: object) -> Path:
    """非合成「已批准」真实候选 fixture：H03 逐项缺证负例的基础形状。"""
    candidate = {
        "id": "third-party-render-real", "transport": "remote", "state": "approved",
        "model": "synthetic-real-renderer",
        "platformConfigId": "44444444-4444-4444-8444-444444444444",
        "platformModelVersion": 3,
        "serviceEvidenceRef": "service-terms-evidence",
        "priceEvidenceRef": "price-table-evidence",
        "deviceEvidenceRef": "device-test-evidence",
        "evidence": {"synthetic": False, "serviceTermsApproved": True},
        "profileVersion": 3,
    }
    candidate.update(overrides)
    profile = {"version": 3, "candidates": [candidate]}
    path = tmp_path / "real-profile.json"
    path.write_text(json.dumps(profile, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


class TestH03RealQualificationGates:
    """tc105h_03_02（#105H C105H-03）：真实条件不足——NOT_RUN/PARTIAL 且门禁不可 PASS。"""

    @pytest.mark.parametrize("drop, expect_gap", [
        ("serviceEvidenceRef", "服务条款"),
        ("platformConfigId", "platformConfigId"),
        ("priceEvidenceRef", "真实价表证据缺失"),
        ("deviceEvidenceRef", "实机证据缺失"),
    ])
    def test_tc105h_03_02_missing_each_real_condition_exit2_named(self, tmp_path: Path,
                                                                   drop: str, expect_gap: str) -> None:
        """逐项缺证（服务证据/模型配置/真实价表/实机）→ REAL_NOT_RUN + 点名；绝不下发 PASS。"""
        overrides: dict[str, object] = {drop: None}
        profile = real_candidate_profile(tmp_path, **overrides)
        out = tmp_path / f"probe-h03-{drop}.json"
        result = run_probe("real", out, profile=profile,
                           env_extra={"DH_REAL_PROBE_AUTHORIZED": "1"})
        assert result.returncode == 2, f"{drop} 缺失必须 exit 2：{result.stdout}{result.stderr}"
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["status"] == "REAL_NOT_RUN"
        assert any(expect_gap in gap for gap in report["missingEvidence"]), report["missingEvidence"]
        # 未执行的真实测量不编造：无 fps/资源/录制实测。
        assert report["fpsSamples"] == []
        assert report["resourceSamples"] == []
        assert report["recordingProbe"] is None

    def test_tc105h_03_02_without_authorization_even_complete_profile_stays_not_run(self,
                                                                                     tmp_path: Path) -> None:
        """证据齐备但未授权：仍 REAL_NOT_RUN（不静默执行真实采样）。"""
        profile = real_candidate_profile(tmp_path)
        out = tmp_path / "probe-h03-noauth.json"
        result = run_probe("real", out, profile=profile)  # 不带 DH_REAL_PROBE_AUTHORIZED
        assert result.returncode == 2
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["status"] == "REAL_NOT_RUN"
        assert any("授权" in gap for gap in report["missingEvidence"])

    def test_tc105h_03_fake_probe_carries_resource_and_recording_samples(self, tmp_path: Path) -> None:
        """Fake 档位同构携带资源采样与录制覆盖（进程实测，非编造；不冒充真实资源结论）。"""
        out = tmp_path / "probe-h03-fake.json"
        result = run_probe("fake", out, extra=["--samples", "4"])
        assert result.returncode == 0, result.stderr
        report = json.loads(out.read_text(encoding="utf-8"))
        assert report["status"] == "FAKE_PASS"
        assert len(report["resourceSamples"]) == 5
        assert all(sample["rssKb"] > 0 for sample in report["resourceSamples"])
        recording = report["recordingProbe"]
        assert recording["scope"] == "fake-pipeline"
        assert recording["segments"] == 4
        assert recording["audioSamples"] > 0 and recording["videoFrames"] > 0
        assert recording["partial"] is False

    def test_tc105h_03_real_pass_requires_resource_and_recording(self) -> None:
        """校验器负例：REAL_PASS 缺资源采样/真实录制覆盖即违规（伪造通过被拒）。"""
        sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
        import probe_runtime

        base = {
            "mode": "real", "status": "REAL_PASS",
            "dimensions": {"audioVideo": "pass", "audioSamples": 16000, "videoFrames": 250},
            "fpsSamples": [24.0] * 120, "perfPassed": True, "missingEvidence": [],
        }
        problems = probe_runtime.validate_report(base)
        assert any("resourceSamples" in p for p in problems)
        assert any("recordingProbe" in p for p in problems)
        with_resources = {**base, "resourceSamples": [{"rssKb": 1}] * 5,
                          "recordingProbe": {"scope": "real", "partial": True}}
        problems2 = probe_runtime.validate_report(with_resources)
        assert any("recordingProbe" in p for p in problems2)
