// TURN/STUN 限时凭证签发（纯函数，可单测；逻辑自 index.js iceServers() 逐字提取）。
// coturn 使用 reverse-proxy 静态密钥模式：username=过期 unix 秒，credential=HMAC-SHA1(secret, username)。
import crypto from 'node:crypto';

export function buildIceServers({ secret, host, port, ttlSec = 3600, now = Date.now() }) {
  const unixSec = Math.floor(now / 1000) + ttlSec; // 1 小时有效
  const username = `${unixSec}`;
  const credential = crypto.createHmac('sha1', secret)
    .update(username).digest('base64');
  return [
    { urls: [`stun:${host}:${port}`] },
    {
      urls: [
        `turn:${host}:${port}?transport=udp`,
        `turn:${host}:${port}?transport=tcp`,
      ],
      username, credential,
    },
  ];
}
