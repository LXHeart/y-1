#!/usr/bin/env bash
# 数字人测试短命证书（任务书 #105E C105E-06 / K13.2）：仅本地测试目录生成，绝不提交私钥。
# SAN 与 K13.2 对齐：Java 校验 runtime（digital-human-runtime），runtime 校验 Java（intelligence-service）。
set -euo pipefail

TARGET_DIR="${1:-test-artifacts/task-105/E/dh-test-certs}"
VALIDITY_DAYS=2

command -v openssl >/dev/null || { echo "需要 openssl" >&2; exit 1; }

mkdir -p "$TARGET_DIR"
cd "$TARGET_DIR"

# 已有一套且未过期则不重造（幂等）。
if [[ -f ca.crt && -f runtime.crt && -f intelligence.crt
      && -f runtime.key && -f intelligence.key && -f ca.key ]]; then
  if openssl x509 -checkend $((3600 * 24)) -noout -in ca.crt >/dev/null 2>&1; then
    echo "证书仍有效：$TARGET_DIR"
    exit 0
  fi
fi

rm -f ca.key ca.crt runtime.key runtime.crt runtime.csr intelligence.key intelligence.crt intelligence.csr runtime-int.key

# 测试 CA（短命、仅本地测试目录）。
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt \
  -days "$VALIDITY_DAYS" -subj "/CN=grassland-dh-test-ca" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

issue_cert () {
  local name="$1" san="$2"
  openssl req -newkey rsa:2048 -nodes -keyout "$name.key" -out "$name.csr" \
    -subj "/CN=$san"
  openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out "$name.crt" -days "$VALIDITY_DAYS" \
    -extfile <(printf 'subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=clientAuth,serverAuth\n' "$san")
  rm -f "$name.csr"
}

issue_cert runtime "digital-human-runtime"
issue_cert intelligence "intelligence-service"

# 私钥权限收紧；srl 是 OpenSSL 副产物。
chmod 600 ./*.key
rm -f ./*.srl

echo "已生成短命测试证书（${VALIDITY_DAYS} 天）：$TARGET_DIR"
echo "ca.crt / runtime.crt+key / intelligence.crt+key（私钥只存在于该 Git 忽略目录，不提交）"
