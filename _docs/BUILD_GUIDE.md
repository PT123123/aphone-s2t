# 快速构建指南

## 🚀 推荐方式：Just命令

### 安装Just
```bash
# Windows
winget install casey.just

# macOS  
brew install just

# Linux
cargo install just
```

### 核心命令
```bash
just build [debug|release]     # 构建APK
just install [debug|release]   # 安装到设备
just run [debug|release]       # 构建+安装+运行
just clean                     # 清理构建
just help                      # 显示帮助
```

### 常用工作流
```bash
# 开发调试
just build
just install  
just logcat

# 发布构建
just clean
just build release
just install release

# 一键运行
just run
```

详细文档: see `JUST_USAGE.md`

## 📋 手动构建

### 环境检查

### Windows用户
```cmd
build.bat
```

### Linux/macOS用户
```bash
chmod +x build.sh gradlew
./build.sh
```

## 手动构建

### 1. 环境检查
- ✅ JDK 17已安装
- ✅ Android SDK已配置在 `local.properties`
- ✅ Android设备已连接或模拟器已启动

### 2. 同步依赖
```bash
./gradlew build --refresh-dependencies
```

### 3. 构建APK
```bash
# Debug版本
./gradlew assembleDebug

# Release版本
./gradlew assembleRelease
```

### 4. 安装应用
```bash
# 安装Debug版本
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 安装Release版本
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 首次使用

1. **启动应用**
2. **进入模型管理**
3. **下载内置模型** (约80MB)
4. **等待下载和校验完成**
5. **返回主界面开始录音**

## 常见问题

### 构建失败
```bash
# 清理后重新构建
./gradlew clean build
```

### 依赖同步问题
```bash
# 强制刷新依赖
./gradlew --refresh-dependencies build
```

### 模型下载失败
- 检查网络连接
- 确认存储空间充足 (>500MB)
- 查看错误消息并重试

## 性能优化

### 加速构建
在 `gradle.properties` 中已配置:
- ✅ 并行构建
- ✅ 构建缓存
- ✅ 配置缓存
- ✅ 增量编译

### 减少APK大小
```bash
# 只构建当前架构
./gradlew assembleDebug -Pandroid.ndk.filters="arm64-v8a"
```

## 调试模式

### 查看详细日志
```bash
adb logcat | findstr "AphoneS2T"
```

### 查看构建日志
```bash
./gradlew assembleDebug --info --stacktrace
```

## 发布准备

### 生成签名密钥
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias
```

### 配置签名
在 `app/build.gradle` 中添加签名配置:
```gradle
android {
    signingConfigs {
        release {
            storeFile file("my-release-key.jks")
            storePassword "your_password"
            keyAlias "my-alias"
            keyPassword "your_password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

### 构建Release版本
```bash
./gradlew assembleRelease
```

生成的APK位于: `app/build/outputs/apk/release/app-release.apk`

---

**提示**: 遇到问题请查看 `PROJECT_STATUS.md` 获取详细技术支持。