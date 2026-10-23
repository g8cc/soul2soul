# SIGNING.md — release 签名：口令在哪、怎么换、怎么验收

从 `AGENTS.md` 「构建」节搬出（10-23 瘦身）。**只有要动 keystore / 口令 / 发版签名时才需要读**。
日常 `assembleProdDebug` 不碰签名，不需要这份文件。

- **签名口令不入库**：`app/app/build.gradle.kts` 只读 `app/keystore.properties`（`.gitignore` 覆盖；模板 `app/keystore.properties.example`）。缺该文件时 release 不配置签名，
  `packageProdRelease` 直接报 `SigningConfig "release" is missing required property "storeFile"`（10-19 实测 rc=1，不会悄悄产出未签名包）。
  **泄露已于 10-19 闭合：口令轮换过（`keytool -storepasswd`）**，旧值只剩在 git 历史里（`git log --all -S` 那 3 个提交）且已无任何文件能用它打开（实测 `keytool -list` rc=1 `password was incorrect`）。
  换的时候三个必须知道的点：① **`app/keystore/*.jks` 实际是 PKCS12**（`file` 报 data、头 4 字节是 DER SEQUENCE `30 82 …` 而不是 JKS 魔数 `feedfeed`；扩展名骗人），
  PKCS12 **只有一个口令** ⇒ `keytool -keypasswd` 直接 `UnsupportedOperationException`，正确做法是 `storePassword`/`keyPassword` 写成轮换后的**同一个值**；
  ② `keytool` 是系统 stub，必须先 `export JAVA_HOME=/Users/wardonguo/tools/jdk17/…`，否则报 `Unable to locate a Java Runtime`——它**确实返回 rc=1**（实测），失败信号一直在，是我用 `keytool … | grep` 加 `${PIPESTATUS[0]}` 把它丢了（`PIPESTATUS` 是 bash 专有，Agent 侧 `/bin/zsh` 里展开为空，于是链尾的写文件照样执行）：**结果是"keystore 仍旧口令、配置文件已改成新口令"的半执行状态**，口令没换而构建配置坏了，见 `docs/TESTING.md` ㉞；取退出码要么不接管道、要么 `$?`；
  ③ 验收 = 证书指纹逐位不变（`keytool -printcert -jarfile` 与 `apksigner verify --print-certs` 都是 `AE:7F…3E:4F`）+ `apksigner` 报 v1/v2 双签为 true ⇒ 已装手机仍能覆盖安装。改口令前的 `.jks` 备份在 `app/keystore/soul2soul-release.jks.bak-20261019`（`keystore/` 整个被忽略）。
  t0 两条守卫盯着：构建脚本里出现 `storePassword = "…"` 字面量即红、`.gitignore` 少了 `keystore.properties` 那行即红。

## 发版时的复测（每次都要做，不是做一次就完）

验收的三条读数 10-23 发 0.2.65 时复量过一遍：`apksigner verify --print-certs` 的证书 SHA-256 仍是 `ae7fb8ec…3e4f`（与 0.2.64 及更早**逐位相同** ⇒ 已装手机是覆盖安装、不用卸载）、v1 与 v2 签名方案都为 true、远端 `version.json` 的 sha256 与本地 `dist/` 产物逐字节相同。
**为什么每次发版都要复量**：轮换口令或换 `.jks` 的唯一硬后果就是指纹变化，而指纹一变，所有已装手机只能卸载重装——`deviceId` 存在 App 私有 SharedPreferences，**卸载即抹掉**，那条配对从此指向不存在的设备（见 `docs/MEASUREMENT.md` 装包纪律）。指纹不变 = 用户不掉配对，这是这条链上唯一的判据。
