#!/usr/bin/env bash
set -uo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${1:-$PROJ/dist/FULL_TEST_RUN_0.39.0-alpha37.log}"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"
cd "$PROJ"
FAILED=0
run_test() {
  local name="$1"; shift
  printf '===== %s =====\n' "$name" | tee -a "$LOG"
  local tmp; tmp="$(mktemp)"
  if "$@" >"$tmp" 2>&1; then
    cat "$tmp" | tee -a "$LOG"
    printf 'RESULT: PASS %s\n\n' "$name" | tee -a "$LOG"
  else
    local rc=$?
    cat "$tmp" | tee -a "$LOG"
    printf 'RESULT: FAIL(%s) %s\n\n' "$rc" "$name" | tee -a "$LOG"
    FAILED=1
  fi
  rm -f "$tmp"
}
run_test validate python3 scripts/validate.py
run_test hosts-manager bash scripts/test-hosts-manager.sh
run_test hosts-xray bash scripts/test-hosts-xray.sh
run_test xray-config bash scripts/test-xray-config.sh
run_test scanner bash scripts/test-scanner-engine.sh
run_test discovery bash scripts/test-discovery-engine.sh
run_test ip-intelligence bash scripts/test-ip-intelligence.sh
run_test provider-core bash scripts/test-provider-core.sh
run_test iptv bash scripts/test-iptv-engine.sh
run_test multicast bash scripts/test-multicast-support.sh
run_test mdns bash scripts/test-mdns-parser.sh
run_test protocol-router bash scripts/test-protocol-router.sh
run_test native-clients bash scripts/test-native-clients.sh
run_test connection-inventory python3 scripts/test-connection-inventory.py
run_test backup python3 scripts/test-backup-contract.py
run_test tls bash scripts/test-tls-trust.sh
run_test stabilization python3 scripts/test-stabilization.py
run_test ui-cleanup python3 scripts/test-ui-cleanup.py
run_test seo-domain bash scripts/test-seo-domain.sh
if command -v pytest >/dev/null 2>&1; then run_test backend-pytest pytest -q backend; else printf 'RESULT: SKIP backend-pytest (pytest unavailable)\n' | tee -a "$LOG"; fi
if [[ "$FAILED" -eq 0 ]]; then echo 'OVERALL=PASS' | tee -a "$LOG"; exit 0; fi
echo 'OVERALL=FAIL' | tee -a "$LOG"; exit 1
