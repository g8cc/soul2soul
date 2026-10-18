// 日志隐私策略：哪些消息正文永不落盘、字段级兜底脱敏。
// 单独成模块是为了能被 t2 单测直接锁（index.js 一 import 就会起服务）。

// 携带长期凭证、时限凭证或用户内容的类型：只记类型，不记正文。
// 这条线不随 S2S_LOG 档位放开（minimal 是"少打"，不是"多打"）。
export const QUIET_TYPES = new Set([
  'sdp', 'ice',              // SDP 正文含候选地址
  'incoming', 'accepted',    // TURN 时限凭证 username/credential
  'msg.post', 'msg.new', 'msg.inbox', // 留言正文
  'paired',                  // 长期重连凭证 token
  'pair.code',               // 六位配对码（知道即可抢配）
]);

// 兜底：万一以后新增消息类型忘了进 QUIET_TYPES，正文里的敏感字段名仍会被替换。
const SECRET_FIELD_RE = /^(token|code|credential|username|password|secret)$/i;

export function redactSecrets(value) {
  if (Array.isArray(value)) return value.map(redactSecrets);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = SECRET_FIELD_RE.test(k) ? `<redacted:${String(v).length}>` : redactSecrets(v);
    }
    return out;
  }
  return value;
}
