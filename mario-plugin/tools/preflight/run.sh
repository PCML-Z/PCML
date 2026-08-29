#!/usr/bin/env bash
# 装机前自检：用 plugin-api 自己的校验器核对插件描述符，并确认主类可加载。
#
# 用法：
#     mario-plugin/tools/preflight/run.sh [插件jar路径]
# 缺省路径为 mario-plugin/build/libs/mario-1.0.0.jar
#
# 这一步能在「重启 PMCL 才发现插件没被识别」之前就抓出描述符格式错误
# （id/版本/api-version/主类名/权限名写错等）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODDIR="$(cd "$HERE/../.." && pwd)"            # mario-plugin/
ROOT="$(cd "$HERE/../../.." && pwd)"           # 仓库根目录
JAR="${1:-$MODDIR/build/libs/mario-1.0.0.jar}"
API_CLASSES="$ROOT/plugin-api/build/classes/kotlin/main"

if [[ ! -f "$JAR" ]]; then
  echo "找不到插件 jar: $JAR —— 先运行 ./gradlew :mario-plugin:jar" >&2
  exit 1
fi

if [[ ! -d "$API_CLASSES" ]]; then
  echo "编译 plugin-api...（自检要复用它的 PluginInfo 校验器）" >&2
  (cd "$ROOT" && ./gradlew :plugin-api:classes -q --console=plain)
fi

GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
KOTLIN_STDLIB="$(find "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.*.jar' -type f 2>/dev/null | grep -v -- '-sources' | head -1)"
if [[ -z "$KOTLIN_STDLIB" ]]; then
  echo "找不到 kotlin-stdlib（在 $GRADLE_CACHE 下）" >&2
  exit 1
fi

CP="$API_CLASSES:$KOTLIN_STDLIB"
OUT="$HERE/out"
rm -rf "$OUT" && mkdir -p "$OUT"

javac -nowarn -cp "$CP" -d "$OUT" "$HERE/Preflight.java"
java -cp "$OUT:$CP" Preflight "$JAR"
