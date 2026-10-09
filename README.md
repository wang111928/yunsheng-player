# 云声

云声是一个面向 Android 的第三方wangyi音乐客户端项目，提供完整的 Gradle 源码结构、架构说明和 Full arm64 安装包。

> 本项目与网易公司及网易云音乐官方无隶属或背书关系。登录、曲库、播放地址等能力依赖第三方服务接口，接口变化可能影响使用。

## 下载

- **Full arm64 APK（云声 0.2.38）**：[GitHub Releases](https://github.com/wang111928/yunsheng-player/releases/latest)
- 包名：`com.litemusic.app.full.debug`
- 最低 Android 版本：Android 9（API 28）
- ABI：`arm64-v8a`
- APK 大小：19,632,999 字节（约 18.72 MiB / 19.63 MB）
- SHA-256：`740A718219ED689CAD41FE0216044A823FDD2E1F4763C0A78A8F11E6008A233D`

这是用于测试分发的 **debug 签名** APK。Android 可能显示安装确认或安全提示。它不能直接覆盖由其他签名密钥签署的同包名应用。

## 本版变化

- 完整缓存的歌曲支持断网和重启应用后播放；未缓存完整的歌曲在离线时灰显，并显示错误的原因。
- 音频缓存保存在应用私有持久目录，不设应用容量上限、不自动淘汰；实际空间受设备剩余存储限制。清理内容缓存只清理封面与歌词；卸载或清除应用数据会删除音频。
- 修正自然结束后的自动续播、播放状态显示，以及应用底部小播放条；上一首始终切到队列前一曲。
- 设置增加“软件更新 → 自动更新”，按需读取本仓库最新正式 Release、下载安装包并交给 Android 系统确认安装。首次更新可能需要允许应用安装来源。

更新与源码对应关系、验证范围见 [0.2.38 版本说明](docs/releases/v0.2.38.md)。历史版本保留在 Releases 中。

## 项目结构

项目由 `app`、`shared`、`network-core`、`data-core`、`design-system`、`player-core`、`lyric-engine` 七个 Gradle 模块组成。职责和依赖关系见[架构与源码说明](ARCHITECTURE.md)。

## 本机构建

需要 JDK 17 和 Android SDK 36。Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleFullDebug
```

APK 输出在 `app/build/outputs/apk/full/debug/`。本项目的设备分发配置只打包 arm64-v8a；此仓库不提供 Min 或 x86 安装包。

应用更新器识别正式 Release 附件 `Yunsheng-full-v版本-vc版本号-arm64-v8a.apk`；维护者应使用递增的 versionCode 和同一签名发布新版本。自行构建生成的其他签名 APK 无法覆盖当前分发包。

## 许可与数据范围

仓库公开用于查看项目源码和构建方式，但目前**没有授予额外的源码再分发或商用许可**。第三方依赖按其各自许可证使用。

为保护用户隐私，公开源码不包含本地开发配置、浏览器资料、设备诊断记录、个人歌单内容或从个人截图提取的识别样本。测试代码中引用这些私有样本的专用回归测试也未公开；一般模块源码及其余测试代码保留。
