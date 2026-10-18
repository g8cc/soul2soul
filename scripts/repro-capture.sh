#!/usr/bin/env bash
# 复现期双端采集（计划 E3 第 3 步）：服务器 diag 流 + 本机真机 logcat 流。
# 为什么要有这个脚本：v0.2.56 那一轮 adb 掉线后 logcat 停在 "- waiting for device -"，
# 采集进程看着还在、其实一个字节都没写，整轮真机日志白丢。所以这里把
# "进程在 ≠ 在采集" 变成机器判据：check 只认**文件字节数在增长**，不认 ps。
#
# 用法：
#   bash scripts/repro-capture.sh start          # 起采集（幂等：先收掉本脚本上一轮的进程）
#   bash scripts/repro-capture.sh check          # 增长判据 + 设备/版本体检；不达标返回 2
#   bash scripts/repro-capture.sh stop           # 收进程
#   SERIAL=7c6a9986 HOST_ALIAS=guo bash scripts/repro-capture.sh start
set -Eeuo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
[ -x "$ADB" ] 2>/dev/null || ADB=$(command -v adb || true)
[ -n "$ADB" ] || { echo "找不到 adb（可用 ADB=/path/to/adb 覆盖）" >&2; exit 2; }

HOST_ALIAS="${HOST_ALIAS:-guo}"
REMOTE_LOG="${REMOTE_LOG:-/opt/soul2soul/server/server.log}"
# 只取诊断与呼叫生命周期，绝不在本地落业务内容（留言正文/语音/配对码）
REMOTE_FILTER='diag\.|\[call\]|\[ws\]|\[relay\]|\[turn\]'
OUT_DIR="${OUT_DIR:-$HOME/s2s-repro}"
STATE="$OUT_DIR/state.tsv"
GROW_WINDOW_S="${GROW_WINDOW_S:-6}"

real_devices() {
  $ADB devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}'
}

pick_serial() {
  if [ -n "${SERIAL:-}" ]; then echo "$SERIAL"; return; fi
  local list n
  list=$(real_devices)
  n=$(printf '%s\n' "$list" | grep -c . || true)
  [ "$n" = "1" ] && printf '%s\n' "$list" | head -1 || true
}

stop_all() {
  [ -f "$STATE" ] || return 0
  local kind pid _f
  while IFS=$'\t' read -r kind pid _f; do
    case "$kind" in
      server|device) [ -n "$pid" ] && kill "$pid" 2>/dev/null || true ;;
    esac
  done < "$STATE"
  rm -f "$STATE"
}

start() {
  mkdir -p "$OUT_DIR"
  stop_all
  local ts serial
  ts=$(date +%Y%m%d-%H%M%S)
  RUN_DIR="$OUT_DIR/$ts"
  mkdir -p "$RUN_DIR"
  printf 'rundir\t%s\t\n' "$RUN_DIR" > "$STATE"

  # 1) 服务器侧：tail -F + 断线重连（ssh 抖动不该让采集静默死掉）
  #    回读 200 行做上下文种子：check 里的"版本上报"要能在刚起采集时就有读数，
  #    否则空文件会让体检误报"设备没打开 App"。
  #    落笔记本前先脱敏：线上那版 send() 的 quiet 名单还没有 paired/pair.code，
  #    [relay] 行会把长期重连凭证与六位配对码原样抄进本地副本（服务器侧修好前先在采集侧堵）。
  #    脱敏必须在远端做：本机这条管道只有一个后台 pid（$!），再多接一段 sed/perl
  #    就会把 ssh 变成 stop 杀不掉的孤儿（采集静默继续写，check 还显示在跑）。
  ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$HOST_ALIAS" \
    "MASK='s/\"(token|code|credential|username)\":\"[^\"]*\"/\"<redacted>\"/g'; \
     tail -n 200 '$REMOTE_LOG' 2>/dev/null | grep -aE '$REMOTE_FILTER' | sed -u -E \"\$MASK\"; \
     while true; do tail -F -n 0 '$REMOTE_LOG' 2>/dev/null | grep -aE '$REMOTE_FILTER' | sed -u -E \"\$MASK\"; sleep 3; done" \
    > "$RUN_DIR/server.log" 2> "$RUN_DIR/server.err" &
  printf 'server\t%s\t%s\n' "$!" "$RUN_DIR/server.log" >> "$STATE"

  # 2) 真机侧：threadtime 带绝对时间，才能和服务器日志对表。
  #    -T 1 = 只从最后一行往后写：不带 -T 会把整块 ring buffer 倒进采集文件，
  #    采集起点变得含混（和坑⑮"基线吞掉窗口"同一类错误）。
  #    ⚠ 设备时钟未必对（实测 Mi5 比宿主慢 ~10.5h），所以时间轴一律以服务器日志为准，
  #    设备行只做事件序列，不许直接拿 logcat 时间戳和服务器对表。
  serial=$(pick_serial)
  if [ -n "$serial" ]; then
    "$ADB" -s "$serial" logcat -T 1 -v threadtime \
      S2S-Diag:V S2S-Session:V S2S-Ctl:V PresenceService:V ScreenShareService:V \
      SessionActivity:V Overlay:V WifiKeeper:V MediaProjectionManager:V \
      ActivityTaskManager:I AndroidRuntime:E "*:S" \
      > "$RUN_DIR/device-$serial.log" 2>&1 &
    printf 'device\t%s\t%s\n' "$!" "$RUN_DIR/device-$serial.log" >> "$STATE"
  else
    printf 'device\t-\t\n' >> "$STATE"
    local n; n=$(real_devices | grep -c . || true)
    if [ "${n:-0}" -gt 1 ]; then
      echo "⚠ 在场真机 ${n} 台，采集认不出该采哪台，客户端侧已放弃：用 SERIAL=<序列号> 重跑 start（复现那台必须显式指定，否则整轮只有服务器日志）。" >&2
      real_devices | sed 's/^/    SERIAL=/' >&2
    else
      echo "⚠ 未接真机（没有非模拟器设备且未指定 SERIAL）：服务器侧仍在采，客户端侧没有。" >&2
    fi
  fi

  echo "采集目录：$RUN_DIR"
  echo "开始复现前先跑：bash scripts/repro-capture.sh check"
}

# grew <file>：窗口内字节数变了返回 0
grew() {
  local f=$1 a b
  [ -f "$f" ] || return 1
  a=$(wc -c < "$f" | tr -d ' ')
  sleep "$GROW_WINDOW_S"
  b=$(wc -c < "$f" | tr -d ' ')
  echo "${b:-0} ${a:-0}"
  [ "$b" -gt "$a" ]
}

check() {
  [ -f "$STATE" ] || { echo "没在采集（先 bash scripts/repro-capture.sh start）" >&2; exit 2; }
  local ok=1 kind pid file rundir="" srv="" dev="" serial="" saw_srv=0 saw_dev=0
  while IFS=$'\t' read -r kind pid file; do
    case "$kind" in
      rundir) rundir=$pid ;;
      server) saw_srv=1; srv=$file
              [ "$pid" != "-" ] && kill -0 "$pid" 2>/dev/null || { echo "  FAIL server 采集进程已退出"; ok=0; } ;;
      device) saw_dev=1; [ "$pid" = "-" ] && { echo "  FAIL 客户端 logcat 未启动（真机没接，或多台在场未指定 SERIAL——看 start 的提示）"; ok=0; } \
              || { dev=$file; serial=$(basename "$file"); serial=${serial#device-}; serial=${serial%.log}
                   kill -0 "$pid" 2>/dev/null || { echo "  FAIL device logcat 进程已退出"; ok=0; }; } ;;
    esac
  done < "$STATE"
  [ -n "$rundir" ] || { echo "  FAIL state 里没有 rundir（state 坏了，重跑 start）" >&2; exit 2; }
  # 缺行必须红：state 里没有 device/server 行 = 这一轮根本没有那一路日志，
  # 而下面按 srv/dev 分支走会因为变量空而整段跳过 → 打出 READY（红一次实测过）。
  [ "$saw_srv" = 1 ] || { echo "  FAIL state 里没有 server 行——服务器侧没在采"; ok=0; }
  [ "$saw_dev" = 1 ] || { echo "  FAIL state 里没有 device 行——客户端侧没在采（重跑 start）"; ok=0; }

  # 判据一：传输本身还活着。上一轮的死法是"进程在、adb 传输没了、文件永不增长"，
  # 光看 ps 看不出来，所以这里主动打一次哑铃，而不是等业务流量。
  if ssh -o ConnectTimeout=8 -o BatchMode=yes "$HOST_ALIAS" "echo ok" >/dev/null 2>&1; then
    echo "  PASS ssh 到 $HOST_ALIAS 可用（服务器侧采集能继续写）"
  else
    echo "  FAIL ssh 到 $HOST_ALIAS 不通——采集即使没退也写不进东西"; ok=0
  fi

  if [ -n "$serial" ]; then
    if [ "$($ADB devices | awk -v s="$serial" '$1==s && $2=="device" {print 1}')" = "1" ] &&
       [ "$($ADB -s "$serial" shell echo ok 2>/dev/null | tr -d '\r')" = "ok" ]; then
      echo "  PASS 真机 $serial 传输可用（logcat 能继续写）"
    else
      echo "  FAIL 真机 $serial 不在场或无响应——logcat 进程会停在 waiting for device 且文件不动"
      ok=0
    fi
  fi

  # 判据二：文件真的在长。静默期不涨是正常（没人在操作），所以只报 WARN 不判死；
  # 但复现进行中一直是 0，就等于这一轮白采——那时候别看 ps，看这里。
  local delta
  if delta=$(grew "$srv"); then echo "  PASS 服务器日志在增长（${delta} 前后字节）"
  else echo "  WARN 服务器日志 ${GROW_WINDOW_S}s 零增长：无人操作时正常，复现中若一直如此=没采到"; fi
  if [ -n "$dev" ]; then
    if delta=$(grew "$dev"); then echo "  PASS 真机 logcat 在增长（$(basename "$dev") ${delta}）"
    else echo "  WARN 真机 logcat ${GROW_WINDOW_S}s 零增长：若 $serial 正在被操作却仍是 0，文件句柄或过滤器有问题"; fi
  fi

  if [ -n "$srv" ] && [ -f "$srv" ]; then
    echo "  最近的双端版本上报（低于本轮候选就别怪代码）："
    grep -a "diag.version" "$srv" | tail -2 | sed 's/^/    /' || echo "    （暂无——设备没连上或没打开 App）"
  fi
  echo "  在场真机：$(real_devices | tr '\n' ' ' || true)"

  if [ "$ok" = 1 ]; then echo "READY：可以开始复现"; return 0; fi
  echo "NOT READY：先修上面 FAIL 的链路，再复现（否则这轮日志又白丢）" >&2
  return 2
}

case "${1:-check}" in
  start) start ;;
  stop)  stop_all; echo "已收进程" ;;
  check) check ;;
  *) echo "用法: start|check|stop" >&2; exit 2 ;;
esac
