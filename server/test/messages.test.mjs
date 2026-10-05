// 留言本单测：锁定限速、TTL±1ms 边界、容量溢出丢最旧、ack 只删自己收件箱、
// forgetDevice 双向清空、sweep 返回被删条目（供语音文件清理）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MessageBook } from '../src/messages.js';

const VID = '0f8fad5b-d9cb-469f-a165-70867728950e';

function makeBook(overrides = {}) {
  let now = 1_700_000_000_000;
  const book = new MessageBook({ now: () => now, ...overrides });
  return { book, advance: (ms) => { now += ms; } };
}

test('text 留言存入成功；恰好 minInterval 后放行，差 1ms 被 too_fast', () => {
  const { book, advance } = makeBook();
  const r1 = book.post('A', 'B', { kind: 'text', text: '想你了' });
  assert.equal(r1.out, 'ok');
  assert.equal(r1.item.text, '想你了');
  assert.match(r1.item.id, /^[0-9a-f-]{36}$/);
  advance(999);
  assert.deepEqual(book.post('A', 'B', { kind: 'text', text: 'x' }), { out: 'rejected', reason: 'too_fast' });
  advance(1);
  assert.equal(book.post('A', 'B', { kind: 'text', text: 'x' }).out, 'ok');
});

test('限速按发件人隔离：A 的窗口不拖累 B', () => {
  const { book } = makeBook();
  assert.equal(book.post('A', 'B', { kind: 'text', text: '1' }).out, 'ok');
  assert.equal(book.post('B', 'A', { kind: 'text', text: '1' }).out, 'ok');
});

test('文本校验：空/纯空白拒绝，500 字符放行，501 拒绝', () => {
  const { book } = makeBook();
  assert.deepEqual(book.post('A', 'B', { kind: 'text', text: '   ' }), { out: 'rejected', reason: 'empty' });
  assert.equal(book.post('A', 'B', { kind: 'text', text: '啊'.repeat(500) }).out, 'ok');
  const { book: b2 } = makeBook();
  assert.deepEqual(b2.post('A', 'B', { kind: 'text', text: '啊'.repeat(501) }), { out: 'rejected', reason: 'too_long' });
});

test('voice 校验：非法 id / 时长越界(0, 60001ms)拒绝，30s 放行', () => {
  const { book } = makeBook();
  assert.deepEqual(book.post('A', 'B', { kind: 'voice', voiceId: 'x', durMs: 3000 }), { out: 'rejected', reason: 'bad_voice_id' });
  assert.deepEqual(book.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 0 }), { out: 'rejected', reason: 'bad_duration' });
  const { book: b2 } = makeBook();
  assert.deepEqual(b2.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 60_001 }), { out: 'rejected', reason: 'bad_duration' });
  const { book: b3 } = makeBook();
  assert.equal(b3.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 30_000 }).out, 'ok');
});

test('自发自收与空收件人都被拒', () => {
  const { book } = makeBook();
  assert.deepEqual(book.post('A', 'A', { kind: 'text', text: 'x' }), { out: 'rejected', reason: 'bad_recipient' });
  assert.deepEqual(book.post('A', '', { kind: 'text', text: 'x' }), { out: 'rejected', reason: 'bad_recipient' });
});

test('list 按时间序返回且是副本；到期条目不出现在 list 里', () => {
  const { book, advance } = makeBook({ ttlMs: 1000 });
  const r1 = book.post('A', 'B', { kind: 'text', text: '一' });
  advance(1000); // 恰好第 2 条在 r1 过期后 1ms 落入过期区
  const r2 = book.post('A', 'B', { kind: 'text', text: '二' });
  const items = book.list('B');
  assert.equal(items.length, 2);
  assert.equal(items[0].id, r1.item.id);
  items[0].text = '篡改';
  assert.equal(book.list('B')[0].text, '一'); // 返回副本，外部改不动
  advance(1); // r1.expires = t0+1000；now=t0+1001 → r1 过期
  assert.deepEqual(book.list('B').map((m) => m.text), ['二']);
  void r2;
});

test('ack 只删自己收件箱里的 id，且返回被删条目', () => {
  const { book } = makeBook();
  const r = book.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 5000 });
  book.post('B', 'A', { kind: 'text', text: '回' });
  const removed = book.ack('B', [r.item.id, '不存在的id']);
  assert.equal(removed.length, 1);
  assert.equal(removed[0].voiceId, VID);
  assert.deepEqual(book.list('B'), []);
  assert.equal(book.list('A').length, 1); // A 收件箱不受影响
});

test('容量上限：第 21 条挤掉最旧一条并把它返回（含语音文件线索）', () => {
  const { book, advance } = makeBook({ maxPerDevice: 3 });
  const ids = [];
  for (let i = 0; i < 3; i++) {
    ids.push(book.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 1000 }).item.id);
    advance(1000);
  }
  const r = book.post('A', 'B', { kind: 'text', text: '溢出' });
  assert.equal(r.out, 'ok');
  assert.equal(r.dropped.length, 1);
  assert.equal(r.dropped[0].id, ids[0]);
  assert.deepEqual(book.list('B').map((m) => m.id), [ids[1], ids[2], r.item.id]);
});

test('forgetDevice 双向清空：我发的、发给我的、限速记录全没', () => {
  const { book } = makeBook();
  book.post('A', 'B', { kind: 'text', text: '1' });
  book.post('B', 'A', { kind: 'text', text: '2' });
  book.post('C', 'D', { kind: 'text', text: '无关' });
  const removed = book.forgetDevice('A');
  assert.equal(removed.length, 2);
  assert.deepEqual(book.list('B'), []);
  assert.deepEqual(book.list('A'), []);
  assert.equal(book.list('D').length, 1); // 无关设备不受牵连
  // 限速记录一并清掉：A 立刻可以再发
  assert.equal(book.post('A', 'B', { kind: 'text', text: '3' }).out, 'ok');
});

test('sweep 删除过期条目并返回（TTL 边界：恰好到期不删，过 1ms 删）', () => {
  const { book, advance } = makeBook({ ttlMs: 5000 });
  book.post('A', 'B', { kind: 'voice', voiceId: VID, durMs: 2000 });
  advance(5000);
  assert.deepEqual(book.sweep(), []); // 恰好到期：expires < now 不成立，保留
  advance(1);
  const removed = book.sweep();
  assert.equal(removed.length, 1);
  assert.equal(removed[0].voiceId, VID);
  assert.deepEqual(book.list('B'), []);
});
