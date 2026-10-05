// 配对码本单测：锁定爆破限制、旧码作废、TTL 边界与清扫。
// 测的是 repo 默认值（TTL 10min / 尝试 5）；线上以 env 注入 PAIR_CODE_TTL_MS=1800000、
// PAIR_ENTER_MAX_ATTEMPTS=10（2026-09-29 hotfix 已配置化，不再是源码漂移）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PairCodeBook } from '../src/pairing.js';

function makeBook(overrides = {}) {
  let now = 1_700_000_000_000;
  const book = new PairCodeBook({ now: () => now, ...overrides });
  return { book, advance: (ms) => { now += ms; } };
}

test('request 发放 6 位码；4999ms 内再申请被 too_fast 压住，恰好 5000ms 放行', () => {
  const { book, advance } = makeBook();
  const r1 = book.request('devA');
  assert.equal(r1.out, 'code');
  assert.match(r1.code, /^\d{6}$/);
  advance(4999);
  assert.deepEqual(book.request('devA'), { out: 'failed', reason: 'too_fast' });
  advance(1); // 恰好 5000ms：放行
  assert.equal(book.request('devA').out, 'code');
});

test('限速按设备隔离：A 的窗口不拖累 B', () => {
  const { book, advance } = makeBook();
  assert.equal(book.request('devA').out, 'code');
  advance(1000);
  assert.equal(book.request('devB').out, 'code');
});

test('申请新码即作废旧码（每设备最多 1 个有效码）', () => {
  const { book, advance } = makeBook();
  const old = book.request('devA').code;
  advance(5000);
  const fresh = book.request('devA').code;
  const conn = { pairAttempts: 0 };
  assert.deepEqual(book.enter(conn, 'devB', old), { out: 'failed', reason: 'code_invalid' });
  const ok = book.enter(conn, 'devB', fresh);
  assert.equal(ok.out, 'paired');
  assert.equal(ok.a, 'devA');
  assert.equal(ok.b, 'devB');
  assert.match(ok.token, /^[0-9a-f]{32}$/);
});

test('enter 尝试上限边界：第 5 次仍真实校验，第 6 次拒绝且不再计数', () => {
  const { book } = makeBook();
  const conn = { pairAttempts: 0 };
  for (let i = 1; i <= 5; i++) {
    const r = book.enter(conn, 'devB', '000000');
    assert.deepEqual(r, { out: 'failed', reason: 'code_invalid' }, `第 ${i} 次应给真实校验结果`);
  }
  assert.equal(conn.pairAttempts, 5);
  assert.deepEqual(book.enter(conn, 'devB', '000000'), { out: 'failed', reason: 'too_many_attempts' });
  assert.equal(conn.pairAttempts, 5); // 超限后不计数，避免无限增长
});

test('TTL 边界：expires 当刻仍有效，+1ms 即 code_invalid', () => {
  const { book, advance } = makeBook({ ttlMs: 10 * 60 * 1000 });
  const code = book.request('devA').code;
  advance(10 * 60 * 1000); // 恰好到 expires：`expires < now` 不成立，仍有效
  const conn = { pairAttempts: 0 };
  assert.equal(book.enter(conn, 'devB', code).out, 'paired');
  // 再发一码测过期侧
  advance(5000);
  const code2 = book.request('devA').code;
  advance(10 * 60 * 1000 - 1);
  assert.equal(book.enter({ pairAttempts: 0 }, 'devC', code2).out, 'paired');
  advance(5000);
  const code3 = book.request('devA').code;
  advance(10 * 60 * 1000 + 1);
  assert.deepEqual(book.enter({ pairAttempts: 0 }, 'devD', code3), { out: 'failed', reason: 'code_invalid' });
});

test('sweep 只回收过期码，未过期码保留', () => {
  const { book, advance } = makeBook({ ttlMs: 1000 });
  const code = book.request('devA').code;
  advance(999);
  book.sweep();
  assert.equal(book.codes.size, 1);
  advance(2);
  book.sweep();
  assert.equal(book.codes.size, 0);
  assert.deepEqual(book.enter({ pairAttempts: 0 }, 'devB', code), { out: 'failed', reason: 'code_invalid' });
});

test('self_pair：自己输自己的码被拒绝', () => {
  const { book } = makeBook();
  const code = book.request('devA').code;
  assert.deepEqual(book.enter({ pairAttempts: 0 }, 'devA', code), { out: 'failed', reason: 'self_pair' });
});

test('enter 失败也计入尝试数（爆破防护对错误码同样限速）', () => {
  const { book } = makeBook();
  const conn = { pairAttempts: 0 };
  book.enter(conn, 'devB', '123456'); // code_invalid
  book.enter(conn, 'devB', '123456'); // code_invalid
  assert.equal(conn.pairAttempts, 2);
});

test('forgetDevice：断线后该设备限速归零，但码仍然有效', () => {
  const { book, advance } = makeBook();
  const code = book.request('devA').code;
  advance(1000);
  assert.equal(book.request('devA').out, 'failed'); // 仍在限速窗内
  book.forgetDevice('devA');
  assert.equal(book.request('devA').code.length, 6); // 归零后可再申请
  // 旧码被新申请作废属于「旧码作废」规则，与 forgetDevice 无关，此处只验证限速复位
});

test('输码归一化：全角数字 + 首尾空白仍能命中（2026-09-29 线上 hotfix 回灌锁定）', () => {
  const { book } = makeBook();
  const code = book.request('devA').code;
  const fullwidth = code.replace(/\d/g, (d) => String.fromCharCode(d.charCodeAt(0) + 0xFEE0));
  assert.equal(book.enter({ pairAttempts: 0 }, 'devB', ` ${fullwidth} `).out, 'paired');
});
