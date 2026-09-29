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
ssh guo "printf '{\"versionCode\": %s, \"versionName\": \"%s\", \"url\": \"https://soul.lumi666.cloud/apk/soul2soul-latest.apk\"}' $VC "'\"'"$VN"'\"'" > /opt/soul2soul/apk/version.json"
echo "✅ v$VN (code $VC) 已发布——已安装的 App 会在下次打开时看到更新"
