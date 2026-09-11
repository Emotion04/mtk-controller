# Handoff

Written for whoever picks this up next — human or agent. **Read this whole file
before changing anything in `mtk-optimizer/app/src/main/java/.../data/cpu/` or
`data/powerhal/`.**

**Read [device-vivo-v2430a.md](device-vivo-v2430a.md) alongside this.** The app
cannot read the frequency limits back on the development device, so its own
read-backs are blind there and the only usable measurements come from outside —
that file records what is and is not observable, and what the device has actually
been seen to do.

## How to read this document

Three parts, and they are not equally trustworthy:

| part | status |
|---|---|
| **1. Facts** | Established from primary sources or observed on the device. Treat as true. |
| **2. Open problems** | The complete list of what is *not* solved. The symptoms are real; the causes are not established. |
| **3. Speculation** | **Guesses.** Labelled, with a confidence and a way to test each. Do not act on these as if they were facts. |

If something below turns out to be wrong, fix the document in the same commit
that proves it wrong.

---

# 1. Facts

## 1.1 What this is

An Android app that sets CPU frequency limits on MediaTek SoCs **without root**,
through [Shizuku](https://shizuku.rikka.app/). It talks to MediaTek's PowerHAL
manager directly.

- Package `magicau.mtkcontroller`, label **MTK God**
- `versionCode 6` / `versionName 0.3.3`
- `minSdk 33`, `targetSdk 37`, Kotlin + Compose + Material 3
- Build: `cd mtk-optimizer && ./gradlew assembleDebug`
- **Bump `versionCode` on every hand-off build.** It was left at `1` for many
  builds; the system treats an equal version code as the same build, updates
  silently did nothing, and the user spent an evening reporting bugs in code that
  was weeks old. This is not hypothetical.
- The running version is shown in the log screen header and the startup log line,
  so "which build is installed" is always answerable.

## 1.2 The device this was developed against

| | |
|---|---|
| Model | vivo V2430A |
| SoC | MediaTek Dimensity 9300+ / MT6989 |
| Android | 16 (API 36) |
| Shizuku | started from **wireless debugging** → runs as **shell, uid 2000, not root** |
| cpufreq policies | `policy0` (cpu0-3), `policy4` (cpu4-6), `policy7` (cpu7) |

The **enumeration order of the policy directories is the command index**. Nothing
may assume a fixed cluster layout.

## 1.3 Architecture — the one rule that matters

**All CPU frequency control goes through `CpuControl`.** Nothing else calls
`PowerHal.acquire` / `PowerHal.release`, and nothing else stores a handle.

That is not stylistic. The sequence — release the stored handle, abort if that
fails, acquire, keep the new handle, persist what was applied, wait for the batch
to settle, read back — used to be copy-pasted into six call sites, and every bug
in this area was one of them forgetting a step.

| layer | file | responsibility |
|---|---|---|
| wire format | `data/powerhal/PowerHal.kt` | ids, transacts, parcel layout |
| handle lifecycle | `data/powerhal/PerfLockController.kt` | one named handle; release-before-acquire, never forget, serialise |
| handle persistence | `data/powerhal/PerfHandlerStore.kt` | one slot per named purpose |
| CPU policy + verification | `data/cpu/CpuControl.kt` | the only CPU entry point |
| CPU persistence | `data/cpu/CpuControlStore.kt` | baseline, replay settings |
| cluster discovery | `data/cpu/CpuScanner.kt` | policies, frequencies, governors |
| canary features | `data/lab/*` | declared as data; `LabController` drives them |
| device probe | `data/diag/*` | capability probe + the report users send back |

Invariants, enforced structurally rather than remembered:

- a handle is only forgotten once its release was confirmed
- an acquire is never issued while a handle is held
- apply/release are serialised by a mutex
- **the lab has its own handle**, so an experiment can never disturb the CPU
  limits the user is relying on
- **graduating a canary out of the lab requires giving it its own
  `PerfLockController`** — see the class doc on `LabController`

## 1.4 What is implemented

Screen-by-screen description, interaction model and design language:
[ui.md](ui.md). The summary below is the functional list.

- CPU frequency floor/ceiling per cluster; release; profiles; home dashboard
- Governor per cluster (best-effort, usually needs root, the UI says so)
- GPU page (goes through sysfs, **not** PowerHAL)
- Apply modes: single-shot, or polling re-apply from a foreground service
- Backup export/import (JSON, two-phase)
- Read-only diagnostic panel + a device report users can copy and send
- Canary lab: 17 declared features across 6 areas, each reporting whether the
  device actually moved
- Settings: theme mode, palette, nav bar, apply mode, log level

## 1.5 Verified on the device

- PowerHAL is reachable through Shizuku and returns valid handles
- The command array format is correct: values do reach the kernel — frequencies
  have been observed changing after an apply
- `/proc/ppm/policy/hard_userlimit_cpu_freq` **does not exist** on this device, so
  hard-limit writes are a no-op here

> **Correction, and it invalidates a lot.** An earlier version of this document
> listed "the read-back of `scaling_min_freq`/`scaling_max_freq` reflects service
> state" as verified. A full diagnostic report taken on 2026-09-12 shows those two
> nodes are **not readable at all** on this device, for any cluster, by either the
> direct read or the elevated one — while `scaling_cur_freq` reads fine.
> See [device-vivo-v2430a.md §2](device-vivo-v2430a.md#2-what-the-kernel-exposes--and-what-it-does-not).
>
> Every conclusion this project drew from a read-back — including several rounds
> of "the ceiling did not take effect" — rests on a measurement that does not
> exist. **Treat those as unverified.**

## 1.6 Never verified — do not describe these as working

- **A minimum ≠ maximum range has never been observed to take effect.** Every
  attempt was either unchanged in the kernel or confounded by earlier state.
- GPU control on any device
- **Every feature in the lab.** All 17 are canaries; none is confirmed.
- Anything on a device other than the one in §1.2
- The scrolling-jank fix described in §2 P13

---

# 2. Open problems

Everything still unresolved. Ordered by how much they block useful work.

## P1 — Frequency control stops responding after several applies 🔴

**Symptom.** The first few applies change the frequency. After a while, further
applies change nothing — same values or different, the kernel does not move.
Sometimes a single-frequency apply still works, sometimes not.

**Evidence.** Confirmed by the user with an **external CPU monitor**, not just the
app's own read-back. The log shows applies accepted with valid handles whose
values had not moved.

**Ruled out.** Hard limits (their node does not exist on this device — §1.5).

**This is the most important problem.** Everything else is secondary.

## P2 — A cluster can be left pinned with no in-app recovery 🔴

**Symptom.** A cluster ends up locked at a fixed frequency. Uninstalling the app,
stopping Shizuku, and reinstalling all fail to clear it. Only a reboot works.

**Why — this part is fact.** PowerHAL's merge is `floor = max(all floors)`,
`ceiling = min(all ceilings)`, plus "if ceiling < floor, raise ceiling to floor".
A live request whose handle has been lost therefore keeps clamping, and **no new
request can loosen it**. Releasing that exact handle is the only in-app fix, and
the handle is gone.

**Mitigation shipped** (`CpuControl.emergencyRestore`, button at the bottom of the
CPU screen):

1. write `scaling_max_freq` / `scaling_min_freq` directly, bypassing PowerHAL —
   needs those nodes writable by the elevated uid, i.e. Shizuku as **root**
2. cycle power-save mode so the vendor power service re-evaluates and re-pushes
   its limits — `settings` is shell-writable, so this can work over **adb**

**Not yet tested, and on this device route 1 cannot work at all**: it writes
`scaling_max_freq`/`scaling_min_freq`, which uid 2000 cannot even *read* here
(§P10). Route 2 — cycling power-save through `settings`, which shell can write —
is the only one with a chance. If both fail, the honest answer is "reboot".

## P3 — The ceiling does not appear to take effect 🟠

**Symptom.** The floor is honoured; the ceiling is not. Frequencies go above the
requested maximum. The user also describes a boundary: values below some
frequency work, values above it do not.

**Evidence.** Apply sent `policy4 min=2700 max=2850` → kernel read back
`2400-2400`. Apply sent `policy7 min=3250 max=3400` → kernel `2100-2100`.
Neither matched the request and neither had moved from the previous reading.

## P4 — The prime cluster is pinned outside the requested range 🟠

**Symptom.** `policy7` (the single X4 core) locks at a value that was never
requested, while the other clusters behave.

**Note.** In the readings seen, `policy7` sat at 2100 MHz while `policy4` sat at
2400 MHz — the prime core capped *below* the mid cluster, which is not what the
silicon's own range suggests.

## P5 — Floor and ceiling behave asymmetrically on release 🟠

**Symptom.** On release, `scaling_min_freq` returns to its stock value but
`scaling_max_freq` does not.

**Correction, important.** An earlier version of this document treated the
non-returning ceiling as proof of a stale request. **That was wrong.** Readings of
the same device after the same release gave 2200 MHz once and 2400 MHz later with
nothing of ours applied — the ceiling floats with the platform's own thermal and
DCVS state. The comparison is a *pointer*, not a verdict.

## P6 — Power-save / low battery defeats the limits 🟠

The user reports that in power-save mode, or at low battery, frequency control
stops taking effect. It happens rarely. Cause unidentified.

## P7 — Range support has never been demonstrated 🔴

The protocol permits a floor/ceiling pair and MediaTek's own tests use asymmetric
values, but **this app has never shown a range working on hardware**. See P1–P5.

## P8 — Transaction codes are hardcoded 🟠

`PowerHal.TRANSACT_ACQUIRE` / `RELEASE` are `0x16` / `0x17`; `querySysInfo` is
`0x18`. These are correct **on the development device only** — they are AIDL
declaration order, so they shift when the ROM's interface revision changes.

A read-only detection scheme is designed in
[architecture.md](architecture.md#transaction-code-detection) and **not
implemented**. The descriptor probe (`PowerHal.descriptor`) is implemented and is
the zero-side-effect way to identify the binder first.

## P9 — Requests must retain a stable owner 🟠

The prior implementation sent PowerHAL transactions through
`ShizukuBinderWrapper`, so MTK saw **Shizuku** as the client. That made the
request owner shared and made handle cleanup vulnerable to a Shizuku process
restart.

**Implemented in 0.3.5, not yet verified on device:** acquire and release now
run inside the app's daemon `RuntimeUserService`, matching the reference app's
shape. `PowerHal` reaches it through `IRuntimeService`; the elevated service
resolves `power_hal_mgr_service` and performs both binder transactions. The
returned handle therefore belongs to one stable UserService process and is
released from that same process. Check the runtime-service uid in the diagnostic
report and verify repeated apply/release cycles with an external monitor.

## P10 — The app cannot read the frequency limits on this device 🔴

`scaling_min_freq` / `scaling_max_freq` / `cpuinfo_min_freq` / `cpuinfo_max_freq`
return nothing for **every** cluster — direct read and elevated `cat` both fail,
so it is not something the app can work around as uid 2000.
`scaling_cur_freq` reads fine.

**This is the reason several earlier conclusions were wrong**: the app has been
applying limits and then reporting "no effect" based on a read that never
succeeded. `CpuControl`'s three-sample settle logging, the release verification,
and the read-only panel's 「谁在限制」 section are all inert on this device.

**Do not fix this by guessing a different node.** Establish first whether adb from
a PC (same uid 2000, different SELinux domain) can read them; if it cannot either,
accept that verification must come from an external monitor.

Details and the full node table: [device-vivo-v2430a.md §2](device-vivo-v2430a.md#2-what-the-kernel-exposes--and-what-it-does-not).

## P10b — Something caps the ceiling at ~1.8 / 2.1 / 2.1 GHz 🟠

Observed by the user with an **external CPU monitor** (the app cannot see it):

- the three clusters settle at roughly 1.8 / 2.1 / 2.1 GHz
- a range whose ceiling is below those values **works**
- a single value acts as a **floor, not a lock** — it can still rise above
- a range whose floor is above those values **has no effect**

Read together that is one fact seen three ways: an effective ceiling is held near
those values and the app's ceiling writes cannot get above it.

**Not established:** where that cap comes from. The vendor's thermal/DCVS logic is
the obvious candidate but has not been shown; nothing in the readable node set
confirms it.

**Why the numbers matter:** they are *not* the cluster maximums (2000 / 2850 /
3400). Whatever holds them is dynamic.

## P11 — No automated tests 🟡

None. Everything in [protocol.md](protocol.md) came from primary sources and
on-device observation, and `CpuControl`'s invariants are enforced by construction —
but nothing prevents a regression. The highest-value first tests are listed in
[architecture.md](architecture.md#testing).

## P12 — The lab is entirely unverified 🟡

17 canary features, none confirmed on hardware. **Eight have no read-back node at
all**, so `LabController` can only report "cannot judge" for those — that is
deliberate and honest, not a bug to fix by guessing a node.

## P13 — The scrolling-jank fix has not been re-tested 🟡

The probe code used to read nodes one at a time, each with its own elevated
fallback, which on a device missing most of those nodes meant dozens of shell
process spawns per screen visit. That is now a single batched call
(`Sysfs.readMany`). **The user has not confirmed the stutter is gone.**

## P14 — Several features have unknown or disputed semantics 🟡

- `PERF_RES_THERMAL_POLICY` values live in **encrypted** vendor blobs; the
  meanings are not derivable from public sources
- `PERF_RES_DRAM_OPP_MIN` direction is disputed (MTK-derived evidence says index
  0 = fastest; two community tools write the opposite)
- `PERF_RES_CPUFREQ_CCI_FREQ` is a **0/1 mode flag, not a frequency**
- GPU resources take an **OPP index**, CPU resources take **kHz** — mixing them
  is a silent no-op
- `setPriorityByUid` / `flushPriorityRules` have **no public evidence**; do not
  bind code to those names

## P15 — Re-apply service behaviour against stale state is untested 🟡

`CpuReapplyService` calls `CpuControl.apply` on a timer. It has never been run
against a device already holding stale requests.

## P16 — 0.3.5 single-value and range paths need hardware verification 🟠

The reference app's smali was rechecked. Its one-point control submits all four
per-policy resources (`MIN`, `MAX`, `MIN_HL`, `MAX_HL`) with the same kHz value;
the old app path was not the same as this app's soft-only pair.

**Implemented in 0.3.5, not yet verified on device:** equal endpoints now send
that same four-resource request, so one tap on the segment control is a genuine
single-value lock. Unequal endpoints deliberately send only the soft `MIN` and
`MAX` pair, preserving a range rather than accidentally turning it into a hard
lock. The log names the selected path and its exact endpoints.

---

# 3. Speculation — NOT FACTS

Every entry here is a guess. Each says how confident it is and what would settle
it. **Do not build on these without testing first.**

### S1 — Stale requests from pre-fix builds explain P1, P3, P4 and P5
*Confidence: medium.*

Builds before the `PerfLockController` refactor acquired a new request on every
apply **without releasing the previous one**, and cleared the stored handle even
when the release failed. Each such apply is a request that can never be released.
Under the merge rule (P2), accumulated stale requests would produce exactly the
observed pattern: a floor that only rises, a ceiling that can never be raised,
and eventually no response at all.

**Why it might be wrong.** The user reported the device still pinned after
uninstall, stop-Shizuku and reinstall — which stale requests *would* explain — but
the current build has never been tested on a device confirmed clean by a reboot.
Until that happens, "the bug is only in old builds" is unproven.

**How to test.** Reboot. Check the log's hardware-range line looks sane. Apply
once, read back. Apply again, read back. If both work, S1 is likely right.

### S2 — The 400 ms read-back was too short and reported half-applied batches
*Confidence: low.*

libpowerhal applies a command array one entry at a time and the transaction
returns first, so a single 400 ms sample cannot distinguish "not applied yet" from
"never applied".

**Why it might be wrong.** The user watched an external CPU monitor and saw the
frequency not change at all, which rules this out *as the cause of P1* — but the
read-back is still the app's only evidence, so the ambiguity matters.

**How to test.** `CpuControl` now samples at 400 ms / 1.5 s / 3 s and logs all
three. If they differ, this was real; if all three agree, it was not.

### S3 — The service clamps values at its own idea of the cluster maximum
*Confidence: raised to medium by the 2026-09-12 report.*

The user's observation is exactly this shape: a ceiling that works below some
value and does nothing above it, and a floor that cannot be raised past it. The
cap sits far below the silicon maximum (1.8/2.1/2.1 against 2000/2850/3400 MHz),
so it is not the hardware limit — which points at a software clamp rather than a
write failure.

`perfservice` was reported to clamp with
`param_1 >= ptClusterTbl[i].freqMax ? freqMax : param_1`, and to replace an
at-or-above-maximum ceiling with "no limit". If the service's cluster table is
smaller than the frequencies the app offers, large values would silently do
nothing while small ones work — which would match the reported boundary.

**How to test.** Apply deliberately small values (`min=1500 max=2000`) to every
cluster. If those work and large ones do not, this is likely right.

### S4 — The ceiling id is not the effective ceiling on this device
*Confidence: low.*

`0x00404000` (`PERF_RES_CPUFREQ_MAX_CLUSTER_n`) is confirmed as a real resource by
name and value, but that does not prove it is what constrains this particular BSP.
Alternative levers exist: the perfmgr path, `POWER_SYSLIMITER`, or the hard-limit
pair — whose node is absent here, so not that one.

**How to test.** S3's experiment distinguishes this from S3: if small values work
*and* a ceiling above them is honoured, the id works and S3 was the issue.

### S5 — PowerHAL does not revoke a client's requests when the client dies
*Confidence: medium-high.*

The user stopped Shizuku and the pinned frequency persisted. If powerhal cleaned
up on binder death, stopping Shizuku should have cleared it.

**Why it might be wrong.** Something else could have been holding the value, and
the observation is a single data point from a build with known bugs.

### S6 — The jank was shell-process spawns from the probe code
*Confidence: medium.*

Dozens of `Runtime.exec` calls per screen visit is enough CPU to stutter the whole
system, and the timing matches — the stutter appeared after the lab was added.
Batching was the fix.

**How to test.** The user scrolls after installing a build that has
`Sysfs.readMany`. Not yet done.

### S7 — Hard limits were never involved
*Confidence: high.*

`/proc/ppm/policy/hard_userlimit_cpu_freq` does not exist on this device, and the
implementation was reported to be a no-op when that node is absent. Older builds
did write the hard-limit ids, which makes them an attractive suspect — but the
node is not there.

**How to test.** Effectively already tested; keep the read-only panel's
"硬限制 (全局)" line visible as a standing check.

---

# 4. First actions for the next agent

In order. Do not skip 1.

1. **Ask the user to reboot the device.** It is the only reliable way out of P2,
   and testing against a device holding stale state has already wasted several
   rounds.
2. **Confirm which build is installed** — the log screen header shows the version.
   If it is not the build you just made, stop; the install did not take.
3. **Read [device-vivo-v2430a.md](device-vivo-v2430a.md) before interpreting any
   log.** On this device the app's read-backs are blind, so a log saying "no
   effect" carries no information. Note also that the read-only panel's
   「谁在限制」 section is empty here for the same reason — it is the *first*
   section of that screen, which the user has twice looked for below the fold.
4. **Run S3's experiment**: small values on every cluster, then read back.
5. Only then start on P1 with real evidence.

## The reference material

`reference/` holds the third-party material this protocol was worked out from:
the reference application's APK, the decompiler output for it, memory dumps, and
the vendor files fetched from public repositories while researching PowerHAL.

**It is git-ignored except for its own `README.md`** — those files are a third
party's, and this repository is public. `reference/README.md` lists what is in
each subdirectory and where it came from.

Two things to know before using it:

- **`reference/decompiled/smali/` is the reliable copy.** A Java decompiler cannot
  rebuild Kotlin suspend-function state machines or inline lambda bodies — it
  emits dispatch stubs with the real logic missing, which is why a call site
  searched for in the Java output appeared not to exist. The smali has it. When
  something cannot be found in the Java, search the smali before concluding it is
  not there. This is recorded in [pitfalls.md](pitfalls.md#10).
- **The protocol facts are in [protocol.md](protocol.md), with sources** — prefer
  those over re-reading the dump. The dump is for questions the facts do not
  answer.

Nothing under `reference/` is needed to build or run the app.

## Rules that have already cost time

- `reference/` holds third-party material and is git-ignored except its README.
  **Keep it that way** — this repository is public.
- Every new write path must read its result back. "The service returned a handle"
  and "the value landed" are different facts, and only the second matters.
- Probe before offering a control; hide what the device does not accept.
- Do not ship a control whose semantics are not established — an unavailable
  feature is a smaller problem than a system-wide side effect.
- **Bump `versionCode` on every hand-off build.**

## Where to look

| task | file |
|---|---|
| what the app looks like and how it is operated | [ui.md](ui.md) |
| the protocol, with sources | [protocol.md](protocol.md) |
| third-party reference material, and how to read it | `reference/README.md` |
| why something is the way it is | [pitfalls.md](pitfalls.md) |
| layers, invariants, testing gaps | [architecture.md](architecture.md) |
| what this device can and cannot observe | [device-vivo-v2430a.md](device-vivo-v2430a.md) |
| raw diagnostic report, 2026-09-12 | [reports/2026-09-12-vivo-v2430a-0.3.3.txt](reports/2026-09-12-vivo-v2430a-0.3.3.txt) |
| CPU control | `data/cpu/CpuControl.kt` |
| handle lifecycle | `data/powerhal/PerfLockController.kt` |
| wire format | `data/powerhal/PowerHal.kt` |
| device probe | `data/diag/CapabilityProbe.kt` |
| the report users send back | `data/diag/DiagReport.kt` |
| canary feature catalog | `data/lab/LabFeature.kt` |
