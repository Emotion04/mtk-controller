# MediaTek PowerHAL — verified protocol reference

Everything here is either **confirmed** against a primary source (a MediaTek header, the
shipped AIDL, an open-source kernel drop, a vendor powerhint XML) or explicitly marked
`INFERRED` / `UNKNOWN`. Value semantics that were never verified are marked as such rather
than filled in with plausible numbers.

> Treat any summary of this protocol — including this file — as lossy. When implementing a
> call sequence, re-check it against a primary source. Section 7 explains why that rule
> exists the hard way.

---

## 1. Service and interface

| | |
|---|---|
| Service name | `power_hal_mgr_service` |
| Interface token | `com.mediatek.powerhalmgr.IPowerHalMgr` |
| Reachable without root | yes, through Shizuku (`SystemServiceHelper.getSystemService` + `ShizukuBinderWrapper`) |
| Backing library | `libpowerhal` (the `perfservice` component) |

## 2. Transactions

```
perfLockAcquire(handle, duration, int[] list) -> int handle
perfLockRelease(handle)                        // oneway
querySysInfo(type, param) -> int
```

**Wire format**

```
acquire:  writeInterfaceToken(IFACE)
          writeInt(0)                     // handle slot
          writeInt(duration)              // 0 = never expires
          writeIntArray([cmdId, value, cmdId, value, ...])
          transact(ACQUIRE, data, reply, 0); reply.readException(); h = reply.readInt()

release:  writeInterfaceToken(IFACE)
          writeInt(handle)
          transact(RELEASE, data, null, FLAG_ONEWAY)     // reply = null, oneway

query:    writeInterfaceToken(IFACE)
          writeInt(type); writeInt(param)
          transact(QUERY, data, reply, 0); r = reply.readInt()
```

`duration = 0` means **the request never expires on its own**. That single fact explains a
whole class of misbehaviour — see [pitfalls.md](pitfalls.md#3).

### Transaction codes are version-dependent ⚠️

The numbers come from **AIDL method declaration order**, so they are a property of the
interface revision in the ROM's framework jar — not of the device model, and not stable
across Android versions or OTAs.

Two known revisions, differing by two methods declared earlier in the interface:

| | this project's target device | another observed vendor framework |
|---|---|---|
| `perfLockAcquire` | **22** | 23 |
| `perfLockRelease` | **23** | 24 |
| `querySysInfo` | **24** | 25 |

Do not hardcode. See [architecture.md](architecture.md#transaction-code-detection).

## 3. Command id layout

```
id = MAJOR << 22 | MINOR << 8 | INDEX
```

`INDEX` selects the cluster / instance, `MINOR` selects a sub-family within `MAJOR`, and
`MAJOR` selects the resource area. For CPU frequency this reduces to the simple form seen in
practice:

```
id = base + 0x100 * policy_index
```

where `policy_index` is the **enumeration order of the cpufreq policy directories**
(`policy0` → 0, `policy4` → 1, `policy7` → 2). The order matters: it *is* the index, so a
device with an unusual policy layout maps differently. Always derive it by scanning, never
assume.

## 4. CPU frequency resources

| id | name | meaning |
|---|---|---|
| `0x00400000 + n*0x100` | `PERF_RES_CPUFREQ_MIN_CLUSTER_n` | soft floor (kHz) |
| `0x00404000 + n*0x100` | `PERF_RES_CPUFREQ_MAX_CLUSTER_n` | soft ceiling (kHz) |
| `0x00408000 + n*0x100` | `PERF_RES_CPUFREQ_MIN_HL_CLUSTER_n` | **hard** floor |
| `0x0040C000 + n*0x100` | `PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n` | **hard** ceiling |

**`HL` = hard limit, not "thermal".** The soft pair is an ordinary floor/ceiling. The hard
pair is a separate mechanism: `libpowerhal` writes it **only** to
`/proc/ppm/policy/hard_userlimit_cpu_freq`, and setting both halves to the same value is the
documented way to hard-lock a cluster.

A real range is an ordinary, supported request — two independent entries in the same array:

```c
// MediaTek's own perfservice unit tests
{PERF_RES_CPUFREQ_MIN_CLUSTER_0, 1000000, PERF_RES_CPUFREQ_MAX_CLUSTER_0, 1200000}
```

and shipped vendor powerhint XMLs emit asymmetric pairs too (e.g. `MIN_CLUSTER_0=1075000`
with `MAX_CLUSTER_0=3000000`).

> `MIN_HL` is the reading with the strongest evidence (`mtkperf_resource.h`, quoted verbatim).
> One search could not find that name and marked the id unidentified, and a third found a
> `PERF_RES_CPUFREQ_MAX_THERMAL_CLUSTER_*` family. The reconciliation most consistent with
> all three: **the hard-limit family and the thermal family are the same thing under two
> names** — the thermal engine is what drives hard limits. Confidence: medium-high. This
> project does not depend on the answer, because it only sends the soft pair.

## 5. Other resource families

Confirmed ids unless marked otherwise.

| id base | resource | notes |
|---|---|---|
| `0x00410000` | `PERF_RES_CPUFREQ_CCI_FREQ` | cache-coherent interconnect / DSU clock |
| `0x00414000` | `PERF_RES_CPUFREQ_PERF_MODE` | value must be `1`; pins every cluster to max, all cores online |
| `0x00800000 + n*0x100` | `PERF_RES_CPUCORE_MIN_CLUSTER_n` | **online core count** floor — an integer, not a frequency |
| `0x00804000 + n*0x100` | `PERF_RES_CPUCORE_MAX_CLUSTER_n` | online core count ceiling |
| `0x00C00000` | `PERF_RES_GPU_FREQ_MIN` | ⚠️ value is an **OPP index**, not kHz |
| `0x00C04000` | `PERF_RES_GPU_FREQ_MAX` | OPP index |
| `0x00C08000` | `PERF_RES_GPU_FREQ_LOW_LATENCY` | level |
| `0x01000000` | `PERF_RES_DRAM_OPP_MIN` | OPP index |
| `0x01800000 / 0x01800100` | `PERF_RES_AI_VPU_FREQ_MIN_CORE_0/1` | `INFERRED` — units unknown |
| `0x01804000 / 0x01804100` | `PERF_RES_AI_VPU_FREQ_MAX_CORE_0/1` | `INFERRED` |
| `0x03000000` | `PERF_RES_THERMAL_POLICY` | `UNKNOWN` — the id exists; its legal values were not established |
| `0x03004000` | `PERF_RES_UX_PREDICT_LOW_LATENCY` | level |
| `0x03400000` / `0x03404000` / `0x03408000` | `SCREEN_OFF_STATE` / `SPORTS_MODE` / `TOUCH_BOOST_OPP` | levels |
| `0x10000000+` | `PERF_RES_CUSTOM_RESOURCE_1…` | range MediaTek reserves for OEM additions |

**Two encoding traps.** CPU frequency resources take **kHz**; GPU and DRAM take an **OPP
index**. Mixing them is a silent no-op at best. `PERF_RES_CPUFREQ_PERF_MODE = 1` sets every
cluster's floor to its maximum, which destroys any range you had set.

## 6. Kernel landing points

| node | form | notes |
|---|---|---|
| `/proc/ppm/policy/hard_userlimit_cpu_freq` | `"min max min max …"` | hard limits only |
| `/proc/ppm/policy/userlimit_min_cpu_freq`, `…_max_cpu_freq` | `"<cluster> <freq>"` | soft limits, older kernels |
| `/sys/devices/system/cpu/cpufreq/policyN/scaling_min_freq` / `scaling_max_freq` | integer kHz | where the result is observable |
| `/proc/cpudvfs/cpufreq_debug` | `"<cluster> <min> <max>"` | the one MTK interface carrying a full range in a single write; needs root |
| `/sys/kernel/fpsgo/fbt/limit_cfreq`, `limit_rfreq` | integer | FPSGO per-cluster ceiling/floor |

### Why a range can appear to collapse

`libpowerhal` merges **every enabled scenario**:

```c
floor   = max(all floors)
ceiling = min(all ceilings)
if (ceiling < floor) ceiling = floor;   // ceiling silently raised to meet the floor
```

So one stale request still holding a ceiling makes every later ceiling write look ignored,
and the "raise the ceiling to the floor" rule turns that into an apparent hard lock. A range
is not collapsed by the API; it is collapsed by an earlier request that was never released.

## 7. What a reference implementation on-device did

Observed behaviour of a tuning app on the target device, recorded because it shows what the
protocol looks like when used correctly — not as a specification.

- It sent **all four CPU ids with the same frequency value**, per cluster. That is a hard
  lock, not a range. It had no range UI, so this is a limitation of that app, **not** of the
  API (section 4).
- It **released its previous handle before acquiring a new one**, and aborted the apply if
  that release failed.
- It persisted the returned handle and read it back on the next apply and on release.
- For GPU it emitted `[MIN, 0, MIN+0x100, 0, MAX, hi, MAX+0x100, lo]` with two different
  values in the last pair.
- It treated a returned handle as proof of success. On-device read-back shows it is not —
  see [pitfalls.md](pitfalls.md#2).

## Sources

- `mtkperf_resource.h` — the `PERF_RES_*` enum, including the `MIN`/`MAX`/`MIN_HL`/`MAX_HL`
  CPU ids and the `0x00414000` perf-mode id.
- `perfservice_types.h` — `scn_freq_min` / `scn_freq_max` / `scn_freq_hard_min` /
  `scn_freq_hard_max` per cluster.
- `perfservice.cpp` (vendor open-source kernel drops, e.g. PotatoDevices
  `hardware/power/lib/powerhal/`) — the scenario merge and the ceiling/floor alignment rule.
- MediaTek perfservice unit tests — asymmetric `MIN`/`MAX` pairs.
- Shipped vendor `powerhint.xml` files (StatixOS and other device trees) — real asymmetric
  min/max usage, and the `PERF_RES_CPUFREQ_{MIN,MAX}_CLUSTER_n` naming.
- A public decompiled vendor framework's `IPowerHalMgr.java` — the transaction constant table
  used in section 2.
