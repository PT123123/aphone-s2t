#!/bin/bash
# APK 安装脚本 - 调用 adb 安装到设备
# 用法: bash install.sh [debug|release]

set -e

BUILD_TYPE="${1:-debug}"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "📱 安装 APK 到设备 ($BUILD_TYPE)..."
echo ""

# ─── 检查 adb ───
if ! command -v adb &>/dev/null; then
    echo -e "${RED}❌ 未找到 adb 命令${NC}"
    echo "请将 Android SDK platform-tools 添加到 PATH 环境变量"
    echo "参考路径: C:\\Users\\ted\\AppData\\Local\\Android\\Sdk\\platform-tools"
    exit 1
fi

# ─── 检查设备连接 ───
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo -e "${RED}❌ 未找到已连接的 Android 设备${NC}"
    echo ""
    echo "请检查:"
    echo "  1. 设备已通过 USB 连接"
    echo "  2. 已开启「开发者选项」和「USB 调试」"
    echo "  3. 已在设备上允许此电脑的调试授权"
    echo ""
    echo "当前设备状态:"
    adb devices
    exit 1
fi

# ─── 确定 APK 路径 ───
if [ "$BUILD_TYPE" = "release" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

# ─── 检查 APK 是否存在 ───
if [ ! -f "$APK_PATH" ]; then
    echo -e "${YELLOW}⚠️  APK 不存在: $APK_PATH${NC}"
    echo "正在自动构建..."
    echo ""
    bash "$(dirname "$0")/build.sh" "$BUILD_TYPE"
    echo ""
fi

# ─── 安装 ───
echo -e "${YELLOW}📲 正在安装: $APK_PATH${NC}"
if adb install -r "$APK_PATH"; then
    echo ""
    echo -e "${GREEN}✅ 安装成功！${NC}"
    echo "包名: com.example.aphones2t"
    echo "启动: adb shell am start -n com.example.aphones2t/.MainActivity"
else
    echo ""
    echo -e "${RED}❌ 安装失败${NC}"
    exit 1
fi