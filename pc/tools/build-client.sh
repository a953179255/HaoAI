#!/usr/bin/env bash
# 装配免安装客户端：build/client/HaoAI-PC/
#   HaoAI-PC.exe        客户端壳（WebView2 窗口 + 托盘，真进程真图标）
#   engine\             引擎（jpackage 应用目录原样拷入，壳负责拉起与看护）
# 壳被占用/引擎重打失败会直接报错退出（packageExe 有锁探针守卫，不会删一半）。
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GRADLE="$USERPROFILE/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle"

# 壳必须**自包含**发布（把 .NET 桌面运行时打进目录）：客户端的价值就是双击即用，
# 框架依赖发布会在没装 Desktop Runtime 的机器上弹 "You must install .NET Desktop Runtime"
# （2026-10-05 在本机实测撞上）。引擎重打在实例被占用时会失败——那只说明"包已是现在这份"
# （锁探针保证不会删一半），引擎无代码变化时直接复用现有产物装配。
"$GRADLE" -p "$ROOT/pc" packageExe -q || echo "（引擎被占用，复用上次成功打包的产物继续装配）"
dotnet publish "$ROOT/pc/shell" -c Release -r win-x64 --self-contained true

OUT="$ROOT/build/client/HaoAI-PC"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -r "$ROOT/pc/build/package/HaoAI-PC" "$OUT/engine"
# publish 的**全部**顶层文件都要带：少一个 runtimeconfig.json 壳就秒退（实测事故）
cp "$ROOT/pc/shell/bin/Release/net10.0-windows/win-x64/publish/"* "$OUT/"
echo "客户端装配完成：$OUT"
echo "双击 $OUT/HaoAI-PC.exe 即用（窗口化界面，托盘常驻）。"
