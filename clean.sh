#!/bin/bash
# 构建清理脚本
# 用法: bash clean.sh

set -e

echo "🧹 清理构建文件..."

cd "$(dirname "$0")"

if [ -f "gradlew" ] && [ -s "gradle/wrapper/gradle-wrapper.jar" ]; then
    chmod +x gradlew
    ./gradlew clean
    echo "✅ 清理完成"
else
    # gradle wrapper 不可用时手动删除构建目录
    echo "gradlew 不可用，手动清理构建目录..."
    rm -rf app/build .gradle build
    echo "✅ 手动清理完成"
fi