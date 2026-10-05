// 配对码本：pair.request 限速 / 旧码作废 / enter 尝试上限 / TTL / 过期清扫。
// 纯逻辑自 index.js 提取（时钟注入，node:test 可单测）；消息字面与日志模板由调用方保持原样。
import crypto from 'node:crypto';

export class PairCodeBook {
  constructor({
    now = () => Date.now(),
    ttlMs = 10 * 60 * 1000,
    requestMinIntervalMs = 5000,
    maxEnterAttempts = 5,
  } = {}) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.requestMinIntervalMs = requestMinIntervalMs;
    this.maxEnterAttempts = maxEnterAttempts;
    this.codes = new Map();          // code -> { deviceId, expires }
    this.lastRequestAt = new Map();  // deviceId -> 上次 pair.request 时间戳
  }

  /** 申请新码。返回 {out:'code', code} 或 {out:'failed', reason:'too_fast'} */
  request(deviceId) {
    const now = this.now();
    if (now - (this.lastRequestAt.get(deviceId) || 0) < this.requestMinIntervalMs) {
      return { out: 'failed', reason: 'too_fast' };
    }
    this.lastRequestAt.set(deviceId, now);
    // 每设备最多 1 个有效码：申请新码即撤销旧码（码表规模有上界）
    for (const [c, e] of this.codes) {
      if (e.deviceId === deviceId) this.codes.delete(c);
    }
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    this.codes.set(code, { deviceId, expires: now + this.ttlMs });
    return { out: 'code', code };
  }

  /**
   * 用码配对。conn 是承载 pairAttempts 的连接对象（每连接限次，重连重新计）。
   * 返回 {out:'failed', reason} 或 {out:'paired', a:码主, b:输入者, token}
   */
  enter(conn, deviceId, rawCode) {
    if (conn.pairAttempts >= this.maxEnterAttempts) {
      return { out: 'failed', reason: 'too_many_attempts' };
    }
    conn.pairAttempts += 1;
    // 规范化：去空白 + 全角数字转半角（中文输入法数字键盘会产出全角字符）——线上 2026-09-29 hotfix 回灌
    const code = String(rawCode || '')
      .replace(/\s+/g, '')
      .replace(/[０-９]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0));
    const pending = this.codes.get(code);
    if (!pending || pending.expires < this.now()) {
      return { out: 'failed', reason: 'code_invalid' };
    }
    if (pending.deviceId === deviceId) {
      return { out: 'failed', reason: 'self_pair' };
    }
    const a = pending.deviceId, b = deviceId;
    const token = crypto.randomBytes(16).toString('hex');
    this.codes.delete(code);
    return { out: 'paired', a, b, token };
  }

  /** 连接断开：该设备的申请限速记录一并作废（重连视为新设备节奏） */
  forgetDevice(deviceId) {
    this.lastRequestAt.delete(deviceId);
  }

  /** 清扫过期配对码：只在 enter 时判断过期的话，从不再使用的码会永久滞留（慢性内存泄漏） */
  sweep() {
    const now = this.now();
    for (const [code, entry] of this.codes) {
      if (entry.expires < now) this.codes.delete(code);
    }
  }
}
