// 日志隐私策略单测：0.2.55 生产日志把长期重连凭证和六位配对码原样落盘，
// 这份名单+字段兜底是唯一的线。回滚任何一条，用例必须变红。
import test from 'node:test';
import assert from 'node:assert/strict';
import { QUIET_TYPES, redactSecrets } from '../src/logpolicy.js';

test('长期凭证与配对码类型只记类型、不记正文', () => {
  for (const t of ['paired', 'pair.code', 'sdp', 'ice', 'incoming', 'accepted', 'msg.post']) {
    assert.ok(QUIET_TYPES.has(t), `${t} 必须在 QUIET_TYPES 里`);
  }
});

test('token 与 code 即使漏出名单也会被字段兜底替换', () => {
  const out = redactSecrets({
    type: 'paired',
    token: '54ca03335acc619d157ab8175fcd3124',
    peerOnline: true,
  });
  assert.equal(out.token, '<redacted:32>');
  assert.equal(out.peerOnline, true);
  assert.ok(!JSON.stringify(out).includes('54ca03335acc619d157ab8175fcd3124'));
});

test('嵌套与数组里的敏感字段同样被替换（iceServers 里的 username/credential）', () => {
  const out = redactSecrets({
    iceServers: [{ urls: ['stun:1.2.3.4:3479'], username: '1700003600', credential: 'hm...' }],
    sdp: { type: 'offer', sdp: 'v=0...' },
  });
  assert.equal(out.iceServers[0].username, '<redacted:10>');
  assert.equal(out.iceServers[0].credential, '<redacted:5>');
  assert.equal(out.sdp.sdp, 'v=0...');
});

test('字段名匹配是精确的，业务字段不许被误伤', () => {
  const out = redactSecrets({ codec: 'VP9', encoded: 'x', tokensUsed: 3, code: '774233' });
  assert.equal(out.codec, 'VP9');
  assert.equal(out.encoded, 'x');
  assert.equal(out.tokensUsed, 3);
  assert.equal(out.code, '<redacted:6>');
});

test('非对象输入原样返回（日志兜底不许改数据类型）', () => {
  assert.equal(redactSecrets('abc'), 'abc');
  assert.equal(redactSecrets(7), 7);
  assert.equal(redactSecrets(null), null);
});
