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
id = MAJOR << 22 | MINOR << 14 | GROUP << 8
```

`MAJOR` selects the subsystem (`1` CPUFREQ, `2` CPU cores, `3` GPU, `4` DRAM, `5` scheduler,
`6` APU, `7` power, `8` FPS, `9` display, `10`/`11` network and IO, `13` touch, `64` OEM),
`MINOR` the sub-feature, and `GROUP` the low enumeration slot.

> An earlier revision of this file wrote the middle field as `MINOR << 8`. That is a
> convenient shorthand — it happens to give the right answer for CPU frequency, because
> there `MINOR` is 0 — but it is wrong in general. Use the `<<14` form when synthesising ids
> outside the CPU frequency family.

For CPU frequency it does reduce to the simple form seen in practice:

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
| `/proc/perfmgr/syslimiter/syslimiter_limit_freq` | integer kHz | the platform's own master frequency cap (`POWER_SYSLIMITER`, `0x01C40400`) |
| `/proc/ppm/policy/thermal_limit`, `thermal_cur_power` | integer | what the thermal engine is currently enforcing |
| `/proc/cpufreq/cpufreq_cci_mode` (older) / `/proc/cpuhvfs/cpufreq_cci_mode` (mt6879+) | `0`/`1` | CCI mode: **a binary flag, not a frequency** |
| `/proc/perfmgr/tchbst/user/usrtch` | integer | touch-boost state, shared with MTK's own tuning |

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

## 8. Identifying the binder before trusting it

The same service name can be answered by a different vendor's HAL — that is not hypothetical,
see section 10. Before sending anything, ask the binder who it is:

```
transact(0x5F4E5446, emptyParcel, reply, 0)   // INTERFACE_TRANSACTION
descriptor = reply.readString()               // must be "com.mediatek.powerhalmgr.IPowerHalMgr"
```

`INTERFACE_TRANSACTION` is a Binder meta-transaction answered **before** AIDL dispatch and
before any permission check, so it sits outside the generated stub's range-guarded
`enforceInterface` and an **empty parcel is enough**. No method body runs; there is no side
effect at all.

Two gotchas:

- It must go through `IBinder.transact()`. `ShizukuBinderWrapper.getInterfaceDescriptor()`
  returns **null**, because the wrapper is not the generated Stub.
- If the descriptor is not MTK's, do not guess an id space — fall back to a sysfs-only mode.
  Never decide the id space by trial acquire.

## 9. Ids that are not ours: `0x40804100` and friends

A vendor framework class was observed using `PERF_RES_CPUFREQ_MAX_CLUSTER_0 = 0x40804100`,
`_CLUSTER_1 = 0x40804000`, `_CLUSTER_2 = 0x40804200`, `PERF_RES_GPU_FREQ_MAX = 0x42808000`.
An earlier revision of this file described that as "a shifted MTK id space". **That was
wrong.** The values are **Qualcomm MPCTLV3 resource ids** — Qualcomm publishes the array
`{0x40C00000, 0x1, 0x40804000, 0xFFF, 0x40804100, 0xFFF, …}`, and the low 16 bits coincide
with MTK's semantics by coincidence. In MTK's encoding `0x40804100` decodes to `MAJOR = 258`,
which MediaTek never defines.

The practical consequence is the opposite of "other phones may not support it": **those ids
are not MTK resources at all**, and an MTK device should use the `0x0040xxxx` family. A vendor
that ships both MediaTek and Qualcomm variants in one framework class is the likely
explanation for seeing them together.

## 10. Probing safely

- **Pass a short non-zero `duration` when the request is a probe.** The normal path uses `0`
  (never expires), which is what makes a lost release permanent.
- **`querySysInfo` is the safe target for discovering transaction codes** — it is read-only.
  There is also a fully inert enumeration: `transact(200, emptyParcel, reply, 0)` tells you
  which stub generation the interface uses, after which codes `1..40` can be swept with
  **empty parcels**; a `SecurityException` on `readException()` means "the method exists"
  while no method body executes.
- **Read the codes instead of probing them** where possible. `com.mediatek.powerhalmgr.*`
  lives on the boot classpath, and SELinux permits an app domain to read `system_file`, so the
  `TRANSACTION_*` constants can be pulled straight out of the vendor jar's DEX. Resolve the
  jar path via `getCodeSource().getLocation()` rather than hard-coding a filename.

## 11. Id drift between BSP revisions

Ids are **not** stable across device generations even within MediaTek's own namespace. The
same logical resource was observed at `0x0143C100` on one platform and at `0x0143C200` on a
newer one — a two-`MINOR` shift. FPS and scheduler ids disagree between vendor tables in
several places.

**Consequence:** hard-coding any id outside the small CPU-frequency family is unsafe. Probe,
and treat an unfamiliar device as a fresh problem.

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
