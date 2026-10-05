#!/usr/bin/env bash
# T3 协议黑盒：随机端口起一次性信令实例 → 对抗(protocol-test) + 全链路冒烟(smoke-test)
# 关键护栏：
#  - 数据文件走 mktemp，绝不触碰 server/data/pairings.json（生产配对关系所在）
#  - cwd 必须是 server/（两个黑盒脚本从 process.cwd() 解析 node_modules/ws）
set -eu
cd "$(cd "$(dirname "$0")/.." && pwd)"

PORT=$(node test/port.mjs)
TMPDATA=$(mktemp -d)
SRV=""

cleanup() {
  # shellcheck disable=SC2064
  trap - EXIT
  [ -n "$SRV" ] && kill "$SRV" 2>/dev/null || true
  wait "$SRV" 2>/dev/null || true   # 回收子进程，消除 job-control 的 Terminated 噪音
  rm -rf "$TMPDATA"
}
trap cleanup EXIT

PORT="$PORT" S2S_DATA_FILE="$TMPDATA/pairings.json" \
  TURN_STATIC_AUTH_SECRET=blackbox_secret TURN_HOST=127.0.0.1 \
  node src/index.js >"$TMPDATA/server.log" 2>&1 &
SRV=$!

ready=0
for _ in $(seq 1 100); do
  if curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "$SRV" 2>/dev/null; then
    echo "临时服务器启动即退出：" >&2
    cat "$TMPDATA/server.log" >&2
    exit 1
  fi
  sleep 0.1
done
if [ "$ready" != 1 ]; then
  echo "临时服务器 :$PORT 未就绪：" >&2
  cat "$TMPDATA/server.log" >&2
  exit 1
fi

echo "-- protocol-test.cjs @ :$PORT"
SIG_URL="ws://127.0.0.1:$PORT" node protocol-test.cjs
echo "-- smoke-test.cjs @ :$PORT"
SIG_URL="ws://127.0.0.1:$PORT" node smoke-test.cjs
