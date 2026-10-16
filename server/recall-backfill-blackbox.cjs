// T3 存量配对回填黑盒：起一台"上线前就有老配对"的一次性服务器（pairings.json 预置、
// history.json 不存在），验证 hello 后 registered.hasHistory=true——否则现存情侣一解绑
// 就没有"找回TA"资格。数据全在 mktemp，绝不触碰生产 pairings.json。
// 用法: node recall-backfill-blackbox.cjs （cwd 必须在 server/，自管子进程与端口）
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const WebSocket = require(path.join(process.cwd(), 'node_modules', 'ws'));

let failures = 0;
function check(name, cond) {
  if (cond) console.log(`  PASS ${name}`);
  else { failures++; console.log(`  FAIL ${name}`); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function mkClient(url, id, token) {
  const ws = new WebSocket(url);
  const pending = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    const i = waiters.findIndex((w) => w.pred(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else pending.push(msg);
  });
  ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', deviceId: id, token })));
  return {
    ws,
    waitFor: (pred, timeout = 3000, label = '?') =>
      new Promise((resolve, reject) => {
        const idx = pending.findIndex(pred);
        if (idx >= 0) return resolve(pending.splice(idx, 1)[0]);
        waiters.push({ pred, resolve });
        setTimeout(() => reject(new Error('timeout: ' + label)), timeout);
      }),
  };
}

(async () => {
  const PORT = Number(await new Promise((resolve, reject) => {
    const p = spawn('node', ['test/port.mjs'], { cwd: process.cwd() });
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.on('close', (code) => (code === 0 ? resolve(out.trim()) : reject(new Error('port.mjs fail'))));
  }));
  const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 's2s-backfill-'));
  const TOKEN = 'a'.repeat(32);
  fs.writeFileSync(path.join(DATA, 'pairings.json'), JSON.stringify({
    'bfDevX': { peer: 'bfDevY', token: TOKEN },
    'bfDevY': { peer: 'bfDevX', token: TOKEN },
  }));

  const env = {
    ...process.env,
    PORT: String(PORT),
    S2S_DATA_FILE: path.join(DATA, 'pairings.json'),
    TURN_STATIC_AUTH_SECRET: 'backfill_secret',
    TURN_HOST: '127.0.0.1',
  };
  const srv = spawn('node', ['src/index.js'], { cwd: process.cwd(), env, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  srv.stdout.on('data', (d) => (log += d));
  srv.stderr.on('data', (d) => (log += d));

  const url = `ws://127.0.0.1:${PORT}`;
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/health`);
      if (res.ok) { ready = true; break; }
    } catch { /* 还没起来 */ }
    await sleep(100);
  }
  if (!ready) {
    console.log(log);
    srv.kill('SIGKILL');
    console.error('临时服务器未就绪');
    process.exit(1);
  }

  try {
    console.log('== Soul2Soul 存量配对回填黑盒 ==', url);
    check('预置 pairings 无 history 时服务正常启动', log.includes('[pair] loaded 2 pairing entries'));
    check('启动日志报告回填条数', log.includes('backfilled 1 history entries'));
    const X = mkClient(url, 'bfDevX', TOKEN);
    const Y = mkClient(url, 'bfDevY', TOKEN);
    const rx = await X.waitFor((m) => m.type === 'registered', 3000, 'registered@X');
    const ry = await Y.waitFor((m) => m.type === 'registered', 3000, 'registered@Y');
    check('老设备X重连即有找回资格(hasHistory)', rx.hasHistory === true);
    check('老设备Y重连即有找回资格(hasHistory)', ry.hasHistory === true);
    X.ws.close(); Y.ws.close();
    check('history.json 已落盘', fs.existsSync(path.join(DATA, 'history.json')));
  } finally {
    srv.kill('SIGKILL');
    fs.rmSync(DATA, { recursive: true, force: true });
  }
  console.log(failures === 0 ? '== ALL PASS ==' : `== ${failures} FAILURES ==`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('黑盒异常:', e); process.exit(1); });
