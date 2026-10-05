#!/usr/bin/env bash
# 107-fix-3 C12: connectivity evidence only; paid generation requires separate validation.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"
CATALOG="platform-hypit/upstream-manifest.json"
OUT=""
usage() {
  cat <<'HELP'
verify-107-live.sh — LIVE 前置连通性检查（不能代替生成/账务/成片验收）
  --catalog <json>    providers/providerMappings 目录
  --out <directory>  独立证据目录；已有记录拒绝覆盖
  HYPIT_LIVE_ENABLED=1 + HYPIT_LIVE_BUDGET_CENTS=<正整数> 必须显式设置
  HYPIT_LIVE_BASE_URL 为探针入口；HYPIT_LIVE_TIMEOUT_SECONDS 默认为30，范围(0,30]
默认证据：test-artifacts/task-107/C24/live-runs/<本次独立目录>/live-records.jsonl
未启用=NOT_ENABLED；HTTP200最多PROBE_PASS；无业务证据最终UNVERIFIED、非零退出。
HELP
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --catalog|--out)
      [[ $# -ge 2 && -n "$2" ]] || { usage >&2; exit 2; }
      if [[ "$1" == --catalog ]]; then CATALOG="$2"; else OUT="$2"; fi
      shift 2 ;;
    *) usage >&2; exit 2 ;;
  esac
done
if [[ -z "$OUT" ]]; then
  mkdir -p test-artifacts/task-107/C24/live-runs
  OUT="$(mktemp -d test-artifacts/task-107/C24/live-runs/run-XXXXXX)"
fi
mkdir -p "$OUT"
# exec makes SIGTERM reach the probe process, with no orphaned child.
exec python3 - "$CATALOG" "$OUT" <<'PYTHON'
import json, math, os, re, sys, urllib.error, urllib.parse, urllib.request
from pathlib import Path

catalog_path, output = sys.argv[1:]
output = Path(output)
records = output / 'live-records.jsonl'
summary = output / 'live-summary.json'
if records.exists() or summary.exists():
    print('UNVERIFIED: evidence already exists; choose a new output directory', file=sys.stderr)
    sys.exit(2)
rows = []
def record(item, status, reason, **details):
    row = dict(item=item, status=status, reason=reason, costCents=None, samplePath=None, **details)
    rows.append(row)
    with records.open('a') as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + '\n')
def finish(status='UNVERIFIED', code=1):
    summary.write_text(json.dumps(dict(status=status, exitCode=code, total=len(rows),
        probePassed=sum(r['status'] == 'PROBE_PASS' for r in rows),
        reason='连通性不证明模型效果、计费或可解码成片', costCents=None), ensure_ascii=False, indent=2) + '\n')
    print(f'{status}: recorded {len(rows)} item(s); exit={code}')
    sys.exit(code)

budget = os.environ.get('HYPIT_LIVE_BUDGET_CENTS', '0')
if os.environ.get('HYPIT_LIVE_ENABLED') != '1' or not re.fullmatch(r'[0-9]+', budget) or int(budget) <= 0:
    record('hypit-live', 'NOT_ENABLED', '需要显式启用和正整数预算')
    finish('NOT_ENABLED', 2)
try:
    manifest = json.loads(Path(catalog_path).read_text())
    providers = manifest.get('providers', manifest.get('providerMappings', []))
    if isinstance(providers, dict):
        providers = list(providers.values())
    if not isinstance(providers, list) or not providers:
        raise ValueError('empty catalog')
except (OSError, ValueError, AttributeError):
    record('hypit-live', 'UNVERIFIED', '目录缺失、无效或没有provider')
    finish()

base = os.environ.get('HYPIT_LIVE_BASE_URL', '')
try:
    url = urllib.parse.urlsplit(base)
    timeout = float(os.environ.get('HYPIT_LIVE_TIMEOUT_SECONDS', '30'))
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('invalid base')
    if not math.isfinite(timeout) or not 0 < timeout <= 30:
        raise ValueError('invalid timeout')
except ValueError:
    record('hypit-live', 'UNVERIFIED', '探针base或超时配置缺失/无效')
    finish()

# Match the registered provider packages, not arbitrary catalog paths.
registered = set(re.findall(r'packageId: "@hypit/provider-([a-z0-9-]+)"',
    Path('platform-hypit/backend/src/providers/catalog.ts').read_text()))
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
opener = urllib.request.build_opener(NoRedirect)
for index, provider in enumerate(providers):
    name = provider if isinstance(provider, str) else provider.get('name') if isinstance(provider, dict) else None
    short = name.removeprefix('@hypit/provider-').removesuffix('.default') if isinstance(name, str) else ''
    if short not in registered:
        record(f'provider-index:{index}', 'UNVERIFIED', '未知provider，不发起网络请求')
        continue
    item = f'provider:{short}'
    try:
        path = base.rstrip('/') + '/providers/' + urllib.parse.quote(name, safe='') + '/probe'
        with opener.open(path, timeout=timeout) as response:
            body = response.read(65537)
            if response.status != 200 or len(body) > 65536 or not isinstance(json.loads(body), dict):
                raise ValueError('invalid probe response')
        record(item, 'PROBE_PASS', '仅确认HTTP探针，真实业务/账务/成片均未验收')
    except urllib.error.HTTPError as error:
        record(item, 'PROBE_FAIL', f'HTTP_{error.code}')
    except Exception as error:
        # Never echo response bodies, URLs, query strings or credentials.
        record(item, 'PROBE_FAIL', type(error).__name__)
finish()
PYTHON
