<div align="center">

<img src="docs/icon.png" width="128" />

# 🎵 RhythmicTouch 音律触感

**🎶 让马达随着音律而舞动 🎶**

*🔊 LSPosed 模块 · 从系统全局音频流中捕捉音乐节奏并触发马达振动*

[![🔨 Build](https://github.com/LyonHyrik/RhythmicTouch/actions/workflows/build.yml/badge.svg)](https://github.com/LyonHyrik/RhythmicTouch/actions/workflows/build.yml)
[![📜 License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![📱 API](https://img.shields.io/badge/API-28%2B-brightgreen.svg)](https://developer.android.com/about/versions/pie)

</div>

---

## 🏗️ 架构

```
┌──────────────┐         ┌──────────────────┐
│   SystemUI   │         │  App (如 Phira)   │
│  AudioTrack  │         │  Oboe / AAudio    │
└──────┬───────┘         └────────┬─────────┘
       │  Xposed Hook             │  C++ Native GOT Hook
       ▼                          ▼
┌─────────────────────────────────────────────┐
│             RhythmicEngine                  │
│  32-band FFT  →  Beat Analysis              │
│  Adaptive Threshold  +  Mode Matching       │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│            VibratorDriver                   │
│  8 种振动模式 · Per-mode 频段过滤           │
└─────────────────────────────────────────────┘
```

### 🔗 双链路数据流

| 🔹 链路 | 🎯 Hook 目标 | 🎤 采集方式 | 📊 数据 |
|---------|-------------|-----------|---------|
| **SystemUI** | `com.android.systemui` | Xposed Hook AudioTrack / AAudio | 🎵 全局媒体音频流 |
| **App** | `org.flos.phira` 等 | C++ GOT Hook `AAudioStream_write()` | 🎮 应用内 Oboe PCM 原始数据 |

两条链路的数据最终汇入同一个 `RhythmicEngine`，由 `BeatAnalyzer` 进行 32 频段 FFT 分析，再根据能量分布、节拍检测和自适应阈值匹配到振动模式，驱动马达。

## ✨ 功能

- 🎛️ **32 频段 FFT 实时分析** — 基于 Oboe 原生音频采集，33ms 帧率
- 📳 **8 种振动模式** — 💥重长振 / 💢重短振 / 🎵中敲击 / ⚡中等击打 / 🎶上升轻击 / 🔊长脉动 / 🔄情感脉动 / ✨柔和细节
- 🎚️ **Per-mode 频段配置** — 每个模式独立设置触发频段范围或手动勾选频段
- 📁 **多配置文件系统** — 创建多个配置文件，按应用自动切换（scope-based）
- 📤 **配置文件导入导出** — 单个 JSON 或批量 ZIP 打包分享
- 🧠 **自适应阈值** — 根据历史能量动态调整触发灵敏度
- 🎨 **Miuix UI** — Material You 动态取色主题

## 📋 环境要求

- 🤖 Android 9+ (API 28+)
- 🔧 [LSPosed](https://github.com/LSPosed/LSPosed) / EdXposed
- ✅ `com.android.systemui` 作用域已勾选

## 📱 支持设备与系统

| 📱 设备 | 🤖 系统版本 | 📝 状态 |
|---------|-----------|---------|
| OPPO Reno8 Pro | ColorOS 14.0 | ✅ 已验证 |
| Nothing Phone (3) | Android 16 | ✅ 已验证 |

> 💡 欢迎提交新设备测试结果！其他机型请自测。

## 📥 安装

1. ⬇️ 从 [Releases](https://github.com/LyonHyrik/RhythmicTouch/releases) 下载 APK
2. 📦 在 LSPosed 中安装并启用模块
3. ☑️ 勾选 `com.android.systemui` 作用域
4. 🎮 （可选）勾选目标应用如 Phira 以启用 App 端原生采集
5. 🔄 重启 SystemUI 或重启手机

## 🔨 编译

```bash
./gradlew assembleDebug
```

📦 APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`

## 👤 作者

- 🌐 博客：[https://lyonhyrik.github.io/](https://lyonhyrik.github.io/)
- 📱 酷安：[LyonHyrik](https://www.coolapk.com/u/24533526)
- 💻 GitHub：[LyonHyrik](https://github.com/LyonHyrik)

## 📜 License

[GNU Affero General Public License v3.0](LICENSE)
