# Gradle Wrapper 设置说明

## 问题说明
项目缺少 `gradle-wrapper.jar` 文件，导致无法运行 Gradle 构建命令。

## 解决方案

### 方法1: 使用 Android Studio (推荐)
1. 用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. Android Studio 会自动下载并生成 `gradle-wrapper.jar`

### 方法2: 手动下载
1. 访问 Gradle Wrapper 官方页面下载 jar 文件
2. 或者从其他 Android 项目复制 `gradle-wrapper.jar`
3. 将文件放置在 `gradle/wrapper/` 目录下

### 方法3: 使用命令行 (需要安装 Gradle)
```bash
# 安装 Gradle (如果未安装)
# Windows: 使用 SDKMAN 或手动下载
# macOS: brew install gradle  
# Linux: sudo apt install gradle

# 在项目根目录运行
gradle wrapper --gradle-version 8.9
```

### 方法4: 修复现有问题
检查 `gradle/wrapper/` 目录结构：
```
gradle/
├── wrapper/
│   ├── gradle-wrapper.jar    # 缺失此文件
│   └── gradle-wrapper.properties
└── wrapper/
```

## 临时解决方案

如果需要快速测试 just 命令功能，可以先使用以下方式：

### 测试 just 命令基本功能
```bash
# 查看 just 版本
just --version

# 查看可用命令
just --list

# 查看 help 命令 (不需要 gradle)
just help
```

### 使用 Android Studio 构建
1. 打开 Android Studio
2. 点击 "Build" -> "Make Project"
3. 构建成功后，APK 文件会在 `app/build/outputs/apk/debug/app-debug.apk`

## 验证修复

修复后，可以使用以下命令验证：

```bash
# 测试 gradlew
./gradlew --version

# 使用 just 命令构建
just build

# 安装到设备
just install
```

## 相关资源

- Gradle 官方文档: https://docs.gradle.org/
- Gradle Wrapper 文档: https://docs.gradle.org/current/userguide/gradle_wrapper.html
- Android Gradle 插件: https://developer.android.com/studio/build

## 注意事项

- `gradle-wrapper.jar` 是 Gradle Wrapper 的核心文件，必须存在
- 文件大小通常约 60KB
- 不同 Gradle 版本的 jar 文件可能不兼容
- 确保网络连接正常，Gradle 首次运行会下载依赖