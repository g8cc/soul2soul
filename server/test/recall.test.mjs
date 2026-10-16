// 老搭档复配簿单测：锁定历史 TTL、召回限频、弹窗确认、互摇窗口与断线/清扫路径。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RecallBook } from '../src/recall.js';

const DAY = 24 * 3600 * 1000;

function makeBook(overrides = {}) {
  let now = 1_700_000_000_000;
  const book = new RecallBook({ now: () => now, ...overrides });
  return { book, advance: (ms) => { now += ms; } };
}

test('recordPair 后双向都有历史；30 天整仍有效，+1ms 过期', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  assert.ok(book.hasHistory('devA') && book.hasHistory('devB'));
  assert.equal(book.peerOf('devA'), 'devB');
  advance(30 * DAY);
  assert.ok(book.hasHistory('devA'), 'TTL 当刻仍有效');
  advance(1);
  assert.ok(!book.hasHistory('devA'));
  assert.equal(book.peerOf('devB'), null);
});

test('无历史设备召回被 no_history 拒；陌生 deviceId 无法借召回骚扰', () => {
  const { book } = makeBook();
  assert.deepEqual(book.requestRecall('stranger'), { out: 'failed', reason: 'no_history' });
});

test('召回限频 10s：窗口内 too_fast，恰好 10s 放行', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  assert.deepEqual(book.requestRecall('devA'), { out: 'sent', peer: 'devB' });
  advance(9999);
  assert.deepEqual(book.requestRecall('devA'), { out: 'failed', reason: 'too_fast' });
  advance(1);
  assert.deepEqual(book.requestRecall('devA'), { out: 'sent', peer: 'devB' });
});

test('answer：被召回方同意→bind(发起方在前)；拒绝→declined 带发起方', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  assert.deepEqual(book.answer('devB', false), { out: 'declined', initiator: 'devA' });
  advance(10_000); // 过召回限频窗再发起
  book.requestRecall('devA');
  assert.deepEqual(book.answer('devB', true), { out: 'bind', a: 'devA', b: 'devB' });
});

test('answer：无挂起请求时 ignored；答复即消费 pending（重复答复 ignored）', () => {
  const { book } = makeBook();
  assert.deepEqual(book.answer('devB', true), { out: 'ignored' });
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  assert.deepEqual(book.answer('devB', true), { out: 'bind', a: 'devA', b: 'devB' });
  assert.deepEqual(book.answer('devB', true), { out: 'ignored' });
});

test('answer 过期：pending 挂满 60s 后答复收到 expired', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  advance(60_001);
  assert.deepEqual(book.answer('devB', true), { out: 'failed', initiator: 'devA', reason: 'expired' });
});

test('摇一摇：对方挂着等我的召回 → 我摇即 bind', () => {
  const { book } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA'); // A 发起，等 B 确认
  assert.deepEqual(book.shake('devB'), { out: 'bind', a: 'devA', b: 'devB' });
});

test('摇一摇：双方 3s 内互摇即 bind；第 4 秒摇不算（waiting）', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
  advance(2999);
  assert.deepEqual(book.shake('devB'), { out: 'bind', a: 'devB', b: 'devA' });
  const { book: b2, advance: a2 } = makeBook();
  b2.recordPair('devA', 'devB');
  assert.equal(b2.shake('devA').out, 'waiting');
  a2(3001);
  assert.deepEqual(b2.shake('devB'), { out: 'waiting' });
});

test('摇一摇：自己重摇永远不算确认（必须对方也摇过）', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
  advance(2000);
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
  advance(2000);
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
});

test('摇一摇限频 2s：抖动连发被 ignored（不建摇动记录）', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
  advance(1999);
  assert.deepEqual(book.shake('devA'), { out: 'ignored' });
  // 被 ignored 的这次不刷新时间戳：再等 1ms（距首摇 2000ms）可再受理
  advance(1);
  assert.equal(book.shake('devA').out, 'waiting');
});

test('摇一摇：对方历史已指向别人时不撮合（防三角误绑）', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  book.recordPair('devB', 'devC'); // B 后来和 C 配过，A 的旧记录变单向
  assert.equal(book.peerOf('devA'), 'devB');
  assert.deepEqual(book.shake('devA'), { out: 'waiting' });
  advance(1000);
  assert.deepEqual(book.shake('devB'), { out: 'waiting' }); // B 的搭档是 C，不会和 A 绑
});

test('无历史设备摇动 → failed no_history', () => {
  const { book } = makeBook();
  assert.deepEqual(book.shake('stranger'), { out: 'failed', reason: 'no_history' });
});

test('settle（解除配对路径）：清挂起与摇动，但历史保留', () => {
  const { book } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  book.settle('devB');
  assert.deepEqual(book.answer('devB', true), { out: 'ignored' });
  assert.ok(book.hasHistory('devA'), '解绑不删历史——找回TA 的凭据');
});

test('onPeerGone：被等的一方掉线，返回等待方并清 pending', () => {
  const { book } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  assert.deepEqual(book.onPeerGone('devB'), ['devA']);
  assert.deepEqual(book.answer('devB', true), { out: 'ignored' });
  assert.deepEqual(book.onPeerGone('devB'), []);
});

test('sweep：pending 超 60s 被清并通知名单；历史超 30 天被删', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  book.requestRecall('devA');
  advance(60_001);
  assert.deepEqual(book.sweep(), ['devA']);
  advance(30 * DAY);
  book.sweep();
  assert.equal(book.history.size, 0);
});

test('snapshot/restore 往返：落盘重启后召回能力不丢', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  const dump = book.snapshot();
  const { book: revived } = makeBook();
  revived.restore(JSON.parse(JSON.stringify(dump)));
  assert.equal(revived.peerOf('devA'), 'devB');
  assert.deepEqual(revived.requestRecall('devA'), { out: 'sent', peer: 'devB' });
  revived.restore({ bad: 1, devX: { peer: 'y' }, devY: { ts: 1 } }); // 脏数据静默忽略
  assert.equal(revived.peerOf('devX'), null);
});

test('bind 后 recordPair 刷新双方 ts 并清挂起：老关系不会因单次召回而过期', () => {
  const { book, advance } = makeBook();
  book.recordPair('devA', 'devB');
  advance(29 * DAY);
  book.requestRecall('devA');
  book.recordPair('devA', 'devB'); // 复配成功
  advance(2 * DAY);
  assert.ok(book.hasHistory('devA'), 'ts 已刷新，仍在窗口内');
  assert.deepEqual(book.answer('devB', true), { out: 'ignored' });
});
