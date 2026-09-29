#!/bin/bash
# 精简驱动: 启动模拟器→配对→呼叫→接听(钩子兜底)→授权→静止画面存活→笔迹→锁屏→挂断
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
EMU=$HOME/Library/Android/sdk/emulator/emulator
APK=/tmp/s2s-emu.apk
S1=emulator-5554; S2=emulator-5556
PKG=com.soul2soul.app.emu
ACT=$PKG/com.soul2soul.app.MainActivity
UI="python3 /Users/wardonguo/Documents/work/code/AI/AIworkspace/soul2soul/scripts/ui.py"
P=0; F=0
ck(){ if [ "$2" = "1" ]; then echo "  PASS $1"; P=$((P+1)); else echo "  FAIL $1"; F=$((F+1)); fi }
tapped(){ # 设备 文本 -> 只有真的点过且按钮存在才算 1
  local out; out=$($UI tap "$1" "$2" 2>&1); echo "$out" | grep -q TAPPED && return 0 || return 1
}

# ---------- 启动两台模拟器（已在跑则复用） ----------
B1=$($ADB -s $S1 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
B2=$($ADB -s $S2 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
if [ "$B1" != "1" ] || [ "$B2" != "1" ]; then
  pkill -f "avd s2s1" 2>/dev/null; pkill -f "avd s2s2" 2>/dev/null; sleep 3
  rm -f ~/.android/avd/s2s1.avd/*.lock ~/.android/avd/s2s2.avd/*.lock
  $EMU -avd s2s1 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5554 >/tmp/emu1.log 2>&1 &
  echo "启动模拟器 A..."
  for i in $(seq 1 100); do
    B1=$($ADB -s $S1 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); [ "$B1" = "1" ] && break; sleep 3
  done
  $EMU -avd s2s2 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5556 >/tmp/emu2.log 2>&1 &
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
  $ADB -s $S shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null
  $ADB -s $S shell cmd appops set $PKG USE_FULL_SCREEN_INTENT allow 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
done

$ADB -s $S1 shell am start -n $ACT >/dev/null; $ADB -s $S2 shell am start -n $ACT >/dev/null; sleep 8
$ADB -s $S1 logcat -c; $ADB -s $S2 logcat -c

# 1) 配对
CODE=""
for i in 1 2 3 4; do tapped $S1 "显示我的配对码"; sleep 2; C=$($UI code $S1 2>/dev/null); [ -n "$C" ] && [ "$C" != "NONE" ] && CODE=$C && break; done
ck "A 生成配对码 ($CODE)" $([ -n "$CODE" ] && echo 1 || echo 0)
DIALOG=0
for i in 1 2 3 4; do tapped $S2 "输入对方的配对码"; sleep 2; T=$($UI has $S2 "确定" 2>/dev/null); [ "$T" = "YES" ] && DIALOG=1 && break; done
ck "B 打开输入框" $([ "$DIALOG" = "1" ] && echo 1 || echo 0)
$ADB -s $S2 shell input text "$CODE"; sleep 1
for i in 1 2 3 4; do tapped $S2 "确定"; sleep 3; T=$($UI has $S1 "一起玩" 2>/dev/null); [ "$T" = "YES" ] && break; done
ck "配对完成" $([ "$($UI has $S1 "一起玩" 2>/dev/null)" = "YES" ] && echo 1 || echo 0)

# 2) 呼叫
for i in 1 2 3; do tapped $S1 "一起玩"; sleep 2; T=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "invite sent"); [ "$T" -gt 0 ] && break; done
ck "A 发出邀请" $([ "$($ADB -s $S1 logcat -d 2>/dev/null | grep -c 'invite sent')" -gt 0 ] && echo 1 || echo 0)
IN=0
for i in 1 2 3 4 5; do T=$($UI has $S2 "好想你" 2>/dev/null); [ "$T" = "YES" ] && IN=1 && break; sleep 2; done
ck "B 收到来电" $([ "$IN" = "1" ] && echo 1 || echo 0)

# 3) 接听 (点击重试 + 钩子兜底, 以 logcat 为准)
OK=0
for i in 1 2 3 4; do tapped $S2 "接受"; sleep 2; T=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "accept sent"); [ "$T" -gt 0 ] && OK=1 && break; done
if [ "$OK" != "1" ]; then
  echo "  (钩子兜底 autoAccept)"
  $ADB -s $S2 shell am start -n $PKG/com.soul2soul.app.session.SessionActivity --ez autoAccept true >/dev/null 2>&1; sleep 4
  T=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "accept sent"); [ "$T" -gt 0 ] && OK=1
fi
ck "B 接听(accept sent)" $([ "$OK" = "1" ] && echo 1 || echo 0)

# 4) 授权
UP=0
for i in $(seq 1 10); do T=$($UI has $S1 "Start now" 2>/dev/null); [ "$T" = "YES" ] && UP=1 && break; sleep 3; done
ck "授权弹窗出现" $([ "$UP" = "1" ] && echo 1 || echo 0)
G=0
if [ "$UP" = "1" ]; then for i in 1 2 3 4 5; do tapped $S1 "Start now"; sleep 3; T=$($UI has $S1 "Start now" 2>/dev/null); [ "$T" = "NO" ] && G=1 && break; done; fi
ck "授权完成" $([ "$G" = "1" ] && echo 1 || echo 0)

# 5) 会话存活 60s (静止画面不再被看门狗误杀)
sleep 60
ICEA=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "ice state: COMPLETED\|ice state: CONNECTED")
CKA=$($ADB -s $S1 logcat -d -b crash 2>/dev/null | grep -c "Fatal signal")
CKB=$($ADB -s $S2 logcat -d -b crash 2>/dev/null | grep -c "Fatal signal")
ck "ICE 连接成功" $([ "$ICEA" -gt 0 ] && echo 1 || echo 0)
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
ck "笔迹跨端到达 A" $([ "${RED:-0}" -gt 3 ] && echo 1 || echo 0)

# 7) 锁屏 30s 自动结束 (AC-6)
$ADB -s $S1 shell input keyevent 26; sleep 40
BYE=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "signal: bye\|signal: peer.gone")
ck "锁屏后会话自动结束(B 收到 bye/gone)" $([ "$BYE" -gt 0 ] && echo 1 || echo 0)
$ADB -s $S1 shell input keyevent 26; sleep 2; $ADB -s $S1 shell input keyevent 82

echo "== DRIVE: PASS=$P FAIL=$F =="
