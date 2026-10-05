// 离线留言本：文字/语音留言的暂存、未读下发、阅后即删、限速、容量上限、TTL 过期清扫。
// 纯逻辑（时钟注入，node:test 可单测）；文件落盘与在线判定由调用方（index.js）负责。
// 隐私原则：留言只短存（TTL 到期或被收件人 ack 即删），解除配对时双向清空。
import crypto from 'node:crypto';

export class MessageBook {
  constructor({
    now = () => Date.now(),
    ttlMs = 7 * 24 * 60 * 60 * 1000,   // 7 天自动销毁
    maxPerDevice = 20,                  // 每个收件人最多暂存条数（超出丢最旧）
    minIntervalMs = 1000,               // 每个发件人限速 1 条/秒（防刷屏）
    maxTextChars = 500,
  } = {}) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.maxPerDevice = maxPerDevice;
    this.minIntervalMs = minIntervalMs;
    this.maxTextChars = maxTextChars;
    this.inbox = new Map();   // toDeviceId -> [item]
    this.lastPostAt = new Map(); // fromDeviceId -> 上次成功留言时间戳
  }

  /**
   * 存入一条留言。调用方负责先做配对/鉴权校验。
   * msg: {kind:'text', text} | {kind:'voice', voiceId, durMs}
   * 返回 {out:'ok', item} 或 {out:'rejected', reason}
   */
  post(from, to, msg) {
    if (!to || to === from) return { out: 'rejected', reason: 'bad_recipient' };
    const now = this.now();
    const last = this.lastPostAt.get(from) || 0;
    if (now - last < this.minIntervalMs) {
      return { out: 'rejected', reason: 'too_fast' };
    }
    let item;
    if (msg.kind === 'text') {
      const text = typeof msg.text === 'string' ? msg.text.trim() : '';
      if (!text) return { out: 'rejected', reason: 'empty' };
      if (text.length > this.maxTextChars) return { out: 'rejected', reason: 'too_long' };
      item = { id: crypto.randomUUID(), from, kind: 'text', text, ts: now, expires: now + this.ttlMs };
    } else if (msg.kind === 'voice') {
      const voiceId = String(msg.voiceId || '');
      if (!/^[0-9a-f-]{36}$/i.test(voiceId)) return { out: 'rejected', reason: 'bad_voice_id' };
      const durMs = Number(msg.durMs) || 0;
      if (durMs <= 0 || durMs > 60_000) return { out: 'rejected', reason: 'bad_duration' };
      item = { id: crypto.randomUUID(), from, kind: 'voice', voiceId, durMs, ts: now, expires: now + this.ttlMs };
    } else {
      return { out: 'rejected', reason: 'bad_kind' };
    }

    const list = this.inbox.get(to) || [];
    list.push(item);
    // 容量上限：溢出丢最旧（连同语音文件由调用方删除）
    const dropped = [];
    while (list.length > this.maxPerDevice) dropped.push(list.shift());
    this.inbox.set(to, list);
    this.lastPostAt.set(from, now);
    return { out: 'ok', item, dropped };
  }

  /** 收件人上线：按时间序返回未读留言（只读，不删除——ack 才删，掉线不丢） */
  list(deviceId) {
    const now = this.now();
    const list = (this.inbox.get(deviceId) || []).filter((m) => m.expires >= now);
    this.inbox.set(deviceId, list);
    return list.map((m) => ({ ...m }));
  }

  /** 收件人已看到/听过：删除这些 id 的留言，返回被删条目（调用方删语音文件）。只允许删自己收件箱里的。 */
  ack(deviceId, ids) {
    const want = new Set((Array.isArray(ids) ? ids : []).map(String));
    const list = this.inbox.get(deviceId) || [];
    const kept = [];
    const removed = [];
    for (const m of list) {
      if (want.has(m.id)) removed.push({ ...m });
      else kept.push(m);
    }
    this.inbox.set(deviceId, kept);
    return removed;
  }

  /** 解除配对/设备注销：双向清空（含发出记录限速），返回被删条目供清理语音文件 */
  forgetDevice(deviceId) {
    const removed = [];
    for (const [to, list] of this.inbox) {
      const kept = list.filter((m) => {
        if (m.from === deviceId || to === deviceId) { removed.push({ ...m }); return false; }
        return true;
      });
      if (kept.length) this.inbox.set(to, kept); else this.inbox.delete(to);
    }
    this.lastPostAt.delete(deviceId);
    return removed;
  }

  /** 从磁盘恢复一条已存留言（不做限速校验；形状由调用方粗筛） */
  restore(to, item) {
    if (!item || typeof item.id !== 'string' || typeof item.from !== 'string') return;
    const list = this.inbox.get(to) || [];
    list.push(item);
    this.inbox.set(to, list);
  }

  totalCount() {
    let n = 0;
    for (const list of this.inbox.values()) n += list.length;
    return n;
  }

  /** 收件人是否有一条未读语音指向 voiceId（下载鉴权用：防止凭 id 拉别人的语音文件） */
  hasVoice(deviceId, voiceId) {
    const now = this.now();
    return (this.inbox.get(deviceId) || []).some(
      (m) => m.kind === 'voice' && m.voiceId === voiceId && m.expires >= now,
    );
  }

  /** 过期清扫：TTL 到期的留言删除并返回（调用方清理语音文件），防慢性堆积 */
  sweep() {
    const now = this.now();
    const removed = [];
    for (const [to, list] of this.inbox) {
      const kept = list.filter((m) => {
        if (m.expires < now) { removed.push({ ...m }); return false; }
        return true;
      });
      if (kept.length) this.inbox.set(to, kept); else this.inbox.delete(to);
    }
    return removed;
  }
}
