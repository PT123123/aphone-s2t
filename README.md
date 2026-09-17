# 实时语音转写应用

一个基于Android的实时语音转文字应用，使用sherpa-onnx流式Paraformer引擎实现离线语音识别。

## 主要特性

- 🎤 **实时语音转写**: 16kHz音频采集，实时识别中英双语语音
- 📱 **离线工作**: 完全离线识别，无需网络连接
- 🧠 **模型管理**: 状态筛选（未下载 / 下载中 / 已下载）、语言筛选、体积排序，卡片可展开看下载详情
- 🐞 **失败原因可见**: 下载失败会显示阶段、HTTP 状态码、异常类型/消息、日志与堆栈摘要，可一键复制
- ⏯️ **后台下载**: WorkManager 前台任务，退出页面 / 切后台继续下载，支持暂停与断点续传
- 📝 **录音列表**: 实时录音 / 导入 / 离线转写统一一个列表，可播放、拖动进度
- ⏱️ **点文字跳时间**: 转写文本按句带时间轴，点某一句直接跳到录音对应时间
- 🔄 **断点续传**: 模型下载支持HTTP Range断点续传
- ✅ **SHA-256校验**: 确保模型文件完整性
- 🛠️ **自定义模型**: 支持添加自定义ASR模型

## 界面结构

主界面是三个页面（顶部 Tab）：

| 页面 | 内容 |
| --- | --- |
| **实时转写** | 当前模型卡片（可切换已安装模型）、实时转写文本、录音 / 暂停控制 |
| **录音** | 唯一的录音列表：播放、进度拖动、按句时间轴跳转、转写 / 生成时间轴 / 复制 / 分享 / 删除 |
| **模型** | 模型管理：状态与语言筛选、排序、下载、详情与失败原因、设为当前模型、删除、自定义模型 |

设置页的「管理模型 / 管理录音」直接跳到对应页面，不再有独立的模型管理 / 历史记录两套界面。

## 技术架构

### 骨架 (MeetingTranscriptionApp风格)
- AudioRecord音频采集
- 前台服务保持录音状态
- 广播机制传递识别结果
- ViewBinding实现UI

### ASR引擎 (sherpa-onnx)
- 流式Paraformer / Zipformer / CTC 模型自动识别
- 支持Transducer模型
- 端点检测切分句子，并记录每段起止毫秒（时间轴）
- 自动检测模型文件结构

### 工程功能 (VoiceNotes风格)
- WorkManager下载管理（前台任务，可后台继续）
- HTTP Range断点续传
- SHA-256校验和原子化安装
- 下载诊断持久化（DownloadDiagnostics，失败原因不丢）
- Room数据库存储录音与分段时间轴

## 项目结构

```
app/src/main/java/com/example/aphones2t/
├── MainActivity.kt                 # 主界面：实时转写 / 录音 / 模型 三个页面
├── ModelListController.kt          # 模型列表：筛选、卡片、进度详情、失败原因
├── RecordingsController.kt         # 录音列表：播放、时间轴跳转、转写 / 删除
├── TranscriptionService.kt         # 前台服务，音频采集和识别
├── SettingsActivity.kt             # 设置（存储 / 管理入口 / 关于）
├── CustomModelActivity.kt          # 批量粘贴自定义模型
├── asr/
│   └── SherpaStreamingAsr.kt       # ASR引擎封装（含分段与时间轴）
├── model/
│   ├── ModelManager.kt             # 模型状态编排（下载 / 暂停 / 取消 / 删除）
│   ├── ModelCatalog.kt             # 模型目录
│   ├── ModelDownloadWorker.kt      # 下载工作器（进度 / 速度 / 失败现场）
│   ├── DownloadDiagnostics.kt      # 下载诊断持久化
│   └── ArchiveExtractor.kt         # 压缩包提取器
├── data/
│   ├── AppDatabase.kt              # Room数据库（v3，含 segments 列）
│   ├── TranscriptEntity.kt         # 转录实体
│   ├── TranscriptSegment.kt        # 分段时间轴编解码
│   ├── TranscriptDao.kt            # 数据访问对象
│   └── TranscriptRepository.kt     # 数据仓库
├── dialog/
│   └── AddCustomModelDialog.kt     # 自定义模型对话框
└── utils/
    ├── FormatUtils.kt              # 体积 / 速度 / 时长格式化
    └── FileTranscriber.kt          # 离线转写音频文件（返回文本 + 时间轴）
```

## 模型管理

### 内置模型
- Paraformer中英双语流式模型
- 支持离线实时转写
- 自动下载和安装

### 下载失败怎么排查
卡片上的红色「失败原因」点开即是完整现场：

- 失败阶段（下载 / 解压 / 校验 / 安装 / 加载校验）
- HTTP 状态码、下载地址
- 异常类型、异常消息、底层 cause、堆栈摘要
- 失败时已下载多少字节
- 按时间排列的下载日志（进入页面 / 断点续传 / 解压 / 失败）
- 「复制详情」可整段发出来定位问题

常见原因：磁盘空间不足（下载前会预检并给出需要/可用空间）、GitHub 直链被墙或超时、服务器返回 404。

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

# 安装到手机（自动排除平板）
just install-phone

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

1. **首次使用**: 打开「模型」页，下载一个识别模型（下载会自动进行，退出页面也会继续）
2. **开始录音**: 回到「实时转写」页，顶部能看到当前使用的模型，点「开始录音」
3. **暂停/恢复**: 录音中可随时暂停或继续
4. **停止录音**: 点「停止」，结果自动进入「录音」页
5. **看录音**: 「录音」页可播放、拖动进度；点文本里的任意一句跳到对应时间
6. **换模型**: 「实时转写」页点「切换」，或去「模型」页设为当前模型

## 权限说明

- `RECORD_AUDIO`: 录制音频
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_DATA_SYNC`: 录音与模型下载的前台服务
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
- 模型: 流式 Paraformer / Zipformer / Zipformer-CTC / LSTM
- 端点检测: 三条检测规则，端点处切句并记录时间戳

### 存储结构
```
/data/data/com.example.aphones2t/files/
├── models/                        # 模型文件
│   └── sherpa-onnx/<model-id>/
│       ├── <version>/             # 已安装（含 installed.marker / manifest.json）
│       └── <version>.staging/     # 下载中（含 .part 分片、.paused 标记）
└── recordings/                    # 录音文件
    ├── record_*.wav
    └── record_*.txt               # 带时间轴的纯文本副本
```

## 性能优化

- 使用协程进行异步音频处理
- WorkManager管理后台下载任务
- 模型列表增量渲染，避免每次进度回调重建全部卡片
- 取消 / 删除的磁盘清理放到后台线程（主线程只切状态，不卡界面）
- UI 层不加载 ONNX 模型（只有真正识别时才构造 recognizer）
- Room数据库支持Flow响应式更新

## 故障排除

### 模型下载失败
- 在卡片上展开「详情」看失败阶段与 HTTP 状态码
- 检查网络（GitHub 直链在部分网络下会超时）
- 确认存储空间：卡片详情会显示需要 / 可用空间

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

## 发布（正式签名包 → GitHub Release）

```bash
just release              # 构建 release APK 到 dist/ 并自检（拒收 debug 证书）
just release bump aphone  # versionCode+1 / versionName 末段+1（只做 aphone；不传则 aphone+aread 都做）
just release publish aphone   # 四道闸校验 → 打 tag → gh release create → 真下载比 sha256
just release verify aphone    # 只自检 dist/ 里现成的包
```

固定资产名 `aphones2t-release.apk`，永久直链：

```
https://github.com/PT123123/aphone-s2t/releases/latest/download/aphones2t-release.apk
```

Obtainium 添加应用 → GitHub → `PT123123/aphone-s2t` → 跟踪 "latest release" 即可在线升级。

发布说明由 `tools/release.sh` 生成；若存在 `dist/changelog-<app>.md`（`aphone` / `aread`），
其内容会追加到 Release Notes 里。

## 更新日志

### v1.0.0 (2024-09-01)
- 初始版本
- 实时语音转写、模型管理、历史记录、自定义模型支持

### v1.0.1 (2026-09-18) — 界面改版
- 主界面重排为「实时转写 / 录音 / 模型」三页，录音列表从实时转写页移出
- 录音列表统一（原来「本次录音」与「历史记录」是两套，管理录音里看不到内容）
- 模型管理新增状态筛选（未下载 / 下载中 / 已下载）与数量角标
- 下载失败显示完整原因（阶段 / HTTP / 异常 / 日志 / 堆栈），支持复制与重试
- 下载进度新增速度、预计剩余、已下载/总量，卡片可展开查看全部信息
- 修复取消 / 删除卡顿：磁盘清理移出主线程，状态立即更新
- 实时转写页显示当前模型并可一键切换
- 新增分段时间轴：点文本任意一句跳到录音对应时间；老录音可「生成时间轴」
- 数据库 v3：新增 segments 列（Room 迁移 2→3）

