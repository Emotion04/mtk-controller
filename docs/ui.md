# UI — screens, interaction, design language

What the app looks like and how it is meant to be operated, for someone who has
never run it. Code is in `feature/` and `ui/`; this file is the map.

---

## Navigation

Five tabs, in a floating capsule bar pinned to the bottom:

```
首页 · CPU · GPU · 方案 · 设置
```

The bar is `ui/components/FloatingCapsuleNavBar.kt`. It is a pill-shaped floating
panel, not a system navigation bar; the selected tab is marked by a filled capsule
that can also be **dragged** horizontally, snapping to the nearest tab on release.
Each tab has its own accent colour (`Destination.accent`), and the bar style is a
user preference: colourful / follow theme / a chosen palette.

Everything is edge-to-edge. `AppNavHost` applies `statusBarsPadding()`; the bar
applies `navigationBarsPadding()`. Every scrollable screen reserves **110 dp** of
bottom content padding so nothing hides under the bar.

### Sub-screens

Reached from Settings, and all share one chrome (`SubScreenScaffold` +
`SubScreenTopBar`): a centred title with a back button on the left.

| route | screen |
|---|---|
| `settings/diagnostics` | capability probe |
| `settings/notifications` | deep links into the system notification settings |
| `settings/tweaks` | shell-based system tweaks |
| `settings/logs` | in-memory log + the device report |
| `settings/lab` | canary feature list |
| `settings/lab/panel` | read-only panel |
| `settings/lab/raw` | per-id CPU resource test |

The nav host owns the sub-screen chrome; the screen bodies are pure content. That
is why the titles are centred consistently — the placement lives in one file.

---

## Design language

- Ground is a light warm grey (`#F0F0F2`); cards are near-white (`#FFFFFF`) and
  lifted by a 1.5 dp shadow. **No outlines anywhere.**
- Rows inside a card are separated by a hairline that stops short of both edges
  (`InsetDivider`, 62 dp start indent) so a group reads as one object.
- Settings rows have a rounded tinted icon tile on the left, then title/subtitle,
  then optional trailing text. `SettingsComponents.kt`.
- Accent colours per section (palette, nav bar, tools…) are picked to read on a
  **white** card and are lifted automatically on a dark one
  (`adaptiveAccent`).
- Theming: `MaterialTheme` colour scheme from `AppPalette` (Material You
  dynamic when set to follow the system, 6 standard + 6 muted palettes otherwise),
  and a separate light/dark/system mode. `MtkOptimizerTheme` wraps everything in a
  **`Surface`** — without it `LocalContentColor` defaults to black and every
  unstyled `Text` is invisible in dark mode.

---

## 首页 (Home)

A dashboard of reorderable cards, layout persisted. Card types include a live
CPU frequency chart (1 Hz sampling, 60 samples, Canvas), current profile with
one-tap apply, cluster status, permission status, GPU status, and a quick-settings
card. Cards can be moved, removed, added, reset. Long-press a drag handle to
reorder.

The 1 Hz sampler runs **only while the screen is resumed** — the ViewModel outlives
the screen, so without that gate it kept reading the kernel forever in the
background.

---

## CPU

The screen that matters most. Top to bottom:

1. **Header** — cluster count and cores per cluster, active profile, and the
   **app version** (see `docs/handoff.md` for why the version is here).
2. **Governor section** — collapsed by default. It is a secondary knob that
   usually needs root, so it should not dominate a screen about frequency limits.
3. **One card per cluster**, each with the cluster label, its live frequency, the
   segment bar, and a `min`/`max` readout.
4. **Actions** — 应用 / 释放, then 紧急恢复, then 保存为方案.

### The segment bar — one control, three gestures

One cell per available frequency step, drawn as a row of separate rounded blocks
with a 2 dp gap. This is deliberate: it makes the discrete steps visible and it
gives each step its own hit target, which a continuous slider does not.

| gesture | result |
|---|---|
| tap | lock to that step (`min = max`) |
| tap a second step | range between the two taps |
| drag | range across the dragged span |

The range is **always normalised to low..high**, so the gesture's direction never
matters. An earlier version could hand the high value to the `min` slot when the
user tapped right-to-left — that is the bug this normalisation exists to prevent.

**There is no mode setting.** Both gestures are live in the same control; a
previous attempt to expose them as a preference was wrong and was reverted.

Drag highlight follows the finger; the committed range is what shows when idle.

---

## GPU

Frequency floor/ceiling for the GPU. This path goes through **sysfs**, not
PowerHAL — the PowerHAL GPU resources exist but are not used. On the development
device no GPU channel is detected at all, so the screen shows an empty state.

---

## 方案 (Profiles)

Saved cluster settings, applied with one tap. Apply, rename, delete, release.
Backed by `ProfileRepository` (DataStore JSON).

---

## 设置 (Settings)

Grouped cards, each row expanding in place rather than opening a new screen:

```
外观      深色/浅色模式 · 主题色 · 底栏样式
CPU       应用方式(单次/轮询) · CPU 调节方式[已移除] · 轮询间隔
通用      默认标签页
功能      实用工具 · 通知管理
实验室    只读面板 · Canary 列表 · 逐 ID 测试
其他      诊断信息 · 日志记录级别 · 运行日志
备份      导出配置 · 导入配置
关于      资料卡
```

Import is two-phase on purpose: picking a file parses it and shows a summary,
and only a second confirmation overwrites anything.

---

## 实验室 (Lab)

Three entry points, separated by risk:

- **只读面板** — never writes. First section is 「谁在限制」: hardware range against
  the live range, plus power-save, thermal status and battery. On the development
  device most of these nodes are unreadable, so it is largely empty there.
- **Canary list** — 17 declared features, each with a risk badge, an observed
  verdict badge, a control, and a 测试 button. Applying uses a 20 s expiring
  request unless 长期保持 is on.
- **逐 ID 测试** — sends one CPU frequency resource at a time so "what does this id
  do here" becomes a measurement. Restricted to the four CPU frequency ids;
  a free-form id field would let a typo reach radio-power and page-cache resources.

---

## 运行日志 (Log)

Newest at the bottom, every line selectable (long-press), 500 entries, level
filter. Header shows the **app version**. Two buttons: 复制报告 and 导出报告 —
the report is the main way a device we cannot test gets characterised, and it now
embeds the read-only panel's sections as well as the log.

---

## What is *not* here

- No onboarding, no permission wizard beyond a Shizuku button on the diagnostics
  screen and the CPU screen's availability message.
- No widget, no quick-settings tile, no automation triggers.
- No per-app rules, despite the protocol supporting them — see
  `docs/handoff.md` §Deliberately not done.
