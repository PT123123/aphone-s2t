#!/bin/bash
# Android 项目构建脚本
# 用法: bash build.sh [debug|release]

set -e

BUILD_TYPE="${1:-debug}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "🔨 构建 Android 项目 ($BUILD_TYPE)..."
echo ""

# ─── 检查 1: gradlew 可执行文件 ───
if [ ! -f "gradlew" ] || [ ! -f "gradlew.bat" ]; then
    echo -e "${RED}❌ 缺少 gradlew / gradlew.bat 文件${NC}"
    exit 1
fi
chmod +x gradlew 2>/dev/null || true

# ─── 检查 2: gradle-wrapper.jar ───
if [ ! -s "$WRAPPER_JAR" ]; then
    echo -e "${RED}❌ 缺少 gradle-wrapper.jar (位置: $WRAPPER_JAR)${NC}"
    echo ""
    echo "解决方法 (任选其一):"
    echo ""
    echo "  方法1 [推荐]: 用 Android Studio 打开项目"
    echo "    打开后 AS 会自动下载 Gradle 并生成 wrapper jar"
    echo ""
    echo "  方法2: 用已安装的 gradle 生成"
    echo "    gradle wrapper --gradle-version 8.9"
    echo ""
    echo "  方法3: 从其他 Android 项目复制 gradle-wrapper.jar"
    echo "    (版本需与 gradle-8.9-bin.zip 兼容)"
    echo ""
    echo "文件来源参考:"
    echo "  https://services.gradle.org/distributions/gradle-8.9-bin.zip"
    exit 1
fi

# ─── 检查 3: Java 环境 ───
if ! command -v java &>/dev/null; then
    echo -e "${RED}❌ 未找到 Java，请安装 JDK 17 并配置 JAVA_HOME${NC}"
    exit 1
fi

# ─── 执行构建 ───
echo -e "${YELLOW}⏳ 正在构建 ${BUILD_TYPE} 版本...${NC}"
echo ""

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

echo ""
if [ -f "$APK_PATH" ]; then
    echo -e "${GREEN}✅ 构建成功: $APK_PATH${NC}"
    ls -lh "$APK_PATH"
else
    echo -e "${RED}❌ 构建失败: 未生成 APK 文件${NC}"
    exit 1
fi