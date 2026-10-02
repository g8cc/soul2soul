#!/bin/bash
# 发版脚本: 构建 release → 上传服务器 → 更新版本清单（App 内自动更新即生效）
# 用法: ./scripts/publish-release.sh <versionName> <versionCode>
set -e
VN=${1:?版本名，如 0.2.5}; VC=${2:?版本号(递增整数)}
cd "$(dirname "$0")/../app"
export JAVA_HOME=${JAVA_HOME:-/Users/wardonguo/tools/jdk17/jdk-17.0.2.jdk/Contents/Home}
~/tools/gradle-8.7/bin/gradle :app:assembleProdRelease --no-daemon -q
APK=app/build/outputs/apk/prod/release/app-prod-release.apk
[ -f "$APK" ] || { echo "APK 未生成"; exit 1; }
scp "$APK" guo:/opt/soul2soul/apk/soul2soul-latest.apk
# 用 heredoc 生成清单：printf 内嵌引号在 ssh 双层解析下会产出非法 JSON（""0.2.5""），App 端静默解析失败=更新渠道失效
ssh guo "cat > /opt/soul2soul/apk/version.json" <<EOF
{"versionCode": $VC, "versionName": "$VN", "url": "https://soul.lumi666.cloud/apk/soul2soul-latest.apk"}
EOF
# 远端校验清单合法性，坏 JSON 立即失败而不是静默上线
ssh guo "node -e 'JSON.parse(require(\"fs\").readFileSync(\"/opt/soul2soul/apk/version.json\",\"utf8\"))'" 2>/dev/null \
  || curl -s https://soul.lumi666.cloud/apk/version.json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>JSON.parse(s))' \
  || { echo "❌ version.json 非法 JSON，请检查服务器"; exit 1; }
# 本地归档: 拷贝发版产物到 dist/，只保留最近 2 个版本
DIST="$(cd "$(dirname "$0")/.." && pwd)/dist"
mkdir -p "$DIST"
cp "$APK" "$DIST/Soul2Soul-v$VN-release.apk"
ls -1t "$DIST"/Soul2Soul-v*.apk 2>/dev/null | tail -n +3 | xargs rm -f
echo "✅ v$VN (code $VC) 已发布——已安装的 App 会在下次打开时看到更新"
