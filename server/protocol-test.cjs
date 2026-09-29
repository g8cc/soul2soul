// T2 协议对抗测试：对本地服务器实例做鲁棒性/安全轰炸
// 用法: PORT=18081 node src/index.js 启动实例后，SIG_URL=ws://127.0.0.1:18081 node protocol-test.cjs
const path = require('path');
const WebSocket = require(path.join(process.cwd(), 'node_modules', 'ws'));

const URL = process.env.SIG_URL || 'ws://127.0.0.1:18081';
let failures = 0;
function check(name, cond) {
  if (cond) console.log(`  PASS ${name}`);
  else { failures++; console.log(`  FAIL ${name}`); }
}
function mk(id, token, onMsg) {
  const ws = new WebSocket(URL);
  const pending = [];
  const waiters = [];
  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    const i = waiters.findIndex((w) => w.pred(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else pending.push(msg);
    onMsg && onMsg(msg);
  });
  ws.on('open', () => {
    const hello = { type: 'hello', deviceId: id };
    if (token) hello.token = token;
    ws.send(JSON.stringify(hello));
  });
  return {
    ws, pending,
    send: (obj) => ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj)),
    waitFor: (pred, timeout = 3000, label = '?') =>
      new Promise((resolve, reject) => {
        const idx = pending.findIndex(pred);
        if (idx >= 0) return resolve(pending.splice(idx, 1)[0]);
        waiters.push({ pred, resolve });
        setTimeout(() => reject(new Error('timeout: ' + label)), timeout);
      }),
    alive: () => ws.readyState === WebSocket.OPEN,
  };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log('== 协议对抗测试 ==', URL);

  // 0. 一对正常设备作为对照组
  const tokens = { a: null, b: null };
  const A = mk('advA', null);
  const B = mk('advB', null);
  await sleep(400);
  A.send({ type: 'pair.request' });
  const code = (await A.waitFor((m) => m.type === 'pair.code')).code;
  B.send({ type: 'pair.enter', code });
  const pA = await A.waitFor((m) => m.type === 'paired');
  const pB = await B.waitFor((m) => m.type === 'paired');
  tokens.a = pA.token; tokens.b = pB.token;

  // 1. 畸形输入轰炸：服务器不能崩
  const junk = mk('advJunk');
  await sleep(300);
  junk.send('not json at all');
  junk.send('');
  junk.send('{"type":');
  junk.send(JSON.stringify({ type: 'sdp', sdp: { type: 'offer', sdp: 'x'.repeat(1024 * 1024) } })); // 1MB 巨包
  junk.send('{"type":"hello"}'); // 无 deviceId
  junk.send('null');
  junk.send('[]');
  junk.send('{"type":"unknown_type","whatever":1}');
  await sleep(500);
  check('畸形轰炸后服务器仍健康', A.alive() && B.alive());
  A.send({ type: 'invite' });
  const inc = await B.waitFor((m) => m.type === 'incoming', 3000, 'invite after junk');
  check('对照组在轰炸后仍正常工作', inc.type === 'incoming');

  // 2. 未 hello 直接发媒体消息：被忽略
  const ghost = mk('advGhost', null); // 未配对设备，hello 即注册
  await sleep(300);
  ghost.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'ghost' } });
  let ghostGotOffer = false;
  ghost.ws.on('message', (raw) => { if (JSON.parse(raw.toString()).type === 'sdp') ghostGotOffer = true; });
  A.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'to-advB-only' } });
  const got = await B.waitFor((m) => m.type === 'sdp', 3000, 'sdp to B');
  await sleep(400);
  check('sdp 只到配对对端', got.sdp.sdp === 'to-advB-only');
  check('第三方收不到别人的 sdp', !ghostGotOffer);

  // 3. invite 限频：2 秒内第二次被拒
  await sleep(2100); // 先冷却，确保第一次 invite 不撞上上一段的限频窗口
  A.send({ type: 'invite' });
  await B.waitFor((m) => m.type === 'incoming', 3000, 'invite1');
  A.send({ type: 'invite' });
  const limited = await A.waitFor((m) => m.type === 'peer.offline' && m.reason === 'too_fast', 3000, 'rate limited');
  check('invite 限频生效', limited.reason === 'too_fast');
  await sleep(2100);

  // 4. pair.enter 爆破: 新连接 6 次错码 → too_many_attempts
  const brute = mk('advBrute');
  await sleep(300);
  let sawLimit = false;
  for (let i = 0; i < 6; i++) {
    brute.send({ type: 'pair.enter', code: '00000' + i });
    await sleep(120);
  }
  // 服务端在第 6 次尝试后拒绝
  for (let i = 0; i < 5 && !sawLimit; i++) {
    brute.send({ type: 'pair.enter', code: '999999' });
    try {
      const m = await brute.waitFor((m) => m.type === 'pair.failed' && m.reason === 'too_many_attempts', 1500, 'limit');
      sawLimit = true;
    } catch { /* 继续等 */ }
  }
  check('pair.enter 爆破被限制', sawLimit);

  // 5. 同 deviceId 三连挤占：最后的连接存活，前两个收到关闭
  let closedCount = 0;
  const s1 = mk('advDup', tokens.a); await sleep(300);
  s1.ws.on('close', () => { closedCount++; });
  const s2 = mk('advDup', tokens.a); await sleep(300);
  s2.ws.on('close', () => { closedCount++; });
  const s3 = mk('advDup', tokens.a); await sleep(500);
  check('同 deviceId 挤占后仅最新连接存活', closedCount >= 1 && s3.alive());

  // 6. 对端掉线后发 sdp：不崩，对端重连后可恢复转发
  B.ws.close();
  await sleep(400);
  A.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'into-the-void' } }); // 对端离线，静默
  await sleep(300);
  check('对端离线发 sdp 不崩溃', A.alive());
  // B 带令牌重连
  const B2 = mk('advB', tokens.b);
  await sleep(500);
  A.send({ type: 'sdp', sdp: { type: 'offer', sdp: 'after-reconnect' } });
  const got2 = await B2.waitFor((m) => m.type === 'sdp', 3000, 'sdp after reconnect');
  check('重连后转发恢复', got2.sdp.sdp === 'after-reconnect');

  // 7. 重连风暴: 同一设备 10 秒内重连 5 次
  for (let i = 0; i < 5; i++) {
    const w = mk('advB', tokens.b);
    await sleep(150);
    w.ws.close();
  }
  await sleep(500);
  const storm = mk('advB', tokens.b);
  await sleep(500);
  check('重连风暴后可正常注册', storm.alive());
  await sleep(2100); // 冷却 invite 限频窗口
  storm.send({ type: 'invite' });
  const inc2 = await A.waitFor((m) => m.type === 'incoming', 3000, 'incoming after storm');
  check('风暴后业务正常', inc2.type === 'incoming');

  console.log(failures === 0 ? '== 对抗测试全部通过 ==' : `== ${failures} 项失败 ==`);
  [A, B, junk, ghost, brute, s1, s2, s3, B2, storm].forEach((c) => c.ws.close?.());
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('PROTOCOL-TEST ERROR:', e.message);
  process.exit(2);
});
