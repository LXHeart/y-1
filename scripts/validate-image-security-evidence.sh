#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${1:-${IMAGE_SECURITY_OUTPUT_DIR:-$ROOT_DIR/test-artifacts/image-security}}"
SERVICES=(frontend database-bootstrap release-migrator edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)

command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }
[[ -d "$REPORT_DIR" ]] || { echo "image security report directory is missing: $REPORT_DIR" >&2; exit 1; }

for service in "${SERVICES[@]}"; do
  vuln="$REPORT_DIR/$service.vulnerabilities.json"
  sbom="$REPORT_DIR/$service.spdx.json"
  [[ -s "$vuln" ]] || { echo "missing vulnerability report: $vuln" >&2; exit 1; }
  [[ -s "$sbom" ]] || { echo "missing SPDX SBOM: $sbom" >&2; exit 1; }
  jq -e '(.Results | type) == "array"' "$vuln" >/dev/null \
    || { echo "invalid Trivy vulnerability report: $vuln" >&2; exit 1; }
  jq -e '.spdxVersion | type == "string"' "$sbom" >/dev/null \
    || { echo "invalid SPDX SBOM: $sbom" >&2; exit 1; }
done

[[ -s "$REPORT_DIR/signing-status.txt" ]] || { echo "missing signing status evidence" >&2; exit 1; }
echo "image security evidence is complete for ${#SERVICES[@]} services"
