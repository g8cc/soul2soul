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
import { QUIET_TYPES, redactSecrets } from './logpolicy.js';
import { PairCodeBook } from './pairing.js';
import { MessageBook } from './messages.js';
import { RecallBook } from './recall.js';

const PORT = Number(process.env.PORT || 8080);
const TURN_STATIC_AUTH_SECRET = process.env.TURN_STATIC_AUTH_SECRET || 'change_me';
const TURN_HOST = process.env.TURN_HOST || 'YOUR_SERVER_PUBLIC_IP';
const TURN_PORT = process.env.TURN_PORT || '3478';
const DATA_FILE = process.env.S2S_DATA_FILE || path.join(process.cwd(), 'data', 'pairings.json');
const MESSAGES_FILE = process.env.S2S_MESSAGES_FILE || path.join(path.dirname(DATA_FILE), 'messages.json');
const HISTORY_FILE = process.env.S2S_HISTORY_FILE || path.join(path.dirname(DATA_FILE), 'history.json');
const VOICE_DIR = path.join(path.dirname(MESSAGES_FILE), 'voice');
const VOICE_MAX_BYTES = 1024 * 1024; // 30s AAC ≈ 300KB，1MB 封顶
const VOICE_ORPHAN_TTL_MS = 60 * 60 * 1000;
const PAIR_CODE_TTL_MS = Number(process.env.PAIR_CODE_TTL_MS || 10 * 60 * 1000);
const PAIR_ENTER_MAX_ATTEMPTS = Number(process.env.PAIR_ENTER_MAX_ATTEMPTS || 5);
const PAIR_REQUEST_MIN_INTERVAL_MS = 5000;
const INVITE_MIN_INTERVAL_MS = 2000;
const MAX_MESSAGE_BYTES = 256 * 1024; // SDP offer 最大约 50KB，256KB 封顶（巨包在传输层即断连，不进业务代码）

// ---- 统一日志出口：每行前缀本地时间 YYYY-MM-DD HH:MM:SS.mmm ----
// 断连排查要把双端事件按毫秒对齐（谁先掉、掉之前最后一次协商、心跳有没有断），
// 旧日志没有时间戳，只能靠相邻行顺序猜。这里只加前缀，不改任何行为与调用点。
// 必须带日期：这份文件跨天连续写（无轮转），只有 HH:MM:SS 时
// "07:23 的 close" 分不清是今天还是昨天，客户端补报的 at= 也没法对表。
const __logTs = () => {
  const d = new Date();
  const p2 = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ` +
    `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}` +
    `.${String(d.getMilliseconds()).padStart(3, '0')}`;
};
for (const level of ['log', 'warn', 'error']) {
  const raw = console[level].bind(console);
  console[level] = (...args) => raw(`[${__logTs()}]`, ...args);
}

// ---- 日志档位：S2S_LOG=minimal 关掉逐帧噪声，默认 diag 保持现行详细度 ----
// 设这个开关是为了"排查完能关"：今天想静音只能改代码上线（服务器是高风险区）。
// 注意隐私线不随档位放开：quiet 名单里的正文任何档位都不落盘（见 send()）。
const LOG_MINIMAL = String(process.env.S2S_LOG || 'diag').trim().toLowerCase() === 'minimal';


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

function saveJsonAtomic(file, value, label) {
  const tmp = file + '.tmp';
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2));
    fs.renameSync(tmp, file);
    return true;
  } catch (err) {
    console.error(`[persist] ${label} failed: ${err.message}`);
    try { fs.rmSync(tmp, { force: true }); } catch { /* 保留原文件，等待下次重试 */ }
    return false;
  }
}
function savePairings() {
  return saveJsonAtomic(DATA_FILE, pairings, 'pairings');
}

// ---------- 历史配对关系（找回TA / 摇一摇复配的依据）----------
// history[deviceId] = { peer, ts }；解除配对不删（这正是"找回"的凭据），30 天不活动自动过期。
const recallBook = new RecallBook();
try {
  recallBook.restore(JSON.parse(fs.readFileSync(HISTORY_FILE, 'utf8')));
  console.log(`[recall] loaded ${recallBook.history.size} history entries`);
} catch { /* 首次启动无文件 */ }
function saveHistory() {
  return saveJsonAtomic(HISTORY_FILE, recallBook.snapshot(), 'history');
}

// 存量回填：本功能上线前绑定的老配对没有 history 行，不补的话这对情侣一旦解绑
// 就当场失去"找回TA/互摇"资格（正是本功能要救的场景）。启动时把在册 pairings 补进历史。
{
  let backfilled = 0;
  for (const [a, v] of Object.entries(pairings)) {
    const b = v && v.peer;
    if (b && pairings[b] && b !== a && !recallBook.hasHistory(a)) {
      recallBook.recordPair(a, b);
      backfilled++;
    }
  }
  if (backfilled > 0) {
    saveHistory();
    console.log(`[recall] backfilled ${backfilled} history entries from active pairings`);
  }
}

/** 建立/重建配对关系并通知双方：pair.enter 与召回绑定共用 */
function bindPair(a, b) {
  if (pairingOf(a) || pairingOf(b)) return false;
  const token = crypto.randomBytes(16).toString('hex');
  const oldA = pairings[a];
  const oldB = pairings[b];
  pairings[a] = { peer: b, token };
  pairings[b] = { peer: a, token };
  if (!savePairings()) {
    if (oldA) pairings[a] = oldA; else delete pairings[a];
    if (oldB) pairings[b] = oldB; else delete pairings[b];
    return false;
  }
  recallBook.recordPair(a, b);
  saveHistory();
  const wa = online.get(a), wb = online.get(b);
  // 配对成功即视为鉴权通过；客户端会把 token 存下来用于之后的 hello
  if (wa) { wa.authed = true; send(wa, { type: 'paired', token, peerOnline: !!wb }); }
  if (wb) { wb.authed = true; send(wb, { type: 'paired', token, peerOnline: !!wa }); }
  console.log(`[pair] bound ${a} <-> ${b}`);
  return true;
}

// ---------- 离线留言（文字/语音）持久化 ----------
// messages[toDeviceId] = [{id, from, kind, text|voiceId+durMs, ts, expires}]
// 决策逻辑全在 messages.js（单测覆盖）；这里只做落盘、鉴权与收发。
let msgBook;
try {
  const loaded = JSON.parse(fs.readFileSync(MESSAGES_FILE, 'utf8'));
  msgBook = new MessageBook();
  for (const [to, items] of Object.entries(loaded.inbox || {})) {
    for (const m of items) if (m && typeof m.id === 'string') msgBook.restore(to, m);
  }
  console.log(`[msg] loaded ${msgBook.totalCount()} message entries`);
} catch { msgBook = new MessageBook(); /* 首次启动无文件 */ }

// 上传完成但尚未提交 msg.post 的语音没有消息索引，不能无限期留在磁盘。
// 运行时登记归属，重启后以留言箱为准，旧孤儿按 mtime 延迟清理。
const voiceUploads = new Map(); // voiceId -> { owner, ts }
function referencedVoiceIds() {
  const ids = new Set();
  for (const items of msgBook.inbox.values()) {
    for (const item of items) {
      if (item && item.kind === 'voice' && typeof item.voiceId === 'string') ids.add(item.voiceId);
    }
  }
  return ids;
}
function cleanupOrphanVoices() {
  const referenced = referencedVoiceIds();
  fs.readdir(VOICE_DIR, { withFileTypes: true }, (err, entries) => {
    if (err) { if (err.code !== 'ENOENT') console.warn(`[voice] scan failed: ${err.message}`); return; }
    for (const entry of entries) {
      if (!entry.isFile() || !/^[0-9a-f-]{36}\.m4a$/i.test(entry.name)) continue;
      const voiceId = entry.name.slice(0, -4);
      if (referenced.has(voiceId) || voiceUploads.has(voiceId)) continue;
      const file = path.join(VOICE_DIR, entry.name);
      fs.stat(file, (statErr, st) => {
        if (!statErr && Date.now() - st.mtimeMs >= VOICE_ORPHAN_TTL_MS) {
          fs.rm(file, { force: true }, (rmErr) => {
            if (rmErr) console.warn(`[voice] orphan cleanup failed: ${rmErr.message}`);
          });
        }
      });
    }
  });
}
cleanupOrphanVoices();

function saveMessages() {
  return saveJsonAtomic(MESSAGES_FILE, { inbox: Object.fromEntries(msgBook.inbox) }, 'messages');
}
function deleteVoices(items) {
  for (const m of items) {
    if (m.kind !== 'voice' || !m.voiceId) continue;
    fs.rm(path.join(VOICE_DIR, m.voiceId + '.m4a'), { force: true }, () => {});
  }
}

// ---------- 运行时状态 ----------
const online = new Map();           // deviceId -> WebSocket
// 配对码本（纯逻辑在 pairing.js，单测覆盖）：限速/旧码作废/尝试上限/TTL 常量在此注入
const pairBook = new PairCodeBook({
  ttlMs: PAIR_CODE_TTL_MS,
  requestMinIntervalMs: PAIR_REQUEST_MIN_INTERVAL_MS,
  maxEnterAttempts: PAIR_ENTER_MAX_ATTEMPTS,
  isPaired: (deviceId) => Boolean(pairings[deviceId]),
});
const lastInviteAt = new Map();     // deviceId -> 上次 invite 时间戳

// 清扫过期配对码：只在 enter 时判断过期的话，从不再使用的码会永久滞留（慢性内存泄漏）
setInterval(() => pairBook.sweep(), 60 * 1000).unref();
// 清扫过期留言（TTL 7 天）：语音文件一并删除，落盘同步
setInterval(() => {
  const removed = msgBook.sweep();
  if (removed.length) { deleteVoices(removed); saveMessages(); }
  const now = Date.now();
  for (const [voiceId, upload] of voiceUploads) {
    if (now - upload.ts >= VOICE_ORPHAN_TTL_MS) {
      voiceUploads.delete(voiceId);
      fs.rm(path.join(VOICE_DIR, voiceId + '.m4a'), { force: true }, () => {});
    }
  }
  cleanupOrphanVoices();
}, 60 * 1000).unref();
// 清扫召回挂起（60s 无答复）与过期历史（30 天）：过期通知发起方，历史有删减即落盘
setInterval(() => {
  const before = recallBook.history.size;
  for (const initiator of recallBook.sweep()) {
    send(online.get(initiator), { type: 'pair.recall_failed', reason: 'expired' });
  }
  if (recallBook.history.size !== before) saveHistory();
}, 60 * 1000).unref();

function pairingOf(deviceId) { return pairings[deviceId] || null; }
function peerDeviceId(deviceId) { return pairings[deviceId]?.peer || null; }
function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    if (obj.type !== undefined && !LOG_MINIMAL) {
      // 留言正文不落日志（隐私）：与 sdp/ice 同样只记类型
      // incoming/accepted 携带 TURN 时限凭证（username/credential），同样只记类型
      // paired/pair.code 是长期重连凭证与可抢用的六位配对码，同样只记类型
      const quiet = QUIET_TYPES.has(obj.type);
      console.log(`[relay] ${ws.deviceId || '?'} <- ${obj.type}` +
        (quiet ? '' : ` ${JSON.stringify(redactSecrets(obj)).slice(0, 120)}`));
    }
    try {
      ws.send(JSON.stringify(obj));
      return true;
    } catch (err) {
      // readyState 检查和 send 之间存在关闭竞态；不能让一个掉线连接
      // 的同步异常冒泡到 WebSocket 业务回调，进而影响整个进程。
      console.log(`[ws] ${ws.deviceId || '?'} send failed: ${err.message}`);
    }
  }
  return false;
}

// ---- 断连排查用的日志安全取值：枚举名/标量可以落盘，自由文本一律过滤 ----
const safeTag = (v, max) => String(v ?? '?').replace(/[^\w.-]/g, '_').slice(0, max);
const safeNum = (v) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : -1);
const safeBool = (v) => (v === true ? 'true' : v === false ? 'false' : '?');
// ICE 候选只记类型（host/srflx/relay）：是否退化到 TURN 中转是断连排查的关键分叉，
// 但候选行含内网/公网地址与端口，不落盘。
function iceCandidateType(c) {
  const line = String((c && c.candidate) || c || '');
  const m = line.match(/\btyp\s+([A-Za-z]+)/);
  return m ? m[1] : '?';
}

function iceServers() {
  // 纯逻辑在 turn.js（单测覆盖）：reverse-proxy 限时凭证 + stun/turn udp+tcp 三 urls
  return buildIceServers({
    secret: TURN_STATIC_AUTH_SECRET, host: TURN_HOST, port: TURN_PORT,
  });
}

// ---------- WebSocket 服务 ----------
// 语音文件鉴权：已配对设备的 token 必须与 pairings 一致；只回给"收件人"本人。
function authedDevice(url) {
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  const token = String(url.searchParams.get('token') || '');
  const pairing = deviceId && pairings[deviceId];
  if (!pairing || pairing.token !== token) return null;
  return deviceId;
}
const server = http.createServer((req, res) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch { res.writeHead(400); res.end(); return; }
  if (url.pathname === '/health') { res.writeHead(200); res.end('ok'); return; }

  if (url.pathname === '/voice' && req.method === 'POST') {
    const deviceId = authedDevice(url);
    if (!deviceId) { res.writeHead(401); res.end('{}'); return; }
    let size = 0;
    let tooLarge = false;
    const chunks = [];
    req.on('data', (c) => {
      if (tooLarge) return;
      size += c.length;
      if (size > VOICE_MAX_BYTES) {
        tooLarge = true;
        chunks.length = 0;
        // 先给出完整 HTTP 响应，再继续消费请求流；req.destroy() 会把
        // 客户端看到的 413 变成连接重置，移动网络下容易被误判为断网。
        res.writeHead(413, { 'content-type': 'application/json' });
        res.end('{}');
        req.resume();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (res.headersSent || tooLarge) return;
      if (size === 0) { res.writeHead(400, { 'content-type': 'application/json' }); res.end('{}'); return; }
      const id = crypto.randomUUID();
      try {
        fs.mkdirSync(VOICE_DIR, { recursive: true });
        fs.writeFileSync(path.join(VOICE_DIR, id + '.m4a'), Buffer.concat(chunks));
        voiceUploads.set(id, { owner: deviceId, ts: Date.now() });
      } catch { res.writeHead(500); res.end('{}'); return; }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id }));
    });
    req.on('aborted', () => {});
    req.on('error', () => {});
    return;
  }

  const dl = url.pathname.match(/^\/voice\/([0-9a-f-]{36})$/i);
  if (dl && req.method === 'GET') {
    const deviceId = authedDevice(url);
    const voiceId = dl[1];
    if (!deviceId || !msgBook.hasVoice(deviceId, voiceId)) { res.writeHead(403); res.end(); return; }
    const file = path.join(VOICE_DIR, voiceId + '.m4a');
    fs.stat(file, (err, st) => {
      if (err) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-type': 'audio/mp4', 'content-length': st.size });
      const stream = fs.createReadStream(file);
      stream.on('error', (streamErr) => {
        console.warn(`[voice] read failed: ${streamErr.message}`);
        if (!res.headersSent) res.writeHead(404);
        res.destroy();
      });
      stream.pipe(res);
    });
    return;
  }
  res.writeHead(404); res.end();
});
const wss = new WebSocketServer({ server, maxPayload: MAX_MESSAGE_BYTES });

wss.on('connection', (ws) => {
  ws.deviceId = null;
  ws.authed = false;      // hello 令牌校验通过 / 刚完成配对
  ws.pairAttempts = 0;
  ws.connectedAt = Date.now();  // 排查用：连接存活时长
  ws.iceSeen = 0;               // 排查用：本连接转发过的 ICE 候选数
  ws.sessionAt = 0;             // 排查用：本连接最后一次 offer 时间

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
      if (old && old !== ws) {
        // 同 deviceId 新连接顶掉旧连接：旧会话的媒体会随之失去信令，必须留痕
        console.log(`[ws] ${ws.deviceId} replaced old connection ` +
          `(oldAliveFor=${Date.now() - (old.connectedAt || Date.now())}ms)`);
        old.close();
      }
      online.set(ws.deviceId, ws);
      const peer = peerDeviceId(ws.deviceId);
      send(ws, {
        type: 'registered',
        paired: !!pairing,
        peerOnline: !!peer && online.has(peer),
        hasHistory: recallBook.hasHistory(ws.deviceId),
      });
      // 上线即推送留言箱（离线消息没有推送通道，打开 App 才送达）
      const pending = msgBook.list(ws.deviceId);
      if (pending.length) send(ws, { type: 'msg.inbox', items: pending });
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
        const r = pairBook.request(ws.deviceId);
        if (r.out === 'failed') { send(ws, { type: 'pair.failed', reason: r.reason }); break; }
        send(ws, { type: 'pair.code', code: r.code });
        break;
      }
      case 'pair.enter': {
        const r = pairBook.enter(ws, ws.deviceId, msg.code);
        if (r.out === 'failed') { send(ws, { type: 'pair.failed', reason: r.reason }); break; }
        if (!bindPair(r.a, r.b)) send(ws, { type: 'pair.failed', reason: 'server_storage' });
        break;
      }
      case 'pair.recall': {
        // 找回老搭档：只对"有历史关系"的设备生效，且必须对方确认（弹窗或互摇）
        if (!ws.authed) break;
        if (pairingOf(ws.deviceId)) break; // 已配对，无需召回
        const r = recallBook.requestRecall(ws.deviceId);
        if (r.out === 'failed') { send(ws, { type: 'pair.recall_failed', reason: r.reason }); break; }
        const peerWs = online.get(r.peer);
        if (!peerWs) {
          recallBook.settle(ws.deviceId);
          send(ws, { type: 'pair.recall_failed', reason: 'peer_offline' });
          break;
        }
        if (pairingOf(r.peer)) {
          recallBook.settle(ws.deviceId);
          send(ws, { type: 'pair.recall_failed', reason: 'peer_busy' });
          break;
        }
        send(ws, { type: 'pair.recall_sent' });
        send(peerWs, { type: 'pair.recall_ask', from: ws.deviceId });
        break;
      }
      case 'pair.recall_ok': {
        // 被召回方答复。accept=true 也要复查双方状态（弹窗期间可能已发生别的配对）
        if (!ws.authed) break;
        const r = recallBook.answer(ws.deviceId, msg.accept === true);
        if (r.out === 'ignored') break;
        if (r.out === 'failed') { send(online.get(r.initiator), { type: 'pair.recall_failed', reason: r.reason }); break; }
        if (r.out === 'declined') {
          send(online.get(r.initiator), { type: 'pair.recall_failed', reason: 'declined' });
          break;
        }
        const { a, b } = r;
        if (pairingOf(a) || pairingOf(b)) {
          if (online.get(a)) send(online.get(a), { type: 'pair.recall_failed', reason: 'peer_busy' });
          if (online.get(b)) send(online.get(b), { type: 'pair.recall_failed', reason: 'peer_busy' });
          break;
        }
        if (!bindPair(a, b)) {
          if (online.get(a)) send(online.get(a), { type: 'pair.recall_failed', reason: 'server_storage' });
          if (online.get(b)) send(online.get(b), { type: 'pair.recall_failed', reason: 'server_storage' });
        }
        break;
      }
      case 'pair.shake': {
        // 千里同步：双方都在主页 3s 内摇动即视为双向意愿，直接复配
        if (!ws.authed) break;
        if (pairingOf(ws.deviceId)) break;
        const r = recallBook.shake(ws.deviceId);
        if (r.out === 'ignored' || r.out === 'waiting') break;
        if (r.out === 'failed') { send(ws, { type: 'pair.recall_failed', reason: r.reason }); break; }
        const { a, b } = r;
        if (pairingOf(a) || pairingOf(b)) {
          if (online.get(a)) send(online.get(a), { type: 'pair.recall_failed', reason: 'peer_busy' });
          if (online.get(b)) send(online.get(b), { type: 'pair.recall_failed', reason: 'peer_busy' });
          break;
        }
        if (!bindPair(a, b)) {
          if (online.get(a)) send(online.get(a), { type: 'pair.recall_failed', reason: 'server_storage' });
          if (online.get(b)) send(online.get(b), { type: 'pair.recall_failed', reason: 'server_storage' });
        }
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
        // 留言箱是配对关系内的私物：解绑即双向清空（文字+语音），不给新伴侣留旧账
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (!peer) { send(ws, { type: 'unpaired' }); break; }
        const oldSelfPair = pairings[ws.deviceId];
        const oldPeerPair = pairings[peer];
        delete pairings[ws.deviceId];
        delete pairings[peer];
        if (!savePairings()) {
          pairings[ws.deviceId] = oldSelfPair;
          pairings[peer] = oldPeerPair;
          send(ws, { type: 'error', reason: 'server_storage' });
          break;
        }
        // 历史关系保留（找回TA 的凭据），只清挂起的召回/摇动
        recallBook.settle(ws.deviceId);
        recallBook.settle(peer);
        deleteVoices([...msgBook.forgetDevice(ws.deviceId), ...msgBook.forgetDevice(peer)]);
        saveMessages();
        send(ws, { type: 'unpaired' });
        send(online.get(peer), { type: 'unpaired' });
        console.log(`[pair] unpaired ${ws.deviceId} <-> ${peer}`);
        break;
      }

      // ---- 离线留言（文字/语音）----
      case 'msg.post': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        if (!peer) { send(ws, { type: 'msg.failed', reason: 'unpaired' }); break; }
        if (msg.kind === 'voice' && /^[0-9a-f-]{36}$/i.test(String(msg.voiceId || ''))) {
          const upload = voiceUploads.get(String(msg.voiceId).toLowerCase()) || voiceUploads.get(String(msg.voiceId));
          if (!upload || upload.owner !== ws.deviceId) {
            send(ws, { type: 'msg.failed', reason: 'voice_not_uploaded' });
            break;
          }
        }
        const r = msgBook.post(ws.deviceId, peer, msg);
        if (r.out === 'rejected') {
          // 被拒的语音文件是孤儿（收件人永远看不到它）：只清理当前
          // 设备自己上传且已登记的文件，避免任意请求借机删别人的文件。
          if (msg.kind === 'voice' && msg.voiceId) {
            const voiceId = String(msg.voiceId).toLowerCase();
            const upload = voiceUploads.get(voiceId);
            if (upload && upload.owner === ws.deviceId) {
              voiceUploads.delete(voiceId);
              fs.rm(path.join(VOICE_DIR, voiceId + '.m4a'), { force: true }, () => {});
            }
          }
          send(ws, { type: 'msg.failed', reason: r.reason });
          break;
        }
        if (r.item.kind === 'voice') voiceUploads.delete(r.item.voiceId.toLowerCase());
        saveMessages();
        deleteVoices(r.dropped); // 超容量挤掉的最旧语音一并删文件
        send(ws, { type: 'msg.sent', id: r.item.id });
        const peerWs = online.get(peer);
        if (peerWs) send(peerWs, { type: 'msg.new', ...r.item });
        break;
      }
      case 'msg.read': {
        // 阅后即删：客户端展示后回执删除（隐私默认，不留长期存储）
        if (!ws.authed) break;
        const removed = msgBook.ack(ws.deviceId, msg.ids);
        if (removed.length) { deleteVoices(removed); saveMessages(); }
        break;
      }
      case 'msg.recall': {
        // 撤回：只认自己发的、发出后 5 分钟内。未读从信箱摘除+删语音文件；
        // 双方各收 msg.recalled 打墓碑（收件人已读且离线时只能撤发件人侧——隐私模型不追删本地）
        if (!ws.authed) break;
        const id = typeof msg.id === 'string' ? msg.id : '';
        if (!/^[0-9a-f-]{36}$/i.test(id)) break;
        const r = msgBook.recall(ws.deviceId, id);
        if (r.out === 'rejected') {
          send(ws, { type: 'msg.recall_failed', id, reason: r.reason });
          break;
        }
        if (r.item) deleteVoices([r.item]);
        saveMessages();
        send(ws, { type: 'msg.recalled', id });
        const peerWs = online.get(r.to);
        if (peerWs) send(peerWs, { type: 'msg.recalled', id });
        break;
      }

      // ---- 媒体协商与挂断（原样转发）----
      case 'sdp':
      case 'ice':
      case 'bye': {
        if (!ws.authed) break;
        const peer = peerDeviceId(ws.deviceId);
        // 断连排查：协商/挂断按到达时序留痕，只记标量（kind、候选类型、计数），
        // 不含 SDP 正文与候选地址。peerOnline=false 时消息会静默丢弃，必须有痕迹。
        if (type === 'ice') ws.iceSeen = (ws.iceSeen || 0) + 1;
        if (type === 'sdp' && msg.sdp && msg.sdp.type === 'offer') ws.sessionAt = Date.now();
        const detail = type === 'sdp'
          ? `kind=${safeTag(msg.sdp && msg.sdp.type, 8)} len=${String((msg.sdp && msg.sdp.sdp) || '').length}`
          : type === 'ice'
            ? `#${ws.iceSeen} typ=${iceCandidateType(msg.candidate)}`
            : `sessionMs=${ws.sessionAt ? Date.now() - ws.sessionAt : -1} iceSeen=${ws.iceSeen || 0}`;
        // sdp/ice 逐帧行是日志体积的大头（一次协商几十条），S2S_LOG=minimal 时只留 bye
        if (!LOG_MINIMAL || type === 'bye') {
          console.log(`[call] ${type} ${ws.deviceId} -> ${peer || '-'} ${detail} ` +
            `peerOnline=${peer ? online.has(peer) : false}`);
        }
        if (peer) send(online.get(peer), msg);
        break;
      }
      // ---- 客户端自诊断上报（远程排查断连：服务器看不到 WebRTC 媒体，只能靠双端自报）----
      // 只认已鉴权连接，只记枚举名与标量，不落任何业务内容；未知字段忽略。
      case 'diag.session': {
        if (!ws.authed) break;
        console.log(`[diag.session] ${ws.deviceId} role=${safeTag(msg.role, 16)} ` +
          `reason=${safeTag(msg.reason, 32)} ice=${safeTag(msg.ice, 16)} ` +
          `path=${safeTag(msg.path, 16)} setupMs=${safeNum(msg.setupMs)} ` +
          `liveMs=${safeNum(msg.liveMs)} fg=${safeBool(msg.fg)} ` +
          `screenOn=${safeBool(msg.screenOn)} proj=${safeTag(msg.proj, 24)} ` +
          `app=${safeTag(msg.app, 16)}`);
        break;
      }
      case 'diag.event': {
        if (!ws.authed) break;
        console.log(`[diag.event] ${ws.deviceId} role=${safeTag(msg.role, 16)} ` +
          `event=${safeTag(msg.event, 32)} ice=${safeTag(msg.ice, 16)} ` +
          `fg=${safeBool(msg.fg)} screenOn=${safeBool(msg.screenOn)} ` +
          `liveMs=${safeNum(msg.liveMs)} detail=${safeTag(msg.detail, 64)} ` +
          `app=${safeTag(msg.app, 16)}`);
        break;
      }

      case 'app.version': {
        // 客户端可控字符串必须过滤：裸插一个含换行的 model/name 就能伪造任意日志行
        console.log(`[diag.version] ${ws.deviceId} code=${safeNum(msg.localCode)} ` +
          `name=${safeTag(msg.localName, 16)} remote=${safeNum(msg.remoteCode)}/${safeTag(msg.remoteName, 16)} ` +
          `update=${safeBool(msg.hasUpdate)}`);
        break;
      }
      case 'app.diag': {
        // 会话能力自检矩阵（机型/系统/权限）：跨机型问题远程定位用，只含公开设备信息
        console.log(`[diag.selfcheck] ${ws.deviceId} role=${safeTag(msg.role, 16)} ` +
          `model=${safeTag(msg.model, 32)} brand=${safeTag(msg.brand ?? '-', 16)} ` +
          `os=${safeTag(msg.os, 12)}/sdk${safeNum(msg.sdk)} rom=${safeTag(msg.rom ?? '-', 24)} ` +
          `app=${safeTag(msg.app, 16)} notif=${safeBool(msg.notif)} overlay=${safeBool(msg.overlay)} ` +
          `acc=${safeBool(msg.acc)} accConnected=${safeBool(msg.accConnected)} ` +
          `accGestureCap=${safeBool(msg.accGestureCap)} mic=${safeBool(msg.mic)} ` +
          `battery=${safeBool(msg.battery)} ctl=${safeBool(msg.ctlAllowed ?? msg.ctlSupported)}`);
        break;
      }
      default: break;
    }
  });

  ws.on('close', (code, reason) => {
    // 区分三种死法：客户端主动关(1000/1001)、心跳超时被 terminate(1006)、被新连接顶掉
    console.log(`[ws] ${ws.deviceId || '?'} close code=${code} ` +
      `reason=${String(reason || '').slice(0, 24) || '-'} ` +
      `aliveFor=${Date.now() - (ws.connectedAt || Date.now())}ms ` +
      `iceSeen=${ws.iceSeen} sessionMs=${ws.sessionAt ? Date.now() - ws.sessionAt : -1}`);
    if (ws.deviceId && online.get(ws.deviceId) === ws) {
      online.delete(ws.deviceId);
      pairBook.forgetDevice(ws.deviceId);
      lastInviteAt.delete(ws.deviceId);
      // 正被召回/撮合的一方掉线：通知等待方别傻等
      for (const initiator of recallBook.onPeerGone(ws.deviceId)) {
        send(online.get(initiator), { type: 'pair.recall_failed', reason: 'peer_offline' });
      }
      const peer = peerDeviceId(ws.deviceId);
      if (peer) {
        console.log(`[call] peer.gone ${ws.deviceId} -> ${peer}（信令断开，观看/共享端会收到对方离线）`);
        send(online.get(peer), { type: 'peer.gone' });
      }
    }
  });
});

// 心跳: 清理假死连接
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      // 30s 内没回 pong：链路已假死（Doze/切网/被 ROM 冻结），服务器主动断
      console.log(`[ws] ${ws.deviceId || '?'} pong 超时 terminate（假死）`);
      ws.terminate();
      return;
    }
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
