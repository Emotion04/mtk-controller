# Device record — vivo V2430A

Everything below is **observed**, not inferred. The raw diagnostic report this is
built from is archived at
[`reports/2026-09-12-vivo-v2430a-0.3.3.txt`](reports/2026-09-12-vivo-v2430a-0.3.3.txt).

This file exists because the app's own read-back turned out to be blind on this
device (see §2), which makes the user's external observations the only ground
truth available.

---

## 1. Environment

| | |
|---|---|
| Device | vivo V2430A (`PD2430`), board `k6989v1_64` |
| SoC | MediaTek MT6989 (Dimensity 9300+) |
| Android | 16 (API 36) |
| Build | `PD2430D_A_16.1.12.0.W10` |
| Shizuku | **ADB mode — uid 2000, not root** (started from wireless debugging) |
| User service | bound, actual uid 2000 |

## 2. What the kernel exposes — and what it does not

**This is the most important section in the file.**

| node | readable? |
|---|---|
| `cpufreq/policyN/scaling_cur_freq` | **yes** — 1600 / 2100 / 2100 MHz observed |
| `cpufreq/policyN/scaling_min_freq` | **no** |
| `cpufreq/policyN/scaling_max_freq` | **no** |
| `cpufreq/policyN/cpuinfo_min_freq` / `cpuinfo_max_freq` | **no** |
| `/proc/ppm/policy/*` | no |
| `/proc/perfmgr/*` | no |
| `/sys/kernel/fpsgo/fbt/*` | no |
| `/dev/cpuctl/*/cpu.uclamp.*` | no |

Both the direct read and the elevated `cat` fail, so this is not a permissions
problem the app can work around — as uid 2000 it simply cannot see these.

**Consequences, and they are large:**

- `CpuControl` cannot verify an apply or a release on this device. Every
  `内核实际上下限` line in its logs says `读取失败`.
- Every conclusion this project drew from a read-back — including several rounds
  of "the ceiling did not take effect" — was drawn from a measurement that does
  not exist. **Treat all of them as unverified.**
- The read-only panel's 「谁在限制」 section is empty here for the same reason.
- `Scaling_cur_freq` still works, which is why the home dashboard's chart is
  fine, and why the *user's* observations remain usable.

Note the descriptor probe also fails on this device (`接口标识 = 取不到`), so the
one zero-side-effect way to confirm the binder is MTK's is unavailable too.

## 3. Cluster layout

```
index=0  policy0  cpu 0-3   18 steps   300-2000 MHz
index=1  policy4  cpu 4-6   25 steps   550-2850 MHz
index=2  policy7  cpu 7     30 steps   600-3400 MHz
```

Driver: `mtk-cpufreq-hw` on all three. `governor` reads as null and the governor
node is not writable, so the governor control is inert here.

`index` is the enumeration order of the policy directories and **is** the command
index — `id = base + index * 0x100`.

## 4. Observed frequency behaviour

From the user, watching an external CPU monitor rather than the app:

- The three clusters settle at roughly **1.8 / 2.1 / 2.1 GHz**.
- **A range whose ceiling is below those values works.**
- **A single value acts as a floor, not a lock** — the frequency floor becomes the
  requested value, but the frequency can still rise above it.
- **A range whose floor is above those values has no effect.**

Read together: something is holding an effective **ceiling** at about
1.8 / 2.1 / 2.1 GHz, and the app's ceiling writes cannot get above it. The third
observation is the same fact from the other side — a floor set above a ceiling
needs the kernel to raise the ceiling to meet it, which does not happen here.

Why those particular numbers, and whether the cap is the vendor's thermal/DCVS
logic or something else, is **not established**.

## 5. What applies to this device

- **Only the soft pair is sent** (`MIN_CLUSTER_n`, `MAX_CLUSTER_n`). Hard limits
  are a no-op here — the node they are written to does not exist.
- Governor control does nothing.
- The GPU page finds no channel.
- Every lab feature whose node is listed as unreadable in §2 will report
  「本机不支持」, correctly.

## 6. How to get usable evidence on this device

Because the app cannot read the limits back, **the only trustworthy measurement
comes from outside it**:

1. an external CPU monitor (what the user has been using), or
2. `adb shell cat /sys/devices/system/cpu/cpufreq/policyN/scaling_min_freq` etc.
   from a PC with adb, if one is available — adb runs as the same uid 2000, so
   this may fail for the same reason; worth one attempt to confirm.

Any change made against this device should be judged by one of those, not by the
app's own log.
