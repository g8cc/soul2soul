#!/bin/bash
# 精简驱动: 启动模拟器→配对→呼叫→授权→来电接听(主叫 logcat signal: accept 为准)→静止画面存活→笔迹→锁屏保活→挂断
set -u -o pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB_BIN:-$(command -v adb 2>/dev/null || true)}"
EMU="${EMU_BIN:-$(command -v emulator 2>/dev/null || true)}"
APK="${APK_PATH:-/tmp/s2s-emu.apk}"
S1="${E2E_S1:-emulator-5554}"; S2="${E2E_S2:-emulator-5556}"
PKG="${E2E_PACKAGE:-com.soul2soul.app.emu}"
ACT="$PKG/com.soul2soul.app.MainActivity"
UI_PY="${E2E_UI_PY:-$ROOT/scripts/ui.py}"
P=0; F=0
STARTED_PIDS=()
cleanup() {
  local rc=$?
  # AC-6 会真的把 S1 按灭；中途崩/被杀就会把灭屏留给下一轮（实测下一轮第一步读空配对码）。
  # 收尾无条件唤醒+解钥匙屏，幂等且对正常路径无害。
  [ "${KEEP_TEARDOWN_UNLOCK:-1}" != "0" ] && for s in "$S1" "$S2"; do
    $ADB -s "$s" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    $ADB -s "$s" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  done
  if [ "${KEEP_OFF:-0}" = "1" ]; then
    local pid
    for pid in "${STARTED_PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
    for pid in "${STARTED_PIDS[@]}"; do wait "$pid" 2>/dev/null || true; done
  fi
  exit "$rc"
}
trap cleanup EXIT
sess_page(){ # 设备 -> 1=会话页仍是**焦点窗**。只看 mCurrentFocus：
  # dumpsys 的 mFocusedApp 会滞后留在已 finish 的 ActivityRecord 上（实测 B 的会话页 20:52:29 已 CLOSE、
  # mCurrentFocus 已是 MainActivity，而 mFocusedApp 仍写着 SessionActivity）→ 旧口径恒真，
  # "观看端会话页未退出"因此从没红过，锁屏保活的这条判据一直是空证。
  local f; f=$($ADB -s $1 shell dumpsys window 2>/dev/null | grep -cE "mCurrentFocus=Window.*SessionActivity" || true)
  [ "${f:-0}" -gt 0 ] && echo 1 || echo 0
}
anr_in(){ # 设备 -> 本包 ANR/被系统杀掉的次数（模拟器负载高时会把"媒体没通"伪装成产品故障，必须先差分）
  $ADB -s $1 logcat -d 2>/dev/null | grep -cE "ANR in $PKG|Killing [0-9]+:$PKG" || true
}
endlog(){ # 设备 -> 会话结束类日志计数（作差用；基线必须在"会话建立的那一刻"取，见 AC-6）
  $ADB -s $1 logcat -d 2>/dev/null | grep -cE "signal: bye|signal: declined|peer\.gone" || true
}
alive(){ # 设备 -> 1=被控端共享服务仍在跑。会话若被结束，stopSession 会 stopSelf，此处立刻变 0
  local s; s=$($ADB -s $1 shell dumpsys activity services $PKG 2>/dev/null | grep -c "ServiceRecord.*ScreenShareService" || true)
  [ "${s:-0}" -gt 0 ] && echo 1 || echo 0
}
ck(){ if [ "${2:-0}" = "1" ]; then echo "  PASS $1"; P=$((P+1)); else echo "  FAIL $1"; F=$((F+1)); fi }
UI(){ ADB_BIN="$ADB" python3 "$UI_PY" "$@"; }
tapped(){ # 设备 文本 -> 只有真的点过且按钮存在才算 1
  local out; out=$(ADB_BIN="$ADB" python3 "$UI_PY" tap "$1" "$2" 2>&1); echo "$out" | grep -q TAPPED && return 0 || return 1
}
dismiss_guides(){ # 设备 -> 点掉首启/配对后的引导弹窗；它们盖在主页上，不点就永远找不到「一起玩」。
  # 允许漏看一次（弹窗延迟出现），但一次 dump 要 ~4s，天然是轮询间隔，别再 sleep
  local S=$1 i misses=0
  for i in 1 2 3 4 5; do
    if [ "$(UI has $S "去设置" 2>/dev/null || echo NO)" = "YES" ]; then tapped $S "取消"; misses=0; continue; fi
    if [ "$(UI has $S "让它随叫随到" 2>/dev/null || echo NO)" = "YES" ]; then tapped $S "确定"; misses=0; continue; fi
    misses=$((misses+1)); [ "$misses" -ge 2 ] && return 0
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

# ---------- 启动两台模拟器（已在跑则复用） ----------
B1=$($ADB -s $S1 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
B2=$($ADB -s $S2 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
if [ "$B1" != "1" ] || [ "$B2" != "1" ]; then
  if [ -z "$EMU" ] || [ ! -x "$EMU" ]; then
    echo "模拟器未运行且找不到 emulator，请设置 EMU_BIN" >&2
    exit 2
  fi
  "$EMU" -avd s2s1 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5554 >/tmp/emu1.log 2>&1 &
  STARTED_PIDS+=("$!")
  echo "启动模拟器 A..."
  for i in $(seq 1 100); do
    B1=$($ADB -s $S1 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); [ "$B1" = "1" ] && break; sleep 3
  done
  "$EMU" -avd s2s2 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5556 >/tmp/emu2.log 2>&1 &
  STARTED_PIDS+=("$!")
  echo "启动模拟器 B..."
  for i in $(seq 1 100); do
    B2=$($ADB -s $S2 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); [ "$B2" = "1" ] && break; sleep 3
  done
fi
ck "两台模拟器完成开机" $([ "$B1" = "1" ] && [ "$B2" = "1" ] && echo 1 || echo 0)
if [ "$B1" != "1" ] || [ "$B2" != "1" ]; then echo "!! 模拟器未启动，终止"; exit 1; fi
sleep 8

# ---------- 安装最新包（清空数据保证从配对开始的干净状态） ----------
$ADB -s $S1 install -r -t "$APK" >/dev/null 2>&1 && I1=1 || I1=0
$ADB -s $S2 install -r -t "$APK" >/dev/null 2>&1 && I2=1 || I2=0
$ADB -s $S1 shell pm clear $PKG >/dev/null 2>&1
$ADB -s $S2 shell pm clear $PKG >/dev/null 2>&1
ck "两端 APK 安装成功" $([ "$I1" = "1" ] && [ "$I2" = "1" ] && echo 1 || echo 0)
for S in $S1 $S2; do
  # swiftshader 无头模式里 systemui 常弹 ANR，会把 uiautomator dump 卡死、点击全落空（实测烧掉配对 4 次重试）
  $ADB -s $S shell settings put global hide_error_dialogs 1 2>/dev/null
  # 自动息屏会在无关阶段制造"锁屏"事件，污染后面的锁屏判据，这里统一拉长
  $ADB -s $S shell settings put system screen_off_timeout 900000 2>/dev/null
  $ADB -s $S shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null
  $ADB -s $S shell cmd appops set $PKG USE_FULL_SCREEN_INTENT allow 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  # 上一轮中断可能留下 systemui 的投屏授权窗：uiautomator 只 dump 顶层窗口，
  # 它盖着时「一起玩」明明在界面上也查不到（实测配对已成功却被判失败）
  # 更常见的是"上一轮崩在锁屏段，屏幕还黑着"：实测本轮第 1 步「A 生成配对码」直接读空。
  # 灭屏不是可恢复状态，开局必须显式唤醒 + 解钥匙屏（wm dismiss-keyguard 在无密码 AVD 上直接生效）。
  $ADB -s $S shell input keyevent KEYCODE_WAKEUP 2>/dev/null
  $ADB -s $S shell wm dismiss-keyguard 2>/dev/null
  $ADB -s $S shell input keyevent KEYCODE_HOME 2>/dev/null
done

$ADB -s $S1 shell am start -n $ACT >/dev/null; $ADB -s $S2 shell am start -n $ACT >/dev/null; sleep 8
# 首次启动（pm clear 后）会弹电池豁免引导，压在主页之上，配对按钮永远找不到（实测烧光 4 次重试）
dismiss_guides $S1; dismiss_guides $S2
sleep 2
$ADB -s $S1 logcat -c; $ADB -s $S2 logcat -c
# logcat -c 只清默认缓冲；崩溃判据读的是 crash 缓冲，不清的话上一轮的真崩溃会让"零崩溃"恒 FAIL
$ADB -s $S1 logcat -b crash -c 2>/dev/null; $ADB -s $S2 logcat -b crash -c 2>/dev/null

# 1) 配对
CODE=""
for i in 1 2 3 4; do tapped $S1 "显示我的配对码"; sleep 2; C=$(UI code $S1 2>/dev/null || echo NONE); [ -n "$C" ] && [ "$C" != "NONE" ] && CODE=$C && break; done
ck "A 生成配对码 ($CODE)" $([ -n "$CODE" ] && echo 1 || echo 0)
DIALOG=0
for i in 1 2 3 4; do tapped $S2 "输入对方的配对码"; sleep 2; T=$(UI has $S2 "确定" 2>/dev/null || echo NO); [ "$T" = "YES" ] && DIALOG=1 && break; done
ck "B 打开输入框" $([ "$DIALOG" = "1" ] && echo 1 || echo 0)
$ADB -s $S2 shell input text "$CODE"; sleep 1
for i in 1 2 3 4; do tapped $S2 "确定"; sleep 3; dismiss_guides $S1; T=$(UI has $S1 "一起玩" 2>/dev/null || echo NO); [ "$T" = "YES" ] && break; done
PAIR_OK=0; [ "$(UI has $S1 "一起玩" 2>/dev/null || echo NO)" = "YES" ] && PAIR_OK=1
ck "配对完成" "$PAIR_OK"

# 2) 呼叫。客户端顺序是「先投屏授权、后发邀请」(MainActivity:511 注释 + :135 invite 只在授权回调里打)，
#    所以必须先把 Start now 点掉，A 才会真的发出 invite
CALL=0
for i in 1 2 3; do tapped $S1 "一起玩" && CALL=1 && break; sleep 2; done
ck "A 点下一起玩" $([ "$CALL" = "1" ] && echo 1 || echo 0)
UP=0
for i in 1 2 3 4 5; do T=$(UI has $S1 "Start now" 2>/dev/null || echo NO); [ "$T" = "YES" ] && UP=1 && break; done
ck "投屏授权弹窗出现" $([ "$UP" = "1" ] && echo 1 || echo 0)
G=0
if [ "$UP" = "1" ]; then for i in 1 2 3 4 5; do tapped $S1 "Start now"; sleep 3; T=$(UI has $S1 "Start now" 2>/dev/null || echo YES); [ "$T" = "NO" ] && G=1 && break; done; fi
ck "A 授权完成" $([ "$G" = "1" ] && echo 1 || echo 0)

# 3) 邀请（授权回来后才会出现）
INVITE_OK=0
for i in $(seq 1 8); do T=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "invite sent" || true); [ "${T:-0}" -gt 0 ] && INVITE_OK=1 && break; sleep 2; done
ck "A 发出邀请" "$INVITE_OK"
# 来电判据走 logcat（MainActivity:278 确实打 signal: incoming），不要用 ui dump：
# 来电页 30s 就自动拒接（SessionActivity:946 INCOMING_TIMEOUT_MS），而一次 dump ≈4s，
# 省下来的每一秒都得留给「接受」这一跟（v0.2.56 复盘：dump 校验吃掉 15s，接听必超时→主叫收到 declined）。
IN=0
for i in $(seq 1 12); do T=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "signal: incoming" || true); [ "${T:-0}" -gt 0 ] && IN=1 && break; sleep 1; done
ck "B 收到来电(signal: incoming)" "$IN"

# 4) 接听。判据不能用 "accept sent"——代码里没这条日志，v0.2.56 复盘时它是恒为 0 的空断言（4 次重试+兜底全白跑）。
#    也不用 ui dump 校验：B 的 accept()(SessionActivity:596) 会发 signal "accept"，主叫 MainActivity:278 收到就
#    打 "signal: accept"——logcat 比 dump 快一个数量级，30s 来电窗口里省下的时间全得留给「接受」这一跟。
#    不要加 am start --ez autoAccept 兜底：SessionActivity 未 exported，adb(uid 2000) 起不动，
#    实测 Permission Denial: not exported from uid 10202 —— 那是第二个"永远白跑"的兜底。
OK=0
for i in 1 2 3 4; do
  tapped $S2 "接受" >/dev/null
  for j in 1 2 3 4 5; do
    T=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "signal: accept" || true)
    [ "${T:-0}" -gt 0 ] && { OK=1; break 2; }
    sleep 1
  done
done
ck "B 接听(主叫收到 signal: accept)" $([ "$OK" = "1" ] && echo 1 || echo 0)
# 会话建立即取基线：AC-6 的"零 bye"必须覆盖整段窗口。原先在锁屏前才取基线，
# 而 ICE 失败看门狗早已在 +30s 发过 bye（实测 20:52:28），基线把这段吞掉 → 增量为 0 → 恒绿。
EA0=$(endlog $S1); EB0=$(endlog $S2)

# 5) 会话存活 60s (静止画面不再被看门狗误杀)
sleep 60
ICEA=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "ice state: COMPLETED\|ice state: CONNECTED" || true)
FAILED_A=$($ADB -s $S1 logcat -d 2>/dev/null | grep -cE "ice state: [A-Z_]+->FAILED" || true)
LIVEB=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "viewer-ready" || true)
CKA=$($ADB -s $S1 logcat -d -b crash 2>/dev/null | grep -c "Fatal signal" || true)
CKB=$($ADB -s $S2 logcat -d -b crash 2>/dev/null | grep -c "Fatal signal" || true)
ANR=$(($(anr_in $S1)+$(anr_in $S2)))
# 媒体断言失败时先自证是不是环境（两层，缺一不可，否则就是恒假断言）：
# ① 结构性：两个模拟器的 qemu SLIRP 都把自己报成 10.0.2.15，host candidate 互指自己，guest↔guest
#    本就不可路由；本地 dev server 不设 TURN_HOST 时签发的是占位符 YOUR_SERVER_PUBLIC_IP
#    （server/src/index.js:22），于是既无 srflx 也无 relay → ICE 必然 CHECKING->FAILED(+15s 实测)。
# ② 负载性：宿主 load 30+ 时 swiftshader 模拟器会话页起来要 5s+，系统直接 ANR 杀进程
#    （实测 19:30:27 Killing … user request after error）。
# 二者只会让媒体段失去判定力，绝不允许折算成 PASS。
ENV_SKIP=0
if [ "${ICEA:-0}" -gt 0 ]; then
  ck "ICE 连接成功" 1
  ck "B 连通进入画面(onLive)" $([ "${LIVEB:-0}" -gt 0 ] && echo 1 || echo 0)
  MEDIA_LIVE=1
elif [ "${FAILED_A:-0}" -gt 0 ] && [ "${LIVEB:-0}" -eq 0 ]; then
  echo "  SKIP(环境) ICE 连接成功 / B 连通进入画面：ICE 跑过检查但 FAILED=${FAILED_A} 次且零帧，"
  echo "             符合模拟器不可路由(见上方①)——媒体结论只能到真机双端取，不计通过也不计失败。"
  [ "$ANR" -gt 0 ] && echo "             本轮还叠加了 ANR/杀进程 ${ANR} 次（负载①之外的②）"
  ENV_SKIP=2
  MEDIA_LIVE=0
else
  echo "  FAIL ICE 未连通但不符合「模拟器不可路由」特征(FAILED=${FAILED_A:-0}/viewer-ready=${LIVEB:-0}/ANR=${ANR})" >&2
  echo "       gathering 卡死、握手没发、进程被杀都可能，需人工查日志" >&2
  ck "ICE 连接成功" 0
  ck "B 连通进入画面(onLive)" 0
  # 媒体没通就是没通：笔迹断言在这一支同样没有判定力，不再叠一条必然红的空断言。
  # 上面那两声 FAIL 才是信号，笔迹段跟着记 SKIP。
  MEDIA_LIVE=0
fi
ck "A 60s 零崩溃" $([ "$CKA" = "0" ] && echo 1 || echo 0)
ck "B 60s 零崩溃" $([ "$CKB" = "0" ] && echo 1 || echo 0)

# 6) 笔迹: B 画一笔, 立即截 A
$ADB -s $S1 exec-out screencap -p > /tmp/f_before.png
$ADB -s $S2 shell input swipe 400 800 700 1100 400
$ADB -s $S1 exec-out screencap -p > /tmp/f_after.png
RED=$(python3 - <<'EOF'
from PIL import Image
def reds(p):
    im = Image.open(p).convert('RGB'); n=0
    for x in range(0, im.width, 5):
        for y in range(0, im.height, 5):
            r,g,b = im.getpixel((x,y))
            if r>170 and g<120 and b<140: n+=1
    return n
b4, af = reds('/tmp/f_before.png'), reds('/tmp/f_after.png')
print(af-b4 if af>b4 else 0)
EOF
)
echo "  A 端新增红点: $RED"
if [ "$MEDIA_LIVE" = "1" ]; then
  ck "笔迹跨端到达 A" $([ "${RED:-0}" -gt 3 ] && echo 1 || echo 0)
else
  echo "  SKIP(环境) 笔迹跨端到达 A(ICE 未连通，画不出回显)"
fi

# 7) 锁屏保活 (AC-6，v0.2.56 反转：锁屏=画面暂停·语音继续，绝不结束会话)
#    前置条件：锁屏瞬间会话还得活着（共享服务在跑 + 观看端焦点在会话页）。本机 ICE 结构性连不上，
#    失败看门狗在接通后 ~30s 就主动 bye（实测 20:52:15 FAILED → 20:52:28 bye → B 会话页 CLOSE），
#    会话撑不到锁屏。此时四条断言既不能记 PASS（压根没测到）也不能记 FAIL（不是产品结束会话），
#    只能显式记 SKIP(环境)——恒绿与恒红同样有害（docs/TESTING.md 铁律④）。
ALIVE_PRE=$(alive $S1); PAGE_PRE=$(sess_page $S2)
E0A=$(endlog $S1); E0B=$(endlog $S2)
$ADB -s $S1 shell input keyevent 26; sleep 40
if [ "$ALIVE_PRE" = "1" ] && [ "$PAGE_PRE" = "1" ]; then
  ck "锁屏 40s 后被控端共享服务仍在(15s 看门狗已删)" $(alive $S1)
  ck "锁屏 40s 后观看端会话页未退出" $(sess_page $S2)
  ck "锁屏 40s 内双端零 bye/declined/peer.gone" $([ "$(( $(endlog $S1) - E0A + $(endlog $S2) - E0B ))" = 0 ] && echo 1 || echo 0)
  $ADB -s $S1 shell input keyevent 26; sleep 2; $ADB -s $S1 shell input keyevent 82; sleep 10
  ck "解锁 10s 后被控端共享服务仍在(画面恢复的前提；是否真出帧需人眼确认)" $(alive $S1)
else
  WIN=$(( $(endlog $S1) - EA0 + $(endlog $S2) - EB0 ))
  echo "  SKIP(环境) AC-6 锁屏保活四条断言：锁屏前会话已不在（共享服务=${ALIVE_PRE} 会话页=${PAGE_PRE}），"
  echo "             自接通以来双端累计会话结束日志 ${WIN} 条。"
  echo "             本机 ICE 未连通时会话被失败看门狗提前结束，锁屏保活只能到真机双端验（不计通过也不计失败）。"
  $ADB -s $S1 shell input keyevent 82 >/dev/null 2>&1 || true
  ENV_SKIP=2
fi

echo "== DRIVE: PASS=$P FAIL=$F SKIP_ENV=${ENV_SKIP:-0} =="
if [ "$F" -gt 0 ]; then exit 1; fi
# 媒体段被环境挡住时整层记 2（SKIP），与 test.sh/e2e.sh 同一口径：SKIP 永远不等于通过。
if [ "${ENV_SKIP:-0}" -gt 0 ]; then
  echo "!! 信令/UI 段全绿，媒体段本机不可判定（需真机双端）" >&2
  exit 2
fi
exit 0
