# Architecture

## Layers

```
feature/     Compose screens + ViewModels          (no protocol knowledge)
domain/      Plain data models, no Android          (Profile, ClusterSetting, CpuCluster…)
data/        Everything that touches the device
  privilege/   Shizuku: permission, binder, the elevated user service
  powerhal/    The AIDL surface only — ids, transactions, parcel layout
               PerfLockController + PerfHandlerStore: one named handle's lifecycle
  sysfs/       Kernel text nodes: read, write, write-probe, batched read
  cpu/         CpuControl (the coordinator) + CpuControlStore + CpuScanner
  lab/         LabFeature catalogue + LabController + LabProbe (read-only panel)
  gpu/         GpuScanner + GpuTuner
  diag/        CapabilityProbe + DiagReport
  settings/    DataStore preferences
  profile/     Saved profiles
  backup/      Export / import
  log/         In-memory ring buffer
di/          AppContainer — hand-rolled composition root
```

## The one rule that matters: `CpuControl`

**All CPU frequency control goes through `CpuControl`.** Nothing else calls `PowerHal.acquire`
or `PowerHal.release`, and nothing else stores a handle.

This is not stylistic. The sequence

1. read the stored handle
2. release it, and abort if that fails
3. acquire with the new settings
4. store the new handle
5. persist what was applied, for the re-apply service
6. wait for the batch to settle, then read back

used to be copy-pasted into six call sites — three ViewModels, the re-apply service, and two
release paths. **Every bug in this area was one of those call sites forgetting a step**: a
handle cleared before its release was confirmed, a `CpuTuner()` constructed fresh so the
baseline was never captured, a path that acquired without releasing.

Now it exists once, and the invariants are structural rather than remembered:

- a handle is only forgotten once its release was confirmed
- an acquire is never issued while a handle is held
- `apply` and `release` are serialised by a `Mutex`, so the re-apply service and a ViewModel
  cannot interleave into two live handles
- the pre-touch baseline, the handle and the replay settings share **one store and one
  lifetime** (they used to be split between DataStore and an in-memory field that vanished on
  restart)

`CpuControlStore` is persistence. `CpuControl` is policy. `PowerHal` is wire format. `Sysfs`
is kernel nodes. Each is replaceable without touching the others.

### The same rule, one level down

The request-side invariants live in `PerfLockController`, so there is exactly one
implementation of them and the lab gets them for free. **Each independent area of
control gets its own instance and its own stored handle**, which is why an
experiment in the lab can never disturb the CPU limits the user is relying on:

```kotlin
CpuControl(    PerfLockController(store, "cpu") )   // the CPU screen
LabController( PerfLockController(store, "lab") )   // every canary experiment
```

**Graduating a canary into a real feature means giving it its own controller**,
the way `CpuControl` has one. Reusing the lab's would mean a promoted feature is
silently dropped the next time someone runs a different experiment.

### A stuck handle has no in-app way out

PowerHAL can tighten but never loosen — `floor = max(all floors)`,
`ceiling = min(all ceilings)`. So a request whose handle is lost clamps its cluster
until the device reboots. `CpuControl.emergencyRestore` is the one attempt at a
non-reboot escape: write the hardware range directly to
`scaling_max_freq`/`scaling_min_freq` (needs root), then cycle power-save so the
platform rewrites its own limits (works over adb, because `settings` is
shell-writable). It reports per cluster which, if either, worked. See
[pitfalls.md](pitfalls.md#13).

## Why the limits are sampled more than once

`apply` and `release` read the kernel back at **400 ms, 1.5 s and 3 s**, log all
three, and report the last.

The service applies a command array one entry at a time and the transaction returns
first, so a single early read cannot tell "not applied yet" from "never applied" —
and the app has no other evidence available. A single 400 ms sample was once read
as a collapsed range; it may simply have been a half-applied batch. Three samples
make the difference visible instead of arguable. See
[pitfalls.md](pitfalls.md#2).

> **On the development device this whole mechanism is inert.** uid 2000 cannot
> read `scaling_min_freq`/`scaling_max_freq` there at all, so all three samples
> come back empty. Verification on that device has to come from an external CPU
> monitor — see [device-vivo-v2430a.md](device-vivo-v2430a.md#2-what-the-kernel-exposes--and-what-it-does-not).

## Transaction code detection

Transaction numbers are AIDL declaration order, so they are a property of the ROM's framework
jar, not of the device model — see [protocol.md](protocol.md#transaction-codes-are-version-dependent-).

Planned detection, cheapest first, all read-only:

1. `Class.forName("com.mediatek.powerhalmgr.IPowerHalMgr")` and read
   `IPowerHalMgr$Stub.TRANSACTION_perfLockAcquire` reflectively. MediaTek's framework jar is
   usually on the boot classpath, so this may simply work.
2. If that fails, use the existing Shizuku channel to locate the vendor framework jar and read
   the same `public static final int` fields out of the class. Read-only and safe.
3. Fall back to the known-good values for the target device and say so in the log.

**Not acceptable:** probing by calling candidate transaction codes with a real payload. A
wrong code may be a write operation. Inspecting the jar is the only side-effect-free route.

The result is cached; detection runs once.

## Device adaptation

Two mechanisms, in priority order.

**1. Self-adaptation by probing (preferred).** The app should never assume a device shape:

- cluster/policy layout is discovered by scanning, and the **enumeration order is the command
  index**, so it cannot be hardcoded
- feature availability is decided by probing and reading back, not by a device list — if a
  resource is not accepted, the control is not shown at all rather than shown and broken
- the diagnostic report records everything the probe saw

**2. The diagnostic report (for what probing cannot resolve).** *设置 → 运行日志 → 复制报告*
produces a plain-text snapshot: `Build.*`, SoC, cpufreq policies with their enumerated index,
available frequency counts, governor writability, PowerHAL reachability, the full probe
results, and the run log. It is written to be greppable so a device nobody on the team owns
can be characterised from the text alone.

The intended workflow: a user copies the report, and the response is either "the app already
adapts, here is what it found" or a concrete fix. **The goal is that most devices need no
per-device work at all** — the report is the escape hatch, not the primary mechanism.

## Logging

`AppLog` is a bounded in-memory ring buffer (500 entries) mirrored to logcat under the tag
`MtkGod`, with a user-selectable level. Nothing is written to disk.

Every state-changing operation logs what it sent *and* what it read back, because "the service
returned a handle" and "the value landed" are different facts and only the second one matters.

## Testing

**There are no automated tests.** Everything in [protocol.md](protocol.md) came
from primary sources and on-device observation, and `CpuControl`'s invariants are
enforced by construction — but nothing prevents a regression, and this codebase
has already regressed twice in ways a single test would have caught.

Highest value first, all runnable without a device:

1. **`CpuControl.apply` releases before acquiring.** Given a store holding a live
   handle, the order of calls to `PowerHal` must be release-then-acquire.
2. **`CpuControl.apply` does not clear a handle whose release failed.** The old
   behaviour stranded a live request; this is the bug behind the worst symptom
   the project has.
3. **`CpuControl.release` reports what the kernel says**, not what the oneway
   transaction returned.
4. **`DiagReport` contains the cluster enumeration order** — that mapping is the
   command index, and a device report without it cannot be acted on.
5. **`BackupRepository` import of an older file leaves absent fields alone.**

Tests 1–3 need `PowerHal` to become an interface rather than an `object`. That is
worth doing anyway.
