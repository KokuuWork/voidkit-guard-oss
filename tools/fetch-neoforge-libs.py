#!/usr/bin/env python3
"""NeoForge E2E 辅助：预置 installer 需要的 libraries/。

背景：NeoForge installer 只认 maven.neoforged.net（国内被污染，代理 502 全超时），
而 Gradle（MDG）构建时有 mirror.init.gradle 重写到 neoforged.forgecdn.net，
全部工件已在 ~/.gradle/caches 里。本脚本把 install_profile.json 声明的库
从 Gradle 缓存提取（或从 forgecdn 镜像补齐）到服务端目录的 libraries/，
再跑 installer 即全部本地命中，不再联网。

用法：python tools/fetch-neoforge-libs.py <installer.jar> <server_dir>
"""
import json
import os
import shutil
import sys
import subprocess
import zipfile

GRADLE_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
MIRROR_BASE = "https://neoforged.forgecdn.net"  # 不含 /releases，避免与 art.url 里的重复
PROXY = "http://127.0.0.1:63341"


def fetch(url: str, dst: str) -> None:
    # 用 curl 子进程而不是 urllib：urllib 的 ProxyHandler 在本机对 https
    # CONNECT 支持有问题（WinError 10061），curl 同代理实测可用。
    subprocess.run(
        ["curl", "-x", PROXY, "--tlsv1.2", "-sSL", "--retry", "3", "--retry-all-errors", "-o", dst, url],
        check=True,
    )


def main() -> int:
    installer, server_dir = sys.argv[1], sys.argv[2]
    os.makedirs(os.path.join(server_dir, "libraries"), exist_ok=True)

    with zipfile.ZipFile(installer) as z:
        profile = json.loads(z.read("install_profile.json"))

    ok = miss = 0
    for lib in profile["libraries"]:
        name = lib["name"]                      # group:artifact:version[:classifier][@zip]
        parts = name.split(":")
        g, a = parts[0], parts[1]
        v = parts[2].split("@")[0]              # neoform 的 @zip 是打包形式不是版本
        art = lib.get("downloads", {}).get("artifact", {})
        path = art.get("path") or f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"
        dst = os.path.join(server_dir, "libraries", path)
        if os.path.exists(dst):
            ok += 1
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        # 1) Gradle 缓存（files-2.1/<group>/<artifact>/<version>/<sha1>/<file>）
        cache_dir = os.path.join(GRADLE_CACHE, g, a, v)
        copied = False
        if os.path.isdir(cache_dir):
            for root, _dirs, files in os.walk(cache_dir):
                for f in files:
                    if f.endswith((".jar", ".zip")):
                        shutil.copy(os.path.join(root, f), dst)
                        copied = True
                        break
                if copied:
                    break
        if copied:
            ok += 1
            continue
        # 2) forgecdn 镜像（URL 主机替换；art.url 已是完整文件 URL，不能再拼 path）
        try:
            url = art.get("url") or ("https://maven.neoforged.net/releases/" + path)
            url = url.replace("https://maven.neoforged.net", MIRROR_BASE)
            fetch(url, dst)
            ok += 1
        except Exception as e:                  # noqa: BLE001
            print(f"  [miss] {name}: {e}")
            miss += 1

    print(f"[fetch] libraries 就位 {ok}，缺失 {miss}")
    return 1 if miss else 0


if __name__ == "__main__":
    sys.exit(main())
