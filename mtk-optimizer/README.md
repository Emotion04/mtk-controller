# MTK Controller

天玑(MediaTek Dimensity)机型的 CPU / GPU 频率调节工具。

通过 **Shizuku** 拿到提权身份,用 MTK PowerHAL 下发频率上下限 —— **不需要 root**。

## 功能

| 模块 | 说明 |
|---|---|
| **CPU 调频** | 按簇设置频率**上下限**与调速器;支持双滑块 / 单滑块 / 分段进度条三种控件 |
| **GPU 调频** | 自动探测 gpufreq v2 / v1 / devfreq / GED 接口 |
| **方案** | 把一组上下限存成方案,一键套用 |
| **释放** | 一键撤销调频,回到系统默认调度 |
| **实用工具** | 触控优化、全局插帧、旋转建议、高温降亮度恢复 |
| **首页** | 卡片式仪表盘(CPU 频率曲线、簇状态、方案、快速设置),卡片可拖动排序 |
| **诊断** | 全量展示探测结果,便于在真机上核对 |

## 环境要求

- Android 12+ (minSdk 33)
- **MTK 天玑机型**(非 MTK 设备只能看,不能调)
- **Shizuku**(adb 模式即可;root 模式可额外解锁调速器修改)

## 构建

```bash
# 需要 JDK 17 + Android SDK(compileSdk 37)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包(未签名)
```

构建产物:`app/build/outputs/apk/<variant>/`

## 技术栈

Kotlin + Jetpack Compose + Material 3 · MVVM(UI / Domain / Data 三层)·
DataStore · Navigation Compose · AGP 9.2.1 / Gradle 9.4.1 / compileSdk 37

```
app/src/main/java/magicau/mtkcontroller/
├── data/          privilege(Shizuku) · powerhal · sysfs · cpu · gpu · tweaks · settings
├── domain/model/  CpuCluster · Profile · GpuInfo · HomeCard · UiPreferences
├── feature/       cpu · gpu · profile · home · tweaks · diag · notifications · thermal
└── ui/            theme · navigation · components · screen
```

## 实现要点

- **探测式适配**:不硬编码 `policy0/4/7`,运行时扫描 `cpufreq` 目录,二/三/四丛集都能识别
- **PowerHAL**:`transact(0x16)` 申请 → 返回 handler;`transact(0x17)` 释放。
  命令 ID = `format("0x%08X", policyIndex * 0x100 + base)`,
  base 分别为 min `0x400000` / max `0x404000` / thermalMin `0x408000` / thermalMax `0x40c000`。
  **给 min 和 max 传不同值即得到区间**(而非锁定)
- **提权**:Shizuku `UserService`(daemon),shell 命令在其中以 uid 0 / 2000 执行

## 说明

- 部分 `setprop debug.*` 属性是否生效取决于 ROM 实现
- 无 root 时 `cpuset` / `stune` / `uclamp` 等调度级节点不可写,相关功能未包含
- GPU 调频受厂商固件影响,设置可能被覆盖

## 继续开发

产品与工程契约见仓库根目录的 [`docs/design.md`](../docs/design.md)。其中明确了 CPU 分段条的三种手势、单值与区间的 PowerHAL 请求映射、UI token、About 页面和迁移检查表。当前真实状态和未解决问题以 [`docs/handoff.md`](../docs/handoff.md) 为准。

## 许可

个人项目,未指定开源许可。
