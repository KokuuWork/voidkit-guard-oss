#!/usr/bin/env bash
# ============================================================================
# 同步脚本：把本仓（guard）的功能更新同步到 OSS 仓（voidkit-guard-oss）。
#
# 用法：在本仓根目录执行  ./scripts/sync-oss.sh
#       可选环境变量 OSS_ROOT 指定 OSS 仓根目录（默认 ../voidkit-guard-oss）。
#
# 原理：
#   - 大部分 .kt 是两个仓共用的（协议/加密/蜜罐/统计检测/截屏模块等），直接覆盖复制。
#   - 「隐私差异」文件是两个仓**有意不同**的（采集项 / 截图源 / 封禁维度 / 指纹模型），
#     不在同步范围 —— OSS 仓保持自己的删减版。
#   - 同步后请跑一次 `./build.sh build` 验证：如果某个隐私差异文件的**签名**在本仓
#     被改了（比如加了参数），而调用它的共用文件被同步过去了，OSS 会编译不过 ——
#     这时按编译错误手工对齐 OSS 侧的签名即可。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRIVATE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OSS_ROOT="${OSS_ROOT:-$PRIVATE_ROOT/../voidkit-guard-oss}"

SRC="$PRIVATE_ROOT/guard/src/main/kotlin/org/voidkit/guard"
DST="$OSS_ROOT/guard/src/main/kotlin/org/voidkit/guard"

if [ ! -d "$DST" ]; then
    echo "错误：找不到 OSS 仓目录 $DST" >&2
    echo "用环境变量 OSS_ROOT 指定 OSS 仓根目录后重试。" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 隐私差异文件（两仓有意不同，不同步）
# ---------------------------------------------------------------------------
PRIVACY_SENSITIVE=(
    "client/capture/ScreenCapturer.kt"   # 截图源：全屏多显示器 vs 只 MC 窗口
    "client/hw/Hardware.kt"              # 指纹采集：全采 vs 只机器码
    "client/hw/EnvCollector.kt"          # 环境画像：含位置/区域 vs 不含
    "common/GFingerprint.kt"             # Fingerprint/EnvInfo 模型字段
    "server/GuardConfig.kt"              # 采集/封禁开关字段
    "server/GuardDb.kt"                  # identities 表结构 + 同源查询
    "server/BanFeature.kt"               # 封禁维度（mac/qq）
    "server/DeepScanFeature.kt"          # 综合判定报告里的指纹字段
    "server/GuardHub.kt"                 # recordIdentity 参数
    "server/IntegrityFeature.kt"         # sessions API 里的指纹字段
)

is_privacy() {
    local rel="$1"
    for p in "${PRIVACY_SENSITIVE[@]}"; do
        [ "$rel" = "$p" ] && return 0
    done
    return 1
}

synced=0
skipped=0
while IFS= read -r f; do
    rel="${f#"$SRC"/}"
    if is_privacy "$rel"; then
        skipped=$((skipped + 1))
        continue
    fi
    mkdir -p "$(dirname "$DST/$rel")"
    cp "$f" "$DST/$rel"
    synced=$((synced + 1))
done < <(find "$SRC" -name '*.kt' -type f | sort)

echo "同步完成：复制 $synced 个共用文件，跳过 $skipped 个隐私差异文件。"
echo "下一步：cd $OSS_ROOT && ./build.sh build  验证编译。"
echo "若编译报「未解析的引用」且指向隐私差异文件，说明其签名在本仓改了 —— 手工对齐 OSS 侧。"
