#!/usr/bin/env bash
# 马里奥插件的无头冒烟测试。
#
# 不启动 JavaFX 工具箱、不依赖 PMCL 宿主，直接驱动 Game 的定步长主循环。
# 需要先构建插件 jar：
#     ./gradlew :mario-plugin:jar
# 然后：
#     mario-plugin/tools/smoke/run.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/../../build/libs/mario-1.0.0.jar"   # mario-plugin/build/libs
OUT="$HERE/out"

if [[ ! -f "$JAR" ]]; then
  echo "找不到 $JAR —— 先运行 ./gradlew :mario-plugin:jar" >&2
  exit 1
fi

GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

find_jar() {
  # $1 = 目录前缀（如 org.openjfx/javafx-graphics），$2 = 文件名 glob
  find "$GRADLE_CACHE/$1" -name "$2" -type f 2>/dev/null | grep -v -- "-sources" | head -1
}

KOTLIN_STDLIB="$(find_jar "org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-2.*.jar")"
FX_BASE="$(find_jar "org.openjfx/javafx-base" "*mac-aarch64.jar")"
FX_GRAPHICS="$(find_jar "org.openjfx/javafx-graphics" "*mac-aarch64.jar")"

[[ -n "$FX_GRAPHICS" ]] || FX_GRAPHICS="$(find_jar "org.openjfx/javafx-graphics" "javafx-graphics-*.jar")"
[[ -n "$FX_BASE" ]] || FX_BASE="$(find_jar "org.openjfx/javafx-base" "javafx-base-2*.jar")"

for v in KOTLIN_STDLIB FX_BASE FX_GRAPHICS; do
  if [[ -z "${!v}" ]]; then
    echo "在 $GRADLE_CACHE 下找不到 $v 对应的 jar（先构建一次 :plugin-api 拉依赖）" >&2
    exit 1
  fi
done

CP="$JAR:$KOTLIN_STDLIB:$FX_BASE:$FX_GRAPHICS"

rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$HERE/Smoke.java"
java -cp "$OUT:$CP" Smoke
