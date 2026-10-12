#!/usr/bin/env bash
# VoidkitGuard 构建入口（Windows Git Bash / Linux 通用）
#
# 与 voidkit 主仓库同构：强制 JDK 21 + NeoForge 官方 CDN 镜像重定向。
# 用法：./build.sh build        → guard/build/libs/voidkit_guard-1.0.0.jar
#       ./build.sh runServer    → 起开发服务端（core + guard 一起加载）
set -e

cd "$(dirname "$0")"

# ---- JDK 21 ----
JDK21="${GUARD_JDK21:-C:/Users/26703/.jdks/jdk-21.0.12.1+1}"
if [ ! -x "$JDK21/bin/java" ] && [ ! -f "$JDK21/bin/java.exe" ]; then
  echo "找不到 JDK 21: $JDK21"
  echo "请设置 GUARD_JDK21 指向 JDK 21 安装目录"
  exit 1
fi
export JAVA_HOME="$JDK21"
# Git Bash 只认 POSIX 形式的 PATH 条目，Windows 风格路径会被静默忽略
JDK_POSIX=$(echo "$JDK21" | sed -E 's#^([A-Za-z]):#/\L\1#')
export PATH="$JDK_POSIX/bin:$PATH"

TASK="${1:-build}"
shift || true

echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] 任务: $TASK"

./gradlew --init-script mirror.init.gradle "$TASK" "$@"
