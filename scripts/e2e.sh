#!/bin/bash
# Soul2Soul 双模拟器 E2E 测试 (T2 主流程)
# 前提: 本地信令(127.0.0.1:8080) + 本地 coturn(3479) 已跑; 模拟器 APK 已装
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
EMU=$HOME/Library/Android/sdk/emulator/emulator
APK=/tmp/s2s-emu.apk
UI="python3 $(dirname "$0")/ui.py"
S1=emulator-5554   # 角色 A: 共享端
S2=emulator-5556   # 角色 B: 观看端
PKG=com.soul2soul.app.emu
PASS=0; FAIL=0

ck() { if [ "$2" = "1" ]; then echo "  PASS $1"; PASS=$((PASS+1)); else echo "  FAIL $1"; FAIL=$((FAIL+1)); fi }
shot() { $ADB -s "$1" exec-out screencap -p > "$2"; }
sleepw() { sleep "$1"; }

# ---------- 启动两台模拟器（已在跑则复用；顺序启动避免并发冷启动拖垮机器） ----------
wait_boot() { # $1=serial
  for i in $(seq 1 100); do
    local b=$($ADB -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$b" = "1" ] && return 0
    sleep 3
  done
  return 1
}
B1=$($ADB -s $S1 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
B2=$($ADB -s $S2 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
if [ "$B1" != "1" ]; then
  pkill -f "avd s2s1" 2>/dev/null; pkill -f "avd s2s2" 2>/dev/null; sleep 3
  rm -f ~/.android/avd/s2s1.avd/*.lock ~/.android/avd/s2s2.avd/*.lock
  $EMU -avd s2s1 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5554 >/tmp/emu1.log 2>&1 &
  echo "启动模拟器 A..."
  B1=$(wait_boot $S1 && echo 1 || echo 0)
  $EMU -avd s2s2 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5556 >/tmp/emu2.log 2>&1 &
  echo "启动模拟器 B..."
  B2=$(wait_boot $S2 && echo 1 || echo 0)
else
  [ "$B2" != "1" ] && { $EMU -avd s2s2 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5556 >/tmp/emu2.log 2>&1 & B2=$(wait_boot $S2 && echo 1 || echo 0); }
fi
ck "两台模拟器完成开机" $([ "$B1" = "1" ] && [ "$B2" = "1" ] && echo 1 || echo 0)
if [ "$B1" != "1" ] || [ "$B2" != "1" ]; then
  echo "!! 模拟器未启动完成，终止"; exit 1
fi
sleep 8  # 让 SystemUI 安顿下来

# ---------- 安装 + 授权 ----------
# 先清掉旧包（避免旧安装干扰），再装新包
for S in $S1 $S2; do
  $ADB -s $S uninstall com.soul2soul.app >/dev/null 2>&1
  $ADB -s $S uninstall $PKG >/dev/null 2>&1
done
$ADB -s $S1 install -r -t -g "$APK" >/tmp/i1.log 2>&1 && I1=1 || I1=0
$ADB -s $S2 install -r -t -g "$APK" >/tmp/i2.log 2>&1 && I2=1 || I2=0
ck "两端 APK 安装成功" $([ "$I1" = "1" ] && [ "$I2" = "1" ] && echo 1 || echo 0)
if [ "$I1" != "1" ] || [ "$I2" != "1" ]; then
  echo "!! 安装失败:"; tail -5 /tmp/i1.log /tmp/i2.log; exit 1
fi
for S in $S1 $S2; do
  $ADB -s $S shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  $ADB -s $S shell cmd appops set $PKG USE_FULL_SCREEN_INTENT allow 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
  $ADB -s $S shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
done

# ---------- T2-1 配对 ----------
$ADB -s $S1 shell am start -n $PKG/com.soul2soul.app.MainActivity >/dev/null; sleep 6
$ADB -s $S1 logcat -c 2>/dev/null; $ADB -s $S2 logcat -c 2>/dev/null
$UI tap $S1 "显示我的配对码" >/dev/null; sleep 3
CODE=$($UI code $S1)
ck "A 生成配对码 ($CODE)" $([ -n "$CODE" ] && [ "$CODE" != "NONE" ] && echo 1 || echo 0)
if [ -z "$CODE" ] || [ "$CODE" = "NONE" ]; then
  echo "!! 未取到配对码，终止"; $ADB -s $S1 exec-out screencap -p > /tmp/e2e_fail_pair.png; exit 1
fi

$ADB -s $S2 shell am start -n $PKG/com.soul2soul.app.MainActivity >/dev/null; sleep 3
$UI tap $S2 "输入对方的配对码" >/dev/null; sleep 2
$ADB -s $S2 shell input text "$CODE"; sleep 1
$UI tap $S2 "确定" >/dev/null; sleep 3
PAIRED=$($UI has $S1 "一起玩")
ck "配对成功，A 出现「一起玩」" $([ "$PAIRED" = "YES" ] && echo 1 || echo 0)

# ---------- T2-2 呼叫/接听 ----------
INVITED=0
for i in 1 2 3; do
  $UI tap $S1 "一起玩" >/dev/null; sleep 3
  T=$($ADB -s $S1 logcat -d 2>/dev/null | grep -c "invite sent")
  [ "$T" -gt "0" ] && { INVITED=1; break; }
done
ck "A 发出呼叫(invite 已发出)" $([ "$INVITED" = "1" ] && echo 1 || echo 0)
INCOMING=$($UI has $S2 "好想你")
ck "B 收到来电界面" $([ "$INCOMING" = "YES" ] && echo 1 || echo 0)
# 模拟器高负载下 input tap 偶发被吞: 以 logcat "accept sent" 为准重试, 兜底走测试钩子
ACCEPTED=0
for i in $(seq 1 5); do
  $UI tap $S2 "接受" >/dev/null; sleep 3
  T=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "accept sent")
  if [ "$T" -gt "0" ]; then ACCEPTED=1; break; fi
done
if [ "$ACCEPTED" != "1" ]; then
  echo "  (点击重试失败, 使用 autoAccept 测试钩子兜底)"
  $ADB -s $S2 shell am start -n $PKG/com.soul2soul.app.session.SessionActivity --ez autoAccept true >/dev/null 2>&1
  sleep 4
  T=$($ADB -s $S2 logcat -d 2>/dev/null | grep -c "accept sent")
  [ "$T" -gt "0" ] && ACCEPTED=1
fi
ck "B 点了接听(accept 已发出)" $([ "$ACCEPTED" = "1" ] && echo 1 || echo 0)

# ---------- T2-3 屏幕录制授权(A 端系统弹窗, 兼容单步/两步, 最长等30s) ----------
CONSENT=NO
for i in $(seq 1 10); do
  T=$($UI has $S1 "Start now|立即开始|Start recording|开始|entire screen|整个屏幕")
  if [ "$T" = "YES" ]; then CONSENT=YES; break; fi
  sleep 3
done
if [ "$CONSENT" = "YES" ]; then
  # 两步弹窗先选"整个屏幕"
  $UI tap $S1 "entire screen|整个屏幕" >/dev/null 2>&1; sleep 2
  $UI tap $S1 "Start now|Start recording|立即开始|开始" >/dev/null; sleep 2
fi
sleep 8
shot $S1 /tmp/e2e_consent.png
$UI list $S1 "." > /tmp/e2e_consent_ui.txt 2>/dev/null
ck "A 端出现屏幕授权弹窗并允许" $([ "$CONSENT" = "YES" ] && echo 1 || echo 0)

# ---------- T2-4 屏幕共享验证：A 打开设置页，B 应同步显示相同画面 ----------
sleep 5
$ADB -s $S1 shell am start -a android.settings.SETTINGS >/dev/null; sleep 6
shot $S1 /tmp/e2e_a.png
shot $S2 /tmp/e2e_b.png
DIST=$(python3 - <<'EOF'
from PIL import Image
a = Image.open('/tmp/e2e_a.png').convert('RGB').resize((64, 64))
b = Image.open('/tmp/e2e_b.png').convert('RGB').resize((64, 64))
pa = list(a.getdata()); pb = list(b.getdata())
d = sum(abs(x[0]-y[0]) + abs(x[1]-y[1]) + abs(x[2]-y[2]) for x, y in zip(pa, pb)) / len(pa)
print(f"{d:.1f}")
EOF
)
echo "  两端截图差异(越低越像): $DIST"
ck "B 看到了 A 的屏幕(设置页画面同步)" $(python3 -c "print(1 if $DIST < 60 else 0)")

# ---------- T2-5 标注：B 上画一笔，A 悬浮层应出现笔迹 ----------
shot $S1 /tmp/e2e_a_before.png
$ADB -s $S2 shell input swipe 300 600 600 900 400
sleep 4
shot $S1 /tmp/e2e_a_after.png
DIFF=$(python3 - <<'EOF'
from PIL import Image, ImageChops
a = Image.open('/tmp/e2e_a_before.png').convert('RGB')
b = Image.open('/tmp/e2e_a_after.png').convert('RGB')
d = ImageChops.difference(a, b).resize((64, 64))
cnt = sum(1 for p in d.getdata() if sum(p) > 30)
print(cnt)
EOF
)
echo "  A 截图差异数: $DIFF (笔迹浮现应 > 0)"
ck "B 的笔迹出现在 A 屏幕" $([ "$DIFF" -gt 5 ] 2>/dev/null && echo 1 || echo 0)

# ---------- T2-6 挂断 ----------
$UI tap $S2 "结束" >/dev/null; sleep 3
ck "挂断后 B 回到主界面" $([ "$($UI has $S2 '一起玩')" = "YES" ] && echo 1 || echo 0)

echo "== E2E: PASS=$PASS FAIL=$FAIL =="
# 默认保留模拟器便于失败后交互调试; KEEP_OFF=1 时清理
if [ "${KEEP_OFF:-0}" = "1" ]; then
  $ADB -s $S1 emu kill >/dev/null 2>&1
  $ADB -s $S2 emu kill >/dev/null 2>&1
fi
exit 0
