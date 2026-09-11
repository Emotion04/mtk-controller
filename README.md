# MTK God

MediaTek SoC 的 CPU 与 GPU 频率限制工具（Shizuku，无需 root）。

本仓库是 Android 应用工程：在该目录下构建。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk 37`）。在本目录运行：

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk    —— 日常开发
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk —— 交付用
```

release 包开混淆与资源压缩，签名读 `signing/keystore.properties`。
**`signing/` 被 gitignore**，新克隆的仓库里没有它，此时 `assembleRelease`
会用 debug 签名顶位：能构建，但装不到官方包上（签名不同）—— 这是有意的。
密钥不能换：换了签名的包装不到已装的包上，用户必须先卸载。
