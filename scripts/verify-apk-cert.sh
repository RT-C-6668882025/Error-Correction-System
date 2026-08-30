#!/usr/bin/env bash
# 校验一个 APK 的签名证书是不是仓库钉住的那张。
#
#   scripts/verify-apk-cert.sh ecs-4.1.1.apk
#
# 一致退出 0，不一致或读不出来退出非 0。CI 发版前跑它，用户下载到包之后
# 也能自己跑一遍——指纹对得上才能直接覆盖安装，对不上就是换了密钥。
set -euo pipefail

APK=${1:-}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PINNED_FILE=${PINNED_FILE:-"$ROOT/signing/release-cert-sha256.txt"}

if [ -z "$APK" ]; then
  echo "用法：$0 <apk>" >&2
  exit 2
fi
if [ ! -f "$APK" ]; then
  echo "找不到文件：$APK" >&2
  exit 2
fi
if [ ! -f "$PINNED_FILE" ]; then
  echo "找不到钉住的指纹文件：$PINNED_FILE" >&2
  exit 2
fi

normalize() { tr -d ': \r' | tr 'A-Z' 'a-z'; }

PINNED=$(grep -v '^#' "$PINNED_FILE" | grep -v '^[[:space:]]*$' | head -1 | normalize)
if [ -z "$PINNED" ]; then
  echo "$PINNED_FILE 里没有指纹" >&2
  exit 2
fi

# 有 Android SDK 就用官方工具，没有就用只依赖标准库的 Python 解析器
APKSIGNER=$(command -v apksigner || ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
  ACTUAL=$("$APKSIGNER" verify --print-certs "$APK" \
    | grep -m1 -i 'certificate SHA-256 digest' | awk '{print $NF}' | normalize)
  SOURCE="apksigner"
else
  ACTUAL=$(python3 "$ROOT/scripts/apk_cert_sha256.py" "$APK" | head -1 | normalize)
  SOURCE="apk_cert_sha256.py"
fi

if [ -z "$ACTUAL" ]; then
  echo "读不出 $APK 的签名证书指纹（$SOURCE）" >&2
  exit 1
fi

echo "钉住指纹：$PINNED"
echo "实际指纹：$ACTUAL（$SOURCE）"
if [ "$ACTUAL" = "$PINNED" ]; then
  echo "一致：这个包能直接覆盖安装，数据不丢。"
else
  echo "不一致：签名换了。Android 不允许跨签名覆盖，装它必须先卸载（本地数据会没）。" >&2
  exit 1
fi
