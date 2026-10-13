# 弱网与语音质量验证

本文用于在两台真机上建立可复现的连接/语音基线。WebRTC 指标只写入各设备本地 logcat，不上传服务端，也不包含会话音频或屏幕内容。当前待执行项与每轮验证记录见 [VALIDATION-TODO.md](VALIDATION-TODO.md)。

## 采集日志

两台手机都安装同一版本并各自采集日志；通话中不要清空 logcat，以免丢掉断线前的上下文：

```bash
adb -s <设备序列号> logcat -v time -s WebRtcClient:I WebRtcClient:W
```

重点标记：

| 标记 | 含义 |
|---|---|
| `ICE_ESTABLISHED setupMs=` | 从媒体客户端启动到首次 ICE 连通的时间；不是呼叫响铃时间 |
| `ICE_OUTAGE_STARTED` | ICE 进入短暂断开/失败状态 |
| `ICE_RESTART attempt=` | 共享端发起一次 ICE restart |
| `ICE_RECOVERED count= outageMs= restarts=` | ICE 恢复次数、单次中断时长及该次尝试次数 |
| `ICE_RESTART_BUDGET_RESET` | 连续稳定 30 秒后恢复本次会话的重启额度 |
| `ICE_RECOVERY_TIMEOUT` | 超过恢复窗口仍未连通，随后会结束会话 |
| `S2S_QOS` | 每约 10 秒的网络与媒体质量采样 |

`S2S_QOS` 字段：

- `rttMs`：所选 ICE 候选对的往返时间，不是单向语音延迟。
- `path`：候选类型组合（如 `host->srflx`、`relay->relay`）；`unknown` 表示设备/系统没有提供候选明细。
- `availableBps`：所选路径估计的可用上行带宽；`-1` 表示 WebRTC 未提供。
- `audioLossPct`：本统计窗口内的音频 RTP 丢包率。
- `audioJitterMs`：当前采样的接收抖动缓冲输入指标，不是本窗口平均值。
- `audioConcealedMs`：窗口内解码器用隐藏/补偿音频填补的累计时长；这是“短杂音/断续感”的代理指标，不等价于主观听感。
- `videoLossPct` / `videoJitterMs` / `videoFps`：视频窗口丢包、抖动与帧率。`edge` 是当前采集长边。
- `na`：缺少有效的前后计数样本，不能当作 0 丢包或 0 杂音。

第一条 `S2S_QOS` 常会出现 `na`，因为累计计数需要前后两个样本才能计算窗口值；重点看后续连续采样。共享端与观看端的日志都要保留，才能区分发送侧、接收侧和所选中继路径。

## 建议测试矩阵

每项至少做 3 次，每次保持通话 2–3 分钟。记录机型/Android 版本、Wi-Fi 或蜂窝网络、是否 TURN 中继、首次连通时间、恢复耗时、两端音频指标，以及是否主观听到爆音/杂音。

| 场景 | 操作 | 观察点 |
|---|---|---|
| 稳定基线 | 两端接入稳定 Wi-Fi，正常通话 3 分钟 | 首次连通、路径、RTT、窗口丢包/隐藏音频时长 |
| 短暂断网 | 通话中关闭一端 Wi-Fi 5 秒再打开 | 是否记录 `ICE_OUTAGE_STARTED`，是否在超时前 `ICE_RECOVERED`，恢复期间语音中断多久 |
| 网络切换 | 一端从 Wi-Fi 切换到蜂窝网络 | 新路径是否恢复，候选路径是否从直连变化到中继，是否需要重拨 |
| 持续弱网 | 使用可控网络限速/丢包环境，逐步增加限制 | 音频隐藏时长与视频帧率是否恶化；共享画质是否先于语音可懂度降级 |
| 多次波动 | 先制造数次短暂断网，每次恢复后保持稳定至少 30 秒，再重复 | `ICE_RESTART_BUDGET_RESET` 是否出现；后续故障是否仍能重新尝试恢复 |

目前没有把“主观杂音”自动判定为故障的可靠设备无关指标；若听到杂音，请记录时间点，并对齐该时刻前后的 `audioLossPct`、`audioJitterMs`、`audioConcealedMs`、`path` 和 AudioRecord/AudioTrack 错误日志。短促噪声但上述网络指标正常时，更可能需要检查设备声学处理、音频路由切换或厂商 Audio HAL，而不能通过盲目提高码率解决。

这组采样用于比较同一设备/网络在修改前后的变化，不应把单次测试或 logcat 指标直接宣传成服务等级承诺。真机测试仍按 [TESTING.md](TESTING.md) 的 T4 门控执行。
