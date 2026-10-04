# 云声（netease-music-lite）

云声是一个面向 Android 的第三方音乐客户端项目，包含完整的 Gradle 源码结构以及 Full arm64 测试安装包。

> 本项目与网易公司及网易云音乐官方无隶属或背书关系。登录、曲库、播放地址等能力依赖第三方服务接口，接口变化可能影响使用。

## 下载

- **Full arm64 APK（云声 0.2.29）**：[GitHub Releases](https://github.com/wang111928/netease-music-lite/releases/latest)
- 包名：`com.litemusic.app.full.debug`
- 最低 Android 版本：Android 9（API 28）
- ABI：`arm64-v8a`
- APK 大小：23,903,061 字节（约 22.8 MiB / 23.90 MB）
- SHA-256：`FAC75A7E2EEBDCB0FBA40CB671D1EE1EBE5BB4CDE6CDD02CCCCA82178D53A971`

这是用于测试分发的 **debug 签名** APK。Android 可能显示安装确认或安全提示。它不能直接覆盖由其他签名密钥签署的同包名应用。

## 项目结构

项目由 `app`、`shared`、`network-core`、`data-core`、`design-system`、`player-core`、`lyric-engine` 七个 Gradle 模块组成。职责和依赖关系见[架构与源码说明](ARCHITECTURE.md)。

## 本机构建

需要 JDK 17 和 Android SDK 36。Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleFullDebug
```

APK 输出在 `app/build/outputs/apk/full/debug/`。本项目的设备分发配置只打包 arm64-v8a；此仓库不提供 Min 或 x86 安装包。版本说明见 [`docs/releases/v0.2.29.md`](docs/releases/v0.2.29.md)。

## 许可与数据范围

仓库公开用于查看项目源码和构建方式，但目前**没有授予额外的源码再分发或商用许可**。第三方依赖按其各自许可证使用。

为保护用户隐私，公开源码不包含本地开发配置、浏览器资料、设备诊断记录、个人歌单内容或从个人截图提取的识别样本。测试代码中引用这些私有样本的专用回归测试也未公开；一般模块源码及其余测试代码保留。
