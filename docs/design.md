# MTK Optimizer 设计总览

这份文档是项目迁移和继续开发时的产品与工程契约。它描述稳定的边界、用户可感知的交互和必须保留的行为；具体实现以源码和 `handoff.md` 的最新事实为准。

## 产品定位

MTK Optimizer 是一个面向 MediaTek Dimensity 设备的本地调节工具。它通过 Shizuku 访问 PowerHAL，在不要求 root 的前提下设置 CPU 频率请求，同时把设备差异、权限差异和无法读回的情况明确展示给用户。

产品优先级只有三层：

1. CPU 单值锁频必须可靠、可重复释放。
2. CPU 区间必须表达为独立的下限和上限，不得被 UI 手势方向或资源数组顺序破坏。
3. 其他能力只能在探测到设备支持并能解释其语义后出现。

原参考 App 只有单值锁频。它不是区间实现的来源；它只提供可靠的单值请求形状和句柄生命周期参考。

## 工程边界

```text
feature/        Compose 页面和 ViewModel，只处理用户意图与 UI 状态
domain/         Android 无关的数据模型与状态
data/cpu/       CPU 策略、频点归一化、单值/区间请求构造
data/powerhal/ PowerHAL 资源 ID、Parcel 协议、句柄生命周期
data/privilege/ Shizuku 权限和 daemon UserService
data/sysfs/    只读探测、写入、批量读回
data/profile/  配置持久化
data/diag/     能力报告和设备证据
ui/theme/      颜色、形状、间距和主题模式
ui/components/ 可复用导航、卡片、空状态和反馈组件
di/            AppContainer，唯一组合根
```

依赖方向固定为 `feature -> domain/data`、`data -> domain`；`domain` 不依赖 Android、Compose 或 PowerHAL。页面不得直接调用 `PowerHal`、`Sysfs` 或 `PrivilegeManager`。

## CPU 控制模型

### 用户模型

每个 CPU policy 都有一个 `FrequencySelection`：

```text
Idle             没有选择
Point(value)     单值，表示锁死该频点
Range(low, high) 区间，low <= high
```

频点始终来自该 policy 的 `availableFreqs`。所有外部输入先经过 `normalize(a, b)`，输出 `min(a,b), max(a,b)`；任何代码都不能根据“先点左边”或“手指向左拖动”决定上下限。

### 分段进度条契约

分段条是离散频点选择器，不是连续浮点 Slider。每个 segment 对应一个频点，显示已知的离散 OPP 表。

| 手势 | 状态变化 | 是否立即写入设备 |
|---|---|---|
| 单击一个点 | `Point(i)` | 由页面的“应用”动作统一写入 |
| 在已有点选择后再单击 | `Range(min(anchor,i), max(anchor,i))` | 由页面的“应用”动作统一写入 |
| 水平拖动 | `Range(min(start,end), max(start,end))` | 拖动结束后提交选择，仍由“应用”统一写入 |
| 拖动取消 | 保留提交前状态 | 否 |

拖动中的高亮是预览态，不能修改持久化设置。点击锚点只存在于当前 policy 的控件实例；切换页面、刷新扫描或应用成功后清空锚点。`onRange(index, lowIndex, highIndex)` 是组件唯一输出，调用方再次归一化以防未来组件替换。

### 请求映射

```text
Point(v): MIN_CLUSTER_n=v, MAX_CLUSTER_n=v,
          MIN_HL_CLUSTER_n=v, MAX_HL_CLUSTER_n=v
Range(lo,hi): MIN_CLUSTER_n=lo, MAX_CLUSTER_n=hi
```

单值四资源是参考 App 已验证的锁频形状。区间只使用软 `MIN/MAX`，不发送硬限制资源，否则区间会变成硬锁或留下无法解释的限制。硬资源不可用时，单值路径必须明确记录“软请求已发送、硬限制未确认”，不能假称已经锁死。

`CpuControl` 负责把 `ClusterSetting` 转成请求数组，`PerfLockController` 负责 release-before-acquire、串行化和句柄持久化，`PowerHal` 只负责线协议。三层不能互相越权。

### 应用事务

```text
读取当前句柄
  -> 释放旧句柄；失败则中止，不覆盖旧句柄
  -> 等待 oneway release 排队完成
  -> 发送一次完整的 CPU 请求数组
  -> 保存新句柄和最后一次设置
  -> 应用调速器（可选，失败不影响频率请求）
  -> 400 ms / 1.5 s / 3 s 读回并记录
```

任何“只 acquire 不 release”“release 失败仍清空句柄”“按 policy 名称硬编码 index”的改动都违反契约。

### 范围问题的证据边界

PowerHAL 的软区间在协议层是合法的，但目标设备无法读取 `scaling_min_freq` 和 `scaling_max_freq`。因此：

- `handler > 0` 只能证明服务接受了请求；
- `scaling_cur_freq` 只能观察当前动态频率，不能证明上下限；
- 区间是否真正生效必须由外部 CPU 监控或 adb 读回验证；
- 未验证前不得把“范围无效”解释为 UI、ID 或内核中的某一个原因。

## PowerHAL 与权限架构

`RuntimeUserService` 是 PowerHAL 请求的稳定拥有者。应用进程通过 `IRuntimeService` 请求 daemon 执行 acquire/release；同一 daemon 持有并释放句柄。这样 UI 生命周期、应用进程重建和 Shizuku 连接状态不会把请求归属分散到不同进程。

PowerHAL 资源以 `PowerHalResource` 语义模型描述，至少包含 `id`、单位、软/硬类别、可读回节点和风险等级。`docs/powerhal-resources.md` 是候选清单，不是自动开放的功能列表；每新增资源必须有设备探测、合法值范围和读回证据。

## 状态与并发

ViewModel 对外只发布不可变 `UiState`。设备操作全部在 `Dispatchers.IO`，页面只收集状态和发送意图。CPU 应用、释放、紧急恢复和轮询共享同一个 `CpuControl`，由内部 `Mutex` 串行化。

轮询服务只重放 `CpuControlStore.lastApplied`，不复制请求逻辑。没有设置、UserService 未绑定或 PowerHAL 不可用时停止轮询并写日志。

## UI 设计系统

视觉方向是“安静的工具台”：浅色使用暖灰背景和白色卡片，深色使用近黑背景和略亮的卡片；颜色用于状态、导航和可操作重点，不用大面积渐变或装饰性图形。

### 基础 token

| token | 浅色 | 深色 |
|---|---|---|
| background | `#F0F0F2` | `#121212` |
| surface/card | `#FFFFFF` | `#1E1E1E` |
| surfaceVariant | `#F0EFED` | `#262626` |
| primary text | `#1B1B1A` | `#E8E8E6` |
| secondary text | `#6B6B68` | `#9E9E9A` |
| error | `#B3261E` | `#F2B8B5` |

卡片使用 18 dp 圆角和轻微阴影，不使用外轮廓；页面区块保持平面布局，重复对象才使用卡片。CPU、GPU、方案、设置分别使用稳定的强调色，但中性色背景不随强调色改变。主题模式、调色板和底栏样式是独立设置。

所有页面根部必须提供 `Surface(color = background, contentColor = onBackground)`，否则 Compose 默认 `LocalContentColor` 会让深色主题中的未显式着色文本变黑。

### 导航与反馈

底部浮动胶囊导航栏包含首页、CPU、GPU、方案、设置。它应避开系统导航区，所有可滚动页面预留 110 dp 底部空间。状态反馈优先放在触发动作附近：应用结果、权限原因、读回不可用和紧急恢复结果都使用同一套 `InlineFeedback`，颜色表达成功、警告、失败和无法判断。

### About 页面契约

关于界面是资料卡，不是营销页。它应展示：应用名称、版本名、versionCode、包名、协议与开源材料链接、Shizuku/Android 要求、当前设备是否检测到 MTK PowerHAL，以及“复制诊断报告”入口。版本信息必须从 `BuildConfig` 读取；每个交付 APK 都递增 versionCode。

## 页面职责

| 页面 | 主要任务 | 不应承担 |
|---|---|---|
| 首页 | 当前频率、配置摘要、权限状态、快捷应用 | 构造 PowerHAL 数组 |
| CPU | 分段条选择、应用、释放、紧急恢复 | 直接读写 sysfs |
| GPU | 仅展示已探测且语义明确的 GPU 能力 | 借用 CPU 资源 ID |
| 方案 | 保存和重放 `Profile` | 自己管理句柄 |
| 设置 | 主题、应用方式、日志、备份、关于 | 隐藏失败原因 |
| 实验室 | 低风险、可解释的探测 | 把未知资源变成正式功能 |

## 迁移检查表

迁移到新目录后，先确认 `docs/` 与 `reference/` 的边界、Gradle wrapper、JDK 17 和 Android SDK。首次真机测试前安装包的 versionCode 必须高于设备已有版本，并先重启设备清除旧 sticky request。随后按顺序验证：UserService uid、单值四资源、释放、第二次单值、低于动态上限的区间、反向手势归一化、轮询停止和诊断报告。
