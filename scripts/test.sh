#!/usr/bin/env bash
# Soul2Soul 分层测试 harness 唯一入口（体系说明见 docs/TESTING.md）
#
#   scripts/test.sh              T0 静态 + T1 Android 单测 + T2 服务端单测 + T3 协议黑盒
#   scripts/test.sh --tier t2    只跑某一层（t0|t1|t2|t3|t4）
#   scripts/test.sh --coverage   追加覆盖率（jacoco 需一次联网下载；node 用内建覆盖率）
#   scripts/test.sh --e2e        追加 T4 模拟器 E2E（需 2 台已 boot 设备 + 本地信令 + coturn）
#
# 环境约定：仓库无 gradlew（Android Studio 之外的命令行构建走 ~/tools 下的 JDK17 + Gradle 8.7）
set -u
cd "$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$PWD"
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17/jdk-17.0.2.jdk/Contents/Home}"
GRADLE="${GRADLE_BIN:-$HOME/tools/gradle-8.7/bin/gradle}"
COVERAGE=0
E2E=0
ONLY=""

while [ $# -gt 0 ]; do
  case "$1" in
    --tier) ONLY="${2:-}"; shift 2 ;;
    --coverage) COVERAGE=1; shift ;;
    --e2e) E2E=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1（--tier/--coverage/--e2e）" >&2; exit 2 ;;
  esac
done

declare -a SUMMARY=()
overall=0

# 每层一个函数：返回 0=PASS 1=FAIL 2=SKIP
record() {
  local tier="$1" status="$2"
  SUMMARY+=("$(printf '  %-3s %s' "$tier" "$status")")
  [ "$status" = FAIL ] && overall=1
  return 0
}

run_tier() {
  local tier="$1" fn="$2"
  if [ -n "$ONLY" ] && [ "$ONLY" != "$tier" ]; then return; fi
  if [ "$tier" = t4 ] && [ "$E2E" != 1 ]; then return; fi
  echo
  echo "═════ $tier ═════"
  local rc=0
  "$fn" || rc=$?
  case $rc in
    0) record "$tier" PASS ;;
    2) record "$tier" SKIP ;;
    *) record "$tier" FAIL; echo "── $tier FAILED ──" >&2 ;;
  esac
}

t0_static() {
  local fail=0 f
  for f in "$ROOT"/scripts/*.sh "$ROOT"/server/test/*.sh; do
    [ -e "$f" ] && { bash -n "$f" || { echo "bash -n: $f"; fail=1; }; }
  done
  for f in "$ROOT"/server/src/*.js "$ROOT"/server/*.cjs "$ROOT"/server/test/*.mjs; do
    [ -e "$f" ] && { node --check "$f" || { echo "node --check: $f"; fail=1; }; }
  done
  node -e "JSON.parse(require('fs').readFileSync('$ROOT/server/package.json','utf8'))" || fail=1
  python3 - "$ROOT" <<'PY' || fail=1
import glob, sys, xml.dom.minidom as m
root = sys.argv[1]
paths = glob.glob(root + '/app/app/src/main/res/**/*.xml', recursive=True)
paths.append(root + '/app/app/src/main/AndroidManifest.xml')
bad = 0
for p in paths:
    try:
        m.parse(p)
    except Exception as e:
        print('XML malformed:', p, e)
        bad = 1
sys.exit(bad)
PY
  [ "$fail" -eq 0 ]
}

t1_android_unit() {
  if [ ! -x "$GRADLE" ]; then
    echo "gradle 不存在: $GRADLE（设 GRADLE_BIN 环境变量后可启用 T1）"
    return 2
  fi
  local args=(-p "$ROOT/app" :app:testProdDebugUnitTest -q)
  local tasks=(":app:testProdDebugUnitTest")
  if [ "$COVERAGE" = 1 ]; then
    args+=("-Pcoverage" ":app:testProdDebugUnitTestCoverageReport")
    tasks+=(":app:testProdDebugUnitTestCoverageReport")
  fi
  "$GRADLE" "${args[@]}" || return 1
}

t2_server_unit() {
  local n
  n=$(find "$ROOT/server/test" -name '*.test.mjs' 2>/dev/null | wc -l | tr -d ' ')
  if [ "${n:-0}" = "0" ]; then
    echo "server/test 暂无单测文件"
    return 2
  fi
  cd "$ROOT/server" || return 1
  if [ "$COVERAGE" = 1 ]; then
    node --test --experimental-test-coverage test/
  else
    node --test test/
  fi
}

t3_blackbox() {
  bash "$ROOT/server/test/run-blackbox.sh"
}

t4_e2e() {
  local ADB="${ADB_BIN:-$HOME/Library/Android/sdk/platform-tools/adb}"
  if [ ! -x "$ADB" ]; then echo "adb 不存在: $ADB"; return 2; fi
  local devices
  devices=$("$ADB" devices 2>/dev/null | grep -cw device)
  if [ "${devices:-0}" -lt 2 ]; then
    echo "需要 2 台在线模拟器/设备（当前 $devices），见 docs/TESTING.md T4 前置"
    return 2
  fi
  bash "$ROOT/scripts/e2e.sh"
}

run_tier t0 t0_static
run_tier t1 t1_android_unit
run_tier t2 t2_server_unit
run_tier t3 t3_blackbox
run_tier t4 t4_e2e

echo
echo "═════════ harness 汇总 ═════════"
printf '%s\n' "${SUMMARY[@]}"
exit "$overall"
