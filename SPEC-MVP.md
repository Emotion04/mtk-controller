# StarTravel 重写 · MVP 规格文档

> 依据:对 `base..apk`(SecShell 加固 + NP 控制流混淆 8.0 + R8)的脱壳与逆向分析
> 目标设备:**天玑 9300+,Shizuku**;设计上探测式适配更多机型
> 生成日期:2026-09-08

---

## 0. 范围

### MVP 必做
| 优先级 | 功能 |
|---|---|
| P0 | **CPU 调频**:按簇设置频率上下限 + 调速器(governor) |
| P0 | **GPU 调频**:频率上下限 / 锁定 |
| P0 | **释放/恢复**:一键停止介入,回到系统默认调度 |
| P1 | 主页频率显示(每簇当前/最大频率、负载、温度) |
| P1 | 权限引导(Shizuku / root / adb) |

### MVP 不做(后续)
- CPU 频率、功率、电压/电流检测
- Auth / 授权校验(**移除**)
- 充电模块

### 可选小功能(能加则加)
见 §5。

---

## 1. 权限与运行模型

### 1.1 三种提权模式(原 App 均支持)

| 模式 | 说明 | 用户门槛 |
|---|---|---|
| **Shizuku** | 通过 Shizuku 拿到 system_server 权限,启动一个常驻 runtime 进程 | 低(推荐) |
| **root** | 直接以 root 起 runtime | 高 |
| **adb** | 用户手动执行一条 adb 命令激活 | 中 |

原 App 的 `RuntimeClient` 暴露了完整接口(类名未混淆,可直接照搬设计):

```kotlin
object RuntimeClient {
    const val SERVICE_POLL_INTERVAL_MS = 100L
    const val SERVICE_START_TIMEOUT_MS = 5000L

    fun ping(ctx): Boolean
    fun waitForService(ctx, timeoutMs): Boolean
    fun currentStatus(): RuntimeServiceStatus      // CONNECTED/DISCONNECTED/RESTARTING
    fun startCommands(ctx): ServiceStartCommands   // { adb: String, shell: String }
    fun startWithRoot(ctx): ServiceActivationResult // { success: Boolean, message: String }

    fun getSystemService(ctx, name): IBinder        // ← 拿任意系统服务
    fun newProcess(ctx, cmd: Array<String>, env: Array<String>, cwd: String): RuntimeProcess
    fun getSetting(ctx, type: RuntimeSettingsType, key: String, def: Int): String
    fun putSetting(ctx, type, key, value, def): Boolean
    fun readCpuTimes(ctx): ServiceCpuTimes
    fun readTemperatureSensors(ctx): List<...>
}
```

`RuntimeSettingsType` = `SYSTEM | SECURE | GLOBAL`。

### 1.2 无 Shizuku 时的降级

**调节功能必须 Shizuku 或 root**(写 sysfs/procfs 需提权)。降级策略:

- 读频率:`scaling_cur_freq` 等节点通常 world-readable,**无需提权即可显示**
- 调节按钮:**置灰 + 说明原因 + 跳转 Shizuku 安装/授权引导**
- 部分设置项(状态栏显秒等)可走 `WRITE_SECURE_SETTINGS`(adb 授权),不依赖 Shizuku

### 1.3 Shell 执行

原 App 做法(可复用):构造 `/system/bin/sh -c "<cmd>"` → `RuntimeClient.newProcess(...)` → 读 stdout,30s 超时。

```kotlin
// 伪代码
fun sh(cmd: String, timeoutMs: Long = 30_000): String? =
    RuntimeClient.newProcess(ctx, arrayOf("/system/bin/sh", "-c", cmd), arrayOf(), "/")
        .stdout.readText()
```

---

## 2. CPU 调频规格

### 2.1 探测(启动时)

```
shell: ls -1 /sys/devices/system/cpu/cpufreq/
```

得到 `policy0`, `policy4`, `policy7`, … 每个 policy 目录 = 一个**簇**。

每簇读取:

| 节点 | 含义 | 示例 |
|---|---|---|
| `related_cpus` | 簇成员 | `0 1 2 3` |
| `scaling_available_frequencies` | 可选频率(Hz) | `400000 500000 ... 3200000` |
| `cpuinfo_min_freq` / `cpuinfo_max_freq` | 硬件极值 | `400000` / `3200000` |
| `scaling_available_governors` | 可用调速器 | `schedutil performance powersave ...` |
| `scaling_governor` | 当前调速器 | `schedutil` |
| `scaling_cur_freq` | 当前频率 | `1800000` |

> **适配策略(原 App 的做法)**:不要硬编码 `policy0/4/7`。原 App 能在多机型运行,靠的**不是机型白名单,而是运行时探测** —— 扫描 `/sys/devices/system/cpu/cpufreq/` 目录、逐个读 `scaling_available_frequencies`,按实际结果渲染 UI。
>
> 全 App 唯一的机型特例是**厂商路径差异**,只有一处:`ThermalZoneReader` 检测 `Build.BRAND`/`Build.MANUFACTURER` 含 `vivo`/`iqoo` 时,把温控根目录从 `/sys/class/thermal` 换成 `/sys/devices/virtual/thermal`。即**默认路径 + 极少量厂商特例**。

### 2.2 设置频率上下限

```bash
# 顺序很重要:先 min 后 max,避免出现 min > max 被内核拒绝
echo <min_freq> > /sys/devices/system/cpu/cpufreq/policy<N>/scaling_min_freq
echo <max_freq> > /sys/devices/system/cpu/cpufreq/policy<N>/scaling_max_freq
echo <governor>  > /sys/devices/system/cpu/cpufreq/policy<N>/scaling_governor
```

**UI 约束**:min ≤ max;值必须取自 `scaling_available_frequencies`。

### 2.3 调速器

从 `scaling_available_governors` 动态渲染下拉框。天玑常见:

| governor | 语义 |
|---|---|
| `schedutil` | 默认,内核调度器驱动,平衡 |
| `performance` | 始终最高频 |
| `powersave` | 始终最低频 |
| `userspace` | 由用户空间指定(配合 min=max 锁频) |

### 2.4 释放 / 恢复

**必须先保存原始状态**(首次调节前),释放时写回:

```
保存:policyN → { scaling_min_freq, scaling_max_freq, scaling_governor }
释放:写回上述三个值
```

兜底(无保存记录时):写 `cpuinfo_min_freq` / `cpuinfo_max_freq` + `schedutil`。

> **注意**:MTK 的 powerhal / FPSGO 可能重写 `scaling_max_freq`,所以释放后也要**校验是否生效**。

### 2.5 MTK PowerHAL 协议(原 App 的方式,备选)

原 App **CPU 和 GPU 都走这条路**,不写 sysfs:

```
IBinder = RuntimeClient.getSystemService(ctx, "power_hal_mgr_service")
Parcel data, reply
data.writeInterfaceToken("com.mediatek.powerhalmgr.IPowerHalMgr")
data.writeInt(0); data.writeInt(0)
data.writeIntArray(int[])          // (命令 ID, 值) 成对
binder.transact(0x16, data, reply, 0)   // 申请:返回 handler ID
reply.readException(); ret = reply.readInt()
binder.transact(0x17, data, reply, 0)   // 释放:带上 handler ID
```

命令 ID 由 `format("0x%08X", p2 * 0x100 + p1)` 生成。申请成功后返回的 **handler ID 存进 SharedPreferences**(`gpu_handler` 等键),释放时用 `transact(0x17)` 带回去 —— 这就是「没有可释放的请求」的由来。

GPU 拉满实际传入(从 `s91` 恢复):

```
listOf("0x00c00000", "0", "0x00c00100", "0", "0x00c04000", <freq>, "0x00c04100", <freq>)
```

> ⚠️ 命令 ID 的**语义未验证**(需真机测试或 MTK 文档)。MVP 建议**优先走 sysfs 上下限**(更透明、可控),PowerHAL 作为"兼容层"后续再加。

---

## 3. GPU 调频规格

> **更正**:原 App **已有 GPU 功能** —— `GPU 拉满` / `释放 GPU`(走 PowerHAL,不走 sysfs),没有频率选择。我们要做的是把它扩展成**频率上下限**。dex 里**没有任何 gpufreq sysfs 路径**,说明原 App 完全依赖 PowerHAL;下面的 sysfs 路径用于新实现。

### 3.1 探测(按优先级)

| 优先级 | 判据 | 方案 |
|---|---|---|
| 1 | `/proc/gpufreqv2/fix_target_opp_index` 存在 | **GPUFreq v2**(新天玑,9300+ 大概率是这) |
| 2 | `/proc/gpufreq/gpufreq_opp_freq` 存在 | GPUFreq v1(老天玑) |
| 3 | `/sys/class/devfreq/mtk-mali/` 存在 | devfreq(Mali 标准框架) |
| 4 | `/sys/kernel/ged/hal/gpu_utilization` 存在 | GED(仅用于读利用率) |

### 3.2 读

```bash
# v2:OPP 表
cat /proc/gpufreqv2/gpu_working_opp_table
# v1:OPP dump
cat /proc/gpufreq/gpufreq_opp_dump
# 当前频率
cat /proc/gpufreq/gpufreq_var_dump        # 或 /sys/class/devfreq/mtk-mali/cur_freq
# 利用率
cat /sys/kernel/ged/hal/gpu_utilization
```

### 3.3 设置

**上下限(推荐,devfreq 方案)**:

```bash
echo <freq> > /sys/class/devfreq/mtk-mali/min_freq
echo <freq> > /sys/class/devfreq/mtk-mali/max_freq
echo <governor> > /sys/class/devfreq/mtk-mali/governor
```

**锁定(gpufreq v2)**:

```bash
echo <opp_index> > /proc/gpufreqv2/fix_target_opp_index   # 0 = 最高频
```

**上限(GED)**:

```bash
echo 1 > /d/ged/hal/custom_upbound_gpu_freq
```

### 3.4 释放

```bash
echo -1 > /proc/gpufreqv2/fix_target_opp_index    # v2 解锁
echo 0  > /proc/gpufreq/gpufreq_opp_freq          # v1 解锁
# devfreq:写回保存的原始 min_freq/max_freq
```

### 3.5 防覆盖(重要)

**MTK 的 GPU DVFS 由固件(MicroP / MFlexGraphics)管理**,厂商 power 服务 / GED / FPSGO 可能覆盖用户设置。

对策:**前台服务 + 定时重申**(社区模块普遍用 5 秒间隔),并在 UI 上明示"已被系统覆盖,已重新应用"。

---

## 4. 主页频率显示

原 App 的显示模型(`c20`)读取:

| 项 | 来源 |
|---|---|
| 每簇当前频率 | `cat .../policyN/scaling_cur_freq`(回退 `cpuinfo_cur_freq`) |
| 每簇最大频率 | `cat .../policyN/cpuinfo_max_freq`(回退 `scaling_max_freq`) |
| CPU 负载 | `/proc/stat`(两次采样算 delta) |
| 电池温度 | `ACTION_BATTERY_CHANGED` 的 `temperature` extra |
| 热状态 | `PowerManager.getCurrentThermalStatus()` |
| 核心数 | `Runtime.availableProcessors()` |

**刷新**:建议 1 秒(读 `/proc/stat` 计算需要间隔)。

---

## 5. 可选小功能清单(已从原 App 挖出)

| 功能 | 命令 | 依赖 |
|---|---|---|
| 状态栏显秒 | `settings put system status_bar_clock_seconds 1` | 提权 |
| 触控采样率 | `setprop debug.game_touch_sampling_rate 360` | 提权 |
| 触控上报率 | `setprop debug.touch.report_rate 360` | 提权 |
| 触控响应延迟 | `settings put system touch_scan_press_time_delay 300` | 提权 |
| 指针速度 | `settings put system pointer_speed 7` | 提权 |
| 帧插值 | `settings put system gamecube_frame_interpolation` | 提权 |
| SF 调优 | `setprop debug.sf.latch_unsignaled 1` 等一组 | 提权 |
| HWUI vsync | `setprop debug.hwui.disable_vsync true` | 提权 |

> **"禁止更新"**:原 App **没有**自动更新检查。若你指的是关闭系统 OTA 更新,需另找接口,待确认。

---

## 6. 架构建议

```
app/                    Compose UI(主页、CPU、GPU、设置)
core/runtime/           Shizuku/root/adb 桥接(照搬 RuntimeClient 设计)
core/sysfs/             节点探测 + 读写封装(探测式,不硬编码路径)
feature/cpu/            CPU 簇模型、上下限、governor、释放
feature/gpu/            GPU 探测、上下限/锁定、防覆盖重申
feature/tweaks/         §5 小功能(命令表驱动)
```

要点:
- **探测式**:所有节点路径在运行时发现,UI 按实际支持渲染
- **状态持久化**:保存原始值,保证"释放"可靠
- **命令表驱动**:小功能用数据描述(名称/命令/开关值),避免硬编码
- **前台服务**:防覆盖重申需要常驻(带通知)

---

## 7. 风险与待验证

| 风险 | 影响 | 缓解 |
|---|---|---|
| 无真机验证 | 节点路径可能与 9300+ 实际不符 | **探测式**设计;需你在设备上跑一次探测 |
| GPU 固件覆盖 | 设置被系统回滚 | 周期性重申 + UI 明示 |
| MTK PowerHAL 命令语义未验证 | 0x00c0xxxx 含义不明 | MVP 不依赖它,优先 sysfs |
| 多机型差异 | policy 布局/节点名不同 | 扫描目录 + 能力探测 |
| 系统 OTA 后节点变化 | 功能失效 | 探测失败时优雅降级 |

---

## 附录:反混淆名对照表(已确认)

| 混淆名 | 真实名 | 依据 |
|---|---|---|
| `y91` | `MtkCpuTuneController` | 协程调试字符串 vs 方法签名参数序 |
| `s91` | (CPU 扫描协程) | 含 `ls -1 /sys/.../cpufreq`、`scaling_available_frequencies` |
| `c20` | (CPU 显示模型构建) | 含 sysfs 频率读取 |
| `d20` / `ud` | 每核显示模型 | `c20` 返回值 |
| `lq` | (shell exec) | `/system/bin/sh -c` + `RuntimeClient.newProcess` |

其余原始类名(从协程字符串恢复):`AuthClient`、`AuthExtractionClient`、`ChargingExportSource`、`AppCornerStyle`、`ThemeMode`、`RawTemperatureSensor`。
