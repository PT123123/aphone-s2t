# 🚀 Just命令快速参考

## 核心命令
```bash
just build [debug|release]     # 构建APK
just install [debug|release]   # 安装到设备
just run [debug|release]       # 构建+安装+运行
just clean                     # 清理构建
just help                      # 显示帮助
```

## 调试命令
```bash
just logcat                    # 查看日志
just devices                   # 查看设备
just screenshot [file]         # 截图
just screenrecord [time] [file] # 录屏
```

## 管理命令
```bash
just clear-data                # 清除数据
just uninstall                 # 卸载应用
```

## 安装Just
```bash
# Windows
winget install casey.just

# macOS
brew install just

# Linux
cargo install just
```

## 示例工作流
```bash
# 开发流程
just build
just install
just logcat

# 发布流程
just clean
just build release
just install release
just logcat
```

详细文档: see `JUST_USAGE.md`