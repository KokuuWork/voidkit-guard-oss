#!/usr/bin/env bash
# ============================================================================
# 同步脚本：把本仓（guard）的功能更新同步到 OSS 仓（voidkit-guard-oss）。
#
# 用法：在本仓根目录执行  ./scripts/sync-oss.sh
#       可选环境变量 OSS_ROOT 指定 OSS 仓根目录（默认 ../voidkit-guard-oss）。
#
# 两仓结构相同（common/ + server/ + client/ 三子项目）。差异只在「隐私路径」：
#   - 共用文件（协议/加密/蜜罐/统计检测/截屏模块/platform/入口…）：覆盖复制。
#   - 隐私差异文件（采集项/截图源/封禁维度/指纹模型）：本仓是完整版、
#     OSS 仓是删减版 —— **不同步**，OSS 保持自己的删减版。
#
# ⚠️ 前提：OSS 仓结构要与本仓一致（三子项目）。若 OSS 仓还是旧的单模块结构，
#    需先按本仓结构重排一次。同步后 `cd $OSS_ROOT && ./build.sh build` 验证编译。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRIVATE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OSS_ROOT="${OSS_ROOT:-$PRIVATE_ROOT/../voidkit-guard-oss}"

# 三个源码模块（相对仓根）
MODULES=(common server client)

# 隐私差异文件（相对仓根的路径）——OSS 保持自己的删减版，不同步
PRIVACY_SENSITIVE=(
    "common/src/main/kotlin/org/voidkit/guard/common/GFingerprint.kt"
    "client/src/main/kotlin/org/voidkit/guard/client/capture/ScreenCapturer.kt"
    "client/src/main/kotlin/org/voidkit/guard/client/hw/Hardware.kt"
    "client/src/main/kotlin/org/voidkit/guard/client/hw/EnvCollector.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/GuardConfig.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/GuardDb.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/BanFeature.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/DeepScanFeature.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/GuardHub.kt"
    "server/src/main/kotlin/org/voidkit/guard/server/IntegrityFeature.kt"
)

is_privacy() {
    local rel="$1"
    for p in "${PRIVACY_SENSITIVE[@]}"; do
        [ "$rel" = "$p" ] && return 0
    done
    return 1
}

if [ ! -d "$OSS_ROOT" ]; then
    echo "错误：找不到 OSS 仓目录 $OSS_ROOT" >&2
    echo "用环境变量 OSS_ROOT 指定后重试。" >&2
    exit 1
fi

synced=0
skipped=0
for m in "${MODULES[@]}"; do
    src_root="$PRIVATE_ROOT/$m/src/main"
    dst_root="$OSS_ROOT/$m/src/main"
    [ -d "$src_root" ] || continue
    while IFS= read -r f; do
        rel_in_mod="${f#"$src_root/"}"
        rel="${m}/src/main/${rel_in_mod}"
        if is_privacy "$rel"; then
            skipped=$((skipped + 1))
            continue
        fi
        mkdir -p "$(dirname "$dst_root/$rel_in_mod")"
        cp "$f" "$dst_root/$rel_in_mod"
        synced=$((synced + 1))
    done < <(find "$src_root" -name '*.kt' -type f | sort)
done

echo "同步完成：复制 $synced 个共用文件，跳过 $skipped 个隐私差异文件。"
echo "下一步：cd $OSS_ROOT && ./build.sh build  验证编译。"
echo "若编译报「未解析的引用」且指向隐私差异文件，说明其签名在本仓改了 —— 手工对齐 OSS 侧。"
