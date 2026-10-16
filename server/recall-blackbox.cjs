// T3 老搭档复配黑盒：配对→解绑→找回TA（确认/拒绝/对方离线）→互摇直绑→陌生人防护→已配对忽略
// 用法: SIG_URL=ws://127.0.0.1:PORT node recall-blackbox.cjs
// 注意: 依赖服务端目录下的 node_modules/ws，请在 server/ 目录内运行；数据走 mktemp（run-blackbox.sh 保证）
const path = require('path');
const WebSocket = require(path.join(process.cwd(), 'node_modules', 'ws'));

const URL = process.env.SIG_URL || 'ws://127.0.0.1:8080';
let failures = 0;

function check(name, cond) {
  if (cond) console.log(`  PASS ${name}`);
  else { failures++; console.log(`  FAIL ${name}`); }
}

function mkClient(id, tokenHolder) {
  const ws = new WebSocket(URL);
  const pending = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    const i = waiters.findIndex((w) => w.pred(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else pending.push(msg);
  });
  ws.on('open', () => {
    const hello = { type: 'hello', deviceId: id };
    const t = tokenHolder && tokenHolder.token;
    if (t) hello.token = t;
    ws.send(JSON.stringify(hello));
  });
  return {
    ws,
    send: (obj) => ws.send(JSON.stringify(obj)),
    has: (pred) => pending.some(pred),
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
const closed = (ws) => new Promise((r) => ws.on('close', r));
const isRecallMsg = (m) => String(m.type || '').startsWith('pair.recall');

(async () => {
  console.log('== Soul2Soul 老搭档复配黑盒 ==', URL);
  const tokens = { a: null, b: null };
  const holders = {
    a: { get token() { return tokens.a; } },
    b: { get token() { return tokens.b; } },
  };

  // 1) 正常码配对 → 解绑（制造"有历史、未配对"的前置状态）
  let A = mkClient('rcDevA', holders.a);
  let B = mkClient('rcDevB', holders.b);
  await sleep(400);
  A.send({ type: 'pair.request' });
  const code = await A.waitFor((m) => m.type === 'pair.code', 3000, 'pair.code');
  B.send({ type: 'pair.enter', code: code.code });
  tokens.a = (await A.waitFor((m) => m.type === 'paired', 3000, 'paired@A')).token;
  tokens.b = (await B.waitFor((m) => m.type === 'paired', 3000, 'paired@B')).token;
  const token1 = tokens.a;
  A.send({ type: 'unpair' });
  await A.waitFor((m) => m.type === 'unpaired', 3000, 'unpaired@A');
  await B.waitFor((m) => m.type === 'unpaired', 3000, 'unpaired@B');
  A.ws.close(); await closed(A.ws);
  B.ws.close(); await closed(B.ws);

  // 2) 重连：registered 带 hasHistory；A 发起召回，B 弹窗同意 → 双向 paired（新 token）
  A = mkClient('rcDevA', holders.a);
  B = mkClient('rcDevB', holders.b);
  const regA = await A.waitFor((m) => m.type === 'registered', 3000, 'registered@A');
  await B.waitFor((m) => m.type === 'registered', 3000, 'registered@B');
  check('解绑后重连仍报告有历史搭档', regA.paired === false && regA.hasHistory === true);
  A.send({ type: 'pair.recall' });
  await A.waitFor((m) => m.type === 'pair.recall_sent', 3000, 'recall_sent@A');
  const ask = await B.waitFor((m) => m.type === 'pair.recall_ask', 3000, 'recall_ask@B');
  check('召回请求送达被召回方且标明来源', ask.from === 'rcDevA');
  B.send({ type: 'pair.recall_ok', accept: true });
  const pa = await A.waitFor((m) => m.type === 'paired', 3000, 'paired@A(recall)');
  const pb = await B.waitFor((m) => m.type === 'paired', 3000, 'paired@B(recall)');
  check('召回成功双方拿到同一个新 token', !!pa.token && pa.token === pb.token && pa.token !== token1);
  tokens.a = pa.token; tokens.b = pb.token;
  A.ws.close(); await closed(A.ws);
  B.ws.close(); await closed(B.ws);
  // 新 token 能通过 hello 鉴权（token 真写进了 pairings）
  A = mkClient('rcDevA', holders.a);
  const reg2 = await A.waitFor((m) => m.type === 'registered', 3000, 'registered@A(new token)');
  check('召回后的新 token 重连鉴权通过', reg2.paired === true);

  // 3) 再解绑，B 发起召回，A 拒绝 → B 收 declined
  A.send({ type: 'unpair' });
  await A.waitFor((m) => m.type === 'unpaired', 3000, 'unpaired@A2');
  B = mkClient('rcDevB', holders.b); // 旧 token 已失效，hello 不带 token 也能以未配对身份注册
  await B.waitFor((m) => m.type === 'registered', 3000, 'registered@B2');
  B.send({ type: 'pair.recall' });
  await B.waitFor((m) => m.type === 'pair.recall_sent', 3000, 'recall_sent@B');
  await A.waitFor((m) => m.type === 'pair.recall_ask', 3000, 'recall_ask@A');
  A.send({ type: 'pair.recall_ok', accept: false });
  const declined = await B.waitFor((m) => m.type === 'pair.recall_failed', 3000, 'recall_failed@B');
  check('被召回方拒绝后发起方收到 declined', declined.reason === 'declined');

  // 4) 对方离线：A 掉线后 B 再召回（等过 10s 限频窗）→ peer_offline
  A.ws.close(); await closed(A.ws);
  await sleep(10_100);
  B.send({ type: 'pair.recall' });
  const off = await B.waitFor((m) => m.type === 'pair.recall_failed', 3000, 'recall_failed@offline');
  check('对方不在线时召回立即失败', off.reason === 'peer_offline');

  // 5) 千里同步：双方 3s 内互摇 → 直接双向 paired（免弹窗）
  B.ws.close(); await closed(B.ws);
  A = mkClient('rcDevA', null);
  B = mkClient('rcDevB', null);
  await A.waitFor((m) => m.type === 'registered', 3000, 'registered@A4');
  await B.waitFor((m) => m.type === 'registered', 3000, 'registered@B4');
  A.send({ type: 'pair.shake' });
  await sleep(500);
  B.send({ type: 'pair.shake' });
  const sa = await A.waitFor((m) => m.type === 'paired', 3000, 'paired@A(shake)');
  const sb = await B.waitFor((m) => m.type === 'paired', 3000, 'paired@B(shake)');
  check('互摇 3s 窗口内直接完成绑定', !!sa.token && sa.token === sb.token);
  tokens.a = sa.token; tokens.b = sb.token;

  // 6) 陌生人没有历史：召回与摇一摇都被 no_history 拒
  const C = mkClient('rcStranger', null);
  await C.waitFor((m) => m.type === 'registered', 3000, 'registered@C');
  C.send({ type: 'pair.recall' });
  const cn = await C.waitFor((m) => m.type === 'pair.recall_failed', 3000, 'recall@stranger');
  check('无历史设备召回被拒', cn.reason === 'no_history');
  C.send({ type: 'pair.shake' });
  const cs = await C.waitFor((m) => m.type === 'pair.recall_failed', 3000, 'shake@stranger');
  check('无历史设备摇一摇被拒', cs.reason === 'no_history');
  C.ws.close();

  // 7) 已配对设备发召回/摇动：静默忽略（不打扰现有关系）
  A.send({ type: 'pair.recall' });
  A.send({ type: 'pair.shake' });
  await sleep(500);
  check('已配对时召回与摇动均无回应', !A.has(isRecallMsg));
  A.ws.close(); await closed(A.ws);
  B.ws.close(); await closed(B.ws);

  console.log(failures ? `== FAIL ${failures} ==` : '== ALL PASS ==');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('黑盒异常:', e.message); process.exit(1); });
