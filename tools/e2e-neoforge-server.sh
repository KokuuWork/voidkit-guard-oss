#!/usr/bin/env bash
# VoidkitGuard NeoForge 1.21.1 端到端验证（发布态 jar + 真服务端）
#
# 与 tools/e2e-forge-server.sh（1.20.1 Forge 线）对应，覆盖：
#   1. 发布态 jar 在真正的 NeoForge 服务端上能否被加载器识别为合法 mod
#      （防 eachFile 作用域 bug 剥掉 neoforge.mods.toml 导致静默跳过 —— 假阳性踩坑）
#   2. ip2region shade + sqlite jarJar 在 JPMS 模块路径下不炸
#      （split package ResolutionException 修复验证）
#   3. jarJar 内嵌 sqlite 原生库可用（DB 能建起来）
#   4. 客户端 jar 误装到服务端不崩（dist 守卫）
#
# 用法：
#   tools/e2e-neoforge-server.sh                  # 默认目录 build/e2e-1211
#   SERVER_DIR=/path tools/e2e-neoforge-server.sh # 指定已装好的服务端目录
#
# 前置：目录里已 `java -jar neoforge-installer.jar --installServer .`，
#       且 mods/ 已放 3 个发布态 jar：core / guard server / guard client。
set -u

cd "$(dirname "$0")/.."
PROJECT="$(pwd)"

SERVER_DIR="${SERVER_DIR:-$PROJECT/build/e2e-1211}"
LOG="$SERVER_DIR/server.log"

case "$SERVER_DIR" in
  /*) SERVER_ABS="$SERVER_DIR" ;;
  *)  SERVER_ABS="$PROJECT/$SERVER_DIR" ;;
esac

# 1.21.1 必须 JDK 21（与 build.sh 同一套约定）
JAVA_BIN=""
for c in "C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/java.exe" \
         "C:/Users/26703/.jdks/jdk-21.0.12.1+1/bin/java" java; do
  if [ -f "$c" ] || command -v "$c" >/dev/null 2>&1; then JAVA_BIN="$c"; break; fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "[e2e] 找不到 java（1.21.1 需要 JDK 21）" >&2
  exit 2
fi

if [ ! -d "$SERVER_ABS" ]; then
  echo "[e2e] 目录不存在：$SERVER_ABS" >&2
  echo "      先装服务端：java -jar neoforge-installer.jar --installServer ." >&2
  exit 2
fi

# NeoForge 用 windows_args.txt（classpath 分隔符是 ;，unix_args.txt 在 Windows 上会炸）
ARGS="$SERVER_ABS/libraries/net/neoforged/neoforge/21.1.209/win_args.txt"
if [ ! -f "$ARGS" ]; then
  echo "[e2e] 找不到 NeoForge 启动参数：$ARGS（服务端没装好？）" >&2
  exit 2
fi

# ---------- 前置检查：mods/ 必须是 3 个「发布态」jar ----------
echo "[e2e] 服务端目录：$SERVER_ABS"
for want in voidkit_core voidkit_guard voidkit_guard_client; do
  n=$(ls "$SERVER_ABS"/mods/${want}-*.jar 2>/dev/null | wc -l)
  echo "[e2e] mods/ ${want}: $n 个"
  if [ "$n" != "1" ]; then
    echo "[e2e] ❌ ${want} jar 数不对（应为 1，检查是否混入旧版本）" >&2
    exit 2
  fi
done
# 结构校验：guard 服务端 jar 必须同时含 mod 元数据 + shade 的类 + jarJar payload。
# 三项缺任意一项都说明构建产物损坏（历史上 eachFile 作用域 bug 同时剥掉了前两项）。
if command -v unzip >/dev/null 2>&1; then
  PROBE=$(ls "$SERVER_ABS"/mods/voidkit_guard-*.jar | head -1)
  for must in META-INF/neoforge.mods.toml org/lionsoul/ip2region/xdb/Searcher.class META-INF/jarjar/metadata.json; do
    if unzip -l "$PROBE" "$must" >/dev/null 2>&1; then
      echo "[e2e] ✅ guard jar 含 $must"
    else
      echo "[e2e] ❌ guard jar 缺少 $must（构建产物不完整，禁止起服）" >&2
      exit 2
    fi
  done
fi

# ---------- 启动 ----------
[ -f "$SERVER_ABS/eula.txt" ] || printf 'eula=true\n' > "$SERVER_ABS/eula.txt"
: > "$LOG"
echo "[e2e] 启动服务端…"
cd "$SERVER_ABS"
nohup "$JAVA_BIN" -Xmx2G @user_jvm_args.txt "@$ARGS" nogui > "$LOG" 2>&1 &
SRV_PID=$!
cd "$PROJECT"
echo "[e2e] 服务端 pid=$SRV_PID"

READY=0
CRASH=0
ROUND=0
for i in $(seq 1 48); do
  ROUND=$i
  sleep 5
  if grep -qa 'Done (' "$LOG" 2>/dev/null; then READY=1; break; fi
  if grep -qaE 'Failed to start|Encountered an unexpected exception|\[Server thread/FATAL\]|Mod loading has failed|A fatal error|NoClassDefFoundError|NoSuchMethodError|ResolutionException' "$LOG" 2>/dev/null; then
    CRASH=1
    break
  fi
done
echo "[e2e] 等待 $((ROUND * 5))s：ready=$READY crash=$CRASH"

echo ""
echo "=================== 模组清单（必须含 VoidkitGuard 服务端）==================="
grep -aE 'Voidkit|voidkit' "$LOG" 2>/dev/null | head -25

echo ""
echo "=================== 致命错误（应为空）==================="
if grep -qaE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" 2>/dev/null; then
  grep -aE 'NoSuchMethodError|NoClassDefFoundError|InvalidModFileException|ModLoadingException|Failed to create mod instance|ResolutionException|not a valid mod file|\[Server thread/FATAL\]|Encountered an unexpected exception' "$LOG" | head -20
  CRASH=1
else
  echo "（无）"
fi

# ---------- 强判据：guard 服务端 mod 必须真实加载 ----------
# 只数 "Found mod file" 不够（那只是发现阶段）；NeoForge 在启动前会打印最终模组清单，
# 出现 "VoidkitGuard <版本> (voidkit_guard)" 才说明服务端 mod 真的进了加载列表。
# 假阳性事故：eachFile 作用域 bug 剥掉 mods.toml，jar 被静默跳过，只有 core+client 加载。
if ! grep -qaE 'VoidkitGuard [0-9][^ ]* \(voidkit_guard\)' "$LOG" 2>/dev/null; then
  echo "[e2e] ❌ 模组清单里没有 VoidkitGuard 服务端（jar 被加载器跳过？）" >&2
  CRASH=1
fi
# 功能判据：guard 服务端启动时应落配置 + 建库（证明 shade 的 sqlite 真能跑）
if [ ! -d "$SERVER_ABS/voidkit/guard" ]; then
  echo "[e2e] ⚠️ 未发现 voidkit/guard 数据目录（配置/库未生成？）" >&2
fi

# ---------- 收尾 ----------
echo ""
echo "=================== 收尾 ==================="
if taskkill //F //PID "$SRV_PID" >/dev/null 2>&1; then
  echo "已终止服务端 pid=$SRV_PID"
else
  echo "（pid=$SRV_PID 已不在，可能已自行退出）"
fi

echo ""
if [ "$READY" != "1" ] || [ "$CRASH" = "1" ]; then
  echo "[e2e] ❌ 失败：正式服未正常启动或出现致命错误（见上）"
  exit 1
fi
echo "[e2e] ✅ 通过：发布产物 + 客户端 jar 在真正的 NeoForge 1.21.1 服务端上加载正常"
