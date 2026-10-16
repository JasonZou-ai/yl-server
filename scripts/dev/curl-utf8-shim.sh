#!/bin/bash
# ---------------------------------------------------------------------------
# curl 参数编码修正 shim（仅本地跑冒烟用，不改仓库脚本）
#
# 问题：本机 /mingw64/bin/curl 8.21.0（中文 Windows，ACP=936）把**非 ASCII argv**
#       经 CRT 转成 GBK 再发出 ⇒ 服务端 Jackson 报 "Invalid UTF-8 start byte 0xb7"（10999）。
#       （实测：-d '材料齐全，同意发布' → 32 字节 GBK；--data-binary @file → 41 字节 UTF-8）
# 做法：把含非 ASCII 的 body 参数落到临时文件，改用 --data-binary @file 发送（字节与脚本源码一致，仍是 UTF-8）。
# 口径：这只修正**本地客户端**的编码，服务端与脚本正文均未改动；请求体内容与脚本源码逐字节一致。
# ---------------------------------------------------------------------------
# ★ REAL 自动探测（2026-10-16 订正）：原硬编码 /mingw64/bin/curl 在本机会被解析到
#   另一个 mingw64 根（其中无 curl）⇒ "No such file or directory" ⇒ 登录失败 22 条假红
#   ⇒ 巡检被判失败（**环境问题，不是后端/权限问题**；先探 curl 再判因）。
#   按序探测：环境变量 REAL_CURL → /mingw64/bin/curl → Git for Windows mingw64 → PATH 的 curl。
REAL="${REAL_CURL:-}"
for c in /mingw64/bin/curl "/c/Program Files/Git/mingw64/bin/curl.exe"; do
  [ -z "$REAL" ] && [ -x "$c" ] && REAL="$c"
done
[ -z "$REAL" ] && REAL="$(command -v curl)"
if [ ! -x "$REAL" ]; then
  echo "shim FATAL: 未找到可用的真实 curl（fail-closed，不半跑）" >&2
  exit 127
fi
argv=("$@")
out=()
i=0
while [ $i -lt ${#argv[@]} ]; do
  a="${argv[$i]}"
  case "$a" in
    -d|--data|--data-raw|--data-ascii|--data-binary)
      body="${argv[$((i + 1))]}"
      if printf '%s' "$body" | LC_ALL=C grep -q '[^ -~]'; then
        f=$(mktemp /tmp/d4-run/body-XXXXXX.json)
        printf '%s' "$body" > "$f"
        out+=("--data-binary" "@$f")
      else
        out+=("$a" "$body")
      fi
      i=$((i + 2))
      continue
      ;;
    --data=*|--data-raw=*|--data-ascii=*|--data-binary=*)
      body="${a#*=}"
      if printf '%s' "$body" | LC_ALL=C grep -q '[^ -~]'; then
        f=$(mktemp /tmp/d4-run/body-XXXXXX.json)
        printf '%s' "$body" > "$f"
        out+=("--data-binary" "@$f")
      else
        out+=("$a")
      fi
      i=$((i + 1))
      continue
      ;;
  esac
  out+=("$a")
  i=$((i + 1))
done
exec "$REAL" "${out[@]}"
