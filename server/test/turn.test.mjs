// TURN/STUN 限时凭证单测：固定 secret+now 的离线期望向量 + urls 结构
import test from 'node:test';
import assert from 'node:assert/strict';
import { buildIceServers } from '../src/turn.js';

const SECRET = 'unit_test_secret';
// now = 2023-11-14T22:13:20Z（毫秒）；ttl 3600s → username 应为 1700003600
const NOW_MS = 1700000000000;

test('固定向量的 username/credential 与离线预算一致', () => {
  const servers = buildIceServers({ secret: SECRET, host: '10.0.0.1', port: '3478', now: NOW_MS });
  const turn = servers[1];
  assert.equal(turn.username, '1700003600');
  // HMAC-SHA1("unit_test_secret", "1700003600") base64，openssl 独立复核
  assert.equal(turn.credential, 'ZNctF5viIWZVEE/ByR8Z6EDQnTY=');
});

test('默认 TTL 为 3600 秒', () => {
  const a = buildIceServers({ secret: SECRET, host: 'h', port: '3478', now: NOW_MS });
  const b = buildIceServers({ secret: SECRET, host: 'h', port: '3478', now: NOW_MS, ttlSec: 3600 });
  assert.equal(a[1].username, b[1].username);
  assert.equal(Number(a[1].username), Math.floor(NOW_MS / 1000) + 3600);
});

test('urls 结构：stun 一条 + turn udp/tcp 两条', () => {
  const servers = buildIceServers({ secret: SECRET, host: '1.2.3.4', port: '3478', now: NOW_MS });
  assert.equal(servers.length, 2);
  assert.deepEqual(servers[0].urls, ['stun:1.2.3.4:3478']);
  assert.deepEqual(servers[1].urls, [
    'turn:1.2.3.4:3478?transport=udp',
    'turn:1.2.3.4:3478?transport=tcp',
  ]);
  assert.ok(!('username' in servers[0]), 'stun 条目不应带凭证');
});

test('不同 secret 产生不同 credential；username 相同', () => {
  const a = buildIceServers({ secret: 'aaa', host: 'h', port: '3478', now: NOW_MS });
  const b = buildIceServers({ secret: 'bbb', host: 'h', port: '3478', now: NOW_MS });
  assert.notEqual(a[1].credential, b[1].credential);
  assert.equal(a[1].username, b[1].username);
});

test('毫秒向下取整：同一秒内 now 微差不改变凭证', () => {
  const a = buildIceServers({ secret: SECRET, host: 'h', port: '3478', now: 1700000000000 });
  const b = buildIceServers({ secret: SECRET, host: 'h', port: '3478', now: 1700000000999 });
  assert.deepEqual(a, b);
});
