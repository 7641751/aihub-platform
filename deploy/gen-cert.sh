#!/usr/bin/env sh
# 生成自签 TLS 证书到 deploy/certs/（默认 825 天 —— 浏览器对自签证书的要求上限）。
#
#   ./gen-cert.sh <公网IP或域名> [天数]
#
# 为什么默认自签：大陆 ECS 上域名走 80/443 需要**备案**，没备案时"IP + 高段端口 + 自签证书"
# 是能立刻拿到加密传输的现实解。代价是客户端会警告一次 —— 用 `curl --cacert certs/server.crt`
# 或把 server.crt 装进信任库即可消除；**有域名+备案之后**应换成真证书（见 README 第 3 步）。
set -eu

NAME="${1:?usage: gen-cert.sh <public-ip-or-domain> [days]}"
DAYS="${2:-825}"
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$DIR"

case "$NAME" in
  *[!0-9.]*) SAN="DNS:$NAME" ;;   # 含数字/点以外的字符 ⇒ 当域名
  *)         SAN="IP:$NAME" ;;    # 纯数字与点 ⇒ 当 IP 地址
esac

openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
  -keyout "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/CN=$NAME" -addext "subjectAltName=$SAN"

chmod 600 "$DIR/server.key"
echo "wrote $DIR/server.crt + $DIR/server.key  (SAN=$SAN, $DAYS days)"
echo "verify: openssl x509 -in $DIR/server.crt -noout -subject -dates -ext subjectAltName"
