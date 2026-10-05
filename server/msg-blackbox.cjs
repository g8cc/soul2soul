// T3 离线留言黑盒：配对→离线留言→上线收箱→阅后即删→语音上传/下载鉴权→解绑清空
// 用法: SIG_URL=ws://127.0.0.1:PORT HTTP_URL=http://127.0.0.1:PORT node msg-blackbox.cjs
// 注意: 依赖服务端目录下的 node_modules/ws，请在 server/ 目录内运行；数据走 mktemp（run-blackbox.sh 保证）
const path = require('path');
const crypto = require('crypto');
const WebSocket = require(path.join(process.cwd(), 'node_modules', 'ws'));

const URL = process.env.SIG_URL || 'ws://127.0.0.1:8080';
const HTTP = process.env.HTTP_URL || URL.replace(/^ws/, 'http');
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

(async () => {
  console.log('== Soul2Soul 离线留言黑盒 ==', URL);
  const tokens = { a: null, b: null };
  const holders = {
    a: { get token() { return tokens.a; } },
    b: { get token() { return tokens.b; } },
  };

  // 1) 配对拿到 token，然后双方都下线
  const A = mkClient('msgDevA', holders.a);
  const B = mkClient('msgDevB', holders.b);
  await sleep(400);
  A.send({ type: 'pair.request' });
  const code = await A.waitFor((m) => m.type === 'pair.code', 3000, 'pair.code');
  B.send({ type: 'pair.enter', code: code.code });
  tokens.a = (await A.waitFor((m) => m.type === 'paired', 3000, 'paired@A')).token;
  tokens.b = (await B.waitFor((m) => m.type === 'paired', 3000, 'paired@B')).token;
  A.ws.close(); await closed(A.ws);
  B.ws.close(); await closed(B.ws);

  // 2) 只有 A 上线，给离线的 B 留言 → msg.sent，且不会有任何转发
  const A2 = mkClient('msgDevA', holders.a);
  await A2.waitFor((m) => m.type === 'registered', 3000, 'registered@A');
  A2.send({ type: 'msg.post', kind: 'text', text: '离线文字留言' });
  const sent = await A2.waitFor((m) => m.type === 'msg.sent', 3000, 'msg.sent');
  check('离线留言返回 msg.sent', !!sent.id);
  A2.send({ type: 'msg.post', kind: 'text', text: '太快了' }); // 限速窗内
  const fail = await A2.waitFor((m) => m.type === 'msg.failed', 3000, 'msg.failed(too_fast)');
  check('1 秒内连发被限速拒绝', fail.reason === 'too_fast');
  await sleep(1100);
  A2.send({ type: 'msg.post', kind: 'text', text: '超' + '长'.repeat(500) });
  const fail2 = await A2.waitFor((m) => m.type === 'msg.failed', 3000, 'msg.failed(too_long)');
  check('超 500 字被拒绝', fail2.reason === 'too_long');
  await sleep(1100);

  // 3) 语音：A 上传 → 留言 → B 上线取箱下载；非收件人下载被拒
  const blob = crypto.randomBytes(2048);
  const up = await fetch(`${HTTP}/voice?deviceId=msgDevA&token=${tokens.a}`, {
    method: 'POST', body: blob,
  });
  const upJson = await up.json();
  check('语音上传返回 id', up.status === 200 && /^[0-9a-f-]{36}$/i.test(upJson.id || ''));
  A2.send({ type: 'msg.post', kind: 'voice', voiceId: upJson.id, durMs: 4200 });
  const sentV = await A2.waitFor((m) => m.type === 'msg.sent', 3000, 'msg.sent(voice)');
  check('语音留言返回 msg.sent', !!sentV.id);
  // 伪造 voiceId 直接投递必须被拒（防止指着别人的文件）
  await sleep(1100);
  A2.send({ type: 'msg.post', kind: 'voice', voiceId: 'not-a-uuid', durMs: 1000 });
  const failV = await A2.waitFor((m) => m.type === 'msg.failed', 3000, 'msg.failed(bad_voice_id)');
  check('非法 voiceId 被拒绝', failV.reason === 'bad_voice_id');

  const badDl = await fetch(`${HTTP}/voice/${upJson.id}?deviceId=msgDevA&token=${tokens.a}`);
  check('发件人自己不能下载语音', badDl.status === 403);
  const noTokDl = await fetch(`${HTTP}/voice/${upJson.id}?deviceId=msgDevB&token=wrong`);
  check('错误 token 不能下载语音', noTokDl.status === 403 || noTokDl.status === 401);

  // 4) B 上线 → msg.inbox 按序拿到 3 条（2 文字 + 1 语音），下载语音字节一致
  const B2 = mkClient('msgDevB', holders.b);
  const inbox = await B2.waitFor((m) => m.type === 'msg.inbox', 3000, 'msg.inbox');
  check('B 上线收到留言箱（2 条：被拒的不入库）', Array.isArray(inbox.items) && inbox.items.length === 2);
  const vItem = inbox.items.find((m) => m.kind === 'voice');
  check('留言箱含语音条目', !!vItem && vItem.durMs === 4200 && vItem.from === 'msgDevA');
  const dl = await fetch(`${HTTP}/voice/${vItem.voiceId}?deviceId=msgDevB&token=${tokens.b}`);
  const got = Buffer.from(await dl.arrayBuffer());
  check('收件人下载语音内容一致', dl.status === 200 && got.equals(blob));

  // 5) 全 ack 后再留言 → 在线直达 msg.new；ack 后重复下载被拒
  B2.send({ type: 'msg.read', ids: inbox.items.map((m) => m.id) });
  await sleep(300);
  const afterAck = await fetch(`${HTTP}/voice/${vItem.voiceId}?deviceId=msgDevB&token=${tokens.b}`);
  check('阅后即删：ack 后语音不可再下载', afterAck.status === 403 || afterAck.status === 404);
  A2.send({ type: 'msg.post', kind: 'text', text: '在线直达' });
  const live = await B2.waitFor((m) => m.type === 'msg.new', 3000, 'msg.new(live)');
  check('收件人在线时留言直达 msg.new', live.text === '在线直达' && live.from === 'msgDevA');
  B2.send({ type: 'msg.read', ids: [live.id] });

  // 6) B 再上线不应再有旧箱残留
  await sleep(300);
  A2.ws.close(); await closed(A2.ws);
  B2.ws.close(); await closed(B2.ws);
  const B3 = mkClient('msgDevB', holders.b);
  await B3.waitFor((m) => m.type === 'registered', 3000, 'registered@B3');
  await sleep(500);
  check('阅后即删：重连无残留留言箱', !B3.has((m) => m.type === 'msg.inbox'));

  // 7) 解绑清空：留言后不读，直接 unpair，B 重连应收不到
  const A3 = mkClient('msgDevA', holders.a);
  await A3.waitFor((m) => m.type === 'registered', 3000, 'registered@A3');
  await sleep(1200); // 越过发件限速窗（上一条成功留言距此可能不足 1s）
  A3.send({ type: 'msg.post', kind: 'text', text: '解绑前的留言' });
  await A3.waitFor((m) => m.type === 'msg.sent', 3000, 'msg.sent@unpair-test');
  A3.send({ type: 'unpair' });
  await Promise.all([
    A3.waitFor((m) => m.type === 'unpaired', 3000, 'unpaired@A'),
    B3.waitFor((m) => m.type === 'unpaired', 3000, 'unpaired@B'),
  ]);
  A3.ws.close(); B3.ws.close(); await Promise.all([closed(A3.ws), closed(B3.ws)]);
  const B4 = mkClient('msgDevB', { token: null });
  const reg4 = await B4.waitFor((m) => m.type === 'registered', 3000, 'registered@B4');
  check('解绑后 paired=false', reg4.paired === false);
  await sleep(500);
  check('解绑清空：未读留言不残留', !B4.has((m) => m.type === 'msg.inbox'));
  B4.ws.close(); await closed(B4.ws);
  A.ws.close();

  console.log(failures === 0 ? '== 全部通过 ==' : `== ${failures} 项失败 ==`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('MSG-BLACKBOX ERROR:', e.message);
  process.exit(2);
});
