// Soul2Soul 信令服务器
// 职责: 设备配对 / 呼叫转发 / SDP-ICE 中转 / TURN 时限凭证签发
// 协议见 docs/SPEC.md 第二节。只把消息转发给"已配对"的另一台设备。
//
// 安全设计:
//  - 配对成功时服务器为这对设备签发 pairToken，之后每次 hello 必须携带（防止知道 deviceId 即可冒充）
//  - pair.enter 每连接限 5 次尝试（防 6 位码爆破）
//  - invite 每 2 秒最多 1 次（防骚扰转发）

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { WebSocketServer, WebSocket } from 'ws';
import { buildIceServers } from './turn.js';

const PORT = Number(process.env.PORT || 8080);
const TURN_STATIC_AUTH_SECRET = process.env.TURN_STATIC_AUTH_SECRET || 'change_me';
const TURN_HOST = process.env.TURN_HOST || 'YOUR_SERVER_PUBLIC_IP';
const TURN_PORT = process.env.TURN_PORT || '3478';
const DATA_FILE = process.env.S2S_DATA_FILE || path.join(process.cwd(), 'data', 'pairings.json');
const PAIR_CODE_TTL_MS = Number(process.env.PAIR_CODE_TTL_MS || 10 * 60 * 1000);
const PAIR_ENTER_MAX_ATTEMPTS = 5;
const PAIR_REQUEST_MIN_INTERVAL_MS = 5000;
const INVITE_MIN_INTERVAL_MS = 2000;
const MAX_MESSAGE_BYTES = 256 * 1024; // SDP offer 最大约 50KB，256KB 封顶（巨包在传输层即断连，不进业务代码）

// ---------- 配对关系持久化 ----------
// pairings[deviceId] = { peer: 对端deviceId, token: 配对令牌 }
let pairings = {};
try {
  const loaded = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  for (const [k, v] of Object.entries(loaded)) {
    pairings[k] = typeof v === 'string'
      ? { peer: v, token: crypto.randomBytes(16).toString('hex') } // 旧格式迁移
      : v;
  }
  console.log(`[pair] loaded ${Object.keys(pairings).length} pairing entries`);
} catch { /* 首次启动无文件 */ }

function savePairings() {
  fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(pairings, null, 2));
  fs.renameSync(tmp, DATA_FILE);
}

// ---------- 运行时状态 ----------
const online = new Map();           // deviceId -> WebSocket
const pendingPairCodes = new Map(); // code -> { deviceId, expires }
const lastInviteAt = new Map();     // deviceId -> 上次 invite 时间戳
const lastPairRequestAt = new Map();// deviceId -> 上次 pair.request 时间戳

// 清扫过期配对码：只在 enter 时判断过期的话，从不再使用的码会永久滞留（慢性内存泄漏）
setInterval(() => {
  const now = Date.now();
  for (const [code, entry] of pendingPairCodes) {
    if (entry.expires < now) pendingPairCodes.delete(code);
  }
}, 60 * 1000).unref();

function pairingOf(deviceId) { return pairings[deviceId] || null; }
function peerDeviceId(deviceId) { return pairings[deviceId]?.peer || null; }
function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    if (obj.type !== undefined) {
      console.log(`[relay] ${ws.deviceId || '?'} <- ${obj.type}` +
        (obj.type === 'sdp' || obj.type === 'ice' ? '' : ` ${JSON.stringify(obj).slice(0, 120)}`));
    }
    ws.send(JSON.stringify(obj));
  }
}
function iceServers() {
  // 纯逻辑在 turn.js（单测覆盖）：reverse-proxy 限时凭证 + stun/turn udp+tcp 三 urls
  return buildIceServers({
    secret: TURN_STATIC_AUTH_SECRET, host: TURN_HOST, port: TURN_PORT,
  });
}

// ---------- WebSocket 服务 ----------
const server = http.createServer((req, res) => {
  if (req.url === '/health') { res.writeHead(200); res.end('ok'); return; }
  res.writeHead(404); res.end();
});
const wss = new WebSocketServer({ server, maxPayload: MAX_MESSAGE_BYTES });

wss.on('connection', (ws) => {
  ws.deviceId = null;
  ws.authed = false;      // hello 令牌校验通过 / 刚完成配对
  ws.pairAttempts = 0;

  // ws 层协议错误（含巨包拒收 1009）默认以 'error' 事件抛出——无人监听会崩进程
  ws.on('error', (err) => {
    console.log(`[ws] ${ws.deviceId || '?'} error: ${err.message}`);
  });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    // JSON.parse('null') 会得到 null，解构会抛异常崩掉整个进程——必须挡住
    if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return;
    const type = typeof msg.type === 'string' ? msg.type.slice(0, 32) : '';
    if (!type) return;

    // ---- hello: 注册并鉴权 ----
    if (type === 'hello') {
      ws.deviceId = String(msg.deviceId || '').slice(0, 64);
      if (!ws.deviceId) { send(ws, { type: 'error', reason: 'bad_device_id' }); return; }
      const pairing = pairingOf(ws.deviceId);
      if (pairing) {
        // 已配对设备必须携带配对令牌，防止得知 deviceId 即可冒充
        if (String(msg.token || '') !== pairing.token) {
          send(ws, { type: 'error', reason: 'bad_token' });
          ws.close(4001, 'bad_token');
          return;
        }
      }
      ws.authed = true;
      const old = online.get(ws.deviceId);
      if (old && old !== ws) old.close();
      online.set(ws.deviceId, ws);
      const peer = peerDeviceId(ws.deviceId);
      send(ws, {
        type: 'registered',
        paired: !!pairing,
        peerOnline: !!peer && online.has(peer),
      });
      // 上线时通知对方，让 TA 的界面亮起来
      if (peer && online.has(peer)) {
        send(online.get(peer), { type: 'peer.online' });
      }
      return;
    }

    if (!ws.deviceId) return; // 未 hello 之前忽略

    switch (type) {
      // ---- 配对 ----
      case 'pair.request': {
        const now = Date.now();
        if (now - (lastPairRequestAt.get(ws.deviceId) || 0) < PAIR_REQUEST_MIN_INTERVAL_MS) {
          send(ws, { type: 'pair.failed', reason: 'too_fast' });
          break;
        }
        lastPairRequestAt.set(ws.deviceId, now);
        // 每设备最多 1 个有效码：申请新码即撤销旧码（码表规模有上界）
        for (const [c, e] of pendingPairCodes) {
          if (e.deviceId === ws.deviceId) pendingPairCodes.delete(c);
        }
        const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
        pendingPairCodes.set(code, { deviceId: ws.deviceId, expires: Date.now() + PAIR_CODE_TTL_MS });
        send(ws, { type: 'pair.code', code });
        break;
      }
      case 'pair.enter': {
        if (ws.pairAttempts >= PAIR_ENTER_MAX_ATTEMPTS) {
          send(ws, { type: 'pair.failed', reason: 'too_many_attempts' });
          break;
        }
        ws.pairAttempts += 1;
        const code = String(msg.code || '');
        const pending = pendingPairCodes.get(code);
        if (!pending || pending.expires < Date.now()) {
          send(ws, { type: 'pair.failed', reason: 'code_invalid' }); break;
        }
        if (pending.deviceId === ws.deviceId) {
          send(ws, { type: 'pair.failed', reason: 'self_pair' }); break;
        }
        const a = pending.deviceId, b = ws.deviceId;
        const token = crypto.randomBytes(16).toString('hex');
        pairings[a] = { peer: b, token };
        pairings[b] = { peer: a, token };
        pendingPairCodes.delete(code);
        savePairings();
        const wa = online.get(a), wb = online.get(b);
        // 配对成功即视为鉴权通过；客户端会把 token 存下来用于之后的 hello
        if (wa) { wa.authed = true; send(wa, { type: 'paired', token, peerOnline: !!wb }); }
        if (wb) { wb.authed = true; send(wb, { type: 'paired', token, peerOnline: !!wa }); }
        break;
      }

      // ---- 呼叫生命周期 ----
      case 'invite': {
        if (!ws.authed) { send(ws, { type: 'peer.offline', reason: 'not_authed' }); break; }
        const now = Date.now();
        if (now - (lastInviteAt.get(ws.deviceId) || 0) < INVITE_MIN_INTERVAL_MS) {
          send(ws, { type: 'peer.offline', reason: 'too_fast' }); break;
        }
        lastInviteAt.set(ws.deviceId, now);
        const peer = peerDeviceId(ws.deviceId);
        const peerWs = peer && online.get(peer);
        if (!peerWs) { send(ws, { type: 'peer.offline' }); break; }
        // 带上主叫设备号：双方同时呼叫（撞车）时客户端用它做确定性裁决
        send(peerWs, { type: 'incoming', from: ws.deviceId, iceServers: iceServers() });
        break;
      }
      case 'accept': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (peer) send(online.get(peer), { type: 'accepted', iceServers: iceServers() });
        break;
      }
      case 'decline': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (peer) send(online.get(peer), { type: 'declined' });
        break;
      }
      case 'cancel': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (peer) send(online.get(peer), { type: 'call.canceled' });
        break;
      }
      case 'unpair': {
        // 解除配对：删除双向关系并通知两端（在线的立即可见，离线的重连时发现）
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (!peer) { send(ws, { type: 'unpaired' }); break; }
        delete pairings[ws.deviceId];
        delete pairings[peer];
        savePairings();
        send(ws, { type: 'unpaired' });
        send(online.get(peer), { type: 'unpaired' });
        console.log(`[pair] unpaired ${ws.deviceId} <-> ${peer}`);
        break;
      }

      // ---- 媒体协商与挂断（原样转发）----
      case 'sdp':
      case 'ice':
      case 'bye': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (peer) send(online.get(peer), msg);
        break;
      }
      case 'app.version': {
        console.log(`[diag.version] ${ws.deviceId} code=${msg.localCode} name=${msg.localName} remote=${msg.remoteCode}/${msg.remoteName} update=${msg.hasUpdate}`);
        break;
      }
      case 'app.diag': {
        // 会话能力自检矩阵（机型/系统/权限）：跨机型问题远程定位用，只含公开设备信息
        console.log(`[diag.selfcheck] ${ws.deviceId} role=${msg.role} model=${msg.model} os=${msg.os}/sdk${msg.sdk} app=${msg.app} notif=${msg.notif} overlay=${msg.overlay} acc=${msg.acc} mic=${msg.mic} battery=${msg.battery} ctl=${msg.ctlAllowed ?? msg.ctlSupported ?? '-'}`);
        break;
      }
      default: break;
    }
  });

  ws.on('close', () => {
    if (ws.deviceId && online.get(ws.deviceId) === ws) {
      online.delete(ws.deviceId);
      lastPairRequestAt.delete(ws.deviceId);
      lastInviteAt.delete(ws.deviceId);
      const peer = peerDeviceId(ws.deviceId);
      if (peer) send(online.get(peer), { type: 'peer.gone' });
    }
  });
});

// 心跳: 清理假死连接
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { ws.terminate(); return; }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);
wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
});

server.listen(PORT, () => {
  console.log(`[soul2soul] signaling listening on :${PORT}`);
  console.log(`[soul2soul] TURN host = ${TURN_HOST}:${TURN_PORT}`);
  if (TURN_STATIC_AUTH_SECRET === 'change_me') {
    console.warn('[soul2soul] WARNING: TURN_STATIC_AUTH_SECRET 未设置，请改 .env');
  }
});
