// T1 信令冒烟测试：模拟两台设备完整会话流程
// 用法: SIG_URL=wss://soul.lumi666.cloud node smoke-test.cjs （默认 ws://127.0.0.1:8080）
// 注意: 依赖服务端目录下的 node_modules/ws，请在 server/ 目录内运行
const path = require('path');
const WebSocket = require(path.join(process.cwd(), 'node_modules', 'ws'));

const URL = process.env.SIG_URL || 'ws://127.0.0.1:8080';
let failures = 0;

function check(name, cond) {
  if (cond) console.log(`  PASS ${name}`);
  else { failures++; console.log(`  FAIL ${name}`); }
}

function mkClient(id, tokenHolder, onMsg) {
  const ws = new WebSocket(URL);
  const pending = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    const i = waiters.findIndex((w) => w.pred(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else pending.push(msg);
    onMsg && onMsg(msg);
  });
  ws.on('open', () => {
    const hello = { type: 'hello', deviceId: id };
    if (tokenHolder && tokenHolder.token) hello.token = tokenHolder.token;
    ws.send(JSON.stringify(hello));
  });
  return {
    ws,
    send: (obj) => ws.send(JSON.stringify(obj)),
    waitFor: (pred, timeout = 3000, label = '?') =>
      new Promise((resolve, reject) => {
        const idx = pending.findIndex(pred);
        if (idx >= 0) return resolve(pending.splice(idx, 1)[0]);
        waiters.push({ pred, resolve });
        setTimeout(() => reject(new Error('timeout: ' + label)), timeout);
      }),
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log('== Soul2Soul 信令冒烟 ==', URL);
  const tokens = { a: null, b: null };
  const A = mkClient('testDeviceA', { get token() { return tokens.a; } });
  const B = mkClient('testDeviceB', { get token() { return tokens.b; } });
  await sleep(500);

  // 配对
  A.send({ type: 'pair.request' });
  const code = await A.waitFor((m) => m.type === 'pair.code', 3000, 'pair.code');
  check('A 收到 6 位配对码', /^\d{6}$/.test(code.code));
  B.send({ type: 'pair.enter', code: code.code });
  const pA = await A.waitFor((m) => m.type === 'paired', 3000, 'paired@A');
  const pB = await B.waitFor((m) => m.type === 'paired', 3000, 'paired@B');
  check('A 收到 paired 且带 token', pA.type === 'paired' && !!pA.token);
  check('B 收到 paired 且带 token', pB.type === 'paired' && !!pB.token);
  tokens.a = pA.token; tokens.b = pB.token;

  // 错误 token 必须被拒
  const BAD = mkClient('testDeviceA', { token: 'wrong-token-wrong-token' });
  let rejected = false;
  BAD.ws.on('close', (c) => { if (c === 4001) rejected = true; });
  BAD.ws.on('message', (raw) => { if (JSON.parse(raw.toString()).reason === 'bad_token') rejected = true; });
  await sleep(600);
  check('错误 token 被拒绝(4001/bad_token)', rejected);

  // 正确 token 重连: 顶替旧连接并通知对端
  const A2 = mkClient('testDeviceA', { get token() { return tokens.a; } });
  await sleep(600);
  const online = await B.waitFor((m) => m.type === 'peer.online', 3000, 'peer.online');
  check('B 收到 peer.online（A 重连）', online.type === 'peer.online');

  // 呼叫
  A2.send({ type: 'invite' });
  const inc = await B.waitFor((m) => m.type === 'incoming', 3000, 'incoming');
  check('B 收到 incoming 且带 iceServers', Array.isArray(inc.iceServers) && inc.iceServers.length >= 2);
  const turn = inc.iceServers.find((s) => (s.urls || []).some((u) => u.startsWith('turn:')));
  check('TURN 凭证已签发', !!turn && !!turn.username && !!turn.credential);

  B.send({ type: 'accept' });
  const acc = await A2.waitFor((m) => m.type === 'accepted', 3000, 'accepted');
  check('A 收到 accepted 且带 iceServers', Array.isArray(acc.iceServers));

  // 呼叫取消（B 发 cancel，A 应收到 call.canceled）
  B.send({ type: 'cancel' });
  const cc = await A2.waitFor((m) => m.type === 'call.canceled', 3000, 'call.canceled');
  check('A 收到 call.canceled', cc.type === 'call.canceled');

  // SDP / ICE 透传
  A2.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'v=0-fake' } });
  const sdp = await B.waitFor((m) => m.type === 'sdp', 3000, 'sdp');
  check('B 收到 offer 透传', sdp.sdp.sdp === 'v=0-fake');
  B.send({ type: 'ice', candidate: { candidate: 'candidate:1', sdpMid: '0', sdpMLineIndex: 0 } });
  const ice = await A2.waitFor((m) => m.type === 'ice', 3000, 'ice');
  check('A 收到 ice 透传', ice.candidate.candidate === 'candidate:1');

  // 伪造连接（坏 token）不能收到转发消息
  const rogue = mkClient('testDeviceB', { token: 'bad' }); // 会被踢
  let leaked = false;
  rogue.ws.on('message', (raw) => { if (JSON.parse(raw.toString()).type === 'sdp') leaked = true; });
  await sleep(600);
  A2.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'v=0-should-not-leak' } });
  await sleep(600);
  check('伪造连接收不到转发消息', !leaked);

  // 挂断 + 掉线
  A2.send({ type: 'bye' });
  const bye = await B.waitFor((m) => m.type === 'bye', 3000, 'bye');
  check('B 收到 bye', bye.type === 'bye');
  A2.ws.close();
  const gone = await B.waitFor((m) => m.type === 'peer.gone', 3000, 'peer.gone');
  check('B 收到 peer.gone（A 掉线）', gone.type === 'peer.gone');

  B.ws.close(); A.ws.close(); BAD.ws.close(); rogue.ws.close();
  console.log(failures === 0 ? '== 全部通过 ==' : `== ${failures} 项失败 ==`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('SMOKE ERROR:', e.message);
  process.exit(2);
});
