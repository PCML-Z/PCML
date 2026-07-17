#!/bin/bash
# PMCL 视频通话双实例测试脚本
# 用法: ./test-video-call.sh
#
# 启动两个独立的 PMCL 实例，分别使用不同的数据目录（~/.pmcl-test1 和 ~/.pmcl-test2）
# 这样两个实例可以拥有独立的身份，通过本地网络互相发现并发起视频通话

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FAT_JAR="$SCRIPT_DIR/ui/build/libs/pmcl-1.0.0-all.jar"

if [ ! -f "$FAT_JAR" ]; then
    echo "❌ 未找到 fat jar: $FAT_JAR"
    echo "请先运行: ./gradlew :ui:fatJar"
    exit 1
fi

# 测试数据目录（各自独立）
HOME1="/tmp/pmcl-test1/home"
HOME2="/tmp/pmcl-test2/home"

# 创建必要目录
for dir in "$HOME1"/.pmcl "$HOME2"/.pmcl; do
    mkdir -p "$dir"/friend-data
done

# 通用 JVM 参数
NATIVE_LIBS="$SCRIPT_DIR/video/native/darwin-aarch64"
JVM_OPTS="-Xmx1g -Djava.library.path=$NATIVE_LIBS -Dorg.bytedeco.javacpp.pathsfirst=true"

echo "================================================"
echo "  PMCL 视频通话双实例测试"
echo "================================================"
echo ""
echo "  实例 1 数据目录: $HOME1"
echo "  实例 2 数据目录: $HOME2"
echo ""
echo "  启动后请按以下步骤测试:"
echo "  1. 两个窗口各进入「好友」页面"
echo "  2. 确保两个实例都加入了同一联机房间（本地网络即可互相发现）"
echo "  3. 互相添加好友"
echo "  4. 一方点击视频通话按钮发起视频通话"
echo "  5. ✅ 验证: 双方应能看到对方的视频画面"
echo "================================================"
echo ""

# 清理旧进程
pkill -f "pmcl-1.0.0-all.jar" 2>/dev/null || true
sleep 1

echo "🚀 启动实例 1 (Alice)..."
java $JVM_OPTS -Duser.home="$HOME1" -jar "$FAT_JAR" &
PID1=$!

sleep 3

echo "🚀 启动实例 2 (Bob)..."
java $JVM_OPTS -Duser.home="$HOME2" -jar "$FAT_JAR" &
PID2=$!

echo ""
echo "两个实例已启动 (PID: $PID1, $PID2)"
echo ""
echo "按 Ctrl+C 停止所有实例"
echo ""

# 等待用户中断
trap "echo ''; echo '🛑 停止所有实例...'; kill $PID1 $PID2 2>/dev/null; exit 0" INT TERM

wait
