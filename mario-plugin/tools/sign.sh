#!/bin/bash
# 用受信任的 pmcl keystore 签名 mario.jar 并安装到 ~/.pmcl/plugins/mario.jar
# 用法:
#   ./sign.sh                     # 签名 build/libs/mario-1.0.0.jar 并安装
#   ./sign.sh /path/to/jar        # 指定 jar 文件
#
# 背景: PMCL 的 loadPlugin 对 JAR 强制 jarsigner 验签(verifyPluginArchive)，
# 且签名证书指纹必须命中 ~/.pmcl/plugins/trusted-signers.txt（否则 "signer not in
# trusted fingerprint list"）。本仓库的 pmcl.keystore(alias=pmcl, 指纹 46:C7:...)
# 已登记在可信列表，用它签名即可直接命中。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="${1:-$ROOT/mario-plugin/build/libs/mario-1.0.0.jar}"
KEYSTORE="$ROOT/pcmlAndroid/app/pmcl.keystore"
ALIAS="pmcl"
STORE_PASS="${PMCL_KEYSTORE_PASS:-pmcl123}"   # 可通过环境变量覆盖

if [ ! -f "$KEYSTORE" ]; then
    echo "错误: 未找到 keystore: $KEYSTORE" >&2
    exit 1
fi
if [ ! -f "$JAR" ]; then
    echo "错误: 未找到 jar: $JAR" >&2
    exit 1
fi

echo "==> 签名: $(basename "$JAR") (alias=$ALIAS)"
jarsigner -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$STORE_PASS" "$JAR" "$ALIAS"

echo "==> 安装到 ~/.pmcl/plugins/mario.jar"
cp "$JAR" "$HOME/.pmcl/plugins/mario.jar"

echo "==> 签名块检查:"
unzip -l "$HOME/.pmcl/plugins/mario.jar" | grep -E "META-INF/(PMCL|.*\.(SF|RSA|EC))" || { echo "!! 未发现签名块"; exit 1; }

echo "==> 可信指纹检查:"
grep -qi "46:C7:AB" "$HOME/.pmcl/plugins/trusted-signers.txt" \
    && echo "OK: pmcl 证书指纹已在 trusted-signers.txt" \
    || echo "警告: trusted-signers.txt 中没有 pmcl 指纹，宿主会拒绝加载！"

echo ""
echo "完成。接下来任选其一:"
echo "  1) 重启 PMCL（最干净，自动加载并启用 mario）"
echo "  2) 插件页点「扫描插件」→ 找到 Super Mario Bros. → 若状态不是 ENABLED 则点 Enable"
