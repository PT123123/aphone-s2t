# Just命令使用指南

## 安装Just

### Windows
```powershell
# 使用winget安装
winget install casey.just

# 或手动下载
# 访问: https://github.com/casey/just/releases
# 下载just.exe并添加到PATH环境变量
```

### macOS
```bash
# 使用Homebrew安装
brew install just
```

### Linux
```bash
# 使用cargo安装
cargo install just

# 或使用包管理器
# Ubuntu/Debian: sudo apt install just
# Arch: sudo pacman -S just
```

## 基本使用

### 构建和安装

```bash
# 构建debug版本
just build

# 构建release版本
just build release

# 安装debug APK到设备
just install

# 安装release APK到设备
just install release

# 一键构建并运行
just run

# 构建并运行release版本
just run release
```

### 清理和管理

```bash
# 清理构建文件
just clean

# 清除应用数据
just clear-data

# 卸载应用
just uninstall
```

### 调试功能

```bash
# 查看应用日志
just logcat

# 查看已连接的设备
just devices

# 截图
just screenshot

# 截图并指定文件名
just screenshot my_screen.png

# 录屏（默认30秒）
just screenrecord

# 录屏60秒并指定文件名
just screenrecord 60 my_recording.mp4
```

### 帮助信息

```bash
# 查看所有可用命令
just --list

# 查看详细帮助
just help
```

## 完整命令列表

| 命令 | 参数 | 描述 |
|------|------|------|
| `build` | `type="debug"` | 构建APK文件 |
| `install` | `type="debug"` | 安装APK到连接的设备 |
| `run` | `type="debug"` | 构建、安装并运行应用 |
| `clean` | - | 清理构建文件 |
| `clear-data` | - | 清除应用数据 |
| `uninstall` | - | 卸载应用 |
| `logcat` | - | 查看应用日志 |
| `devices` | - | 查看已连接的设备 |
| `screenshot` | `file="screenshot.png"` | 截取设备屏幕 |
| `screenrecord` | `time="30" file="screenrecord.mp4"` | 录制设备屏幕 |
| `help` | - | 显示帮助信息 |

## 使用示例

### 开发流程
```bash
# 1. 构建并安装debug版本
just build
just install

# 2. 查看日志
just logcat

# 3. 截图调试
just screenshot debug_state.png

# 4. 清除数据重新测试
just clear-data
just run
```

### 发布流程
```bash
# 1. 清理构建
just clean

# 2. 构建release版本
just build release

# 3. 安装到设备测试
just install release

# 4. 查看日志确保正常
just logcat
```

### 调试流程
```bash
# 1. 查看设备连接状态
just devices

# 2. 安装debug版本
just install

# 3. 运行应用并查看日志
just run
just logcat

# 4. 录制问题视频
just screenrecord 60 issue_demo.mp4

# 5. 截图特定状态
just screenshot error_state.png
```

## 常见问题

### 1. "just未找到"错误
**解决方案**: 确保已安装just并添加到PATH环境变量

### 2. "未找到gradlew文件"错误
**解决方案**: 确保在项目根目录执行命令

### 3. "未找到已连接的Android设备"错误
**解决方案**:
- 确保设备已连接到电脑
- 在设备上启用"开发者选项"和"USB调试"
- 运行 `just devices` 查看设备连接状态

### 4. 安装失败
**解决方案**:
- 确保APK文件已构建完成
- 检查设备存储空间是否充足
- 查看日志了解具体错误信息

## 高级用法

### 结合其他工具
```bash
# 构建并立即运行测试
just build && just install && adb shell am instrument -w com.example.aphones2t.test/androidx.test.runner.AndroidJUnitRunner

# 录制视频并截图关键帧
just screenrecord 30 demo.mp4 & sleep 10 && just screenshot keyframe.png

# 监控日志并保存到文件
just logcat > app_logs.txt
```

### 批量操作
```bash
# 清理并重新构建多个版本
just clean && just build debug && just build release

# 安装多个设备（如果有多个设备连接）
adb devices | grep "device$" | cut -f1 | while read device_id; do
    adb -s "$device_id" install -r app/build/outputs/apk/debug/app-debug.apk
done
```

## 性能优化

### 并行构建
```bash
# 修改gradle.properties已启用并行构建
# org.gradle.parallel=true
```

### 增量构建
```bash
# just命令自动使用Gradle的增量构建功能
# 无需额外配置
```

### 缓存利用
```bash
# 清理后重新构建会重新下载依赖
just clean
just build  # 会重新解析依赖
```

## 集成到IDE

### Android Studio
1. 打开设置 → Tools → External Tools
2. 添加新工具，配置如下：
   - Name: Just Build
   - Program: just
   - Arguments: build
   - Working directory: $ProjectFileDir$

3. 添加更多工具：
   - Just Install: `install`
   - Just Run: `run`
   - Just Logcat: `logcat`

### VS Code
添加到 `.vscode/tasks.json`:
```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Just Build",
      "type": "shell",
      "command": "just",
      "args": ["build"],
      "group": {
        "kind": "build",
        "isDefault": true
      }
    },
    {
      "label": "Just Install",
      "type": "shell",
      "command": "just",
      "args": ["install"]
    },
    {
      "label": "Just Run",
      "type": "shell",
      "command": "just",
      "args": ["run"]
    }
  ]
}
```

## 故障排除

### 查看详细错误信息
```bash
# 使用bash的详细模式
bash -x just build

# 或在justfile中添加调试输出
# 修改shebang为: #!/usr/bin/env bash -x
```

### 重置Gradle
```bash
# 删除Gradle缓存
rm -rf ~/.gradle/caches/

# 重新构建
just clean && just build
```

### 检查ADB连接
```bash
# 重启ADB服务
adb kill-server && adb start-server

# 查看设备状态
just devices

# 手动安装测试
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

**提示**: 使用 `just --list` 查看所有可用命令，`just help` 获取详细帮助信息。