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
  # rc=2 是"信令/UI 全绿、媒体段本机不可判定"（test.sh 记 SKIP），不是失败，别把它打印成"失败"
  if [ "$rc" = 2 ]; then echo "!! T4 未全判定（媒体段需真机双端），日志/截图保留在: $LOG_DIR" >&2
  elif [ "$rc" -ne 0 ]; then echo "!! T4 失败，日志/截图保留在: $LOG_DIR" >&2; fi
  exit "$rc"
}
trap cleanup EXIT

ck() { if [ "${2:-0}" = "1" ]; then echo "  PASS $1"; PASS=$((PASS+1)); else echo "  FAIL $1"; FAIL=$((FAIL+1)); fi }
shot() { "$ADB" -s "$1" exec-out screencap -p > "$2"; }
ui() { ADB_BIN="$ADB" python3 "$UI_PY" "$@"; }
count_log() { "$ADB" -s "$1" logcat -d 2>/dev/null | grep -cE "$2" || true; }
has() { # 设备 文本 -> 退出码断言。ui.py 的 has 恒返回 0，只打印 YES/NO；
        # 直接写 `ui has X && OK=YES` 是恒真空断言（v0.2.56 t4 复盘踩过），必须比字符串。
  [ "$(ui has "$1" "$2" 2>/dev/null || echo NO)" = "YES" ]
}
dismiss_guides() { # 设备 -> 点掉首启/配对后的引导弹窗：它们盖在主页上，不点就永远找不到按钮。
  # 弹窗延迟出现，要允许漏看一次；但一次 uiautomator dump 就要 ~4s，所以不再额外 sleep。
  local S="$1" i misses=0
  for i in 1 2 3 4 5; do
    if has "$S" "去设置"; then ui tap "$S" "取消" >/dev/null 2>&1 || true; misses=0; continue; fi
    if has "$S" "让它随叫随到"; then ui tap "$S" "确定" >/dev/null 2>&1 || true; misses=0; continue; fi
    misses=$((misses + 1)); [ "$misses" -ge 2 ] && return 0
  done
}

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
[ -f "$APK" ] || { echo "APK 不存在: ${APK}（设置 APK_PATH）" >&2; exit 2; }
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
  # swiftshader 无头模式里 systemui 常弹 ANR，会把 uiautomator dump 卡死、点击全落空
  "$ADB" -s "$S" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
  # 自动息屏会在无关阶段制造"锁屏"事件，污染会话判据，这里统一拉长
  "$ADB" -s "$S" shell settings put system screen_off_timeout 900000 >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell cmd appops set "$PKG" USE_FULL_SCREEN_INTENT allow >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  # 上一轮被 Ctrl-C 的残留：投屏授权窗还压在 systemui 上，而 uiautomator 只 dump 顶层窗口，
  # 于是「一起玩」明明在界面上却查不到（实测配对已成功却被判失败）。开局先归零。
  # 同类的还有"上一轮崩在锁屏段 → 屏幕是黑的"：实测 drive-test.sh 崩掉后 S1 停在灭屏，
  # 下一轮第 1 步「A 生成配对码」直接读空。灭屏不是可恢复状态，必须显式唤醒+解钥匙屏。
  "$ADB" -s "$S" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
done

# ---------- T4-1 配对 ----------
"$ADB" -s "$S1" shell am start -n "$PKG/com.soul2soul.app.MainActivity" >/dev/null
sleep 6
# 全新安装（上面刚 uninstall）首启会弹电池豁免引导，压在主页之上，配对按钮永远找不到
dismiss_guides "$S1"
"$ADB" -s "$S1" logcat -c 2>/dev/null || true
"$ADB" -s "$S2" logcat -c 2>/dev/null || true
CODE=NONE
for i in 1 2 3 4; do
  ui tap "$S1" "显示我的配对码" >/dev/null 2>&1 || true
  sleep 3
  CODE="$(ui code "$S1" 2>/dev/null || echo NONE)"
  [ -n "$CODE" ] && [ "$CODE" != "NONE" ] && break
  dismiss_guides "$S1"
done
ck "A 生成配对码 ($CODE)" $([ -n "$CODE" ] && [ "$CODE" != "NONE" ] && echo 1 || echo 0)
if [ -z "$CODE" ] || [ "$CODE" = "NONE" ]; then
  echo "!! 未取到配对码，终止" >&2
  shot "$S1" "$LOG_DIR/e2e_fail_pair.png" || true
  exit 1
fi

"$ADB" -s "$S2" shell am start -n "$PKG/com.soul2soul.app.MainActivity" >/dev/null
sleep 3
dismiss_guides "$S2"
DIALOG=0
for i in 1 2 3 4; do
  ui tap "$S2" "输入对方的配对码" >/dev/null 2>&1 || true
  sleep 2
  if has "$S2" "确定"; then DIALOG=1; break; fi
  dismiss_guides "$S2"
done
if [ "$DIALOG" != "1" ]; then
  echo "!! B 端输码对话框打不开，终止" >&2
  shot "$S2" "$LOG_DIR/e2e_fail_dialog.png" || true
  exit 1
fi
"$ADB" -s "$S2" shell input text "$CODE"
sleep 1
PAIRED=NO
for i in 1 2 3 4; do
  ui tap "$S2" "确定" >/dev/null 2>&1 || true
  sleep 3
  dismiss_guides "$S1"
  has "$S1" "一起玩" && { PAIRED=YES; break; }
done
ck "配对成功，A 出现「一起玩」" $([ "$PAIRED" = "YES" ] && echo 1 || echo 0)
if [ "$PAIRED" != "YES" ]; then
  echo "!! 配对未完成，后续断言无意义，终止" >&2
  shot "$S1" "$LOG_DIR/e2e_fail_pair_done.png" || true
  shot "$S2" "$LOG_DIR/e2e_fail_pair_done_b.png" || true
  exit 1
fi

# ---------- T4-2 呼叫：客户端是「先投屏授权、后发邀请」（MainActivity 的 invite 只在
#            MediaProjection 回调里发出），授权不点掉，invite 永远不进信令，B 也永远等不到来电。
#            所以顺序必须是 点一起玩 → 系统授权 → invite → 来电 → 接听。 ----------
CONSENT=NO
for i in 1 2 3; do
  ui tap "$S1" "一起玩" >/dev/null 2>&1 || true
  sleep 3
  for j in 1 2 3 4; do
    # 一次 dump 本身要 ~4s，就是天然的轮询间隔，别再 sleep
    if has "$S1" "Start now|立即开始|Start recording|开始|entire screen|整个屏幕"; then CONSENT=YES; break 2; fi
  done
done
if [ "$CONSENT" = "YES" ]; then
  # 两步弹窗先选"整个屏幕"（once：单选钮点完弹窗仍在，无法用"消失"来校验，
  # 它真生效由下面的 invite sent 证明）
  ui tap "$S1" "entire screen|整个屏幕" once >/dev/null 2>&1 || true
  sleep 2
fi
sleep 8
shot "$S1" "$LOG_DIR/e2e_consent.png"
ui list "$S1" "." > "$LOG_DIR/e2e_consent_ui.txt" 2>/dev/null || true
ck "A 端出现屏幕授权弹窗" $([ "$CONSENT" = "YES" ] && echo 1 || echo 0)

# 「允许」不是一次点击就能证明的：实测点完 Start now 弹窗仍挂在 systemui 上（授权没落地 →
# invite 永远不发）。所以这里重试点击，并用 logcat 的 invite sent 作为唯一真判据（不占 dump）。
INVITED=0
for i in 1 2 3 4; do
  ui tap "$S1" "Start now|Start recording|立即开始|开始" >/dev/null 2>&1 || true
  for j in $(seq 1 8); do
    [ "$(count_log "$S1" "invite sent")" -gt 0 ] && { INVITED=1; break 2; }
    sleep 2
  done
done
ck "A 发出呼叫(invite 已发出=授权真落地)" $([ "$INVITED" = "1" ] && echo 1 || echo 0)
if [ "$INVITED" != "1" ]; then
  echo "!! T4 卡在投屏授权：A 当前焦点与弹窗文本已存 $LOG_DIR/e2e_consent_ui.txt" >&2
  exit 1
fi
# B 收到来电：用 logcat（MainActivity:278 确实打 signal: incoming），不要用 ui dump。
# 来电页 30s 自动拒接（SessionActivity:946 INCOMING_TIMEOUT_MS），而一次 dump 在负载高的宿主要 4~15s，
# v0.2.56 复盘：dump 轮询把窗口吃光 → 主叫只收到 declined，接听判据永远红。
INCOMING=NO
for i in $(seq 1 12); do
  [ "$(count_log "$S2" "signal: incoming")" -gt 0 ] && { INCOMING=YES; break; }
  sleep 1
done
ck "B 收到来电界面(signal: incoming)" $([ "$INCOMING" = "YES" ] && echo 1 || echo 0)

# ---------- T4-3 接听。判据不能用 logcat "accept sent"——代码里根本没有这条日志，
#            它是恒为 0 的空断言（5 次重试 + autoAccept 兜底全白跑，v0.2.56 t4 复盘）。
#            真判据：B 的 accept()(SessionActivity:596) 发 signal "accept"，主叫收到后打 "signal: accept"。
#            autoAccept 兜底也删了：SessionActivity 未 exported，adb(uid 2000) 起不动，
#            实测 Permission Denial: not exported from uid 10202 —— 第二个永远白跑的兜底。 ----------
ACCEPTED=0
for i in 1 2 3 4; do
  ui tap "$S2" "接受" >/dev/null 2>&1 || true
  for j in 1 2 3 4 5; do
    [ "$(count_log "$S1" "signal: accept")" -gt 0 ] && { ACCEPTED=1; break 2; }
    sleep 1
  done
done
ck "B 点了接听(主叫收到 signal: accept)" $([ "$ACCEPTED" = "1" ] && echo 1 || echo 0)

# ---------- 媒体段前置判定：ICE 到底连不连得上 ----------
# 依赖画面的断言（同屏、笔迹）必须先看 ICE；否则它们在本机是**结构性恒假**断言（v0.2.56 t4 复盘）：
# ① 每个模拟器的 qemu SLIRP 都把自己报成 10.0.2.15，host candidate 互指自己，guest↔guest 不可路由；
# ② 本地 dev server 未设 TURN_HOST 时签发占位符 YOUR_SERVER_PUBLIC_IP（server/src/index.js:22），
#    于是既没有 srflx 也没有 relay。实测：signal: ice 正常收发，ICE 仍 CHECKING->FAILED(+15s)。
# 结论只允许两种：连上了就硬断言；没连上就标 SKIP(环境)，绝不折算成 PASS。
ENV_SKIP=0
ICE=UNKNOWN
for i in $(seq 1 20); do
  # CONNECTED 与 FAILED 都是终态，必须同轮观测：只等 CONNECTED 会在"FAILED 恰好落在轮询结束后"
  # 时把环境失败误报成异常（实测 20:20:32 capture_ok → 20:20:48 FAILED，而旧轮询 20:20:47 就退出了）
  C=$(count_log "$S1" "ice state: COMPLETED|ice state: CONNECTED")
  Fl=$(count_log "$S1" "ice state: [A-Z_]+->FAILED")
  [ "${C:-0}" -gt 0 ] && { ICE=OK; break; }
  [ "${Fl:-0}" -gt 0 ] && { ICE=FAILED; break; }
  sleep 2
done
if [ "$ICE" != "OK" ]; then
  FAILED=$(count_log "$S1" "ice state: [A-Z_]+->FAILED")
  LIVEB=$(count_log "$S2" "viewer-ready")
  if [ "$ICE" = "FAILED" ] && [ "${LIVEB:-0}" -eq 0 ]; then
    # 只有"ICE 确实跑过检查并 FAILED、且一帧都没出"才符合上面①②的结构性环境失败特征
    echo "  !! 媒体段本机不可判定：ICE 未连通（FAILED=${FAILED} 次），原因见上方注释①②"
    echo "     媒体/笔迹结论一律以真机双端为准（docs/TESTING.md T4 段），此处按 SKIP 记账，不算通过。"
    ENV_SKIP=2
  else
    echo "  FAIL 媒体段异常：ICE=$ICE FAILED=${FAILED:-0}/viewer-ready=${LIVEB:-0} 不符合" >&2
    echo "     「模拟器不可路由」的特征（gathering 卡死、进程被杀、握手没发都可能），需人工查日志" >&2
    FAIL=$((FAIL + 1))
    ENV_SKIP=1
  fi
fi

# ---------- T4-3b 共享端"结束"必须自报入口（stop_tap） ----------
# 共享端有两条结束入口走的是同一条 ACTION_STOP（App 内按钮、FGS 通知栏动作），日志里原本
# 只留下同一个 reason=local_stop；这一条证"点了哪一个"确实进了报文体。
# 时机是本机最难的一格：ICE 在本机结构性 FAILED，而 A 的会话会在 ice_failed 后约 13 秒被
# 对端的 bye 拆掉（10-19 09:23:43→09:23:56 实测），所以必须 once 单点、不重试——默认重试模式
# 光 dump 就要 30 秒，点下去时按钮早没了（上一版就是这么 FAIL 的）。
# 抢输了不谎报：A 已经收尾（有 diag.session 而没 stop_tap）就记 SKIP(环境)，
# 因为那一刻根本没有可点的"结束"按钮，这条断言在结构上不可执行。
# 诚实边界：通知栏那条要点系统 UI，本机证不了 ⇒ src_notify 仍是零采样，只能等真机。
if [ "$ICE" != "OK" ]; then
  ui tap "$S1" "结束" once >/dev/null 2>&1 || true
  STOPTAP=0
  for i in 1 2 3 4; do
    [ "$(count_log "$S1" '"event":"stop_tap".*"detail":"src_main_page"')" -gt 0 ] && { STOPTAP=1; break; }
    sleep 1.5
  done
  if [ "$STOPTAP" = 1 ]; then
    ck "共享端点结束时报文体里带 src_main_page" 1
  elif [ "$(count_log "$S1" 'diag.session')" -gt 0 ]; then
    echo "  SKIP(环境) 共享端点结束时报文体里带 src_main_page：对端先把 A 的会话拆了，没有可点的「结束」"
  else
    ck "共享端点结束时报文体里带 src_main_page" 0
  fi
fi

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
if [ "$ICE" = "OK" ]; then
  SIMILAR=0
  python3 - "$DIST" <<'EOF' >/dev/null 2>&1 && SIMILAR=1 || true
import sys
sys.exit(0 if float(sys.argv[1]) < 60 else 1)
EOF
  ck "B 看到了 A 的屏幕(设置页画面同步)" "$SIMILAR"
else
  echo "  SKIP(环境) B 看到了 A 的屏幕(ICE 未连通)"
fi

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
if [ "$ICE" = "OK" ]; then
  ck "B 的笔迹出现在 A 屏幕" $([ "$DIFF" -gt 5 ] 2>/dev/null && echo 1 || echo 0)
else
  echo "  SKIP(环境) B 的笔迹出现在 A 屏幕(ICE 未连通)"
fi

# ---------- T4-6 挂断 ----------
ui tap "$S2" "结束" >/dev/null 2>&1 || true
sleep 3
# 判据用 mCurrentFocus，不用「一起玩」：会话结束后 v0.2.51 的电池豁免引导会再次盖住主页，
# 实测 mCurrentFocus 已经是 MainActivity，但 dump 里只有「让它随叫随到/确定」→ 旧判据把
# "已回到主页"误判成失败（第 5 个坑的会话版）。焦点窗是 dumpsys 的硬事实，不受弹窗遮挡。
# 诚实边界：ICE=OK 之外，这一条测的是"会话最终退出"，不是"点挂断生效"——媒体没通时
# ICE 看门狗会先一步结束会话，点击只是碰巧落在已经退出的界面上。
ENDED=0
for i in 1 2 3 4 5; do
  INSESS=$("$ADB" -s "$S2" shell dumpsys window 2>/dev/null | grep -cE "mCurrentFocus=Window.*SessionActivity" || true)
  HOME=$("$ADB" -s "$S2" shell dumpsys window 2>/dev/null | grep -cE "mCurrentFocus=Window.*$PKG.*MainActivity" || true)
  if [ "${INSESS:-0}" -eq 0 ] && [ "${HOME:-0}" -gt 0 ]; then ENDED=1; break; fi
  sleep 2
done
ck "挂断后 B 焦点回到 MainActivity(会话页已退出)" "$ENDED"

echo "== E2E: PASS=$PASS FAIL=$FAIL SKIP_ENV=$ENV_SKIP =="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
# 媒体段被环境挡住时整层记 SKIP(2)，test.sh 不会把它写成 PASS；发版说明必须如实写"媒体未本机验证"。
if [ "$ENV_SKIP" -gt 0 ]; then
  echo "!! T4 信令/UI 段全绿，媒体段本机不可判定（需真机双端）" >&2
  exit 2
fi
exit 0
