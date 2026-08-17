# RhythmicTouch

Xposed 模块，从系统全局音频流中捕捉音乐节奏并触发马达振动。让马达随着音律而舞动。

## 架构

```
┌─────────────┐     ┌─────────────┐
│  SystemUI   │     │  App (如Phira) │
│  AudioTrack │     │  Oboe/AAudio │
└──────┬──────┘     └──────┬──────┘
       │  Hook              │  Native GOT Hook
       ▼                    ▼
┌──────────────────────────────────┐
│       RhythmicEngine            │
│  32-band FFT → Beat Analysis    │
│  自适应阈值 + 模式匹配          │
└──────────────┬───────────────────┘
               │
               ▼
┌──────────────┐
│  VibratorDriver
│  8种振动模式  │
│  per-mode 频段│
└──────────────┘
```

### 双链路数据流

- **链路一：SystemUI** — Hook `com.android.systemui`，拦截全局 AudioTrack/AAudio 数据，获取系统级音频流（音乐播放器、媒体应用等）
- **链路二：App** — Hook 指定应用（如 Phira），通过 C++ 层 GOT Hook 拦截 `AAudioStream_write()` / Oboe 回调，直接在 native 层抓取 PCM 数据并做 FFT，通过 IPC 发送到 SystemUI 端的引擎

两条链路的数据最终汇入同一个 `RhythmicEngine`，由 `BeatAnalyzer` 进行 32 频段 FFT 分析，再根据能量分布、节拍检测和自适应阈值匹配到 8 种振动模式之一，驱动马达。

## 功能

- **32 频段 FFT 实时分析** — 基于 Oboe 原生音频采集，33ms 帧率
- **8 种振动模式** — 重长振、重短振、中敲击、中等击打、上升轻击、长脉动、情感脉动、柔和细节
- **Per-mode 频段配置** — 每个模式可独立设置触发频段范围（滑条）或手动勾选频段
- **多配置文件系统** — 支持创建多个配置文件，按应用自动切换（scope-based）
- **配置文件导入导出** — 单个 JSON 或批量 ZIP 打包
- **自适应阈值** — 根据历史能量动态调整触发灵敏度
- **Miuix UI** — Material You 动态取色，莫奈取色主题

## 环境要求

- Android 9+ (API 28+)
- LSPosed / EdXposed (Xposed Framework)
- `com.android.systemui` 作用域已勾选

## 安装

1. 从 [Releases](https://github.com/LyonHyrik/RhythmicTouch/releases) 下载 APK
2. 在 LSPosed 中安装并启用模块
3. 勾选 `com.android.systemui` 作用域（可选：勾选目标应用如 Phira 以启用 App 端原生采集）
4. 重启 SystemUI 或重启手机

## 编译

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`

## License

[GPLv3](LICENSE)
