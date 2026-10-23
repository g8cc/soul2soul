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
  # **按条数而不是"有没有"**（10-19 证伪时撞见的洞）：StandbyProbe 注册 screen+doze 两个 receiver，
  # 旧的"文件里含 unregisterReceiver( 即算过"判据，把其中一个整行删掉 t0 照样绿（实测 rc=0）——
  # 因为另一个还在同一文件里。计数版才是这条守卫本来想说的意思。
  # 选择/计数都用 `(^|[^A-Za-z])registerReceiver(`：`unregisterReceiver(` 里也含
  # `registerReceiver(` 这个子串，不加字母锚会把 unregister 也数进 register（净结果恰好抵掉）。
  local orphaned kf regcnt uncmt
  orphaned=""
  for kf in $(grep -rlE --include='*.kt' '(^|[^A-Za-z])registerReceiver\(' "$ROOT/app/app/src/main/java" 2>/dev/null); do
    regcnt=$(grep -oE '(^|[^A-Za-z])registerReceiver\(' "$kf" | wc -l | tr -d ' ')
    uncmt=$(grep -oE '(^|[^A-Za-z])unregisterReceiver\(' "$kf" | wc -l | tr -d ' ')
    [ "$uncmt" -ge "$regcnt" ] || orphaned="$orphaned $(basename "$kf")(register=$regcnt/unregister=$uncmt)"
  done
  if [ -n "$orphaned" ]; then
    echo "静态检查: registerReceiver 条数多于 unregisterReceiver（漏一条=解绑后仍在收系统广播）:$orphaned"
    fail=1
  fi
  orphaned=""
  for kf in $(grep -rlE --include='*.kt' 'registerDefaultNetworkCallback\(' "$ROOT/app/app/src/main/java" 2>/dev/null); do
    regcnt=$(grep -oE 'registerDefaultNetworkCallback\(' "$kf" | wc -l | tr -d ' ')
    uncmt=$(grep -oE 'unregisterNetworkCallback\(' "$kf" | wc -l | tr -d ' ')
    [ "$uncmt" -ge "$regcnt" ] || orphaned="$orphaned $(basename "$kf")(register=$regcnt/unregister=$uncmt)"
  done
  if [ -n "$orphaned" ]; then
    echo "静态检查: registerDefaultNetworkCallback 条数多于 unregisterNetworkCallback（漏一条=探针停了还在刷 net_down）:$orphaned"
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
  # 坑⑲的镜像：那条查"数字撒谎"，这条查"格子消失"。stop_tap 测的是客户端自己的报文体，
  # 与媒体通不通无关，却整个写在 `if [ "$ICE" != "OK" ]` 里（因为点它会拆掉会话，必须排在
  # 同屏/笔迹之后）⇒ 借线上 TURN 让 ICE 连通后（v0.2.62 起本机第一次判得出媒体）那条既不
  # PASS 也不进 ENV_SKIP 的账，实测汇总少一条。两种 ICE 结果都必须判到。
  if ! (grep -qF 'if [ "$ICE" != "OK" ]; then check_stop_tap; fi' "$ROOT/scripts/e2e.sh" 2>/dev/null &&
        grep -qF 'if [ "$ICE" = "OK" ]; then check_stop_tap; fi' "$ROOT/scripts/e2e.sh" 2>/dev/null); then
    echo '静态检查: stop_tap 必须在 ICE 两种结果下都被判定（只写在 !=OK 里＝媒体一通就静默少一条断言，既不 PASS 也不记账）'
    fail=1
  fi
  # 坑㉔（10-19 实测 3058 个变化像素才换回来的两条）：笔迹断言 `A 截图差异数: 0` 有两种成因，
  # 脚本必须同时带着"排除宿主藏层"的那一步，否则下一个人还会从头查一遍通路：
  # ① 共享端停在设置页时，Android 12+ 的 `HIDE_OVERLAY_WINDOWS` 使框架把别人所有
  #    TYPE_APPLICATION_OVERLAY 强制隐藏（窗口建了、内容画了、不合成）⇒ 拍之前必须顶回 MainActivity；
  # ② FAIL 时必须打印 mForceHideNonSystemOverlayWindow 计数，把"宿主藏层"与"通路坏了"分开——
  #    没有这一行，脚本自己分不清这两件事（我就据此误判过一次"canvas 窗口不存在"）。
  if ! (
    grep -qF 'ck "B 的笔迹出现在 A 屏幕"' "$ROOT/scripts/e2e.sh" &&
    grep -qF 'grep -c "mForceHideNonSystemOverlayWindow=true"' "$ROOT/scripts/e2e.sh" &&
    awk -v pat='am start -n "$PKG/com.soul2soul.app.MainActivity"' '
      index($0, "T4-5 标注") { inSec = 1 }
      inSec && index($0, pat) { hit = NR }
      inSec && index($0, "e2e_a_before.png") { if (hit && hit < NR) ok = 1; exit }
      END { exit ok ? 0 : 1 }' "$ROOT/scripts/e2e.sh"
  ); then
    echo '静态检查: 笔迹断言必须①拍前把共享端顶回 MainActivity ②FAIL 时打印 mForceHideNonSystemOverlayWindow（宿主藏层≠通路坏了；缺任一条 10-19 那轮白查）'
    fail=1
  fi
  # 10-19 实锤：emu flavor 只改 SIGNALING_URL、桌面名仍是「陪着你」，把测试包 `adb install -r` 到
  # 用户真机后桌面出现两个同名图标，用户按名字删掉了**真正配对过的正式版**（deviceId 在 App
  # SharedPreferences 里，卸载即抹，重装换号 ⇒ 服务器那条配对变陈旧、必须重新配对）。
  # 测试 flavor 的名字必须自带"可删"信号，且这个不变式不能只靠人记得。
  # 覆盖两个源集而不是只有 emu（10-19 补的半边）：日常真机联调走的是 `assembleProdDebug`，
  # 它的 applicationId 与线上包**相同**（com.soul2soul.app），签名证书不同 ⇒ `install -r` 报
  # INSTALL_FAILED_UPDATE_INCOMPATIBLE，而那句报错的常见"解法"是先卸载——数据正是那一刻没的。
  # 所以 debug 构建类型也必须有独立桌面名（src/debug/res），四种变体里只有 release+prod 保留正式名。
  local main_name ss_name sset
  main_name=$(sed -n 's/.*<string name="app_name">\(.*\)<\/string>.*/\1/p' "$ROOT/app/app/src/main/res/values/strings.xml")
  for sset in emu debug; do
    ss_name=$(sed -n 's/.*<string name="app_name">\(.*\)<\/string>.*/\1/p' "$ROOT/app/app/src/$sset/res/values/strings.xml" 2>/dev/null)
    if [ -z "$ss_name" ] || [ "$ss_name" = "$main_name" ]; then
      echo "静态检查: src/$sset/res/values/strings.xml 必须有独立桌面名且不等于正式版（当前 '$ss_name' vs '$main_name'）——同名图标=用户无法区分=迟早删错"
      fail=1
    fi
  done
  # 签名口令原先明文躺在 app/app/build.gradle.kts 里、跟着提交推到了 GitHub（10-19 用户点头移出）。
  # 这条守卫只保证"别再写回构建脚本"，不声称历史上没漏过——那个值仍在 git 历史里，改口令才是真闭合。
  # 判据扫的是整棵 app/ + scripts/（不含 build/ 产物），按"赋值右边是字符串字面量"匹配：
  # 口令文件本身写的是 `storePassword=真值`（没有引号），不会被这条误伤。
  if grep -rnE '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"' \
       --include='*.kts' --include='*.gradle' --include='*.gradle.kts' --include='*.properties' --include='*.sh' \
       "$ROOT/app" "$ROOT/scripts" 2>/dev/null | grep -v '/build/' | grep -q .; then
    echo "静态检查: 签名口令不许内联在构建脚本里（只允许读 app/keystore.properties，模板见 keystore.properties.example）"
    fail=1
  fi
  # 口令文件的唯一容身处是被 ignore；ignore 那行一旦被人删掉，下一次 commit 就把它推出去了
  if ! grep -qx 'keystore.properties' "$ROOT/.gitignore"; then
    echo "静态检查: .gitignore 必须保留 keystore.properties 这一行（口令文件不许被跟踪）"
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
  # 10-23 把 AGENTS 的读日志口径外分到 docs/DIAG-READINGS.md / docs/MEASUREMENT.md，
  # 禁令必须跟着搬——否则"以后没人看守的那份文档"就是下一个假口径。
  if grep -qF '`beat=`' "$ROOT/docs/PROTOCOL.md" "$ROOT/docs/AUDIT-MATRIX.md" "$ROOT/AGENTS.md" \
              "$ROOT/docs/DIAG-READINGS.md" "$ROOT/docs/MEASUREMENT.md" 2>/dev/null ||
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
  # 坑⑬ 修完字符类之后，成功分支**仍然是**结构性不可达（10-19 复现）：判据写成字面
  # `ice state: CONNECTED`，而客户端唯一的打印形式是 `旧态->新态`（WebRtcClient.kt:725），
  # 实测 20 轮轮询全 0、同一时刻 logcat 明明有 CHECKING->CONNECTED ⇒ 媒体段整段白丢。
  # 冻结字符串式的 ICE 判据必须带箭头；注释里的反例不算（否则解释"为什么不能这么写"的字
  # 被自己的检测器判红，人就学会把守卫删掉）。
  local ice_arrow
  ice_arrow=$(python3 - "$ROOT/scripts/e2e.sh" "$ROOT/scripts/drive-test.sh" <<'PY'
import sys
bad = []
for f in sys.argv[1:]:
    for i, raw in enumerate(open(f, encoding="utf-8"), 1):
        code = raw.split("#", 1)[0]
        if "ice state:" in code and "->" not in code:
            bad.append("%s:%d" % (f.rsplit("/", 1)[-1], i))
print(" ".join(bad))
PY
)
  if [ -n "$ice_arrow" ]; then
    echo "静态检查: harness 的 ICE 成功判据缺箭头，客户端只打印 旧态->新态，字面 CONNECTED 永远匹配不到（恒假）: $ice_arrow" >&2
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
  # 否定式判据（`.none {}`）的作用域必须是完整函数/完整分支，不许取固定行数窗口（TESTING 坑㉚）。
  # 10-19 一轮里实测到四条这种空锁：窗口比被禁的那段短，禁令就只贴在开头几行上，
  # 把"锁屏 15 秒自动结束"那种被废掉的代码原样写回分支尾部，用例照旧绿。
  # 这里按"同一文件内 val X = window(...)/linesAfter(...) 且后面出现 X.none"配对，避免跨文件同名误伤。
  local negwin neginline negdefault tdir
  tdir="$ROOT/app/app/src/test/java"
  negwin=$(awk '
    /^[[:space:]]*@Test/ { for (k in win) delete win[k] }   # 局部 val 只在同一个用例方法内有意义（否则同名变量跨方法误伤=恒红）
    /^[[:space:]]*(val|var)[[:space:]]+[A-Za-z0-9_]+[[:space:]]*=[[:space:]]*(code\([[:space:]]*)?(window|linesAfter)\(/ {
      s=$0; sub(/^[[:space:]]*(val|var)[[:space:]]+/, "", s); sub(/[[:space:]]*=.*$/, "", s)
      win[FILENAME, s]=1
    }
    { for (k in win) { if (split(k, a, SUBSEP) && a[1]==FILENAME && index($0, a[2] ".none") > 0)
        print FILENAME ":" FNR ": " $0 } }
  ' $(find "$tdir" -name '*.kt') 2>/dev/null)
  neginline=$(grep -rnE '(window|linesAfter)\([^)]*\)[[:space:]]*\.none[[:space:]]*\{' "$tdir" 2>/dev/null || true)
  # 扫描器找不到锚点时不许退回"一个恰好够用的数字"：那是 fail-open，锚点腐烂没人知道
  negdefault=$(grep -rEn '\?:[[:space:]]*\(?[0-9]+|\?:[[:space:]]*\([a-zA-Z_]+[[:space:]]*\+[[:space:]]*[0-9]+' "$tdir" 2>/dev/null || true)
  if [ -n "$negwin" ] || [ -n "$neginline" ]; then
    echo "静态检查: 否定式判据（.none{}）的作用域用了固定行数窗口，禁令只贴在函数前半段=假绿（TESTING 坑㉚）:" >&2
    [ -n "$negwin" ] && echo "$negwin" >&2
    [ -n "$neginline" ] && echo "$neginline" >&2
    fail=1
  fi
  if [ -n "$negdefault" ]; then
    echo "静态检查: 源码扫描器的锚点兜底写成了数字或「起点+魔法行数」（fail-open，锚点一改名判据就静默变短），要 ?: error(...) 而不是 ?: (start + 30):" >&2
    echo "$negdefault" >&2
    fail=1
  fi
  # ── v0.2.65 重影/双铅笔根治（计划步骤⑤的三条 t0 锁）──────────────────
  # (a) 通知动作数量：Android 7 只放得下 3 个（v0.2.46 真机实锤"按钮时有时无"），
  #     第 4 个「画笔」必须整体关在 SDK_INT >= O 的块里。守卫漏了不是"少一个按钮"，
  #     是老机型整条常驻通知的动作区溢出——而这台机器（Mi5）正是我们唯一的 Android 7 样本。
  python3 - "$ROOT/app/app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt" <<'PY' || fail=1
import re, sys
p = sys.argv[1]
src = open(p, encoding='utf-8').read()
try:
    i = src.index('private fun buildNotification(')
except ValueError:
    print('静态检查: 找不到 buildNotification（守卫锚点腐烂，不是通过）'); sys.exit(1)
depth = 0
end = None
for j in range(src.index('{', i), len(src)):
    if src[j] == '{':
        depth += 1
    elif src[j] == '}':
        depth -= 1
        if depth == 0:
            end = j + 1
            break
body = src[i:end]
g = re.search(r'if \(Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.O\) \{', body)
if not g:
    print('静态检查: buildNotification 里没有 SDK_INT >= O 守卫（第 4 个通知动作失去闸门）'); sys.exit(1)
gs, gd, d = g.end(), None, 1
for k in range(gs, len(body)):
    if body[k] == '{':
        d += 1
    elif body[k] == '}':
        d -= 1
        if d == 0:
            gd = k
            break
hits = [m.start() for m in re.finditer(r'\.addAction\(', body)]
inside = [h for h in hits if gs <= h < gd]
outside = [h for h in hits if not (gs <= h < gd)]
if len(outside) != 3 or len(inside) != 1:
    print(f'静态检查: 通知动作应为「守卫外 3 个 + 守卫内 1 个」，实际守卫外 {len(outside)}、守卫内 {len(inside)}'
          f'（Android 7 兜不住第 4 个；HyperOS 折叠态另有 ⬜ 格待真机采）'); sys.exit(1)
PY
  # (b) 共享端入口球闲置透明度：必须是 0f。旧值 0.15f 的"淡化"在她那边不算消失——
  #     这颗球是 TYPE_APPLICATION_OVERLAY 窗口，只要可见就被采进视频 ⇒ 第二个铅笔。
  local ballf
  ballf="$ROOT/app/app/src/main/java/com/soul2soul/app/session/AnnotationOverlayService.kt"
  if ! grep -qE 'BALL_DIM_ALPHA[[:space:]]*=[[:space:]]*0f' "$ballf"; then
    echo "静态检查: BALL_DIM_ALPHA 不是 0f（悬浮球一进采集画面=她看到两个铅笔）"; fail=1
  fi
  # (c) echo_ack 两头成对：发送点（ScreenShareService 的 k="a"）与接收分支（SignalRouter 的 "a" ->）。
  #     只埋不接＝观看端永远不交棒，本地副本只能靠兜底（HANDOVER_FALLBACK_MS）淡出（重影窗口回到旧尺寸）；
  #     只接不埋＝遥测整段静默，而 summary 会把它读成"这版没埋"。
  local acksend ackroute
  acksend=$(grep -Fc 'put("k", "a")' "$ROOT/app/app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt" || true)
  ackroute=$(grep -Fc '"a" ->' "$ROOT/app/app/src/main/java/com/soul2soul/app/session/SignalRouter.kt" || true)
  if [ "${acksend:-0}" -lt 1 ] || [ "${ackroute:-0}" -lt 1 ]; then
    echo "静态检查: echo_ack 发送点/接收分支缺一（send=${acksend} route=${ackroute}，两者必须同时存在）"; fail=1
  fi
  # (d) FR-16 内录状态推送两头成对：发送点（服务经 DeviceAudioHandshake.stateJson 发 k="aud"）
  #     与接收分支（SignalRouter 的 "aud" ->）。这是 (c) 同一族的"只埋不接"：
  #     少发送点＝她那行横幅永远停在"版本太旧"（其实他开着）；少接收分支＝落进 else -> None，
  #     她的横幅整段不出现，而两侧都不崩、也没有日志。
  local audpush audroute
  audpush=$(grep -Fc 'DeviceAudioHandshake.stateJson(' "$ROOT/app/app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt" || true)
  audroute=$(grep -Fc '"aud" ->' "$ROOT/app/app/src/main/java/com/soul2soul/app/session/SignalRouter.kt" || true)
  if [ "${audpush:-0}" -lt 1 ] || [ "${audroute:-0}" -lt 1 ]; then
    echo "静态检查: k=aud 发送点/接收分支缺一（push=${audpush} route=${audroute}，两者必须同时存在）"; fail=1
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
