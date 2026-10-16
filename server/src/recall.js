// 老搭档复配簿：历史配对关系（找回TA）+ 摇一摇撮合。
// 纯逻辑（时钟注入、返回决策对象），消息收发与落盘由 index.js 负责，node:test 单测覆盖。
//
// 安全模型：deviceId 不是秘密，所以复配必须满足「有历史关系 + 对方人工确认（弹窗或 ±3s 内互摇）」
// 才能绑定；发起限频防骚扰，pending 短 TTL 防挂起。

export class RecallBook {
  constructor({
    now = () => Date.now(),
    historyTtlMs = 30 * 24 * 3600 * 1000,   // 历史关系记 30 天
    requestMinIntervalMs = 10000,           // 同一设备 10s 内只能发起一次召回
    pendingTtlMs = 60 * 1000,               // 召回请求 60s 无人答复即过期
    shakeWindowMs = 3000,                   // 互摇撮合窗口：双方摇动间隔 ≤3s 算同时
    shakeMinIntervalMs = 2000,              // 摇一摇消息限频（防传感器抖动刷屏）
  } = {}) {
    this.now = now;
    this.historyTtlMs = historyTtlMs;
    this.requestMinIntervalMs = requestMinIntervalMs;
    this.pendingTtlMs = pendingTtlMs;
    this.shakeWindowMs = shakeWindowMs;
    this.shakeMinIntervalMs = shakeMinIntervalMs;
    this.history = new Map();   // deviceId -> { peer, ts }（双向各一条）
    this.pending = new Map();   // initiator -> { to, ts }
    this.lastRequestAt = new Map();
    this.shakes = new Map();    // deviceId -> 上次摇动时间
  }

  /** 落盘形态（index.js 写 history.json 用） */
  snapshot() {
    return Object.fromEntries(this.history);
  }

  restore(entries) {
    for (const [dev, e] of Object.entries(entries || {})) {
      if (e && typeof e.peer === 'string' && Number.isFinite(e.ts)) {
        this.history.set(dev, { peer: e.peer, ts: e.ts });
      }
    }
  }

  /** 正常配对成功 / 复配成功都刷历史记录：关系"最近被确认过" */
  recordPair(a, b) {
    const now = this.now();
    this.history.set(a, { peer: b, ts: now });
    this.history.set(b, { peer: a, ts: now });
    this.pending.delete(a);
    this.pending.delete(b);
    this.shakes.delete(a);
    this.shakes.delete(b);
  }

  hasHistory(deviceId) {
    const h = this.history.get(deviceId);
    return !!h && this.now() - h.ts <= this.historyTtlMs;
  }

  /** 有效历史里的对端（无/过期返回 null） */
  peerOf(deviceId) {
    const h = this.history.get(deviceId);
    if (!h || this.now() - h.ts > this.historyTtlMs) return null;
    return h.peer;
  }

  /** pair.recall：发起召回。返回 {out:'sent', peer} 或 {out:'failed', reason} */
  requestRecall(deviceId) {
    const now = this.now();
    const peer = this.peerOf(deviceId);
    if (!peer) return { out: 'failed', reason: 'no_history' };
    if (now - (this.lastRequestAt.get(deviceId) || 0) < this.requestMinIntervalMs) {
      return { out: 'failed', reason: 'too_fast' };
    }
    this.lastRequestAt.set(deviceId, now);
    this.pending.set(deviceId, { to: peer, ts: now });
    return { out: 'sent', peer };
  }

  _freshPending(initiator) {
    const p = this.pending.get(initiator);
    if (!p) return null;
    if (this.now() - p.ts > this.pendingTtlMs) {
      this.pending.delete(initiator);
      return null;
    }
    return p;
  }

  /** pair.recall_ok：被召回方答复。返回 {out:'bind',a,b} | {out:'declined',initiator} | {out:'ignored'} */
  answer(deviceId, accept) {
    for (const [initiator, p] of this.pending) {
      if (p.to !== deviceId) continue;
      this.pending.delete(initiator);
      if (this.now() - p.ts > this.pendingTtlMs) {
        return { out: 'failed', initiator, reason: 'expired' };
      }
      if (!accept) return { out: 'declined', initiator };
      return { out: 'bind', a: initiator, b: deviceId };
    }
    return { out: 'ignored' };
  }

  /**
   * pair.shake：主页摇一摇。两种撮合路径：
   *  1) 对方正挂着一个等我的召回 → 摇即确认 → bind
   *  2) 双方 3s 内都摇过（互为历史搭档）→ 千里同步 → bind
   * 否则记一次摇动等对方，返回 {out:'waiting'}。
   */
  shake(deviceId) {
    const now = this.now();
    if (now - (this.shakes.get(deviceId) || 0) < this.shakeMinIntervalMs) {
      return { out: 'ignored' };
    }
    this.shakes.set(deviceId, now);
    const peer = this.peerOf(deviceId);
    if (!peer) return { out: 'failed', reason: 'no_history' };
    const mutual = this.history.get(peer)?.peer === deviceId;
    if (!mutual) return { out: 'waiting' }; // 对方历史已指向别人：别拿旧关系撮合

    const peerPending = this._freshPending(peer);
    if (peerPending && peerPending.to === deviceId) {
      this.pending.delete(peer);
      return { out: 'bind', a: peer, b: deviceId };
    }
    // 互摇窗口：对方 3s 内也摇过才算双向意愿（自己重摇永远不算确认）
    if (now - (this.shakes.get(peer) || 0) <= this.shakeWindowMs) {
      this.pending.delete(deviceId);
      return { out: 'bind', a: deviceId, b: peer };
    }
    return { out: 'waiting' };
  }

  /** 解除配对/绑定完成：清掉相关挂起与摇动记录 */
  settle(deviceId) {
    this.pending.delete(deviceId);
    this.shakes.delete(deviceId);
    for (const [init, p] of this.pending) {
      if (p.to === deviceId) this.pending.delete(init);
    }
  }

  /** 连接断开：等它的召回请求即刻判死，返回需要通知的 initiator 列表 */
  onPeerGone(deviceId) {
    const gone = [];
    for (const [init, p] of this.pending) {
      if (p.to === deviceId) {
        this.pending.delete(init);
        gone.push(init);
      }
    }
    return gone;
  }

  /** 清扫：过期 pending 返回给调用方发 expired 通知；shake 记录过期即删。历史由 TTL 兜底 */
  sweep() {
    const now = this.now();
    const expired = [];
    for (const [init, p] of this.pending) {
      if (now - p.ts > this.pendingTtlMs) {
        this.pending.delete(init);
        expired.push(init);
      }
    }
    for (const [dev, ts] of this.shakes) {
      if (now - ts > this.shakeWindowMs * 10) this.shakes.delete(dev);
    }
    for (const [dev, h] of this.history) {
      if (now - h.ts > this.historyTtlMs) this.history.delete(dev);
    }
    return expired;
  }
}
