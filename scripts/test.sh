#!/usr/bin/env bash
# Soul2Soul 分层测试 harness 唯一入口（体系说明见 docs/TESTING.md）
#
#   scripts/test.sh              T0 静态 + T1 Android 单测 + T2 服务端单测 + T3 协议黑盒
#   scripts/test.sh --tier t2    只跑某一层（t0|t1|t2|t3|t4）
#   scripts/test.sh --coverage   追加覆盖率（jacoco 需一次联网下载；node 用内建覆盖率）
#   scripts/test.sh --e2e        追加 T4 模拟器 E2E（需 2 台已 boot 设备 + 本地信令 + coturn）
#
# 环境约定：仓库无 gradlew（Android Studio 之外的命令行构建走 ~/tools 下的 JDK17 + Gradle 8.7）
set -Eeuo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$PWD"
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "警告: JAVA_HOME 无效($JAVA_HOME)，尝试自动探测 JDK17" >&2
  unset JAVA_HOME
fi
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in \
    "$HOME/tools/jdk17/jdk-17.0.2.jdk/Contents/Home" \
    "$HOME/tools/jdk17/usr/lib/jvm/java-17-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk-amd64"; do
    if [ -x "$candidate/bin/java" ]; then JAVA_HOME="$candidate"; export JAVA_HOME; break; fi
  done
fi
if [ -n "${ANDROID_HOME:-}" ] && [ ! -d "$ANDROID_HOME/platform-tools" ]; then
  echo "警告: ANDROID_HOME 无效($ANDROID_HOME)，尝试自动探测 Android SDK" >&2
  unset ANDROID_HOME
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/tools/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$candidate/platform-tools" ]; then ANDROID_HOME="$candidate"; export ANDROID_HOME; break; fi
  done
fi
if [ -n "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
GRADLE="${GRADLE_BIN:-$HOME/tools/gradle-8.7/bin/gradle}"
COVERAGE=0
E2E=0
ONLY=""
STRICT_AUDIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tier)
      if [ $# -lt 2 ]; then
        echo "--tier 必须是 t0|t1|t2|t3|t4" >&2
        exit 2
      fi
      case "$2" in
        t0|t1|t2|t3|t4) ;;
        *)
        echo "--tier 必须是 t0|t1|t2|t3|t4" >&2
        exit 2
        ;;
      esac
      ONLY="$2"; shift 2 ;;
    --coverage) COVERAGE=1; shift ;;
    --e2e) E2E=1; shift ;;
    --strict-audit) STRICT_AUDIT=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1（--tier/--coverage/--e2e/--strict-audit）" >&2; exit 2 ;;
  esac
done

# 显式指定 T4 时不应因为漏写 --e2e 而静默 SKIP。
[ "$ONLY" = t4 ] && E2E=1

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
  for f in "$ROOT"/server/src/*.js "$ROOT"/server/*.cjs "$ROOT"/server/test/*.mjs "$ROOT"/server/test/*.cjs; do
    [ -e "$f" ] && { node --check "$f" || { echo "node --check: $f"; fail=1; }; }
  done
  for f in "$ROOT"/scripts/*.py; do
    if [ -e "$f" ]; then
      python3 -c 'import pathlib,sys; p=pathlib.Path(sys.argv[1]); compile(p.read_text(), str(p), "exec")' "$f" \
        || { echo "python syntax: $f"; fail=1; }
    fi
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
  # v0.2.34 真机踩坑锁定：Context.INPUT_SERVICE 返回硬件 InputManager， cast 成
  # InputMethodManager 必崩 ClassCastException（点表情闪退="权限被重置"假象的元凶）
  if grep -rn --include='*.kt' -A1 "Context.INPUT_SERVICE" "$ROOT/app/app/src/main/java" 2>/dev/null \
     | grep -q "InputMethodManager"; then
    echo "静态检查: INPUT_SERVICE 被 cast 成 InputMethodManager（应为 INPUT_METHOD_SERVICE）"
    fail=1
  fi
  # v0.2.37 审计锁定：java.nio.file.* 是 API 26+，minSdk 23 无 desugaring，
  # 安卓7设备运行到就 NoClassDefFoundError（Updater 更新通道整条断掉的元凶）
  if grep -rn --include='*.kt' "java\.nio\.file" "$ROOT/app/app/src/main/java" 2>/dev/null \
     | grep -vE ':[0-9]+: *(//|\*)' | grep -q "java\.nio\.file"; then
    echo "静态检查: 主源码禁用 java.nio.file（API 26+，minSdk 23 会崩；用 File API 代替）"
    fail=1
  fi
  # v0.2.44 真机踩坑锁定（AUDIT.md L2）：挂上去的悬浮窗必须有配对的拆除调用，
  # 防"加了不拆 / 退出模式不还入口"类单边接线（涂鸦入口球整场丢失的根因）
  local unpaired uf
  unpaired=""
  for uf in $(grep -rl --include='*.kt' "wm\.addView(" "$ROOT/app/app/src/main/java" 2>/dev/null); do
    grep -q "wm\.removeView(" "$uf" || unpaired="$unpaired $uf"
  done
  if [ -n "$unpaired" ]; then
    echo "静态检查: 悬浮窗 wm.addView 缺配对 wm.removeView:$unpaired"
    fail=1
  fi
  # v0.2.46 真机踩坑锁定：Android 8+ 通知折叠态只渲染图标按钮，
  # addAction(0=无图标) 在 MIUI/HyperOS 默认折叠下=按钮"消失"
  if grep -rn --include='*.kt' "addAction(0," "$ROOT/app/app/src/main/java" 2>/dev/null; then
    echo "静态检查: 通知动作按钮禁 icon=0（折叠态不可见），用 res/drawable/ic_notif_* 真实图标"
    fail=1
  fi
  # 审计矩阵：GAP 是确认存在的问题，必须失败；⬜ 是尚未验证的欠债，
  # 日常模式告警，严格审计/发版模式失败。只在矩阵文件内统计，避免把
  # AUDIT.md 的说明文字误当成状态。
  if [ -f "$ROOT/docs/AUDIT-MATRIX.md" ]; then
    local matrix="$ROOT/docs/AUDIT-MATRIX.md" blanks gaps
    blanks=$(grep -E '^\|' "$matrix" | grep -o '⬜' | wc -l | tr -d ' ')
    gaps=$(grep -E '^\|' "$matrix" | grep -oE '(^|[^[:alnum:]_])GAP([^[:alnum:]_]|$)' | wc -l | tr -d ' ')
    echo "审计矩阵: ${blanks:-0} 个空格(⬜)待填，${gaps:-0} 个 GAP — docs/AUDIT-MATRIX.md"
    if [ "${gaps:-0}" -gt 0 ]; then
      echo "静态检查: AUDIT-MATRIX.md 仍登记 GAP，必须先修复并填回 PASS/N-A" >&2
      fail=1
    fi
    if [ "$STRICT_AUDIT" = 1 ] && [ "${blanks:-0}" -gt 0 ]; then
      echo "严格审计: AUDIT-MATRIX.md 仍有未验证格子(⬜)" >&2
      fail=1
    fi
  else
    echo "静态检查: 缺少 docs/AUDIT-MATRIX.md" >&2
    fail=1
  fi
  if grep -RInE '/Users/wardonguo/|Documents/work/code/AI' \
      "$ROOT/scripts/e2e.sh" "$ROOT/scripts/drive-test.sh" "$ROOT/scripts/ui.py" >/dev/null 2>&1; then
    echo "静态检查: E2E harness 禁止绑定其他开发机绝对路径" >&2
    fail=1
  fi
  [ "$fail" -eq 0 ]
}

t1_android_unit() {
  if [ ! -x "$GRADLE" ]; then
    echo "gradle 不存在: $GRADLE（设 GRADLE_BIN 环境变量后可启用 T1）"
    return 2
  fi
  if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
    echo "JDK17 不存在（设置 JAVA_HOME 或加入 PATH 后可启用 T1）"
    return 2
  fi
  if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/platform-tools" ]; then
    echo "Android SDK 不存在（设置 ANDROID_HOME 后可启用 T1）"
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
    node --test --experimental-test-coverage 'test/*.test.mjs'
  else
    node --test 'test/*.test.mjs'
  fi
}

t3_blackbox() {
  bash "$ROOT/server/test/run-blackbox.sh"
}

t4_e2e() {
  local ADB="${ADB_BIN:-$(command -v adb 2>/dev/null || true)}"
  if [ -z "$ADB" ]; then
    for candidate in "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
      if [ -x "$candidate" ]; then ADB="$candidate"; break; fi
    done
  fi
  if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then echo "adb 不存在，请设置 ADB_BIN"; return 2; fi
  local devices
  if ! devices=$(python3 - "$ADB" <<'PY'
import subprocess, sys
try:
    out = subprocess.run([sys.argv[1], "devices"], capture_output=True, text=True, timeout=10)
except (OSError, subprocess.TimeoutExpired):
    raise SystemExit(1)
count = sum(1 for line in out.stdout.splitlines()[1:] if line.split()[1:2] == ["device"])
print(count)
PY
  ); then
    echo "adb daemon 在 10 秒内未响应，T4 SKIP" >&2
    return 2
  fi
  if [ "${devices:-0}" -lt 2 ]; then
    echo "需要 2 台在线模拟器/设备（当前 $devices），见 docs/TESTING.md T4 前置"
    return 2
  fi
  ADB_BIN="$ADB" bash "$ROOT/scripts/e2e.sh"
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
