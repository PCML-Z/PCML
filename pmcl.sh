#!/bin/bash
# PMCL Windows 启动脚本（适用于 Git Bash / WSL / MSYS2）
# 用法:
#   ./pmcl.sh              # 启动 PMCL GUI
#   ./pmcl.sh cli          # 启动 PMCL Shell 模式
#   ./pmcl.sh cli versions # 执行单条 CLI 命令
#
# 前置条件: 已安装 Java 21+
#   验证: java -version

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- Java 版本检查 ----------
if ! command -v java >/dev/null 2>&1; then
    echo "错误: 未检测到 java 命令"
    echo "请先安装 Java 21+:"
    echo "  - Windows (winget):  winget install EclipseAdoptium.Temurin.21.JDK"
    echo "  - Windows (choco):   choco install temurin21 -y"
    echo "  - macOS:             brew install openjdk@21"
    echo "  - Linux:             sdk install java 21.0.5-tem"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 21 ] 2>/dev/null; then
    # 兼容 Java 8 输出格式（version "1.8.0_xxx"）
    if [ "$JAVA_VERSION" = "1" ]; then
        echo "错误: 当前 Java 版本过旧（Java 8），PMCL 需要 Java 21+"
    else
        echo "错误: 当前 Java 版本为 $JAVA_VERSION，PMCL 需要 Java 21+"
    fi
    echo "请升级后重试"
    exit 1
fi

# ---------- 模式分发 ----------
MODE="${1:-gui}"

case "$MODE" in
    gui)
        JAR_PATH="$SCRIPT_DIR/ui/build/libs/pmcl-1.0.0-all.jar"
        if [ ! -f "$JAR_PATH" ]; then
            echo "错误: 未找到 PMCL fat jar: $JAR_PATH"
            echo "请先运行: ./gradlew :ui:fatJar"
            exit 1
        fi
        echo "启动 PMCL GUI (Java $JAVA_VERSION)..."
        # JVM 性能参数：G1GC + 初始/最大堆 + 字符串去重 + 关闭偏向锁（Java 18+ 默认关闭，显式声明避免告警）
        exec java \
            -Xms512m -Xmx2g \
            -XX:+UseG1GC \
            -XX:+UseStringDeduplication \
            -XX:+DisableExplicitGC \
            -XX:MaxGCPauseMillis=200 \
            -XX:SoftMaxHeapSize=1536m \
            -jar "$JAR_PATH"
        ;;
    cli)
        shift
        JAR_PATH="$SCRIPT_DIR/cli/build/libs/cli.jar"
        if [ ! -f "$JAR_PATH" ]; then
            echo "错误: 未找到 PMCL CLI jar: $JAR_PATH"
            echo "请先运行: ./gradlew :cli:jar"
            exit 1
        fi
        exec java -jar "$JAR_PATH" "$@"
        ;;
    -h|--help|help)
        echo "PMCL 启动脚本"
        echo ""
        echo "用法:"
        echo "  ./pmcl.sh              启动 PMCL GUI（默认）"
        echo "  ./pmcl.sh gui          启动 PMCL GUI"
        echo "  ./pmcl.sh cli          进入 PMCL Shell 交互模式"
        echo "  ./pmcl.sh cli <命令>   执行单条 CLI 命令（如 ./pmcl.sh cli versions）"
        echo ""
        echo "前置条件:"
        echo "  - 已安装 Java 21+（验证: java -version）"
        echo "  - 已构建产物（./gradlew :ui:fatJar 或 ./gradlew :cli:jar）"
        exit 0
        ;;
    *)
        echo "未知模式: $MODE"
        echo "使用 ./pmcl.sh help 查看帮助"
        exit 1
        ;;
esac
