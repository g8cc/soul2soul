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
  # v0.2.59 待机遥测的注册对称性：StandbyProbe 是本项目第一处"非会话期"系统广播监听，
  # 漏一条 unregister 就是解绑后仍在收 SCREEN_OFF/网络回调（耗电 + 攒发不出去的事件）。
  # 形状照上面 wm.addView/removeView 那条：按文件配对，不按行号。
  local orphaned kf
  orphaned=""
  for kf in $(grep -rl --include='*.kt' "\.registerReceiver(" "$ROOT/app/app/src/main/java" 2>/dev/null); do
    grep -q "unregisterReceiver(" "$kf" || orphaned="$orphaned $kf"
  done
  if [ -n "$orphaned" ]; then
    echo "静态检查: registerReceiver 缺配对 unregisterReceiver:$orphaned"
    fail=1
  fi
  orphaned=""
  for kf in $(grep -rl --include='*.kt' "registerDefaultNetworkCallback(" "$ROOT/app/app/src/main/java" 2>/dev/null); do
    grep -q "unregisterNetworkCallback(" "$kf" || orphaned="$orphaned $kf"
  done
  if [ -n "$orphaned" ]; then
    echo "静态检查: registerDefaultNetworkCallback 缺配对 unregisterNetworkCallback:$orphaned"
    fail=1
  fi
  # diag.session 的这两列服务器印了两天全是 `_`（字段在服务端一直存在，客户端从没 put 过）
  for kf in path proj; do
    grep -q "put(\"$kf\"" "$ROOT/app/app/src/main/java/com/soul2soul/app/util/SelfCheck.kt" || {
      echo "静态检查: SelfCheck.kt 必须 put(\"$kf\")（缺了它 diag.session 该列永远打印不出 P2P/TURN 之别）"
      fail=1
    }
  done
  # 补报队列的分级淘汰与有界 flush：回滚成 takeLast(200)/单批 20 条就是"在最需要证据的时刻把证据扔了"
  grep -q "BacklogTiers.trim(" "$ROOT/app/app/src/main/java/com/soul2soul/app/util/DiagBacklog.kt" || {
    echo "静态检查: DiagBacklog 必须经 BacklogTiers.trim 分级淘汰（takeLast 保最新会先挤掉死亡瞬间的最旧几条）"
    fail=1
  }
  grep -q "val head = lines.take(FLUSH_MAX)" "$ROOT/app/app/src/main/java/com/soul2soul/app/util/DiagBacklog.kt" || {
    echo "静态检查: DiagBacklog.drain 必须一次取 FLUSH_MAX 而不是 FLUSH_BATCH（退回单批 20 条时，抖动期每 17~104s 才重连一次，200 条积压要十次才排空）"
    fail=1
  }
  # 只 grep 常量名会被"名字留着、值改成 1"绕过（10-18 实测这么改 t0 照样绿），所以值也要卡
  grep -qE "FLUSH_MAX_BATCHES = ([2-9]|[1-9][0-9])" "$ROOT/app/app/src/main/java/com/soul2soul/app/util/DiagBacklog.kt" || {
    echo "静态检查: FLUSH_MAX_BATCHES 必须 ≥2（=1 就等于没有有界多批，只剩一个会骗人的名字）"
    fail=1
  }
  # t4 harness 自身的自检：`ui tap` 的"点完确认消失、没消失就重试"是防 input tap 被吞的
  # 唯一机制，而它坏起来没有静态形状可看：6c0facb(10-17) 写这段时列表推导引用了未绑定的名字，
  # 运行时抛 NameError → ui.py 退出 3 → e2e 里全是 `ui tap … || true` ⇒ 从那天起真机断言
  # 静默退化成"点一次不校验"（点击本身仍落在 clickable 节点上，所以 t4 一直绿），
  # 10-19 手跑 ui.py 才看见那行 ERROR。只有真的跑一遍这条路径才能区分"在保护"和"已废"。
  if ! python3 "$ROOT/scripts/ui_selftest.py" >/dev/null 2>&1; then
    echo "静态检查: scripts/ui_selftest.py 未通过——ui.py 的 tap 重试/校验路径坏了，t4 会退化成点一次不校验（真机断言不可信）"
    fail=1
  fi
  # SKIP_ENV 必须由被跳过的断言自己累加。10-19 复核 t4 输出：汇总行说 `SKIP_ENV=2` 而屏上打了
  # 三行 SKIP(环境)——stop_tap 那条没进账；drive-test.sh 里两处 `ENV_SKIP=2` 还是覆盖不是累加
  # （AC-6 那条明明写着"四条断言"）。发版说明要写"媒体未本机验证"，具体几条必须数得出来。
  if grep -nE 'ENV_SKIP=[0-9]+' "$ROOT/scripts/e2e.sh" "$ROOT/scripts/drive-test.sh" 2>/dev/null \
     | grep -vqE 'ENV_SKIP=0$'; then
    echo '静态检查: SKIP_ENV 不许写死数字（只许 ENV_SKIP=0 初始化 + ENV_SKIP=$((ENV_SKIP + n)) 累加；写死会盖掉别处已记的账）'
    fail=1
  fi
  # v0.2.57 生产日志复盘实锤：[relay] 把 paired.token（长期重连凭证，16 条）与
  # pair.code（六位配对码，12 条）原样落盘。隐私名单收在 logpolicy.js，
  # index.js 必须走它——把 send() 改回内联 quiet 判定就是拆线，必须红。
  if ! grep -q "QUIET_TYPES.has(obj.type)" "$ROOT/server/src/index.js" 2>/dev/null; then
    echo "静态检查: server/src/index.js 的 relay 日志必须走 logpolicy.QUIET_TYPES（禁止内联 quiet 判定）"
    fail=1
  fi
  # 日志行裸插客户端字段 = 一个含换行的 model 就能伪造任意日志行（diag 行全部要过 safe*）
  if grep -q '${msg\.' "$ROOT/server/src/index.js" 2>/dev/null; then
    echo "静态检查: 日志行禁止裸插 \${msg.x}，必须走 safeTag/safeNum/safeBool（防日志伪造）"
    fail=1
  fi
  # 判据必须能在真日志里 grep 到：safeTag 把 detail 里的 `=` 和空格全磨成 `_`
  # （生产实测 `detail=established_true_restarts_0`），所以文档里"补报口径"只能写
  # `detail=at_`。谁把它改回 `detail=at=`，复现时就会按一个不存在的键 grep → 误判"没上报"。
  if grep -q 'detail=at=' "$ROOT/docs/PROTOCOL.md" 2>/dev/null ||
     ! grep -q 'detail=at_' "$ROOT/docs/PROTOCOL.md" 2>/dev/null ||
     ! grep -q 'safeTag' "$ROOT/docs/PROTOCOL.md" 2>/dev/null; then
    echo "静态检查: docs/PROTOCOL.md 的补报 grep 口径必须是 detail=at_（safeTag 会磨平等号），且保留 safeTag 警示"
    fail=1
  fi
  # 同上锁住 v0.2.59 新增的两个 detail 键：代码发的是 `k=` / `q=`（逗号分隔），
  # 而 safeTag 把等号与逗号一起磨成 `_`，所以文档必须同时给出日志侧对形（`k_123456…` / `q_29_h-s…`），
  # 否则复现时按带等号的写法 grep 必然 0 命中 → 又被读成"埋点坏了"。
  # `beat=` 是实现前计划稿里的名字，代码里从来没有过；文档一旦写它就是第三套假口径。
  if grep -qF '`beat=`' "$ROOT/docs/PROTOCOL.md" "$ROOT/docs/AUDIT-MATRIX.md" "$ROOT/AGENTS.md" 2>/dev/null ||
     ! grep -qF 'k_123456' "$ROOT/docs/PROTOCOL.md" 2>/dev/null ||
     ! grep -qF 'q_29_h-s' "$ROOT/docs/PROTOCOL.md" 2>/dev/null; then
    echo "静态检查: 心跳锚点键名只能是 k=（代码里没有 beat=），且 docs/PROTOCOL.md 必须保留日志侧对形 k_123456… / q_29_h-s…" >&2
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
  # v0.2.56 t4 复盘：uiautomator 按文档顺序返回，说明文字排在按钮之前，`ui tap 接受` 曾一直点在
  # 「接受吗？」正文上 → 来电 30s 自动拒接、投屏授权永不落地。锁死「点击优先 clickable」这条不变式，
  # 回滚成直接取首个命中即变红（详见 docs/TESTING.md T4 坑⑨）。
  if ! grep -q 'el.get("clickable") == "true"' "$ROOT/scripts/ui.py" \
     || ! sed -n '/^def tap_text/,/^def /p' "$ROOT/scripts/ui.py" | grep -q "_match_one"; then
    echo "静态检查: ui.py 的 tap_text 必须经 _match_one 优先取 clickable 节点" >&2
    fail=1
  fi
  # v0.2.56 t4 复盘（run5）：`grep -cE "ice state: [A-Z]->FAILED"` 里 [A-Z] 只吃**一个**字符，
  # 真实行是 `ice state: CHECKING->FAILED` → 判据结构性恒假，把本该 SKIP(环境) 的媒体段
  # 报成了 FAIL。状态机转移判据的字符类必须带量词，回滚成 [A-Z] 即变红（TESTING.md 坑⑬）。
  if grep -qn '\[A-Z\]->' "$ROOT/scripts/e2e.sh" "$ROOT/scripts/drive-test.sh"; then
    echo "静态检查: harness 的 ICE 转移判据必须写 [A-Z_]+->（单字符类匹配不到 CHECKING->FAILED=恒假）" >&2
    fail=1
  fi
  # v0.2.56 t4 复盘（drive-run1）：sess_page 把 dumpsys 的 mFocusedApp 也统计进来，而它滞后停在
  # 已 finish 的 ActivityRecord 上（实测 B 会话页 CLOSE、mCurrentFocus 已是 MainActivity，
  # mFocusedApp 仍写着 SessionActivity）→「观看端会话页未退出」恒真。焦点判据只准看 mCurrentFocus。
  if ! sed -n '/^sess_page/,/^}/p' "$ROOT/scripts/drive-test.sh" | grep -q 'grep -cE "mCurrentFocus=Window'; then
    echo "静态检查: drive-test.sh 的 sess_page 必须只统计 mCurrentFocus=Window.*SessionActivity" >&2
    fail=1
  fi
  # 中文文案里"变量引用紧跟全角括号"会被 bash 把多字节字符吞进变量名，set -u 下当场 unbound
  # 崩掉整段（实测 drive-test.sh AC-6 的 SKIP 打印就是这么死的）。必须用大括号形式。
  # ⚠️ 这里的字符类以前只有 `[A-Z_]`：小写变量（`$jar`/`$devices`/`$aei_bad`）全部漏网，
  # 10-18 加 aexit 号—名表守卫时踩到——检测器绿着、脚本当场 "unbound variable" 崩在半路，
  # 而 t0 的"PASS"是上一轮缓存的错觉。名字大小写不是安全边界，两条都要查。
  local fullwidth
  fullwidth=$(python3 - "$ROOT/scripts" <<'PY'
import glob, re, sys
bad = []
for f in sorted(glob.glob(sys.argv[1] + "/*.sh")):
    for i, line in enumerate(open(f, encoding="utf-8"), 1):
        for m in re.finditer(r"\$[A-Za-z_][A-Za-z0-9_]*", line):
            nxt = line[m.end():m.end() + 1]
            if nxt and ord(nxt) > 127:
                bad.append("%s:%d" % (f, i))
                break
print(" ".join(bad))
PY
)
  if [ -n "$fullwidth" ]; then
    echo "静态检查: harness 变量引用紧跟全角字符（bash 会吞进变量名，set -u 当场崩），改大括号形式: $fullwidth" >&2
    fail=1
  fi
  # 铁律②的机器版：harness 里每个 `signal: X` 判据的 X 必须是源码里真实写过的信号字面量。
  # v0.2.56 之前 `"accept sent"` 这条不存在的日志当判据用了半年（恒假，重试+兜底全白跑）。
  local sig missing=""
  for sig in $(grep -ohE 'signal: [a-z][a-z.]*' "$ROOT/scripts/e2e.sh" "$ROOT/scripts/drive-test.sh" \
               | awk '{print $2}' | sort -u); do
    grep -Rq "\"$sig\"" "$ROOT/app/app/src/main/java" || missing="$missing $sig"
  done
  if [ -n "$missing" ]; then
    echo "静态检查: harness 判据用的信号类型源码里不存在（恒假断言）:$missing" >&2
    fail=1
  fi
  # v0.2.56 复盘的第二个坑：采集脚本 check 里"这一路根本没启动"必须显式红。
  # 实测第一版把 device 行漏写进 state，check 因为变量为空整段跳过设备分支 → 打 READY，
  # 而客户端日志一个都没采（和 0.2.56 那轮 logcat 停在 waiting for device 同一类错误）。
  if ! grep -q 'saw_dev" = 1' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q 'saw_srv" = 1' "$ROOT/scripts/repro-capture.sh" 2>/dev/null; then
    echo "静态检查: repro-capture.sh 的 check 缺 state 缺行守卫（缺一路日志会被打成 READY）" >&2
    fail=1
  fi
  # 复现日常把米10 和 Mi5 开发机同时插着：pick_serial 只在恰好一台真机时才猜，多台时脚本必须
  # 把"该给哪个 SERIAL"摊到人面前（含候选行），否则 start 静默不采客户端侧、人却去查线。
  if ! grep -q 'SERIAL=<' "$ROOT/scripts/repro-capture.sh" 2>/dev/null; then
    echo "静态检查: repro-capture.sh 多台真机时不给 SERIAL 候选（客户端日志会静默不采，等于白跑一轮）" >&2
    fail=1
  fi
  # 线上那版 send() 的 quiet 名单还没有 paired/pair.code：[relay] 行带长期重连凭证。
  # 采集侧的脱敏是本地副本唯一的线，去掉它等于把 token 抄进笔记本（实测 sed -l 会吞掉 -E，
  # 静默不生效且看起来像在工作，所以连 -u 一起锁）。
  if ! grep -q 'sed -u -E' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q '<redacted>' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     [ "$(grep -c -- '--line-buffered' "$ROOT/scripts/repro-capture.sh" 2>/dev/null)" -lt 2 ]; then
    echo "静态检查: repro-capture.sh 缺服务器日志脱敏（sed -u -E + <redacted>）或远端 grep 缺 --line-buffered（两处都要），token 会原样落本机 / 最新断连行会卡在 4KB 缓冲区被丢" >&2
    fail=1
  fi
  # 「不再二次埋点」的交付物本身也得有锁：summary 若把事件清单写死在脚本里，
  # 文档与判据就各自腐烂（新增的名字永远"没响"，看上去像埋点坏了）；
  # 补报口径必须是 detail=at_ ——safeTag 把等号磨成下划线，at= 是一个 grep 不到的假口径。
  if ! grep -q "DIAG-EVENT-NAMES:" "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q 'summary) summary' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q 'detail=at_' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     grep -q 'detail=at=' "$ROOT/scripts/repro-capture.sh" 2>/dev/null; then
    echo "静态检查: repro-capture.sh 的 summary 必须读 docs/PROTOCOL.md 的 DIAG-EVENT-NAMES（禁止写死清单）、必须挂进 case、补报口径只能是 detail=at_" >&2
    fail=1
  fi
  # 「乐观发送盲区」是这一版补报成立的前提，文档必须先承认它：send() 写进缓冲就返回 true，
  # 所以 net_down 这类"断网瞬间"的事件会静默消失，靠 down 沿重放副本才补得回来。
  # 谁把这条从 PROTOCOL 里删掉，读日志的人就会把"没补到"读成"没发生"。
  if ! grep -q '乐观发送' "$ROOT/docs/PROTOCOL.md" 2>/dev/null; then
    echo "静态检查: docs/PROTOCOL.md 缺「乐观发送」盲区口径（补报只覆盖 socket 已判死后的事件，死亡窗口靠重放副本）" >&2
    fail=1
  fi
  # 至少一次投递 = 同一事件会有"一条实时 + 一条 at_ 副本"。summary 若只给合并计数，
  # 一次断网就能把 heartbeat 数成 4 次（10-18 真机实测就是这样），读数自己造出"心跳变密"的故事；
  # 所以分开计数的判据 + 文档里的去重口径，两头都不许烂。
  if ! grep -q '补报副本' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q 'rep\[e\]++' "$ROOT/scripts/repro-capture.sh" 2>/dev/null ||
     ! grep -q '不能把 live 与' "$ROOT/docs/PROTOCOL.md" 2>/dev/null; then
    echo "静态检查: summary 缺「实时/补报副本」分开计数，或 PROTOCOL 缺「live 与 at_ 不相加」去重口径（合并计数会把一次事件读成两次）" >&2
    fail=1
  fi
  # StandbyPolicy.bucketName 的字面量表是照记忆写的，v0.2.59 第一版就把 RESTRICTED 记成 50
  # （平台 android-34/35/36 实测是 45，50 不存在）⇒ 真机掉进 restricted 会被读成"未知档"，
  # 而这正是米15 待机断网最想要的那一格。这里直接拿编译 SDK 的 android.jar 对表，凭记忆改立刻红。
  local jar javap_bin bucket_src declared got missing extra
  javap_bin="${JAVA_HOME:+$JAVA_HOME/bin/javap}"; [ -x "${javap_bin:-}" ] || javap_bin=$(command -v javap 2>/dev/null || true)
  jar="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/$(grep -oE 'compileSdk = [0-9]+' "$ROOT/app/app/build.gradle.kts" | grep -oE '[0-9]+' | sed 's/^/android-/')/android.jar"
  bucket_src="$ROOT/app/app/src/main/java/com/soul2soul/app/util/StandbyPolicy.kt"
  if [ -n "$javap_bin" ] && [ -f "$jar" ]; then
    declared=$("$javap_bin" -constants -cp "$jar" android.app.usage.UsageStatsManager 2>/dev/null |
               { grep -oE 'STANDBY_BUCKET_[A-Z_]+ = [0-9]+' || true; } | awk '{print $NF}' | sort -un)
    got=$(grep -oE '^[[:space:]]+[0-9]+ -> ' "$bucket_src" 2>/dev/null | tr -cd '0-9 \n' | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -un)
    if [ -z "$declared" ]; then
      echo "静态检查: javap 没能从 $jar 读出 STANDBY_BUCKET_*（bucket 档位表这轮没校验上，不算通过）" >&2
      fail=1
    else
      missing=$(comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$got"))
      extra=$(comm -13 <(printf '%s\n' "$declared") <(printf '%s\n' "$got"))
      if [ -n "$missing" ] || [ -n "$extra" ]; then
        echo "静态检查: StandbyPolicy.bucketName 与平台常量不一致 缺=$(echo $missing) 多=$(echo $extra)（档位名撒谎=待机断网判据直接读错）" >&2
        fail=1
      fi
    fi
  else
    echo "静态检查: SKIP bucket 档位表校验（缺 javap 或 ${jar}；SKIP 不等于通过）"
  fi
  # aexit 的号—名表是文档里唯一"照记忆写枚举号"的地方，v0.2.59 第一版就把 REASON_OTHER(13) 说成
  # 低内存、REASON_FREEZER(14) 说成用户手动停止——这两格恰恰是"她是被 ROM 杀了还是自己睡死"的判据，
  # 号读反了整轮取证方向就反。与 bucket 同一处理：拿 android.jar 对表。
  # REASON_* 查双向（漏一档=以后读到这个号只能猜）；IMPORTANCE_* 只查正向
  # （IMPORTANCE_CACHED 与 IMPORTANCE_BACKGROUND 同为 400，反向会误报缺档）。
  local aei_doc_r aei_doc_i aei_declared aei_bad="" aei_pair
  aei_doc_r=$(grep -oE '[0-9]+=REASON_[A-Z_]+' "$ROOT/docs/PROTOCOL.md" 2>/dev/null || true)
  aei_doc_i=$(grep -oE '[0-9]+=IMPORTANCE_[A-Z_]+' "$ROOT/docs/PROTOCOL.md" 2>/dev/null || true)
  if [ -z "$aei_doc_r" ] || [ -z "$aei_doc_i" ]; then
    echo "静态检查: docs/PROTOCOL.md 缺 AEI-REASONS / AEI-IMPORTANCE 对照行（aexit 的号没人能对表）" >&2
    fail=1
  elif [ -n "$javap_bin" ] && [ -f "$jar" ]; then
    aei_declared=$("$javap_bin" -constants -cp "$jar" android.app.ApplicationExitInfo 2>/dev/null |
                   { grep -oE 'REASON_[A-Z_]+ = [0-9]+' || true; } |
                   { sed -E 's/([A-Z_]+) = ([0-9]+)/\2=\1/'; } | sort -u)
    if [ -z "$aei_declared" ]; then
      echo "静态检查: javap 没能从 $jar 读出 REASON_*（aexit 号—名表这轮没校验上，不算通过）" >&2
      fail=1
    else
      for aei_pair in $aei_doc_r; do
        printf '%s\n' "$aei_declared" | grep -qxF "$aei_pair" || aei_bad="$aei_bad 假号:$aei_pair"
      done
      for aei_pair in $aei_declared; do
        printf '%s\n' "$aei_doc_r" | grep -qxF "$aei_pair" || aei_bad="$aei_bad 缺档:$aei_pair"
      done
    fi
    aei_declared=$("$javap_bin" -constants -cp "$jar" 'android.app.ActivityManager$RunningAppProcessInfo' 2>/dev/null |
                   { grep -oE 'IMPORTANCE_[A-Z_]+ = [0-9]+' || true; } |
                   { sed -E 's/([A-Z_]+) = ([0-9]+)/\2=\1/'; } | sort -u)
    if [ -z "$aei_declared" ]; then
      echo "静态检查: javap 没能从 $jar 读出 IMPORTANCE_*（importance 半张表这轮没校验上，不算通过）" >&2
      fail=1
    else
      for aei_pair in $aei_doc_i; do
        printf '%s\n' "$aei_declared" | grep -qxF "$aei_pair" || aei_bad="$aei_bad 假importance:$aei_pair"
      done
    fi
    if [ -n "$aei_bad" ]; then
      echo "静态检查: docs/PROTOCOL.md 的 aexit 号—名表与 android.jar 不一致 →${aei_bad}（号读反=死因判据整个反向）" >&2
      fail=1
    fi
  else
    echo "静态检查: SKIP aexit 号—名表校验（缺 javap 或 ${jar}；SKIP 不等于通过）"
  fi
  [ "$fail" -eq 0 ]
}

t1_android_unit() {
  if [ ! -x "$GRADLE" ]; then
    echo "gradle 不存在: ${GRADLE}（设 GRADLE_BIN 环境变量后可启用 T1）"
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
  # --rerun：源码扫描类用例（DiagEventRegistryTest 等）会读模块外的 docs/，而 Gradle 的
  # up-to-date 检查只看声明输入 ⇒ 只改文档时这一层会被缓存成"绿"，锁就形同虚设（10-18 实测：
  # 往注册表里塞一个假名字，不加 --rerun 时 BUILD SUCCESSFUL）
  local args=(-p "$ROOT/app" :app:testProdDebugUnitTest --rerun -q)
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
    echo "需要 2 台在线模拟器/设备（当前 ${devices}），见 docs/TESTING.md T4 前置"
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
