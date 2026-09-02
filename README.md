# 实时语音转写应用

一个基于Android的实时语音转文字应用，使用sherpa-onnx流式Paraformer引擎实现离线语音识别。

## 主要特性

- 🎤 **实时语音转写**: 16kHz音频采集，实时识别中英双语语音
- 📱 **离线工作**: 完全离线识别，无需网络连接
- 🧠 **智能模型管理**: 支持多种ASR模型，自动检测模型类型
- ⏯️ **暂停/恢复**: 录音过程中可暂停和恢复
- 📝 **历史记录**: 保存转写文本和原始音频，支持播放、分享、复制
- 🔄 **断点续传**: 模型下载支持HTTP Range断点续传
- ✅ **SHA-256校验**: 确保模型文件完整性
- 🛠️ **自定义模型**: 支持添加自定义ASR模型

## 技术架构

### 骨架 (MeetingTranscriptionApp风格)
- AudioRecord音频采集
- 前台服务保持录音状态
- 广播机制传递识别结果
- ViewBinding实现UI

### ASR引擎 (sherpa-onnx)
- 流式Paraformer中英双语模型
- 支持Transducer模型
- 自动检测模型文件结构
- 端点检测优化

### 工程功能 (VoiceNotes风格)
- WorkManager下载管理
- HTTP Range断点续传
- SHA-256校验和原子化安装
- Room数据库存储历史记录

## 项目结构

```
app/src/main/java/com/example/aphones2t/
├── MainActivity.kt                 # 主界面，录音控制
├── TranscriptionService.kt         # 前台服务，音频采集和识别
├── ModelManagerActivity.kt         # 模型管理界面
├── HistoryActivity.kt              # 历史记录界面
├── asr/
│   └── SherpaStreamingAsr.kt       # ASR引擎封装
├── model/
│   ├── ModelManager.kt             # 模型管理器
│   ├── ModelCatalog.kt             # 模型目录
│   ├── ModelDownloadWorker.kt      # 下载工作器
│   └── ArchiveExtractor.kt         # 压缩包提取器
├── data/
│   ├── AppDatabase.kt              # Room数据库
│   ├── TranscriptEntity.kt         # 转录实体
│   ├── TranscriptDao.kt            # 数据访问对象
│   └── TranscriptRepository.kt     # 数据仓库
└── dialog/
    └── AddCustomModelDialog.kt     # 自定义模型对话框
```

## 模型管理

### 内置模型
- Paraformer中英双语流式模型
- 支持离线实时转写
- 自动下载和安装

### 自定义模型
- 支持添加自定义ASR模型URL
- 自动检测模型类型 (Paraformer/Transducer)
- 原子化安装和校验

## 构建和运行

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- 最低支持API 24 (Android 7.0)

### 构建步骤

#### 方法1: 使用Just命令（推荐）🚀
```bash
# 安装Just
# Windows: winget install casey.just
# macOS: brew install just
# Linux: cargo install just

# 构建项目
just build

# 安装到设备
just install

# 一键运行
just run

# 查看所有命令
just help
```

#### 方法2: 使用构建脚本
1. 克隆项目到本地
2. 确保Android SDK路径正确配置在 `local.properties`
3. 同步Gradle依赖
4. 连接Android设备或启动模拟器
5. 点击运行

### 关键配置文件
- `local.properties`: Android SDK路径配置
- `gradle.properties`: Gradle构建优化设置

## 使用方法

1. **首次使用**: 打开应用，进入"模型管理"，下载内置模型
2. **开始录音**: 确保模型就绪后，点击"开始录音"
3. **暂停/恢复**: 录音中可随时暂停或继续
4. **停止录音**: 点击"停止"结束识别，自动保存历史记录
5. **查看历史**: 点击菜单"历史记录"查看所有转写记录

## 权限说明

- `RECORD_AUDIO`: 录制音频
- `FOREGROUND_SERVICE`: 前台服务保持录音
- `POST_NOTIFICATIONS`: 显示通知
- `INTERNET`: 下载模型文件

## 技术细节

### 音频处理
- 采样率: 16kHz
- 通道: 单声道
- 编码: PCM 16位
- 缓冲区: 100ms音频块 (1600采样点)

### 识别引擎
- 引擎: sherpa-onnx v1.13.4
- 模型: 流式Paraformer
- 语言: 中英双语
- 端点检测: 三条检测规则优化

### 存储结构
```
/data/data/com.example.aphones2t/files/
├── models/                 # 模型文件
│   └── sherpa-onnx/
└── recordings/             # 录音文件
    ├── record_*.wav
    └── record_*.txt
```

## 性能优化

- 使用协程进行异步音频处理
- WorkManager管理后台下载任务
- Room数据库支持Flow响应式更新
- 音频处理在IO线程避免阻塞UI

## 故障排除

### 模型下载失败
- 检查网络连接
- 确认URL有效性
- 查看错误消息

### 识别不准确
- 确保模型正确安装
- 检查音频环境噪音
- 尝试更换模型

### 应用崩溃
- 查看logcat日志
- 检查存储空间
- 确认权限已授予

## 许可证

Apache-2.0 License

## 致谢

- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- MeetingTranscriptionApp: https://github.com/yumu908/MeetingTranscriptionApp
- VoiceNotes: https://github.com/Akbar02Work/VoiceNotes

## 更新日志

### v1.0.0 (2024-09-01)
- 初始版本
- 实时语音转写
- 模型管理
- 历史记录
- 自定义模型支持