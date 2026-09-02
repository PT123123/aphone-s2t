# Android语音转写应用 - Just命令文件
# 使用方法: just [command]

# 构建项目
build type="debug":
    @bash build.sh {{type}}

# 安装APK到设备
install type="debug":
    @bash install.sh {{type}}

# 构建 + 安装
bi type="debug":
    @bash build.sh {{type}} && bash install.sh {{type}}

# 清理构建
clean:
    @bash clean.sh

# 查看设备
devices:
    @adb devices -l

# 查看日志
logcat:
    @adb logcat | grep "AphoneS2T\|SherpaStreamingAsr\|TranscriptionService"

# 清除应用数据
clear-data:
    @adb shell pm clear com.example.aphones2t

# 卸载应用
uninstall:
    @adb uninstall com.example.aphones2t

# 截图
screenshot file="screenshot.png":
    @adb shell screencap -p /sdcard/{{file}} && adb pull /sdcard/{{file}} . && adb shell rm /sdcard/{{file}}

# 显示帮助
help:
    @echo "🚀 Android语音转写应用 - Just命令"
    @echo ""
    @echo "  just build [debug|release]   构建APK"
    @echo "  just install [debug|release] 安装APK到设备"
    @echo "  just bi [debug|release]      构建+安装"
    @echo "  just clean                   清理构建"
    @echo "  just devices                 查看设备"
    @echo "  just logcat                  查看日志"
    @echo "  just clear-data              清除应用数据"
    @echo "  just uninstall               卸载应用"
    @echo "  just screenshot [file]       截图"
    @echo ""
    @echo "示例:"
    @echo "  just build          # 构建debug版本"
    @echo "  just build release  # 构建release版本"
    @echo "  just install        # 安装debug APK"
    @echo "  just bi release     # 构建并安装release"

# 默认命令
default:
    @just --list