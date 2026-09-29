# Soul2Soul 服务端

两个组件：信令服务器（Node）+ coturn（STUN/TURN 中继，Docker）。

## 部署步骤

```bash
# 1) 信令
cd server
cp .env.example .env          # 改 TURN_STATIC_AUTH_SECRET 和 TURN_HOST(公网IP)
npm install
node src/index.js             # 或用 pm2: pm2 start src/index.js --name soul2soul

# 2) TURN
cd turn
cp .env.example .env          # SECRET 与上面保持一致
docker compose up -d
```

## 安全组 / 防火墙放行

| 端口 | 协议 | 用途 |
|---|---|---|
| 8080 | TCP | 信令 WebSocket（客户端 `ws://IP:8080`） |
| 3479 | UDP+TCP | STUN/TURN（3478 被服务器上已有的 derp 服务占用，故用 3479） |
| 49160-49200 | UDP | TURN 媒体中继端口段 |

> ⚠️ 腾讯云**控制台安全组**需放行以上端口（机器本身 firewalld 已关闭）。
> 截至 2026-09-28 服务器侧已部署完成，等安全组放行后公网即可用。

## 验证

- `curl http://IP:8080/health` → `ok`
- TURN 连通性（可选）: 用 https://icetest.info 或 trickle-ice 测试 `turn:IP:3478`

## 升级路径

- wss：前面挂 Nginx/Caddy 做 TLS 反代 443 → 8080，客户端 URL 改 `wss://`。
- 进程守护：pm2 或 systemd；配对数据在 `data/pairings.json`，注意备份。
