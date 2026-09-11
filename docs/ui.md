# UI — design tokens, screens, interaction

What the app looks like and how it is meant to be operated, for someone who has
never run it. Code lives in `feature/` and `ui/`; this file is the map and the
design spec.

All values below are taken from the source at the time of writing —
`ui/theme/Palettes.kt`, `ui/navigation/Destinations.kt`,
`ui/components/*`, and the per-screen colour constants.

---

## Navigation

Five tabs, in a floating capsule bar pinned to the bottom:

```
首页 · CPU · GPU · 方案 · 设置
```

`ui/components/FloatingCapsuleNavBar.kt`. It is a pill-shaped floating panel, not
a system navigation bar. The selected tab is marked by a filled capsule that can
also be **dragged** horizontally, snapping to the nearest tab on release — the bar
doubles as a slider between destinations. Icons sit above labels; each tab has its
own accent colour (below), and the bar's colouring is a user preference:
彩色 / 跟随主题色 / 自定义色系.

Everything is edge-to-edge. `AppNavHost` applies `statusBarsPadding()`; the bar
applies `navigationBarsPadding()`. **Every scrollable screen reserves 110 dp of
bottom content padding** so nothing hides under the bar.

### Sub-screens

Reached from Settings, all sharing one chrome (`SubScreenScaffold` +
`SubScreenTopBar`): a **centred** title with a back button on the left.

| route | screen |
|---|---|
| `settings/diagnostics` | capability probe |
| `settings/notifications` | deep links into system notification settings |
| `settings/tweaks` | shell-based system tweaks |
| `settings/logs` | in-memory log + the device report |
| `settings/lab` | canary feature list |
| `settings/lab/panel` | read-only panel |
| `settings/lab/raw` | per-id CPU resource test |

The nav host owns that chrome and the screen bodies are pure content — which is
why the titles are centred identically across all seven.

---

## Design tokens

### The neutral ground

The app is built on a **warm off-white**: cards are near-pure white sitting on a
light grey ground, lifted by a soft shadow. **There is no outline anywhere** —
grouping comes from the shadow and from hairline dividers that stop short of the
card edges.

| token | light | dark | used for |
|---|---|---|---|
| background | `#F0F0F2` | `#121212` | the ground behind cards |
| surface | `#FFFFFF` | `#1E1E1E` | card fill |
| surfaceVariant | `#F0EFED` | `#262626` | inactive segment fill |
| surfaceContainer | `#FAFAF9` | `#1A1A1A` | |
| surfaceContainerHigh | `#FFFFFF` | `#1E1E1E` | the nav bar |
| surfaceContainerHighest | `#FFFFFF` | `#1E1E1E` | |
| onBackground / onSurface | `#1B1B1A` | `#E8E8E6` | body text |
| onSurfaceVariant | `#6B6B68` | `#9E9E9A` | secondary text |
| outlineVariant / divider | `#1F1B1B1A` | `#24E8E8E6` | hairlines |
| outline | `#1F1B1B1A` | `#24E8E8E6` | |
| error | `#B3261E` | `#F2B8B5` | |

Note the container levels `High` and `Highest` are deliberately mapped to the
**same** white/near-black as `surface`, so Material's default `Card()` inherits
the white card look instead of a tinted container.

### Accent palettes

Only `primary` / `secondary` / `tertiary` move; the neutral ground above never
changes, so every palette keeps the same paper feel.

**标准** — `AppPalette`:

| name | accent |
|---|---|
| 跟随系统 (Material You) | dynamic, from the wallpaper |
| 蓝 | `#1565C0` |
| 绿 | `#2E7D32` |
| 紫 | `#6A4C93` |
| 橙 | `#B4531A` |
| 青 | `#0B6E6E` |
| 红 | `#9C4146` |

**莫兰迪** — low-saturation, grey-leaning pastels, dark enough to stay readable
as an accent on white but never bright:

| name | accent |
|---|---|
| 莫兰迪玫瑰 | `#A6797B` |
| 莫兰迪雾蓝 | `#7A8FA6` |
| 莫兰迪鼠尾草 | `#7E9179` |
| 莫兰迪藕紫 | `#8C7E96` |
| 莫兰迪陶土 | `#B08064` |
| 莫兰迪灰米 | `#9A8B76` |

Derived at runtime: `onPrimary` is white, `secondary` is the accent blended 35 %
toward `#8A8A8A`, `tertiary` is the accent blended 35 % toward `#C9A227`. On a
dark ground the accent is lifted 45 % toward white so it stays legible.

### Fixed accent colours

Bottom-bar tabs, per `Destination`:

| tab | accent |
|---|---|
| 首页 | `#2E7D32` |
| CPU | `#B4531A` |
| GPU | `#6A4C93` |
| 方案 | `#1565C0` |
| 设置 | `#4A6572` |

Settings section icon tiles — chosen to read on a **white** card, and lifted
automatically on a dark one (`adaptiveAccent`):

| section | tint |
|---|---|
| 深色/浅色模式 | `#55606E` |
| 主题色 | `#6A4C93` |
| 底栏样式 | `#7A8FA6` |
| 默认标签页 | `#2E7D32` |
| 实用工具 | `#B4531A` |
| 通知管理 | `#1565C0` |
| 应用方式 | `#9C4146` |
| 诊断信息 | `#0B6E6E` |
| 日志 / 运行日志 | `#6B6B68` |
| 备份 | `#4A6572` |
| 实验室 | `#6A4C93` |

Lab badges:

| meaning | colour |
|---|---|
| 风险 低 / 生效 | `#2E7D32` |
| 风险 中 | `#B4531A` |
| 风险 高 / 未见效 / 本机不支持 | `#9C4146` |
| 未测试 / 无法判断 | `#6B6B68` |

### Shape and spacing

| | |
|---|---|
| card corner | 18 dp, elevation 1.5 dp |
| inset divider | 0.7 dp tall, 62 dp start indent, 18 dp end |
| settings icon tile | 32 dp square, 9 dp corner, 18 dp glyph, 14 % tint fill |
| list content padding | 16 dp sides, 110 dp bottom |
| nav bar | pill shape, 12 dp shadow, 50 dp item height, 6 dp outer padding, 20 dp side / 14 dp bottom margin |
| segment bar | 30 dp tall, 2 dp gaps, 3 dp segment corner |

### Theming

`MtkOptimizerTheme(themeMode, palette)` resolves a `ColorScheme`, then wraps the
content in a **`Surface(color = background, contentColor = onBackground)`**.

That wrapper is not cosmetic. `MaterialTheme` does not publish
`LocalContentColor` — only `Surface` / `Scaffold` do, and its default is black.
Without it, every `Text` that does not name a colour renders black, which looks
correct in light mode and is invisible in dark mode. See
[pitfalls.md](pitfalls.md#8).

Theme mode is 跟随系统 / 浅色 / 深色, independent of the accent palette.

---

## 首页 (Home)

A dashboard of reorderable cards; the layout is persisted and user-editable
(move, remove, add, reset). Long-press a drag handle to reorder. Card types: live
CPU frequency chart (Canvas, 1 Hz, 60 samples, normalised to the highest
frequency seen), current profile with one-tap apply, cluster status, permission
status, GPU status, and a quick-settings card.

The 1 Hz sampler runs **only while the screen is resumed** — the ViewModel
outlives the screen, so without that gate it kept reading the kernel forever for
a screen nobody was looking at.

---

## CPU

The screen that matters most. Top to bottom:

1. **Header** — cluster count and cores per cluster, active profile, and the
   **app version** plus a one-line gesture reminder. The version is here rather
   than only on the log screen because "which build am I looking at" has twice
   been the real question behind a bug report. See
   [handoff.md](handoff.md).
2. **Governor section** — collapsed by default; a secondary knob that usually
   needs root, so it should not dominate a screen about frequency limits.
3. **One card per cluster** — cluster label, live frequency, the segment bar, and
   a `min` / `max` readout.
4. **Actions** — 应用 / 释放, then 紧急恢复, then 保存为方案, then the availability
   or result message.

### The segment bar — one control, three gestures

One cell per available frequency step: a row of separate rounded blocks with a
2 dp gap. Deliberate — it makes the discrete steps visible and gives each step its
own hit target, which a continuous slider does not.

| gesture | result |
|---|---|
| tap | lock to that step (`min = max`) |
| tap a second step | range between the two taps |
| drag | range across the dragged span |

The range is **always normalised to low..high**, so gesture direction never
matters. An earlier version could put the high value in the `min` slot when the
user tapped right-to-left — that is the bug this normalisation exists to prevent.

**There is no mode setting.** Both gestures are live in the same control. An
attempt to expose them as a preference was wrong and was reverted.

Drag highlight follows the finger; the committed range shows when idle.

---

## GPU

Frequency floor/ceiling for the GPU, **through sysfs, not PowerHAL** — the
PowerHAL GPU resources exist but are unused. On the development device no GPU
channel is detected at all, so the screen shows an empty state.

---

## 方案 (Profiles)

Saved cluster settings applied with one tap; apply, rename, delete, release.
Backed by `ProfileRepository` (DataStore JSON).

---

## 设置 (Settings)

Grouped cards; rows expand in place rather than opening a new screen.

```
外观      深色/浅色模式 · 主题色 · 底栏样式
CPU       应用方式(单次/轮询) · 轮询间隔
通用      默认标签页
功能      实用工具 · 通知管理
实验室    只读面板
其他      诊断信息 · 日志记录级别 · 运行日志
备份      导出配置 · 导入配置
关于      资料卡
```

Backup import is **two-phase**: picking a file parses it and shows a summary, and
only a second confirmation overwrites anything.

---

## 实验室 (Lab)

Three entry points, separated by risk:

- **只读面板** — never writes. Its first section is 「谁在限制」: the hardware
  frequency range against the live range, plus power-save, thermal status and
  battery. On the development device most of those nodes are unreadable so it is
  largely empty there — see
  [device-vivo-v2430a.md](device-vivo-v2430a.md#2-what-the-kernel-exposes--and-what-it-does-not).
  It is the **first** section, above 「当前生效的限制」.
- **Canary list** — 17 declared features, each with a risk badge, an observed
  verdict badge, a control, and a 测试 button. A test uses a 20 s expiring request
  unless 长期保持 is on.
- **逐 ID 测试** — sends one CPU frequency resource at a time so "what does this id
  do here" becomes a measurement. Restricted to the four CPU frequency ids; a
  free-form id field would let a typo reach radio-power and page-cache resources.

---

## 运行日志 (Log)

Newest at the bottom, 500 entries, level filter, **every line selectable**
(long-press) — the lines end up in a report, so pulling one out has to be easy.
The header shows the **app version**. Two buttons: 复制报告 and 导出报告. The
report embeds the read-only panel's sections as well as the log, and is the main
way a device nobody can test gets characterised.

---

## Not here

No onboarding, no permission wizard beyond a Shizuku button on the diagnostics
screen and the availability message on CPU. No widget, no quick-settings tile, no
automation triggers. No per-app rules, despite the protocol supporting them — see
[handoff.md](handoff.md).
