#!/usr/bin/env bash
# Soul2Soul 双模拟器 E2E 测试 (T4 主流程)
# 退出码：0=断言全部通过，1=测试失败，2=环境不满足（test.sh 记为 SKIP）。
# 可覆盖：ADB_BIN、EMU_BIN、APK_PATH、E2E_S1、E2E_S2、E2E_PACKAGE、E2E_LOG_DIR、KEEP_OFF。
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB_BIN:-$(command -v adb 2>/dev/null || true)}"
EMU="${EMU_BIN:-$(command -v emulator 2>/dev/null || true)}"
APK="${APK_PATH:-/tmp/s2s-emu.apk}"
UI_PY="${E2E_UI_PY:-$ROOT/scripts/ui.py}"
S1="${E2E_S1:-emulator-5554}"   # 角色 A: 共享端
S2="${E2E_S2:-emulator-5556}"   # 角色 B: 观看端
PKG="${E2E_PACKAGE:-com.soul2soul.app.emu}"
PASS=0; FAIL=0
LOG_DIR="${E2E_LOG_DIR:-${TMPDIR:-/tmp}/s2s-e2e-$$}"
STARTED_PIDS=()
mkdir -p "$LOG_DIR"

cleanup() {
  local rc=$?
  if [ "${KEEP_OFF:-0}" = "1" ]; then
    local pid
    for pid in "${STARTED_PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
    for pid in "${STARTED_PIDS[@]}"; do wait "$pid" 2>/dev/null || true; done
  fi
  if [ "$rc" -ne 0 ]; then echo "!! T4 失败，日志/截图保留在: $LOG_DIR" >&2; fi
  exit "$rc"
}
trap cleanup EXIT

ck() { if [ "${2:-0}" = "1" ]; then echo "  PASS $1"; PASS=$((PASS+1)); else echo "  FAIL $1"; FAIL=$((FAIL+1)); fi }
shot() { "$ADB" -s "$1" exec-out screencap -p > "$2"; }
ui() { ADB_BIN="$ADB" python3 "$UI_PY" "$@"; }
count_log() { "$ADB" -s "$1" logcat -d 2>/dev/null | grep -cE "$2" || true; }

if [ -z "$ADB" ]; then
  for candidate in "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -x "$candidate" ] && { ADB="$candidate"; break; }
  done
fi
if [ -z "$EMU" ]; then
  for candidate in "$HOME/Library/Android/sdk/emulator/emulator" "$HOME/Android/Sdk/emulator/emulator"; do
    [ -x "$candidate" ] && { EMU="$candidate"; break; }
  done
fi
[ -n "$ADB" ] && [ -x "$ADB" ] || { echo "adb 不存在，请设置 ADB_BIN" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK 不存在: $APK（设置 APK_PATH）" >&2; exit 2; }
[ -f "$UI_PY" ] || { echo "UI 脚本不存在: $UI_PY" >&2; exit 2; }
python3 -c 'from PIL import Image' >/dev/null 2>&1 || { echo "T4 需要 Python Pillow" >&2; exit 2; }

# ---------- 启动两台模拟器（已在跑则复用；顺序启动避免并发冷启动拖垮机器） ----------
wait_boot() { # $1=serial
  for i in $(seq 1 100); do
    local b=$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [ "$b" = "1" ] && return 0
    sleep 3
  done
  return 1
}
booted() { [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = "1" ]; }
start_emu() {
  local avd="$1" port="$2" logfile="$3"
  [ -n "$EMU" ] && [ -x "$EMU" ] || { echo "模拟器未运行且找不到 emulator，请设置 EMU_BIN" >&2; return 2; }
  "$EMU" -avd "$avd" -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port "$port" >"$logfile" 2>&1 &
  STARTED_PIDS+=("$!")
}
B1=0; B2=0
booted "$S1" && B1=1 || true
booted "$S2" && B2=1 || true
if [ "$B1" != "1" ]; then
  start_emu s2s1 5554 "$LOG_DIR/emu1.log" || exit $?
  echo "启动模拟器 A..."
  wait_boot "$S1" && B1=1 || true
fi
if [ "$B2" != "1" ]; then
  start_emu s2s2 5556 "$LOG_DIR/emu2.log" || exit $?
  echo "启动模拟器 B..."
  wait_boot "$S2" && B2=1 || true
fi
ck "两台模拟器完成开机" $([ "$B1" = "1" ] && [ "$B2" = "1" ] && echo 1 || echo 0)
if [ "$B1" != "1" ] || [ "$B2" != "1" ]; then
  echo "!! 模拟器未启动完成" >&2; exit 1
fi
sleep 8  # 让 SystemUI 安顿下来

# ---------- 安装 + 授权 ----------
# 先清掉旧包（避免旧安装干扰），再装新包
for S in "$S1" "$S2"; do
  "$ADB" -s "$S" uninstall com.soul2soul.app >/dev/null 2>&1 || true
  "$ADB" -s "$S" uninstall "$PKG" >/dev/null 2>&1 || true
done
"$ADB" -s "$S1" install -r -t -g "$APK" >"$LOG_DIR/i1.log" 2>&1 && I1=1 || I1=0
"$ADB" -s "$S2" install -r -t -g "$APK" >"$LOG_DIR/i2.log" 2>&1 && I2=1 || I2=0
ck "两端 APK 安装成功" $([ "$I1" = "1" ] && [ "$I2" = "1" ] && echo 1 || echo 0)
if [ "$I1" != "1" ] || [ "$I2" != "1" ]; then
  echo "!! 安装失败:"; tail -20 "$LOG_DIR/i1.log" "$LOG_DIR/i2.log"; exit 1
fi
for S in "$S1" "$S2"; do
  "$ADB" -s "$S" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell cmd appops set "$PKG" USE_FULL_SCREEN_INTENT allow >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
done

# ---------- T4-1 配对 ----------
"$ADB" -s "$S1" shell am start -n "$PKG/com.soul2soul.app.MainActivity" >/dev/null
sleep 6
"$ADB" -s "$S1" logcat -c 2>/dev/null || true
"$ADB" -s "$S2" logcat -c 2>/dev/null || true
ui tap "$S1" "显示我的配对码" >/dev/null 2>&1 || true
sleep 3
CODE="$(ui code "$S1" 2>/dev/null || echo NONE)"
ck "A 生成配对码 ($CODE)" $([ -n "$CODE" ] && [ "$CODE" != "NONE" ] && echo 1 || echo 0)
if [ -z "$CODE" ] || [ "$CODE" = "NONE" ]; then
  echo "!! 未取到配对码，终止" >&2
  shot "$S1" "$LOG_DIR/e2e_fail_pair.png" || true
  exit 1
fi

"$ADB" -s "$S2" shell am start -n "$PKG/com.soul2soul.app.MainActivity" >/dev/null
sleep 3
ui tap "$S2" "输入对方的配对码" >/dev/null 2>&1 || true
sleep 2
"$ADB" -s "$S2" shell input text "$CODE"
sleep 1
ui tap "$S2" "确定" >/dev/null 2>&1 || true
sleep 3
PAIRED=NO
ui has "$S1" "一起玩" && PAIRED=YES || true
ck "配对成功，A 出现「一起玩」" $([ "$PAIRED" = "YES" ] && echo 1 || echo 0)

# ---------- T4-2 呼叫/接听 ----------
INVITED=0
for i in 1 2 3; do
  ui tap "$S1" "一起玩" >/dev/null 2>&1 || true
  sleep 3
  T=$(count_log "$S1" "invite sent")
  [ "$T" -gt "0" ] && { INVITED=1; break; }
done
ck "A 发出呼叫(invite 已发出)" $([ "$INVITED" = "1" ] && echo 1 || echo 0)
INCOMING=NO
ui has "$S2" "好想你" && INCOMING=YES || true
ck "B 收到来电界面" $([ "$INCOMING" = "YES" ] && echo 1 || echo 0)
# 模拟器高负载下 input tap 偶发被吞: 以 logcat "accept sent" 为准重试, 兜底走测试钩子
ACCEPTED=0
for i in $(seq 1 5); do
  ui tap "$S2" "接受" >/dev/null 2>&1 || true
  sleep 3
  T=$(count_log "$S2" "accept sent")
  if [ "$T" -gt "0" ]; then ACCEPTED=1; break; fi
done
if [ "$ACCEPTED" != "1" ]; then
  echo "  (点击重试失败, 使用 autoAccept 测试钩子兜底)"
  "$ADB" -s "$S2" shell am start -n "$PKG/com.soul2soul.app.session.SessionActivity" --ez autoAccept true >/dev/null 2>&1 || true
  sleep 4
  T=$(count_log "$S2" "accept sent")
  [ "$T" -gt "0" ] && ACCEPTED=1
fi
ck "B 点了接听(accept 已发出)" $([ "$ACCEPTED" = "1" ] && echo 1 || echo 0)

# ---------- T4-3 屏幕录制授权(A 端系统弹窗, 兼容单步/两步, 最长等30s) ----------
CONSENT=NO
for i in $(seq 1 10); do
  if ui has "$S1" "Start now|立即开始|Start recording|开始|entire screen|整个屏幕"; then CONSENT=YES; break; fi
  sleep 3
done
if [ "$CONSENT" = "YES" ]; then
  # 两步弹窗先选"整个屏幕"
  ui tap "$S1" "entire screen|整个屏幕" >/dev/null 2>&1 || true
  sleep 2
  ui tap "$S1" "Start now|Start recording|立即开始|开始" >/dev/null 2>&1 || true
  sleep 2
fi
sleep 8
shot "$S1" "$LOG_DIR/e2e_consent.png"
ui list "$S1" "." > "$LOG_DIR/e2e_consent_ui.txt" 2>/dev/null || true
ck "A 端出现屏幕授权弹窗并允许" $([ "$CONSENT" = "YES" ] && echo 1 || echo 0)

# ---------- T4-4 屏幕共享验证：A 打开设置页，B 应同步显示相同画面 ----------
sleep 5
"$ADB" -s "$S1" shell am start -a android.settings.SETTINGS >/dev/null
sleep 6
shot "$S1" "$LOG_DIR/e2e_a.png"
shot "$S2" "$LOG_DIR/e2e_b.png"
DIST=999
if DIST=$(python3 - "$LOG_DIR/e2e_a.png" "$LOG_DIR/e2e_b.png" <<'EOF'
from PIL import Image
import sys
a = Image.open(sys.argv[1]).convert('RGB').resize((64, 64))
b = Image.open(sys.argv[2]).convert('RGB').resize((64, 64))
pa = list(a.getdata()); pb = list(b.getdata())
d = sum(abs(x[0]-y[0]) + abs(x[1]-y[1]) + abs(x[2]-y[2]) for x, y in zip(pa, pb)) / len(pa)
print(f"{d:.1f}")
EOF
); then :; else echo "截图差异计算失败" >&2; fi
echo "  两端截图差异(越低越像): $DIST"
SIMILAR=0
python3 - "$DIST" <<'EOF' >/dev/null 2>&1 && SIMILAR=1 || true
import sys
sys.exit(0 if float(sys.argv[1]) < 60 else 1)
EOF
ck "B 看到了 A 的屏幕(设置页画面同步)" "$SIMILAR"

# ---------- T4-5 标注：B 上画一笔，A 悬浮层应出现笔迹 ----------
shot "$S1" "$LOG_DIR/e2e_a_before.png"
"$ADB" -s "$S2" shell input swipe 300 600 600 900 400
sleep 4
shot "$S1" "$LOG_DIR/e2e_a_after.png"
DIFF=0
if DIFF=$(python3 - "$LOG_DIR/e2e_a_before.png" "$LOG_DIR/e2e_a_after.png" <<'EOF'
from PIL import Image, ImageChops
import sys
a = Image.open(sys.argv[1]).convert('RGB')
b = Image.open(sys.argv[2]).convert('RGB')
d = ImageChops.difference(a, b).resize((64, 64))
cnt = sum(1 for p in d.getdata() if sum(p) > 30)
print(cnt)
EOF
); then :; else echo "笔迹差异计算失败" >&2; fi
echo "  A 截图差异数: $DIFF (笔迹浮现应 > 0)"
ck "B 的笔迹出现在 A 屏幕" $([ "$DIFF" -gt 5 ] 2>/dev/null && echo 1 || echo 0)

# ---------- T4-6 挂断 ----------
ui tap "$S2" "结束" >/dev/null 2>&1 || true
sleep 3
ENDED=0
ui has "$S2" "一起玩" && ENDED=1 || true
ck "挂断后 B 回到主界面" "$ENDED"

echo "== E2E: PASS=$PASS FAIL=$FAIL =="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
exit 0
