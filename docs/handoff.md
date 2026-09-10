# Handoff

State of the project as of 2026-09-11. Read [protocol.md](protocol.md) first if the task is
about PowerHAL behaviour, and [pitfalls.md](pitfalls.md) before changing anything in
`data/cpu/`.

---

## What this is

An Android app that sets CPU/GPU frequency limits on MediaTek SoCs, without root, through
Shizuku. Built from scratch after studying how a reference implementation on the device did
it. Only the soft CPU frequency pair is written; see
[protocol.md](protocol.md#4-cpu-frequency-resources) for why.

Package `magicau.mtkcontroller`, label **MTK God**, `minSdk 33` / `targetSdk 37`.

## What works

- CPU frequency floors/ceilings per cluster, applied through PowerHAL
- Release back to the platform's own scheduling
- Governor per cluster (best-effort — the node usually needs root, and the UI says so)
- GPU page, profiles, home dashboard with live frequency chart
- Settings: theme mode (light/dark/system), palette, nav bar style, apply mode, log level
- **Apply modes**: single-shot, or polling re-apply from a foreground service
- **Backup**: export/import all profiles and preferences as JSON
- **Diagnostic report**: copy or export a full device snapshot for adaptation
- Diagnostics screen with a capability probe

## What is verified, and what is not

**Verified on a real device (vivo V2430A, Dimensity 9300+, Android 16):**

- PowerHAL is reachable through Shizuku and returns valid handles
- The command array format, ids and transaction codes behave as documented
- The read-back of `scaling_min_freq` / `scaling_max_freq` reflects service state

**Not verified:**

- **Whether a soft min/max range takes effect end-to-end on this device.** The code sends it
  and the protocol supports it, but every on-device observation so far has been confounded by
  stranded requests from earlier builds. See "Known open problem" below — this is the first
  thing to re-test.
- GPU control (the GPU page is present; the PowerHAL GPU path is not used at all — GPU goes
  through sysfs)
- Anything on a device other than the one above

## Known open problem: stranded requests

Builds before the current one wrote the user's range into the **hard-limit** pair and did not
reliably release their handles. Hard limits are sticky, and a request whose handle was lost
cannot be released.

**On the test device there are almost certainly such requests still clamping clusters.** They
will survive app upgrades.

**Clearing them:** restart Shizuku (which is the process that issues the calls), or reboot.
Do this before evaluating any change to frequency behaviour, otherwise the measurement is
against a polluted device.

The current code prevents new ones — see [architecture.md](architecture.md#the-one-rule-that-matters-cpucontrol)
— and reports `仍与初始值不同,可能有旧的调频请求残留` when a release does not return the kernel
to its recorded baseline.

## Immediate next steps

1. **Re-test a range on a clean device state.** Restart Shizuku, confirm the log shows the
   baseline matching stock, then apply an asymmetric range and read the log's
   `内核实际上下限` line. That single line settles the question.
2. **Transaction code detection** — the app hardcodes 22/23/24, which is correct on the test
   device and not portable. Implements the read-only scheme in
   [architecture.md](architecture.md#transaction-code-detection).
3. **Fill the Lab** — a Settings section for probed, verified-on-this-device features:
   per-cluster core-count limits (`0x00800000` / `0x00804000`), one-tap turbo
   (`0x00414000 = 1`), GPU OPP-count via `querySysInfo`, and the accelerator/DRAM/CCI
   families listed in [protocol.md](protocol.md#5-other-resource-families).
   **Rule: probe first, and hide anything the device does not accept.**
4. **First tests** for `CpuControl` — see
   [architecture.md](architecture.md#testing). `PowerHal` needs to become an interface.

## Deliberately not done

- **`setPriorityByUid` / `flushPriorityRules`.** They look like a system-wide rule table with
  unknown blast radius and possible persistence. Not shipping until the semantics are
  established. See [pitfalls.md](pitfalls.md#12).
- **Hard limits.** Only the soft pair is written; hard limits are left to the platform's
  thermal policy.
- **CCI as a naive slider.** Raising the interconnect clock only helps when cross-cluster
  communication is the bottleneck; otherwise it spends power that the CPU clusters share a
  budget with. If exposed at all, it belongs in the Lab at stock default with that warning.
- **`PERF_RES_CPUFREQ_PERF_MODE`** is understood but not wired up yet — it sets every floor to
  its maximum, which destroys any range in effect.

## Repository hygiene

`.gitignore` excludes the reverse-engineering working set (`*.apk`, `*.dex`, `*.jar`,
`cfr_out/`, `apktool_out2/`, `tools/`, `*.bin`). **Keep it that way** — those are derived from
a third party's binary and must not be published. `git ls-files` should only ever show source,
docs and this project's own assets.

## Where to look

| task | file |
|---|---|
| the protocol, with sources | `docs/protocol.md` |
| why something is the way it is | `docs/pitfalls.md` |
| layers and invariants | `docs/architecture.md` |
| CPU control | `data/cpu/CpuControl.kt` |
| wire format | `data/powerhal/PowerHal.kt` |
| device probe | `data/diag/CapabilityProbe.kt` |
| the report users send back | `data/diag/DiagReport.kt` |
