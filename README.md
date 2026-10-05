# Soul2Soul · 陪着你

异地恋人的「陪伴按钮」：一键召唤对方，把你的手机屏幕实时共享给 TA——TA 能看着你的画面说话，
还能用手指在你的屏幕上画圈圈（"先消这块冰！"），像坐在你旁边一起玩手机。

> 状态：v0.1（M0+M1 已实现：配对 / 呼叫接听 / 屏幕实时共享 / 双向语音 / 画笔标注 / 看门狗容错）

## 仓库结构

```
docs/         PRD 需求 · SPEC 技术规格 · DESIGN 架构 · OPEN-QUESTIONS 待确认清单
server/       信令服务器 (Node.js) + coturn TURN 中继 (docker) + 冒烟测试
app/          Android 客户端 (Kotlin + libwebrtc, minSdk 29)
scripts/      双模拟器 E2E 自动化 (e2e.sh + ui.py)
```

## 快速开始

### 1. 服务端（已上线：soul.lumi666.cloud）

- 信令：`wss://soul.lumi666.cloud`（nginx 反代 443 → 127.0.0.1:8080，Let's Encrypt 证书自动续期）
- TURN：`49.232.173.115:3479`（UDP+TCP，HMAC 时限凭证），媒体中继端口 49160-49200/UDP
- 部署细节见 `server/README.md`；nginx 配置在服务器 `/etc/nginx/conf.d/soul2soul.conf`

信令冒烟测试：`SIG_URL=wss://soul.lumi666.cloud node smoke-test.cjs`（11 项全过 ✅）

### 2. Android 端

- 直接安装 `dist/Soul2Soul-v0.1-prod.apk`（已指向 wss 正式入口）；改地址用 Android Studio 打开 `app/` 改 `SIGNALING_URL`
- 两台手机都装上：A 生成配对码 → 微信发给 B → B 输入 → 绑定完成
- A 点「一起玩」→ B 接听 → A 授权屏幕录制 → A 切到游戏开玩，B 全屏观看 + 画笔 + 语音

国产 ROM 必做（否则收不到呼叫）：小米开「自启动 + 省电无限制 + 后台弹出界面」；
华为「应用启动管理 → 手动管理 → 全开」。App 内有引导提示。

### 3. 模拟器联调（无需真机）

```bash
# 本地起信令和 TURN
cd server && npm i
TURN_STATIC_AUTH_SECRET=test TURN_HOST=10.0.2.2 TURN_PORT=3479 node src/index.js &
docker run -d --name s2s-coturn -p 3479:3479/udp -p 3479:3479/tcp -p 49160-49200:49160-49200/udp \
  coturn/coturn:latest -n --realm=s2s --fingerprint --use-auth-secret \
  --static-auth-secret=test --no-cli --listening-port=3479 --min-port=49160 --max-port=49200 --external-ip=10.0.2.2

# 编译模拟器包并跑 E2E（两台 AVD: s2s1/s2s2）
cd app && ../gradlew assembleDebug -PS2S_SIGNALING_URL=ws://10.0.2.2:8080
../scripts/e2e.sh
```

## 开发快速上手

```bash
# 1. 跑测试（分层 harness：静态/JVM单测/Server单测/协议黑盒，无需设备，约1分钟）
bash scripts/test.sh

# 2. 构建调试包（app/ 无 gradlew，用系统 Gradle + JDK17）
export JAVA_HOME=/Users/wardonguo/tools/jdk17/jdk-17.0.2.jdk/Contents/Home
~/tools/gradle-8.7/bin/gradle -p app assembleProdDebug
adb install -r app/app/build/outputs/apk/prod/debug/app-prod-debug.apk

# 3. 发版（构建 release → 上传自托管 APK 通道 → App 内自动更新生效）
./scripts/publish-release.sh 0.2.23 23
```

- 工程约定/服务器漂移警告见根目录 `AGENTS.md`；测试怎么写见 `docs/TESTING.md`；线上协议以 `docs/PROTOCOL.md` 为准。

## 当前已知限制

- 云服务器安全组未放行前，公网不可用（本地模拟器联调不受影响）
- 屏幕录制授权每次会话弹一次（Android 14+ 系统限制，无法豁免）
- 观看端 PiP 小窗、持久文字标注、游戏声音共享在 v2（见 PRD P1/P2）
