# MediaTek PowerHAL — the resource namespace

The `PERF_RES_*` and transaction inventory assembled from public sources while
researching this protocol. **This is a catalogue, not a verified list** — note the
status marker on each entry. What the app actually depends on is far smaller and
lives in [protocol.md](protocol.md).

Ids drift between BSP revisions (see
[protocol.md §11](protocol.md#11-id-drift-between-bsp-revisions)), so nothing here
may be hardcoded without the probe described in
[architecture.md](architecture.md#device-adaptation).

---


## Binder transport and service detection

**`MAJOR<<22 | MINOR<<14 | GROUP<<8` · PERF_RES_* command-id encoding**  —  `已确认`

Universal MTK resource-id formula. Verified locally against the local reference dump whose header is literally COMMAND,MAJOR,MINOR,GROUP: SCHED_UCLAMP_MIN_TA,5,2,3 -> 0x01408300; GPU_FREQ_MAX,3,1,0 -> 0x00C04000; CPUFREQ_MIN_CLUSTER_2,1,0,2 -> 0x00400200.

- 取值:MAJOR selects subsystem (1=CPUFREQ ... 15=PEAK_POWER, 64=custom). MINOR = sub-feature. GROUP = low enumeration slot (cluster/core index or running counter). Practically id = base + index*0x100.
- 可用于:Synthesise or validate any id yourself and detect typos in ROM tables. The most portable result in this catalogue.

**`0x16 (22)` · IPowerHalMgr.perfLockAcquire**  —  `已确认`

Acquires a handle covering every (id,value) pair. Wire: writeInterfaceToken; writeInt(0)=pl_handle new-lock; writeInt(duration); writeIntArray(flat [id,value,...]); transact(22,data,reply,0); readException(); readInt()=handle. Matches vendor HIDL perfLockAcquire(pl_handle, duration, boostsList, reserved) with reserved dropped.

- 取值:handle>0 = success. duration in ms; 0 = UNLIMITED/sticky. Array length must be even. Code 22 is per-ROM, not universal (OPPO A92s framework puts the same method at 23).
- 可用于:The only write path. While probing pass a short non-zero duration so a lost release cannot leave a sticky limit.

**`0x17 (23)` · IPowerHalMgr.perfLockRelease**  —  `已确认`

Releases a handle. Wire: writeInterfaceToken; writeInt(handle); transact(23, parcel, null, FLAG_ONEWAY).

- 取值:ONEWAY: transact returns true whenever the binder is alive, so the boolean is NOT a success verdict. Verify release by re-reading the kernel node.
- 可用于:Undoing a probe. Never report its boolean as success/failure to the user.

**`0x18 (24)` · IPowerHalMgr.querySysInfo**  —  `已确认`

Read-only query. Wire: writeInterfaceToken; writeInt(cmd); writeInt(param); transact(24,data,reply,0); readException(); readInt(). Reference app calls cmd=5,param=0 and treats the result as a GPU frequency level.

- 取值:Returns an int. The cmd enum is NOT published anywhere. Non-zero likely means 'supported, semantics unknown'.
- 可用于:BEST PROBE TARGET for runtime transaction-code discovery: read-only, so sweeping candidate codes is comparatively safe. Do not sweep the counterpart setSysInfo(int,String) which mutates state.

**`0x5F4E5446` · INTERFACE_TRANSACTION**  —  `已确认`

Binder meta-transaction answered by every Java Binder BEFORE AIDL dispatch; returns the interface descriptor string. It lies outside FIRST..LAST_CALL_TRANSACTION so the generated stub's range-guarded enforceInterface is skipped and an EMPTY parcel works.

- 取值:Constant. Empty parcel in, String out, flags=0. Must be issued via transact(); ShizukuBinderWrapper.getInterfaceDescriptor() returns null.
- 可用于:Zero-side-effect identity check that settles which vendor id-space to use (MTK 0x0040/0x00C0 vs Qualcomm 0x4080).


## CPU frequency (MAJOR 1)

**`0x00400000 (+0x100 per cluster 0..2)` · PERF_RES_CPUFREQ_MIN_CLUSTER_n**  —  `已确认`

Soft minimum frequency floor per cluster, node /proc/ppm/policy/userlimit_cpu_freq (or /proc/perfmgr/boost_ctrl/cpu_ctrl/perfserv_freq).

- 取值:kHz absolute (800000..3000000 observed). -1 = PPM_IGNORE / unrequest. The app's BASE_MIN=0x400000 is correct.
- 可用于:Primary CPU floor slider - the safest, most useful knob.

**`0x00404000 (+0x100 per cluster 0..2)` · PERF_RES_CPUFREQ_MAX_CLUSTER_n**  —  `已确认`

Soft maximum frequency ceiling per cluster, same soft PPM path.

- 取值:kHz; values are clamped to the cluster's OPP table at cmdSetting time. The app's BASE_MAX=0x404000 is correct.
- 可用于:CPU ceiling slider - the id the app's own comment already names correctly.

**`0x00408000 (+0x100 per cluster 0..2)` · PERF_RES_CPUFREQ_MIN_HL_CLUSTER_n**  —  `已确认`

HARD minimum frequency limit. Separate mechanism from the soft pair; written to /proc/ppm/policy/hard_userlimit_cpu_freq.

- 取值:kHz. Setting min and max halves to one value hard-locks the cluster. setClusterHardFreq() is a NO-OP if /proc/ppm/policy/hard_userlimit_cpu_freq does not exist.
- 可用于:Advanced only, behind a warning - it overrides other clients and thermal.

**`0x0040C000 (+0x100 per cluster 0..2)` · PERF_RES_CPUFREQ_MAX_HL_CLUSTER_n**  —  `已确认`

HARD maximum frequency limit, node /proc/ppm/policy/hard_userlimit_cpu_freq.

- 取值:kHz; a max >= the cluster hardware max is sent as -1 (no limit). Hard limits survive a soft release.
- 可用于:Advanced hard cap. A stuck hard limit is far more disruptive than a stuck soft limit - never use for probing.

**`0x00410000` · PERF_RES_CPUFREQ_CCI_FREQ**  —  `已确认`

CCI (cache-coherent interconnect / DSU fabric) mode selector, node /proc/cpufreq/cpufreq_cci_mode (older) or /proc/cpuhvfs/cpufreq_cci_mode (mt6879+).

- 取值:Binary mode flag, not kHz: 0 = normal/auto (default), 1 = performance. Con-table Compare=more, Min 0, Max 1. The kernel CCI driver picks the actual clock.
- 可用于:At most a single 'memory latency boost' toggle. Too coarse for a slider and duplicates MTK's own scenario hints.

**`0x00414000` · PERF_RES_CPUFREQ_PERF_MODE**  —  `已确认`

CPU high-performance mode; MTK doc says value 1 turns on all CPU cores and pulls them to max frequency.

- 取值:Binary 0/1. Setting 1 destroys any min/max range the app has set.
- 可用于:A one-tap 'max performance' toggle, but it must be explicitly excluded/zeroed whenever the app offers ranges.

**`0x00418000 (+0x100 per core 0..7)` · PERF_RES_CPUFREQ_MIN_CORE_n**  —  `已确认`

Per-CPU-core frequency floor (finer than the per-cluster floor).

- 取值:kHz absolute.
- 可用于:Pro/advanced panel; rarely needed since cluster floors cover most cases.

**`0x0041C000 (+0x100 per core 0..7)` · PERF_RES_CPUFREQ_MAX_CORE_n**  —  `已确认`

Per-CPU-core frequency ceiling.

- 取值:kHz absolute.
- 可用于:Advanced per-core cap; not a consumer-facing control.

**`0x0040C000-family alias` · PERF_RES_CPUFREQ_MAX_THERMAL_CLUSTER_n (hypothesis)**  —  `未知`

Appears ONLY in Transsion/Infinix/itel powerhint XMLs (4 files total); absent from every MTK header, PowerHalMgr.java and the lamu command table. Used exactly where a thermal CPU ceiling belongs.

- 取值:Unknown. Values seen are clamps like 1300000/1500000/1800000.
- 可用于:Hypothesis only: treat as identical to MAX_HL_CLUSTER_n and verify by writing then reading /proc/ppm/policy/hard_userlimit_cpu_freq. Do not ship on the assumption.


## CPU cores and hotplug (MAJOR 2)

**`0x00800000 (+0x100 per cluster 0..2)` · PERF_RES_CPUCORE_MIN_CLUSTER_n**  —  `已确认`

Minimum number of online CPU cores per cluster. Node /proc/perfmgr/boost_ctrl/cpu_ctrl/*.

- 取值:Core count. Moto mt6879 con-table: Min 0, Max 6, Default 6 for cluster 0.
- 可用于:Hotplug floor / core pinning - directly controllable and very visible to the user.

**`0x00804000 (+0x100 per cluster 0..2)` · PERF_RES_CPUCORE_MAX_CLUSTER_n**  —  `已确认`

Maximum online cores per cluster.

- 取值:Core count (Max 2 observed on cluster 1 on mt6879).
- 可用于:Advanced; setting it low can offline cores a foreground task needs.

**`0x00808000` · PERF_RES_CPUCORE_PERF_MODE**  —  `已确认`

All-CPU de-isolation / performance mode; node /proc/perfmgr/boost_ctrl/cpu_ctrl/perfserv_all_cpu_deisolated.

- 取值:0/1. MTK's own scenario tables set it to 1 for ACT_SWITCH / PACK_SWITCH / LAUNCH / PROCESS_CREATE.
- 可用于:Excellent bounded 'keep all cores online' toggle that the OEM itself uses.

**`0x0080C000 (+0x100 per core 0..7)` · PERF_RES_CPUCORE_FORCE_PAUSE_CPU / _n**  —  `已确认`

Force-pause scheduling of CPU cores.

- 取值:0/1 per core; 0x0080C000 is the aggregate, 0x0080C100..0x0080C800 are cores 0..7.
- 可用于:Not useful for a tuning app - pausing cores degrades performance for no user-visible gain.

**`0x00810000 (+0x100 per core 0..7)` · PERF_RES_CPUCORE_ONLINE_CPU_n**  —  `已确认`

Bring a specific core online/offline.

- 取值:0/1 per core.
- 可用于:Diagnostics only; the platform hotplug governor manages this better.


## GPU (MAJOR 3)

**`0x00C00000` · PERF_RES_GPU_FREQ_MIN**  —  `已确认`

GPU minimum frequency request, sent as an OPP index into MTK's GPU DVFS table.

- 取值:OPP INDEX, not kHz. Index 0 = the HIGHEST GPU frequency (MTK doc: 'GPU frequency configured to 0 means the top gear'). Bounded by comp:100 in the resource table.
- 可用于:GPU floor slider, but the index is inverted relative to the label - the UI must map index 0 to 'max'. The app's GPU_CMD_A is correct.

**`0x00C00100` · PERF_RES_GPU_FREQ_MIN_HL**  —  `已确认`

HARD minimum GPU frequency. The app's GPU_CMD_B is correct.

- 取值:OPP index, 0 = fastest.
- 可用于:Advanced hard GPU floor; generally leave at 0.

**`0x00C04000` · PERF_RES_GPU_FREQ_MAX**  —  `已确认`

GPU maximum frequency ceiling.

- 取值:OPP index, 0 = fastest. Confirmed as GPU_FREQ_MAX by lamu_command.csv row 'PERF_RES_GPU_FREQ_MAX,3,1,0' -> 0x00C04000.
- 可用于:GPU ceiling. THE APP IS WRONG: PowerHal.kt calls this GPU_MIN, so its 'GPU min' control actually sets the maximum.

**`0x00C04100` · PERF_RES_GPU_FREQ_MAX_HL**  —  `已确认`

HARD maximum GPU frequency limit.

- 取值:OPP index, 0 = fastest. Confirmed by lamu_command.csv row 'PERF_RES_GPU_FREQ_MAX_HL,3,1,1'.
- 可用于:Hard GPU cap. THE APP IS WRONG: PowerHal.kt calls this GPU_MAX, so what the UI calls 'GPU max' is really the hard-limit variant of MAX.

**`0x00C08000` · PERF_RES_GPU_FREQ_LOW_LATENCY**  —  `已确认`

GPU low-latency mode.

- 取值:0/1.
- 可用于:Niche GPU responsiveness toggle.

**`0x00C0C100` · PERF_RES_GPU_GED_MARGIN_MODE**  —  `已确认`

GPU DVFS margin used by the GED governor. Node /sys/kernel/ged/hal/dvfs_margin_value.

- 取值:Integer; moto Min 1 Max 6553599 Default 130; another device 10..110. Higher = more aggressive GPU ramp.
- 可用于:Fine GPU aggression control for an advanced panel.

**`0x00C0C300` · PERF_RES_GPU_GED_LOADING_BASE_DVFS_STEP**  —  `已确认`

GED loading-based DVFS step size.

- 取值:Integer; moto Min 0 Max 25855 Default 4.
- 可用于:Advanced GED tuning; requires the accompanying margin to be meaningful.

**`0x00C0C600` · PERF_RES_GPU_GED_DVFS_LOADING_MODE**  —  `已确认`

GED DVFS loading mode selector.

- 取值:Small enum, 0..2 per con-table.
- 可用于:Pairs with GED_MARGIN_MODE for GPU behaviour profiles.

**`0x00C0C000` · PERF_RES_GPU_GED_BENCHMARK_ON**  —  `已确认`

GED benchmark mode (steady-state GPU performance).

- 取值:0/1.
- 可用于:Benchmark-only; not for daily use.

**`0x00C0C500` · PERF_RES_GPU_GED_GX_BOOST**  —  `已确认`

GED GX (GPU) boost trigger.

- 取值:0/1.
- 可用于:Optional GPU boost toggle.

**`0x00C10000 / 0x00C10100` · PERF_RES_GPU_POWER_POLICY / _POWER_ONOFF_INTERVAL**  —  `推断`

GPU power policy and its power on/off interval.

- 取值:Enum / interval; ranges not published.
- 可用于:Not useful until probed - no user-meaningful label exists for the enum.

**`0x00C14000 / 0x00C18000 / 0x00C1C000` · PERF_RES_GPU_ACP_HINT / _BW_MODE_HINT / _DCS_POLICY**  —  `推断`

GPU ACP, bandwidth-mode and DCS policy hints.

- 取值:Unpublished enums.
- 可用于:Not useful - opaque platform hints with no observable read-back.

**`0x00C28000-block` · PERF_RES_GPU_GED_LOADING_BASE_WINDOW_SIZE / STRIDE_SIZE / GED_FALLBACK_***  —  `推断`

GED tuning parameters: loading window/stride size, fallback timing, interval, window size and frequency adjust, plus FORCE_LOADING_BASE (0x00C30000) and FRAME_BASE_THRESHOLD (0x00C30100).

- 取值:Integers, unpublished ranges.
- 可用于:Deep GED tuning only; no user-facing value.

**`0x00C34000` · PERF_RES_GPU_RECLAIM_POLICY**  —  `未知`

GPU reclaim policy - present only on Xiaomi corot/air tables, not in the Motorola table.

- 取值:Unpublished enum.
- 可用于:Not useful; per-OEM and unverified.


## APU / AI (MAJOR 6)

**`0x01800000 / 0x01800100` · PERF_RES_AI_VPU_FREQ_MIN_CORE_0 / _CORE_1**  —  `已确认`

Minimum frequency floor for VPU core 0 and core 1. Seen in a real libPowerHal RscCfgTbl init dump as cmdID:1800000 param:-1 defaultVal:1 comp:100.

- 取值:DOES NOT use kHz. comp:100 bounds it, so it is an OPP index (kernel apu_opp2freq: 'opp 0 means the max freq') or a 0..100 percentage. Exact mapping unconfirmed - no captured log shows a value actually applied.
- 可用于:Candidate NPU/VPU floor control. Must be validated on-device; inert if the SoC has no VPU.

**`0x01804000 / 0x01804100` · PERF_RES_AI_VPU_FREQ_MAX_CORE_0 / _CORE_1**  —  `已确认`

Maximum frequency ceiling for VPU core 0 and core 1 (defaultVal 2 in the same init dump).

- 取值:comp:100 bound, non-kHz, OPP index or percent.
- 可用于:The most interesting AI knob for sustained-inference power/thermal control. Validate on-device.

**`0x01808000` · PERF_RES_AI_MDLA_FREQ_MIN**  —  `已确认`

Minimum frequency floor for the MDLA (the NPU proper). Exactly one MDLA min/max pair exists; there is no MDLA_CORE_1.

- 取值:comp:100 bound, non-kHz, OPP index or percent.
- 可用于:NPU floor - only meaningful for Neuron-SDK inference workloads.

**`0x0180C000` · PERF_RES_AI_MDLA_FREQ_MAX**  —  `已确认`

Maximum frequency ceiling for the MDLA.

- 取值:comp:100 bound, non-kHz.
- 可用于:NPU ceiling for thermal control during long inference.

**`0x01810000` · PERF_RES_AI_APUSYS_BOOST_IPU_IF**  —  `已确认`

APU QoS boost for the IPU interface; node /sys/kernel/apusys/mnoc_apu_qos_boost.

- 取值:0/1, Min 0 Max 1 Default 0 (ROM tables spell the id '0X01810000' with a capital X).
- 可用于:Harmless 'AI boost' toggle; user-visible benefit is limited.

**`none` · PERF_RES_APU_***  —  `已确认`

No such resource exists. MTK's AI block is VPU (2 cores) + MDLA (1 pair) only.

- 取值:n/a
- 可用于:Do not invent an APU frequency id - it will not exist.


## DRAM, CCI and memory (MAJOR 4)

**`0x01000000` · PERF_RES_DRAM_OPP_MIN**  —  `已确认`

Floor on the DRAM operating performance point; forbids DRAM/EMI DVFS from dropping below the chosen gear. Node /proc/perfmgr/boost_ctrl/dram_ctrl/ddr.

- 取值:OPP INDEX. Con-table: Compare=less, Min 0, Max 2, Default -1 (boot log prints normal:-1, default:-1). -1 = no request. DISPUTED DIRECTION - see disagreements.
- 可用于:A 'force DRAM high' toggle. Gains only on bandwidth-bound workloads; pinning it permanently is a battery-burner.

**`0x01000100` · PERF_RES_DRAM_OPP_MIN_LP5**  —  `已确认`

Same concept for LPDDR5 platforms, which expose more DRAM gears. Node /proc/perfmgr/boost_ctrl/dram_ctrl/ddr_lp5.

- 取值:OPP index, Min 0, Max 10, Default -1.
- 可用于:The relevant variant for a Dimensity 9300+ class device, IF the resource is registered at all.

**`0x01000200` · PERF_RES_DRAM_OPP_MIN_LP5_HFR**  —  `已确认`

LPDDR5 DRAM floor for high-frame-rate scenarios. Node .../ddr_lp5_hfr.

- 取值:OPP index, Min 0, Max 10, Default -1.
- 可用于:Platform-internal HFR tuning; do not expose.

**`0x01004000 / 0x01004100` · PERF_RES_DRAM_VCORE_MIN / _LP3**  —  `已确认`

Floor on the DVFSRC vcore operating point coupled to DRAM.

- 取值:OPP index.
- 可用于:Not recommended - voltage/OPP knobs with no user-facing value and real destabilisation risk.

**`0x01010000` · PERF_RES_DRAM_CM_MGR**  —  `已确认`

CPU-memory ratio manager (cm_mgr) enable; couples CPU frequency voting to bandwidth demand. Node /proc/cm_mgr/dbg_cm_mgr, written with a cm_mgr_perf_enable^ prefix.

- 取值:0/1, Default 1.
- 可用于:A more principled 'memory boost' than a raw DRAM floor, but still a platform tuning knob.

**`0x01010100 .. 0x01010A00` · PERF_RES_DRAM_CM_MGR_* block**  —  `已确认`

CM_MGR sub-controls: CAM enable, map-DRAM enable, aggressive, CM hint mask, DSU hint, DSU lmode, DRAM OPP floor, DRAM OPP ceil, DVFS QOS mode, map-DRAM OPP.

- 取值:0/1 flags and small enums; ranges unpublished.
- 可用于:Deep memory-subsystem tuning only; no consumer value.

**`0x01014000 .. 0x01014900` · PERF_RES_DRAM_CM_RATIO_UP_X_0..4 / CM_PASSIVE / CM_PERF_MODE_* / CM_THERMAL_HINT**  —  `已确认`

CM ratio-up thresholds, passive mode, perf-mode enable/ceiling/threshold and thermal hint.

- 取值:Integers; unpublished ranges.
- 可用于:Not useful for a consumer app.

**`0x0102C000 / 0x0102C200 / 0x0102C300` · PERF_RES_DRAM_VM_SWAPINESS / _WATERMARK_EXTRAKBYTESADJ / _WATERMARK_SCALEFACTOR**  —  `已确认`

Linux-visible memory-management knobs exposed through PowerHAL.

- 取值:swappiness 0..100; the others are KB and a percent scalefactor.
- 可用于:Attractive for low-RAM tuning profiles - but only if the device registers the ids.

**`0x0102C100` · PERF_RES_DRAM_VM_DROP_CACHES**  —  `已确认`

Flush the page cache.

- 取值:0/1-ish trigger.
- 可用于:DO NOT SHIP. It discards page cache and produces an immediate system-wide stall.

**`0x01030000 .. 0x01030500` · PERF_RES_DRAM_LMK_* block**  —  `已确认`

lmkd tunables: kill timeout, kill-heaviest-task, release-mem, thrashing limit, swap-free-low percent, minfree scalefactor.

- 取值:Platform-dependent integers; ranges unpublished.
- 可用于:Memory tuning for low-RAM profiles; aggressive values cause app kills the user will blame on the tuner.

**`0x01054000 .. 0x01054200` · PERF_RES_DRAM_DURASPEED_CPUTHRESHOLD / _CPUTARGET / _POLICYLEVEL**  —  `推断`

Duraspeed CPU thresholds and policy level.

- 取值:Unpublished integers.
- 可用于:Not useful; vendor-internal.

**`0x01008000 / 0x01008100 / 0x01008200` · PERF_RES_DRAM_VCORE_BW_ENABLE / _THRES / _THRESH_LP3**  —  `已确认`

DRAM vcore bandwidth tracking enable and thresholds.

- 取值:0/1 and integer thresholds.
- 可用于:Not useful for a tuning app.

**`0x0100C000` · PERF_RES_DRAM_VCORE_POLICY**  —  `推断`

DRAM vcore policy selector.

- 取值:Enum, unpublished.
- 可用于:Not useful.


## Scheduler and uclamp (MAJOR 5)

**`0x01408300` · PERF_RES_SCHED_UCLAMP_MIN_TA**  —  `已确认`

EAS uclamp.min floor for the top-app cgroup (the focused app). Node /dev/cpuctl/top-app/cpu.uclamp.min or /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_ta_uclamp_min.

- 取值:0..100 = percent of max capacity, Default 0. This is the resource MTK's own powerscntbl sets to 100 on LAUNCH and PACK_SWITCH.
- 可用于:The single highest-impact perceived-smoothness knob, and the modern replacement for schedtune boost. Top shortlist candidate.

**`0x01408100` · PERF_RES_SCHED_UCLAMP_MIN_FG**  —  `已确认`

EAS uclamp.min for the foreground cgroup. Node /dev/cpuctl/foreground/cpu.uclamp.min.

- 取值:0..100.
- 可用于:Companion to the top-app floor; label it as affecting ALL foreground processes, not one app.

**`0x01408200 / 0x01408000` · PERF_RES_SCHED_UCLAMP_MIN_BG / _ROOT**  —  `已确认`

uclamp.min for the background and root cgroups.

- 取值:0..100.
- 可用于:BG is useful for a battery mode; ROOT is redundant - hide it.

**`0x01408900` · PERF_RES_SCHED_UCLAMP_MAX_TA**  —  `已确认`

EAS uclamp.max capacity ceiling for the top-app cgroup. Node /dev/cpuctl/top-app/cpu.uclamp.max.

- 取值:0..100, default 100.
- 可用于:Cap how much CPU the focused app may demand - a genuine power-saving lever.

**`0x01408600 / 0x01408700 / 0x01408800` · PERF_RES_SCHED_UCLAMP_MAX_ROOT / _FG / _BG**  —  `已确认`

uclamp.max for root, foreground and background cgroups (GROUP slots 6,7,8 of MINOR 2).

- 取值:0..100, default 100.
- 可用于:FG/BG ceilings are useful for battery profiles; ROOT is redundant.

**`0x01408C00 / 0x01408D00` · PERF_RES_SCHED_UCLAMP_MIN_SYSBG / _MAX_SYSBG**  —  `已确认`

uclamp floor/ceiling for the system-background group.

- 取值:0..100.
- 可用于:Advanced; useful when tuning background work.

**`0x01400300` · PERF_RES_SCHED_BOOST_VALUE_TA**  —  `已确认`

Legacy schedtune boost for the top-app group. Node /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_ta_boost (or /dev/stune/schedtune.boost).

- 取值:-100..100, Default 0, Compare=more.
- 可用于:Good fallback when uclamp is absent on an older BSP; otherwise redundant with UCLAMP_MIN_TA.

**`0x01400100 / 0x01400200 / 0x01400000` · PERF_RES_SCHED_BOOST_VALUE_FG / _BG / _ROOT**  —  `已确认`

schedtune boost for the foreground, background and root groups.

- 取值:-100..100 (ROOT: 0..100, Default 0).
- 可用于:BG set negative is a legitimate battery-mode lever. ROOT overlaps with other writers - hide.

**`0x01404300 / 0x01404100` · PERF_RES_SCHED_PREFER_IDLE_TA / _FG**  —  `已确认`

schedtune.prefer_idle (latency-sensitive) flag for the top-app and foreground groups. Written through /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_prefer_idle.

- 取值:0/1, Default 0. These share ONE sysfs node as a bit-field (Prefix '3^' for TA, '1^' for FG) - a naive raw write clobbers the other group.
- 可用于:Latency / UI-smoothness toggle, but only via the Prefix-aware path, never by writing the node raw.

**`0x01410000` · PERF_RES_SCHED_BOOST**  —  `已确认`

Global kernel sched_boost switch; node /sys/devices/system/cpu/sched/sched_boost.

- 取值:0..2.
- 可用于:A simple bounded 'boost scheduling' toggle.

**`0x01438300 (+0x100 per cluster 0..2)` · PERF_RES_SCHED_UTIL_UP_RATE_LIMIT_US_CLUSTER_n**  —  `已确认`

How fast sugov_ext may raise frequency. Node /sys/devices/system/cpu/cpufreq/policyN/sugov_ext/up_rate_limit_us.

- 取值:Microseconds, Default 1000 (moto). Set 0 for instant ramp.
- 可用于:Strongly recommended advanced knob - setting 0 noticeably improves touch/scroll ramp.

**`0x01438600 (+0x100 per cluster 0..2)` · PERF_RES_SCHED_UTIL_DOWN_RATE_LIMIT_US_CLUSTER_n**  —  `已确认`

How fast sugov_ext may drop frequency. Node .../sugov_ext/down_rate_limit_us.

- 取值:Microseconds, Default 1000.
- 可用于:Raise it to smooth the frequency sawtooth and reduce jitter.

**`0x01438000 (+0x100 per cluster 0..2)` · PERF_RES_SCHED_UTIL_RATE_LIMIT_US_CLUSTER_n**  —  `已确认`

Combined sugov_ext rate limit (GROUP slots 0,1,2 of MINOR 14).

- 取值:Microseconds.
- 可用于:Advanced; the split up/down pair is usually the better control.

**`0x01414000` · PERF_RES_SCHED_MIGRATE_COST**  —  `已确认`

EAS migration cost between clusters. Node /proc/perfmgr/boost_ctrl/eas_ctrl/m_sched_migrate_cost_n.

- 取值:ns; Min 0, Max 10000000, Default 200000.
- 可用于:Advanced big.LITTLE balance tuning.

**`0x0143C100` · PERF_RES_SCHED_CORE_CTL_POLICY_ENABLE**  —  `推断`

core_ctl (dynamic hotplug) policy enable. Node /sys/module/mtk_core_ctl/parameters/policy_enable.

- 取值:0..2. NOTE: this id's neighbourhood drifted across revisions (see disagreements) - chopin assigns 0x0143C100 to BOTH this name and OFFLINE_THROTTLE_MS_CLUSTER_1, a bug in that ROM table.
- 可用于:Hotplug policy control, but probe before trusting - the surrounding ids moved by two MINOR steps between BSP revisions.

**`0x01420200-block` · PERF_RES_SCHED_CACHE_SET_CT_* / CACHE_AUDIT / CACHE_CPUQOS_MODE**  —  `已确认`

Cache-audit and cache-partition controls for ROOT/TA/FG/BG/SYSBG/RT/SYS/TASK groups.

- 取值:Unpublished enums/bitmasks.
- 可用于:Not useful - no user-facing meaning.

**`0x01444900-block` · PERF_RES_SCHED_TARGET_FREQ_C0-C2 / TARGET_MARGIN_C0-C2**  —  `已确认`

Per-cluster target frequency and margin used by the scheduler's frequency guidance.

- 取值:kHz / percent.
- 可用于:Advanced; overlaps with the CPUFREQ floors the app already exposes.

**`0x01454000-block` · PERF_RES_SCHED_GEAR_MIGR_* / TASK_GEAR_HINTS_***  —  `已确认`

Gear-based migration up/down percentages and task gear-hint controls.

- 取值:Percent / bitmasks.
- 可用于:Not useful for a consumer app.

**`0x0145C000-block` · PERF_RES_SCHED_CPUQOS_USER_GROUP_1..6 / CCL_TO_USER_GROUP_1..6**  —  `已确认`

CPU QoS user-group mapping.

- 取值:Unpublished.
- 可用于:Not useful.

**`0x01448100-block` · PERF_RES_SCHED_TA_MASK / FG_MASK / BG_MASK / TASK_LS_SET / TASK_VIP_SET / TA_VIP_SET / FG_VIP_SET / BG_VIP_SET**  —  `推断`

Scheduler VIP/priority task masks per group.

- 取值:Bitmasks / task sets; the underlying resource table shows comp:536870912 (0x20000000) suggesting an encoded pid/mask, not a plain boolean.
- 可用于:Do not expose without reversing the encoding - a wrong value can starve other tasks.


## Power, limiter, HPS and PPM (MAJOR 7)

**`0x01C40400` · PERF_RES_POWER_SYSLIMITER**  —  `已确认`

SystemLimiter frequency ceiling - the platform's global frequency cap. Node /proc/perfmgr/syslimiter/syslimiter_limit_freq.

- 取值:kHz; chopin Min 0, Max 3000000, Default -1 (-1 = no limit).
- 可用于:A clean, reversible power-saving master slider.

**`0x01C44000` · PERF_RES_POWER_SYSLIMITER_DISABLE**  —  `已确认`

Force-disables the SystemLimiter. Node /proc/perfmgr/syslimiter/syslimiter_force_disable.

- 取值:0/1, Default 0.
- 可用于:Escape hatch when the platform limiter is capping performance - but it removes an OEM safety ceiling, so gate it.

**`0x01C44100` · PERF_RES_POWER_SYSLIMITER_TARGET_FPS_TOLERANCE_PERCENT**  —  `已确认`

Tolerance percent used by the FPS-driven SystemLimiter.

- 取值:Percent.
- 可用于:Advanced; only matters if the device uses FPS-driven limiting.

**`0x01C40000 .. 0x01C40300` · PERF_RES_POWER_SYSLIMITER_60 / _90 / _120 / _144**  —  `已确认`

Per-refresh-rate SystemLimiter ceilings.

- 取值:kHz ceilings.
- 可用于:Advanced display-rate-aware power capping.

**`0x01C00000 / 0x01C04000 / 0x01C08000 / 0x01C0C000` · PERF_RES_POWER_CPUFREQ_HISPEED_FREQ / MIN_SAMPLE_TIME / ABOVE_HISPEED_DELAY / POWER_MODE**  —  `已确认`

Classic interactive/governor-style tunables exposed through PowerHAL.

- 取值:kHz / us / ms / enum.
- 可用于:Legacy governor tuning; mostly superseded by the sugov_ext rate limits.

**`0x01C10000 .. 0x01C1C100` · PERF_RES_POWER_HPS_* block**  —  `已确认`

HPS (hotplug scheduler) tunables: threshold up/down, times up/down, rush-boost enable/threshold, heavy task.

- 取值:Load percentages and counts; unpublished ranges.
- 可用于:Advanced hotplug-governor tuning; not a consumer control.

**`0x01C28000 / 0x01C24000 / 0x01C2C000 / 0x01C30000` · PERF_RES_POWER_PPM_MODE / ROOT_CLUSTER / HICA_VAR / LIMIT_BIG**  —  `已确认`

PPM power-policy mode, root cluster, HICA variable and the big-core limit.

- 取值:Enums / integers; unpublished.
- 可用于:PPM_LIMIT_BIG is a real system-wide big-core limiter, but without published ranges it is risky to expose.

**`0x01C3C000 / 0x01C3C100` · PERF_RES_POWER_CPUIDLE_MCDI_ENABLE / PM_QOS_CPUIDLE_MCDI_ENABLE**  —  `已确认`

MCDI CPU-idle enable (two aliases of the same feature).

- 取值:0/1.
- 可用于:Not useful for a tuning app.

**`0x01C3C200` · PERF_RES_PM_QOS_CPU_DMA_LATENCY_VALUE**  —  `已确认`

PM-QoS CPU DMA latency constraint.

- 取值:Microseconds of latency tolerance.
- 可用于:Advanced latency/PM trade-off; can defeat deep idle.

**`0x01C48000` · PERF_RES_POWER_MPMM_ENABLE**  —  `已确认`

MPMM (MediaTek power modelling) enable.

- 取值:0/1.
- 可用于:Not useful; internal power modelling.


## FPS / FPSGO / FBT / FRS (MAJOR 8)

**`0x02004000` · PERF_RES_FPS_FPSGO_ENABLE**  —  `已确认`

Master enable for FPSGO, MTK's frame-rate-driven CPU/GPU boost framework. Node /sys/kernel/fpsgo/common/fpsgo_enable.

- 取值:0/1.
- 可用于:The highest-leverage single toggle in MAJOR 8 - it gates all game/smoothness boosting.

**`0x02050000` · PERF_RES_FPS_FPSGO_IDLEPREFER**  —  `已确认`

Prefer-idle policy inside FPSGO. Node /sys/kernel/fpsgo/fbt/switch_idleprefer.

- 取值:0/1; chopin Default 1. Powerhint XMLs set it to 0 for scrolling.
- 可用于:A real responsiveness-vs-power trade the user can feel.

**`0x02018000 / 0x02014000` · PERF_RES_FPS_FBT_KMIN / _FLOOR_BOUND**  —  `已确认`

FBT (frame-boost) k parameter and floor bound. Nodes /sys/module/fbt_cpu/parameters/kmin and floor_bound.

- 取值:1..20 each.
- 可用于:Frame-boost aggressiveness - a genuine gaming knob.

**`0x02020000` · PERF_RES_FPS_FBT_BHR_OPP**  —  `已确认`

FBT boost-hold-rate CPU OPP held while boosting.

- 取值:OPP index; 0..15 on chopin, 0..31 on motorola; Default 1.
- 可用于:How hard frames are boosted - one of the strongest gaming knobs.

**`0x02024000` · PERF_RES_FPS_FBT_BHR**  —  `已确认`

FBT boost-hold-rate percent.

- 取值:0..100.
- 可用于:Pairs with BHR_OPP for frame-boost shaping.

**`0x02064000 / 0x02064100` · PERF_RES_FPS_FBT_LIMIT_CFREQ / _LIMIT_RFREQ**  —  `已确认`

Frame-boost CPU and render frequency caps. Nodes /sys/kernel/fpsgo/fbt/limit_cfreq and limit_rfreq.

- 取值:0..3000000 Hz.
- 可用于:Cap boost frequency to save power while gaming - a well-understood trade-off.

**`0x02054000 / 0x02054100 / 0x02054200 / 0x02054300 / 0x02054400` · PERF_RES_FPS_FPSGO_THRM_TEMP_TH / _LIMIT_CPU / _SUB_CPU / _ACTIVATE_FPS / _ENABLE**  —  `已确认`

FPSGO's own thermal limiting: threshold temperature, how much CPU it may take, the substitute-CPU allowance, the FPS at which it activates and the master enable.

- 取值:DegC (chopin sets TEMP_TH to 30 in some configs; default 65) and 0/1 for ENABLE. Full ranges unpublished.
- 可用于:Disabling (ENABLE=0) or loosening THRM_TEMP_TH stops FPSGO underclocking a game below what the thermal policy already allows - a known 'stutter while cool' cause.

**`0x02070000 / 0x02070200 / 0x02070300 / 0x02070400` · PERF_RES_FRS_ENABLE / _TARGET_TEMP / _MAX_FPS / _MIN_FPS**  —  `已确认`

FRS (Frame Rate Stabilizer): master enable, target temperature, and FPS bounds.

- 取值:FRS_TARGET_TEMP is in MILLIDEGREES C - real powerhint XMLs use 46500, 48000, 49000, 50000, 53000, 55000. FRS_ENABLE 0/1.
- 可用于:Raise TARGET_TEMP or disable FRS to stop the frame-rate saver capping FPS below the real thermal throttle point - a softer alternative to attacking THERMAL_POLICY.

**`0x02071B00 .. 0x02073200` · PERF_RES_FRS_FREQ_TEMP_MAPPING_CLUSTER{0,1,2}_1..8**  —  `已确认`

Per-cluster frequency/temperature mapping table used by FRS.

- 取值:Paired freq/temp entries.
- 可用于:Expert-only; requires understanding the whole FRS curve.

**`0x02073300 / 0x02073600` · PERF_RES_FRS_FREQ_TEMP_MAPPING_HYSTERESIS / FRS_RESET**  —  `已确认`

FRS mapping hysteresis and a reset trigger.

- 取值:Integer / trigger.
- 可用于:FRS_RESET is useful as a restore hook.

**`0x02058000 / 0x02058100 / 0x02058200 / 0x02058300 / 0x02058400 / 0x02058500` · PERF_RES_FPS_FPSGO_CM_BIG_CAP / CM_TDIFF (+90/120 variants)**  —  `已确认`

Big-core capacity cap and time-diff used by the capacity-margin heuristic, with per-refresh-rate variants. Node /sys/module/fbt_cpu/parameters/cm_big_cap.

- 取值:0..100 for caps (Default 95); CM_TDIFF is ns (-50000000..50000000, Default 1000000).
- 可用于:Advanced frame-boost shaping - a well-regarded gaming tune.

**`0x02000000 / 0x02000100` · PERF_RES_FPS_FSTB_FPS_LOWER / _UPPER**  —  `已确认`

FSTB frame-rate lower/upper bound enforced by the FPS governor.

- 取值:Integer fps; Default 1 and 60. Boot log shows per-game entries like 'init_fstb com.tencent.tmgp.sgame 2 60-60 30-30'.
- 可用于:A real 'cap FPS' / 'raise FPS floor' feature that MTK itself drives per game package. Note the governor may override per-package values.

**`0x0204C000 / 0x0204C100 / 0x0204C200 / 0x0204C300 / 0x0204C400 / 0x0204C500` · PERF_RES_FPS_GBE1_ENABLE / GBE2_ENABLE / GBE2_TIMER2_MS / GBE2_LOADING_TH / GBE2_MAX_BOOST_CNT / GBE_POLICY_MASK**  —  `已确认`

GBE (game boost engine) enables, timers, loading threshold, boost count and policy mask.

- 取值:0/1 and integers; ranges unpublished.
- 可用于:Opaque; only worth exposing once probed on-device.

**`0x02064000-block FBT_LIMIT_*` · PERF_RES_FPS_FBT_LIMIT_CFREQ_M / _LIMIT_RFREQ_M / FBT_CEILING_ENABLE / FBT_MINITOP_ENABLE**  —  `已确认`

Multi-scene FBT limit variants and ceiling/minitop enables.

- 取值:Hz / 0-1.
- 可用于:Advanced FBT ceiling control.

**`0x02034000 .. 0x02034200` · PERF_RES_FPS_FPSGO_MARGIN_MODE / _DBNC_A / _DBNC_B**  —  `已确认`

FPSGO margin mode and its debounce parameters.

- 取值:MARGIN_MODE 0..2.
- 可用于:Advanced.

**`0x0203C000 .. 0x0203C900` · PERF_RES_FPS_FBT_RESCUE_* block**  —  `已确认`

FBT rescue controls: F, percent, ultra-rescue, C, check, second-rescue enable, F_OPP.

- 取值:RESCUE_PERCENT 0..100; others enums/integers.
- 可用于:Advanced frame-rescue tuning; risky without a frame-timing test harness.

**`0x02098000 .. 0x02098400` · PERF_RES_FPS_FPSGO_JANK_DETECTION_MASK / JANK_MIN_CAP_RATIO / JANK_MAX_CAP_RATIO / JANK_PRIORITY / JANK_CPU_MASK**  —  `已确认`

Jank detection controls introduced on newer BSPs.

- 取值:Masks / ratios / priority; unpublished.
- 可用于:Advanced; no user-facing meaning yet.

**`0x0200C000 .. 0x02048000` · PERF_RES_FPS_FBT_SHORT_RESCUE_NS / MIN_RESCUE_PERCENT / DEQTIME_BOUND / BOOST_TA / EARA_BENCH / FSTB_JUMP_CHECK_* / FPSGO_GPU_BLOCK_BOOST**  —  `已确认`

Large block of FBT/EARA/FSTB tuning entries.

- 取值:Mixed integers and enums; ranges largely unpublished.
- 可用于:Mostly not useful without per-device probing.

**`0x02088000 / 0x02088100` · PREF_RES_FPS_FSTB_NOTIFY_FPS_BY_PID / PREF_RES_FPS_FSTB_CAM_FPS**  —  `推断`

Two entries in the vendor table itself are misspelled with the prefix PREF_ instead of PERF_, and require a target PID.

- 取值:Per-PID values.
- 可用于:Not useful as global settings - they are per-process hints. Do not surface.


## Thermal (policy, CFP, MAGT)

**`0x03000000` · PERF_RES_THERMAL_POLICY**  —  `已确认`

Selects the active MTK thermal-policy profile. Implemented as load_thm_api(idx,start) -> dlopen(/vendor/lib64/libmtcloader.so) -> change_policy("thermal_policy_%02d", start). Refcounted in the property vendor.thermal.manager.data.perf; the HIGHEST index with a non-zero count wins. Real logcat: '[load_thm_api] idx:1, start:1' then 'libMtcLoader: enable thermal policy thermal_policy_01.'

- 取值:int; resource table declares Min 0 MAX 19 Default -1. Value N requires /vendor/etc/.tp/.thermal_policy_NN to exist (modern devices ship 00..19, older ones only 00..02). perfservice cmdSetting() range-checks 0..19, so -1 sent through a powerhint XML is rejected as 'exceed reasonable range' and ignored - but -1 works fine as a system property / reset sentinel. Individual value meanings are NOT published (encrypted OEM blobs); only empirical: 8 is the most common gaming choice across vendors, vivo MT6983 uses 10/11/18/5/0 for its monster/benchmark/superperformance/perf/yuanshen modes, and 2 for the camera.
- 可用于:The single highest-leverage thermal knob - a user-selectable thermal profile. Must be released with the same index; provide a reset path.

**`0x03008000 .. 0x03008700` · PERF_RES_CFP_ENABLE / POLLING_MS / UP_LOADING / DOWN_LOADING / UP_TIME / DOWN_TIME / UP_OPP / DOWN_OPP**  —  `已确认`

CFP (CPU Frequency Policy) - MTK's frame-aware CPU boost governor. Node /proc/perfmgr/boost_ctrl/cpu_ctrl/cfp_* (or /sys/module/mtk_fpsgo/parameters/cfp_onoff).

- 取值:ENABLE 0/1; POLLING_MS 8..32767; UP/DOWN_TIME 1..32768; UP/DOWN_OPP 0..15.
- 可用于:Toggle and tune frame-rate-aware CPU boosting - noticeable in games, safe to flip, and the bounds are published.

**`0x0300C000` · PERF_RES_PERF_TASK_TURBO**  —  `已确认`

Task-turbo feature bitmask (per-task scheduler boost). Node /sys/module/task_turbo/parameters/feats.

- 取值:0..15 bitmask, Default 0. Powerhint XMLs set 15 for scrolling.
- 可用于:A small, bounded boost-mask control the OEM itself uses.

**`0x0300C100 .. 0x0300CA00` · PERF_RES_SMART_LAUNCH_* block**  —  `已确认`

Smart-launch time target, cluster-2 freq/cap/OPP maxima, per-task VIP enables, and memory/CPU/IO PSI thresholds.

- 取值:Mixed integers; ranges unpublished.
- 可用于:App-launch tuning; medium value, needs per-device probing.

**`0x03014000` · PERF_RES_THERMAL_SPORTS_CG_POLICY**  —  `已确认`

Thermal interaction with MTK's SPORTS mode clock-gating policy.

- 取值:Unpublished enum.
- 可用于:Not useful until probed.

**`0x03018000 .. 0x03018600` · PERF_RES_MAGT_* block**  —  `已确认`

MAGT (Multi-Agent Game Tuning?): thermal-aware threshold, fps-drop-aware threshold, battery average/max current advice, target-FPS throttling temperature, light threshold, game-suggestion job.

- 取值:Unpublished integers.
- 可用于:Not useful for a consumer app; no published semantics.

**`0x03004000 .. 0x03004F00` · PERF_RES_UX_PREDICT_LOW_LATENCY / _GAME_MODE / UX_SCROLL_SAVE_POWER / UX_SBE_* block**  —  `已确认`

UX prediction and SBE (scroll-boost engine) controls: rescue enhance, checkpoints, scroll durations, SBB enable-later, scroll frequency floor, frame decision, dynamic frame thresholds.

- 取值:0/1 flags and durations; unpublished ranges.
- 可用于:UX_SBE_SCROLL_FREQ_FLOOR is the only one with an intuitively user-visible effect.

**`0x03010000` · PERF_RES_TOUCH_CHANGE_RATE**  —  `已确认`

Touch sampling/change rate.

- 取值:Hz.
- 可用于:Possible touch-responsiveness tuning, but real effect is device-dependent.

**`0x03000000-block kernel path` · /proc/ppm/policy/thermal_limit and thermal_cur_power**  —  `已确认`

Userspace thermal power budget handed to PPM. Writing activates thermal protection; writing 0 disables it. Companion RO node thermal_cur_power prints 'current / min / max power'.

- 取值:Unsigned int power budget; 0 = disable/clear.
- 可用于:Direct route to observe or suppress thermal power capping - and a far better READ-OUT source than guessing at THERMAL_POLICY values.

**`0x02038000` · PERF_RES_FPS_EARA_THERMAL_ENABLE**  —  `已确认`

EARA thermal switch; also reachable at /sys/kernel/eara_thermal/enable.

- 取值:0/1.
- 可用于:Setting 0 is a known, commonly used way to stop EARA's thermal-driven ceiling on games.

**`0x0204C700 / 0x01440100` · PERF_RES_FPS_GBE_THRM_HDRM_THRS / PERF_RES_SCHED_THERMAL_HEADROOM_INTERVAL_TICK**  —  `已确认`

GBE thermal-headroom threshold and the scheduler's thermal-headroom sampling interval.

- 取值:Integers with unpublished bounds.
- 可用于:Only useful once probed on-device.

**`none` · thermal_sp / thermal_vr / thermal_vrsp**  —  `已确认`

These are libMtcLoader-internal slots 0..2 (the g_perf_tc table), NOT addressable through PERF_RES_THERMAL_POLICY. Slot N+3 == thermal_policy_NN, which matches both the snprintf path and the 'active: 4' log for idx=1.

- 取值:n/a
- 可用于:Do not try to select these as policy values - only 0..19 via themal_policy_NN exist.


## Touch boost and powerhint (MAJOR 13)

**`0x03408500` · PERF_RES_POWERHAL_TOUCH_BOOST_ENABLE**  —  `已确认`

Master enable for touch boost.

- 取值:0/1; moto Default 1.
- 可用于:A simple, safe on/off toggle for touch boost.

**`0x03408000` · PERF_RES_POWERHAL_TOUCH_BOOST_OPP**  —  `已确认`

CPU OPP applied on touch-down. Node /proc/perfmgr/tchbst/user/usrtch.

- 取值:OPP index; moto Min 0 Max 31 Default 2; chopin Min 0 Max 15 Default 2. Higher = stronger boost.
- 可用于:Classic, user-visible MTK tuning knob - touch-response boost strength.

**`0x03408100` · PERF_RES_POWERHAL_TOUCH_BOOST_DURATION**  —  `已确认`

How long the touch boost lasts.

- 取值:UNITS DIFFER PER PLATFORM: milliseconds on Motorola (Min 10 Max 2000 Default 100) but nanoseconds on chopin (Min 10000000 Max 2000000000 Default 200000000). Must be normalised per device.
- 可用于:Touch-boost length slider - but only after the app determines the device's unit scale.

**`0x03408200` · PERF_RES_POWERHAL_TOUCH_BOOST_ACTIVE_TIME**  —  `已确认`

Active window for touch boost.

- 取值:moto Min 0 Max 1000 Default 100; chopin Min 0 Max 1000000 Default 100000 - again different scales.
- 可用于:Advanced touch-boost timing.

**`0x03408800 / 0x03408900 / 0x03408A00` · PERF_RES_POWERHAL_TOUCH_BOOST_CLUSTER_0/1/2_OPP**  —  `已确认`

Per-cluster OPP applied by touch boost.

- 取值:OPP index.
- 可用于:Finer touch-boost targeting for big.LITTLE tuning.

**`0x03408300` · PERF_RES_POWERHAL_TOUCH_BOOST_EAS_BOOST**  —  `已确认`

EAS boost amount applied by touch boost.

- 取值:0..100, default 80.
- 可用于:Pairs with TOUCH_BOOST_OPP for a complete touch-boost profile.

**`0x03408D00 / 0x03408E00` · PERF_RES_POWERHAL_TOUCH_BOOST_PREFER_IDLE_TA / _FG**  —  `已确认`

Prefer-idle flags applied by touch boost.

- 取值:0/1.
- 可用于:Advanced touch-latency tuning.

**`0x03408F00 / 0x03409000` · PERF_RES_POWERHAL_TOUCH_BOOST_UP / _DOWN**  —  `推断`

Touch-boost up/down parameters.

- 取值:Unpublished.
- 可用于:Unknown semantics; do not expose.

**`0x03400000` · PERF_RES_POWERHAL_SCREEN_OFF_STATE**  —  `已确认`

Screen-off power state machine control.

- 取值:Enum: 0 = DISABLE, 1 = ENABLE, 2 = WAIT_RESTORE (vendor Java MTKPOWER_SCREEN_OFF_* constants).
- 可用于:Mostly internal - mis-setting it can break doze/suspend. Read-only is safer.

**`0x03404000 / 0x03404100 / 0x03404200` · PERF_RES_POWERHAL_SPORTS_MODE / _APP_SMART_MODE / _GAME_MODE_ENABLE**  —  `已确认`

PowerHAL sports mode, app-smart mode and game-mode enables.

- 取值:0/1 / enum.
- 可用于:Possible game-mode toggle, but semantics are not published.

**`0x03410000 .. 0x03410500` · PERF_RES_POWER_HINT_HOLD_TIME / EXT_HINT / EXT_HINT_HOLD_TIME / END_HINT_HOLD_TIME / INSTALL_MAX_DURATION / EXT_HINT_FOR_GAME**  —  `已确认`

Power-hint lifetimes and the install (compile) hint duration.

- 取值:HOLD_TIME 90000 observed in powerhint XMLs for install; others ms.
- 可用于:INSTALL_MAX_DURATION controls how long app-install boosts last - a power/performance trade the user can feel.

**`0x0341C000` · PERF_RES_POWERHAL_PRIORITY**  —  `推断`

PowerHAL internal priority.

- 取值:Unpublished.
- 可用于:Not useful.

**`0x03420000 / 0x03424000` · PERF_RES_POWERHAL_TEST_CMD / _ONESHOT_RESET**  —  `已确认`

Test command and one-shot reset hooks.

- 取值:Unpublished.
- 可用于:DO NOT SHIP POWERHAL_TEST_CMD. ONESHOT_RESET is only useful as an internal restore hook.

**`0x0300C100-area whitelist` · /data/vendor/powerhal/power_whitelist_cfg.xml**  —  `已确认`

The persistent per-app whitelist: /vendor/etc/power_app_cfg.xml is the shipped table (<WHITELIST> with <Package name><Activity name><FPS><data cmd=PERF_RES_x param1=y>), and /data/vendor/powerhal/power_whitelist_cfg.xml is an optional override checked at boot. Boot log prints parser limits 'nXmlPackNum:32 nXmlActivityNum:32 nXmlCmdNum:48'.

- 取值:Hard cap of about 32 packages / 32 activities / 48 commands. Keyed by package AND activity name, so the app must be installed and actually go foreground for an entry to fire.
- 可用于:The correct, non-binder route to per-app tuning - and a genuine feature differentiator, since it persists across reboot.


## Display (MAJOR 9)

**`0x02400000` · PERF_RES_DISP_DFPS_MODE**  —  `已确认`

Dynamic FPS / display refresh mode selector.

- 取值:Enum from vendor Java: 0 DEFAULT, 1 FRR, 2 ARR, 3 INTERNAL_SW, 4 MAXIMUM.
- 可用于:Refresh-rate strategy - expose as a display-mode picker.

**`0x02400100` · PERF_RES_DISP_DFPS_FPS**  —  `已确认`

Requested display frame rate.

- 取值:Integer fps (60/90/120/144).
- 可用于:Direct refresh-rate setter - extremely user-visible.

**`0x0240C000` · PERF_RES_DISP_IDLE_TIME**  —  `已确认`

Display idle time before entering a low-power display state. Node /proc/displowpower/idletime.

- 取值:ms; chopin Min 33 Max 1000000.
- 可用于:A clean display power-saving trade-off slider.

**`0x02408000` · PERF_RES_DISP_DECOUPLE**  —  `已确认`

Display decouple mode.

- 取值:0/1.
- 可用于:Advanced; no user-facing benefit.

**`0x02414000` · PERF_RES_DISP_LPHRT_MODE**  —  `已确认`

Low-power high-refresh-rate mode.

- 取值:Enum.
- 可用于:Advanced refresh-rate power tuning.

**`0x02420100 .. 0x02420600` · PERF_RES_DISP_APP_DUR_60/90/120 / DISP_SF_DUR_60/90/120**  —  `已确认`

Per-refresh-rate app and SurfaceFlinger frame-duration targets.

- 取值:Duration values; unpublished scale.
- 可用于:Expert-only display latency tuning.

**`0x02420700 / 0x02424000` · PERF_RES_SF_LOW_POWER_HINT_ENABLE / DISP_WEBP_USE_THREADS**  —  `已确认`

SurfaceFlinger low-power hint enable and WebP multi-threading.

- 取值:0/1.
- 可用于:Minor power/encoding tweaks; low user value.

**`0x02404000 / 0x02410000 / 0x02418000 / 0x0241C000` · PERF_RES_DISP_VIDEO_MODE / VIDEO_COLOR_CONVERT_MODE / VIDEO_HIGH_CPU_FREQ_MODE / VIDEO_LOW_LATENCY_MODE**  —  `已确认`

Video playback mode, colour conversion and CPU-freq/low-latency video hints.

- 取值:Enums / 0-1.
- 可用于:NET_/VIDEO_LOW_LATENCY_MODE is a plausible video-smoothness toggle.


## Network and IO (MAJOR 10, 11)

**`0x02800000` · PERF_RES_NET_WIFI_CAM**  —  `已确认`

WiFi CAM (constantly-active mode). Node /proc/net/wlan/setCAM.

- 取值:0/1.
- 可用于:Higher WiFi responsiveness at a power cost; a legitimate toggle.

**`0x02804000` · PERF_RES_NET_WIFI_LOW_LATENCY**  —  `已确认`

Wi-Fi low-latency mode.

- 取值:0/1.
- 可用于:Gaming ping improvement toggle - high perceived value.

**`0x0280C100` · PERF_RES_NET_MD_GAME_MODE**  —  `已确认`

Cellular modem game mode (low-latency RRC handling).

- 取值:0/1.
- 可用于:Mobile-gaming latency toggle.

**`0x0280C000` · PERF_RES_NET_MD_LOW_LATENCY**  —  `已确认`

Modem low-latency mode.

- 取值:0/1.
- 可用于:Companion to MD_GAME_MODE.

**`0x02810000 / 0x02810100` · PERF_RES_NET_BT_AUDIO_LOW_LATENCY / _ULTRA_LOW_LATENCY**  —  `已确认`

Bluetooth audio latency modes.

- 取值:0/1.
- 可用于:Genuinely user-visible for headphone users - a good feature.

**`0x02808000 / 0x02808100 / 0x02808200` · PERF_RES_NET_NETD_BOOST_UID / _BLOCK_UID / _BOOST_UID_2**  —  `已确认`

The genuine per-UID network QoS resource. In power_app_cfg.xml it is set per package (1 for com.tencent.tmgp.sgame, 2 for cn.wsds.gamemaster).

- 取值:int; defaultVal 0, comp:500000 - consistent with a UID or a UID-mode value rather than a boolean. The 1-vs-2 distinction is NOT documented.
- 可用于:The only real per-UID knob in PowerHAL. Do not build on it until the 1-vs-2 meaning is confirmed on-device.

**`0x0280C200 / 0x0280C300` · PERF_RES_NET_MD_CERT_PID / _CRASH_PID**  —  `已确认`

Modem network QoS keyed by a specific PID.

- 取值:int pid; comp 500000 used as 'unset'.
- 可用于:Not useful for a general tuning app.

**`0x0280C400 / 0x0280C500` · PERF_RES_NET_MD_WEAK_SIG_OPT / NET_MD_HSR_MODE**  —  `已确认`

Weak-signal optimisation and HSR mode.

- 取值:0/1.
- 可用于:Plausible signal-quality toggles.

**`0x02804100 .. 0x02804800` · PERF_RES_NET_WIFI_SMART_PREDICT / TWT_SMART_STA / INFO_* block**  —  `已确认`

WiFi smart predict, TWT smart station, and delay/priority/phy-rate informational hints.

- 取值:0/1 and small integers.
- 可用于:Mostly informational - do not surface as controls.

**`0x02C0C000` · PERF_RES_IO_BLKDEV_READAHEAD**  —  `已确认`

Block-device read-ahead size.

- 取值:KB; resource table shows comp:1024.
- 可用于:Low-risk, portable storage tuning slider.

**`0x02C00000 / 0x02C00100 / 0x02C00200` · PERF_RES_IO_BOOST_VALUE / IO_UCLAMP_MIN / IO_UTIL_MIN**  —  `已确认`

IO boost flag and IO uclamp/util floors.

- 取值:BOOST 0/1; UCLAMP_MIN/UTIL_MIN 0..100.
- 可用于:Simple storage boost toggle; low risk.

**`0x02C18000` · PERF_RES_IO_UFS_CLKSCALE**  —  `已确认`

UFS clock scaling enable (spelled IO_UFS_CLK_SCALE on Xiaomi trees, same id).

- 取值:0/1.
- 可用于:Storage power/performance trade-off toggle.

**`0x02C1C000` · PERF_RES_IO_UFS_AUTO_HIBERN8**  —  `推断`

UFS auto-hibernate-8 (Xiaomi trees label the same id PERF_RES_IO_PERF_MODE - a naming conflict).

- 取值:0/1.
- 可用于:Advanced storage power tuning; the naming conflict means verify before labelling.

**`0x02C20000 / 0x02C24000 / 0x02C28000` · PERF_RES_IO_UFS_IRQ_AFFINITY / IO_SCHEDULER / IO_UFS_TRACING_MODE**  —  `已确认`

UFS IRQ affinity, IO scheduler selection and UFS tracing.

- 取值:SCHEDULER = enum (none / mq-deadline / kyber / bfq).
- 可用于:IO_SCHEDULER is a portable, understandable storage knob; the others are not.

**`0x02C04000 / 0x02C08000 / 0x02C10000 / 0x02C14000` · PERF_RES_IO_F2FS_UFS_BOOST / F2FS_EMMC_BOOST / EXT4_DATA_BOOST / DATA_FS_BOOST**  —  `已确认`

Filesystem-level boost flags.

- 取值:0/1.
- 可用于:Simple storage boost toggles.


## OEM / custom / vendor-specific

**`0x10000000` · PERF_RES_CUSTOM_RESOURCE_1**  —  `已确认`

The MAJOR=64 reserved customization slot. OEMs add private resources at 0x1000xxxx that cannot be discovered from any generic table.

- 取值:Vendor-defined.
- 可用于:Not useful generically - but if you ever dump a vivo MT6989 command.csv, this is where vivo's private ids will live.

**`0x10000700` · PERF_RES_KSWAPD_AFFINITY**  —  `推断`

Seen ONLY in Xiaomi corot/air command.csv (MAJOR 64, MINOR 0, GROUP 7); absent from the Motorola table.

- 取值:Unpublished.
- 可用于:Proof that MAJOR 64 is per-OEM; do not ship.

**`0x40804100 / 0x40804000 / 0x42800100 / 0x42804100` · vivo MTKMFRCController constants**  —  `未知`

Decoded from com.android.server.display.color.displayenhance.MTKMFRCController: it defines 'PERF_RES_CPUFREQ_MAX_CLUSTER_0 = 1082147072 = 0x40804100', 'MAX_CLUSTER_1 = 0x40804000', 'GPU_FREQ_MIN = 0x42800100', 'GPU_FREQ_MAX = 0x42804100' and calls them via mVPerf.perfLockAcquire(0, {id,value,...}) with CPU values 500/1100/1300.

- 取值:Three readings, unresolved. (a) STRONGEST: these are Qualcomm MPCTLV3 perf-lock ids, not MTK - Qualcomm's own doc publishes the array {0x40C00000, 0x1, 0x40804000, 0xFFF, 0x40804100, 0xFFF, ...} with the identical flat shape, and the low 16 bits match MTK's semantics exactly (0x0000+n = MIN cluster n, 0x4000+n = MAX cluster n). In MTK's encoding 0x40804100 has MAJOR=258, which MTK does not define. (b) MTK 're-based id space on newer platforms'. (c) A vivo-specific extension. Also note the cluster 0/1 suffixes appear SWAPPED versus MTK's canonical order, and the values look like MHz against MTK's kHz.
- 可用于:Do NOT treat as an MTK id-space shift. Local check: no file in the local reference dump contains the substring 0x4080 at all. Resolve by descriptor probe, not by trial.

**`n/a` · setPriorityByUid / setPriorityByLinkinfo / flushPriorityRules / configBoosterInfo**  —  `未知`

These four method names have NO public evidence anywhere (grep.app over ~1M repos: no results; Google exact-phrase: zero results; no decompiled framework dump carries them). No IPowerHalMgr AIDL is public either - the only public trace is the descriptor string inside dumpsys listings.

- 取值:n/a
- 可用于:Do not bind code to these names. If they exist they are private-decompile reconstructions at best.

**`n/a` · notifyAppState(string pack, string act, int32 pid, int32 state, int32 uid)**  —  `已确认`

The REAL per-app/per-UID hook in vendor.mediatek.hardware.mtkpower@1.0::IMtkPower (oneway). On-device logs render it as '[perfNotifyAppState] foreground:<pkg>, pid:..., uid:...'. It is an app-lifetime/foreground notification, not a scalar priority.

- 取值:state 1 = foreground. Carries both pid and uid.
- 可用于:Reachable only via the vendor hwservicemanager HAL path, which is SELinux-restricted to system/vendor domains - a Shizuku app cannot normally call it. Do not attempt.

**`n/a` · IMtkPerf.hal (native equivalent)**  —  `已确认`

@1.0 perfLockAcquire(int32 pl_handle, uint32 duration, vec<int32> boostsList, int32 reserved) -> int32 and oneway perfLockRelease(int32 pl_handle, int32 reserved); @1.1 adds perfCusLockHint; @1.2 adds perfLockReleaseSync.

- 取值:No method takes a UID. The revision-additive pattern is exactly why AIDL transaction codes shift between vendor builds.
- 可用于:Reference for the wire semantics; perfLockReleaseSync is the fix for release not being verifiable.

**`n/a` · MTKPOWER_HINT_* scenario names**  —  `推断`

Real named hints in powerscntbl.xml: MTKPOWER_HINT_LAUNCH, ACT_SWITCH, PACK_SWITCH, WHITELIST_LAUNCH, WHITELIST_ACT_SWITCH, PROCESS_CREATE, APP_ROTATE, PMS_INSTALL, UX_SCROLLING(+_COMMON/_NORMAL_MODE), UX_TOUCH_MOVE, SCENE_TRANSITION, AUDIO_LATENCY_UL, AUDIO_POWER, EXT_LAUNCH_FOR_GAME, GAME_MODE, EXT_HINT.

- 取值:Numeric enum values are NOT public - do not invent them.
- 可用于:Vocabulary only; not a callable API from a Shizuku app.


## Kernel nodes (direct, for read-back and validation)

**`/proc/ppm/policy/userlimit_cpu_freq` · PPM soft user limit**  —  `已确认`

The node the soft CPUFREQ_MIN/MAX_CLUSTER_n family writes to.

- 取值:Space-separated 'min0 max0 min1 max1 ...' in kHz; -1 = PPM_IGNORE.
- 可用于:Read it to confirm a soft limit actually applied - the only honest way to verify the acquire worked.

**`/proc/ppm/policy/hard_userlimit_cpu_freq` · PPM hard user limit**  —  `已确认`

The node the HL family (and, by hypothesis, MAX_THERMAL_CLUSTER_n) writes to. Also exported as hard_userlimit_min_cpu_freq / hard_userlimit_max_cpu_freq and RO hard_userlimit_freq_limit_by_others.

- 取值:'min0 max0 min1 max1 ...' in kHz; -1 = no constraint; min>max is auto-corrected.
- 可用于:The cleanest way to detect an active thermal hard cap and to build correct restore logic.

**`/proc/perfmgr/syslimiter/syslimiter_limit_freq` · SystemLimiter limit**  —  `已确认`

Backing node for PERF_RES_POWER_SYSLIMITER.

- 取值:kHz; -1 = no limit.
- 可用于:Read-back for the syslimiter slider.

**`/proc/perfmgr/boost_ctrl/cpu_ctrl/perfserv_freq` · perfmgr CPU freq node**  —  `已确认`

Alternative backing node for the soft cluster limits (perfmgrCpu path).

- 取值:'min0 max0 ...'.
- 可用于:Alternate read-back target when /proc/ppm is absent.

**`/proc/perfmgr/tchbst/user/usrtch` · Touch boost node**  —  `已确认`

Shared backing node for ALL PERF_RES_POWERHAL_TOUCH_BOOST_* ids; written with a Prefix mechanism ('touch_opp^', '3^', '1^').

- 取值:Prefixed text writes; a raw write clobbers the other fields sharing the node.
- 可用于:Read-back for touch boost state; a reminder that several ids share one node.

**`/proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_prefer_idle` · prefer_idle bitfield node**  —  `已确认`

Single node shared by PREFER_IDLE_TA and PREFER_IDLE_FG via Prefix '3^' and '1^'.

- 取值:Bit-field.
- 可用于:Read-back for prefer-idle; never write raw.

**`/proc/perfmgr/boost_ctrl/dram_ctrl/ddr` · DRAM OPP floor node**  —  `已确认`

Backing node for PERF_RES_DRAM_OPP_MIN. Siblings ddr_lp5 and ddr_lp5_hfr.

- 取值:OPP index; -1 = unset.
- 可用于:Read-back for the DRAM floor.

**`/proc/displowpower/idletime` · Display idle time**  —  `已确认`

Backing node for PERF_RES_DISP_IDLE_TIME.

- 取值:ms.
- 可用于:Read-back for the display idle slider.

**`/proc/driver/thermal/ta_fg_pid` · Thermal top-app PID**  —  `已确认`

PowerHAL writes the top-app pid here so MTK's thermal daemon can apply per-app thermal handling. /sys/module/ged/parameters/gx_top_app_pid does the same for GED.

- 取值:integer pid, written once (latched).
- 可用于:Diagnostic: confirms PowerHAL is alive and shows which pid the thermal stack considers foreground.

**`/proc/driver/thermal/tp_test and tp_pid` · Thermal policy poke nodes**  —  `已确认`

Created by the kernel module mtk_change_policy.c. tp_pid takes the target userspace pid; tp_test takes '<idx> <onoff>' and on READ calls mtk_change_thermal_policy, which sends SIGIO (si_code 4) to that pid.

- 取值:tp_test write: "<int idx> <int onoff>".
- 可用于:Root-only A/B test to discover which thermal policy index actually changes throttling on this device - useful evidence gathering before exposing THERMAL_POLICY values.

**`/sys/class/devfreq/APUVPU, APUVPU0, APUVPU1, APUVPU2, APUMDLA, APUMDLA0, APUMDLA1` · APU VPU/MDLA devfreq devices**  —  `推断`

Real devfreq devices registered by drivers/misc/mediatek/apusys/power/2.5/devices/dev-freq-{vpu,mdla}.c, named via apu_dev_string(). A userspace governor exists (agov_userspace, APUGOV_USR).

- 取值:Standard devfreq: available_frequencies (Hz), cur_freq, min_freq, max_freq, governor.
- 可用于:The cleanest direct APU clock route IF the nodes exist and are writable - verify per device; SELinux may block. Not verified on MT6989.

**`/sys/kernel/debug/apusys/power` · apusys power debugfs**  —  `推断`

Created by apu-dbg.c as debugfs_create_dir('apupwr') + file 'power' (0644) + a 'power' symlink. Write-only command interface: opp_table, curr_status, fix_opp, dvfs_debug, power_hal <user> <min> <max>, power_hal_opp <user> <min_opp> <max_opp>, power_stress, log_level. Unknown tokens return -EINVAL, no panic.

- 取值:'<cmd> [args]', max 5 integer args.
- 可用于:'opp_table' enumerates valid APU OPP indices and 'curr_status' reads actual MHz - the authoritative way to learn the VPU/MDLA value scale. Requires root/debugfs access.

**`/proc/cpuhvfs/cpufreq_cci_mode` · CCI mode node (newer platforms)**  —  `已确认`

Backing node for PERF_RES_CPUFREQ_CCI_FREQ on mt6879 and later; the older name is /proc/cpufreq/cpufreq_cci_mode.

- 取值:0/1.
- 可用于:Read-back for the CCI toggle; also a cheap way to discover which of the two node names this device uses.

**`/proc/perfmgr/boost_ctrl/cpu_ctrl/perfserv_all_cpu_deisolated` · all-CPU de-isolation**  —  `已确认`

Backing node for PERF_RES_CPUCORE_PERF_MODE.

- 取值:0/1.
- 可用于:Read-back for the 'keep all cores online' toggle.

**`/dev/cpuctl/<group>/cpu.uclamp.min | .max | .latency_sensitive` · cgroup uclamp nodes**  —  `已确认`

The kernel-side cgroup files the SCHED_UCLAMP_* resources ultimately configure.

- 取值:0..100 for min/max; 0/1 for latency_sensitive.
- 可用于:The most trustworthy read-back for the app's responsiveness sliders - confirms the value landed even if PowerHAL's own node is opaque.


---

## Disagreements between sources

Recorded rather than resolved. Treat every one of these as open.

- ID ENCODING FORMULA. One agent states the brief's 'MAJOR<<22 | MINOR<<8 | INDEX' is wrong and that the middle field is <<14, i.e. id = (MAJOR<<22)|(MINOR<<14)|(GROUP<<8); five other agents used the <<8 shorthand. RESOLVED in favour of <<14/<<8: the authoritative vendor table the local reference dump has the header COMMAND,MAJOR,MINOR,GROUP and its rows (PERF_RES_SCHED_UCLAMP_MIN_TA,5,2,3 -> 0x01408300; PERF_RES_CPUFREQ_MAX_CLUSTER_2,1,1,2 -> 0x00404200; PERF_RES_GPU_FREQ_MAX_HL,3,1,1 -> 0x00C04100) reproduce every id only under the <<14/<<8 split. CONSEQUENCE: the code comment at mtk-optimizer/.../PowerHal.kt line 21 ('id = MAJOR<<22 | MINOR<<8 | INDEX') is numerically wrong even though the arithmetic it uses (base + index*0x100) is right. Fix the comment, not the code.
- 0x40804100 (the 'vivo MTKMFRCController' ids). THREE conflicting readings: (a) it is a Qualcomm MPCTLV3 perf-lock id, not MTK at all - Qualcomm's own documentation publishes the array {0x40C00000, 0x1, 0x40804000, 0xFFF, 0x40804100, 0xFFF, ...}, the low 16 bits match MTK's semantics exactly, and in MTK's encoding this value has MAJOR=258 which MTK never defines; (b) it is an MTK 're-based id space on newer platforms'; (c) it is a vivo-specific extension. Reading (a) is best supported, and a local check confirms no file under the local reference dump contains the substring 0x4080 at all. The same vivo file also appears to SWAP cluster 0/1 and to use MHz (500/1100/1300) where MTK uses kHz. Do not treat this as evidence of an MTK id-space shift.
- TRANSACTION NUMBERS 22/23/24 vs 23/24/25. Two sources disagree (this device vs the OPPO A92s framework). UNRESOLVED and unresolvable from public sources - no IPowerHalMgr AIDL or generated Stub is published anywhere. A one-method insertion before perfLockAcquire shifts everything after it by +1, and framework-private AIDL has no frozen aidl_api to prevent that. Must be discovered per device (DEX parse preferred, calibrated probe as fallback).
- PERF_RES_DRAM_OPP_MIN DIRECTION. MTK-derived evidence says index 0 = FASTEST gear (dvfsrc OPP tables number opp0 as the top-bandwidth point; the con-table Compare tag is 'less', matching 'smaller value = more aggressive'; MTK's own MLPerf bundles pair DRAM_OPP_MIN=0 with max CPU). Two community tools write the OPPOSITE (0 = power-save, 2 = performance). Treat 0 = fastest as high-likelihood, not vendor-documented, and never show the user a raw index without on-device measurement.
- TOUCH_BOOST_DURATION / ACTIVE_TIME UNITS. Milliseconds on Motorola configs (Min 10 Max 2000 Default 100) but NANOseconds on chopin (Min 10000000 Max 2000000000 Default 200000000). A slider that assumes one scale will be wrong by 1e6 on the other device. Must be normalised per device.
- PERF_RES_THERMAL_POLICY SEMANTICS. The id and its 0..19 bound are confirmed, but the meanings of individual values are NOT published and cannot be derived - they live in encrypted /vendor/etc/.tp/.thermal_policy_NN blobs. One agent's table says powerhint XMLs use 0/2/8; another documents vivo MT6983 using 10/11/18/5/0 for its named modes and 2 for the camera; a third notes -1 works as a system property but is rejected by cmdSetting()'s 0..19 range check when sent through a powerhint XML. Do not invent an enum.
- VPU/MDLA FREQUENCY UNITS. The RscCfgTbl shows comp:100 and defaultVal 1 (MIN) / 2 (MAX), the kernel's apu_opp2freq documents 'opp 0 means the max freq', and the debugfs hook takes opp indices - so OPP index is favoured. But no captured log shows a value actually applied, so percent-of-max cannot be excluded. Unresolved.
- setPriorityByUid / setPriorityByLinkinfo / flushPriorityRules / configBoosterInfo. These four names have NO public evidence (grep.app over ~1M repos and Google exact-phrase both return zero). One agent treats them as plausible-but-wrong reconstructions. Do not bind code to them. The real per-app hook is notifyAppState (SELinux-restricted vendor HAL), and the real per-UID knob is PERF_RES_NET_NETD_BOOST_UID.
- PERF_RES_CPUFREQ_MAX_THERMAL_CLUSTER_n. Exists only in four Transsion/Infinix/itel vendor XMLs; absent from every MTK header, from PowerHalMgr.java and from the Motorola command table. The hypothesis that it aliases MAX_HL_CLUSTER_n is circumstantial. Marked unknown, not confirmed.
- PERF_RES_IO_UFS_AUTO_HIBERN8 vs PERF_RES_IO_PERF_MODE. Two device families assign DIFFERENT names to the identical id 0x02C1C000. Whichever is right, the app must not display a confident label for this id.
- ID DRIFT ACROSS BSP REVISIONS (not a two-agent disagreement but a systematic hazard). chopin puts SCHED_OFFLINE_THROTTLE_MS_CLUSTER_1/2 at 0x0143C100/0x0143C200 while the newer Motorola table puts CLUSTER_0/1/2 at 0x0143C200/0x0143C300/0x0143C400 (a two-MINOR shift); corot/air disagree with Motorola on four FBT_LIMIT_*2CAP_BY_PID ids; pearl assigns 0x0204C700 to FPS_GBE_CPU_2 where the newer table assigns GBE_THRM_HDRM_THRS. Practical rule: MAJOR is stable, MINOR families from about MINOR 6 upward drift - prefer ids that agree across two or more independent device tables.

---

## Sources

Public references. Local working paths from the research are described in
`reference/README.md` rather than reproduced here.

- <https://github.com/PotatoDevices/vendor_mediatek_opensource/blob/master/hardware/power/include/mtkperf_resource.h>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/master/hardware/power/include/mtkperf_resource.h>
- <https://github.com/akdmjeau-eng/android_vendor_motorola_lamu/blob/main/proprietary/etc/command.csv>
- <https://raw.githubusercontent.com/akdmjeau-eng/android_vendor_motorola_lamu/main/proprietary/etc/command.csv>
- <https://github.com/YGYoghurt/vendor_xiaomi_corot/blob/main/proprietary/vendor/etc/command.csv>
- <https://github.com/ht7813/android_device_xiaomi_air/blob/main/recovery/root/vendor/etc/command.csv>
- <https://github.com/moto-common/android_device_mediatek_common/blob/master/vendor/perf/configs/mt6879/powercontable.xml>
- <https://github.com/moto-common/android_device_mediatek_common/blob/master/vendor/perf/configs/mt6879/powerscntbl.xml>
- <https://github.com/moto-common/android_device_mediatek_common/blob/master/vendor/perf/configs/mt6879/power_app_cfg.xml>
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/lineage-21/configs/perf/powercontable.xml>
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/lineage-21/configs/perf/powerscntbl.xml>
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/lineage-21/configs/perf/power_app_cfg.xml>
- <https://github.com/pearl-developments/android_device_xiaomi_pearl/blob/master/configs/power/powercontable.xml>
- <https://github.com/snnbyyds/device_xiaomi_blossom/blob/master/configs/power/powercontable.xml>
- <https://github.com/snnbyyds/device_xiaomi_blossom/blob/master/configs/power/powerscntbl.xml>
- <https://github.com/Mashopy/mtkpower_hint_parser/blob/main/refs/powerscntbl.xml>
- <https://github.com/Mashopy/mtkpower_hint_parser/blob/main/main.py>
- <https://github.com/SuperAviation001/android_device_nothing_Tetris/blob/master/configs/nnapi_powerhal.json>
- <https://github.com/nothing-Pacman/android_vendor_nothing_Pacman/blob/main/proprietary/vendor/etc/nnapi_powerhal.json>
- <https://github.com/boydaihungst/tecno-reverse-engineering/blob/master/out/out_framework_jar/sources/com/mediatek/powerhalmgr/PowerHalMgr.java>
- <https://github.com/AxionAOSP/Axion_research_docs/blob/main/AxBoostFwk_Research.md>
- <https://github.com/helloklf/scheduler/blob/master/core-v1.2.0/mt6983/vivo/powerscntbl.xml>
- <https://github.com/LineageOS/android_hardware_mediatek/tree/lineage-22.2/libmtkperf_client>
- <https://www.cpu52.com/archives/253.html>
- <https://github.com/search?q=path%3Amtkperf_resource.h&type=code>
- <https://github.com/search?q=path%3Acommand.csv+PERF_RES&type=code>
- <https://github.com/search?q=path%3Annapi_powerhal.json&type=code>
- <https://grep.app/search?q=PERF_RES_CPUCORE_>
- <https://grep.app/search?q=PERF_RES_POWERHAL_>
- <https://grep.app/search?q=PERF_RES_DRAM_>
- <https://grep.app/search?q=perf_lock_acq>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/lib/powerhal/perfservice_rsccfgtbl.h>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/lib/powerhal/utility_thermal.cpp>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/lib/powerhal/utility_thermal.h>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/lib/powerhal/perfservice.cpp>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/lib/powerhal/common.h>
- <https://raw.githubusercontent.com/PotatoDevices/vendor_mediatek_opensource/dumaloo-release/hardware/power/include/mtkperf_resource.h>
- <https://raw.githubusercontent.com/SakthivelNadar/android_vendor_mediatek-opensource/master/power/include/mtkperf_resource.h>
- <https://raw.githubusercontent.com/PixelExperience-Blobs/vendor_mediatek-opensource/master/hardware/power/lib/powerhal/utility_thermal.cpp>
- <https://raw.githubusercontent.com/PixelExperience-Blobs/vendor_mediatek-opensource/master/hardware/power/lib/powerhal/common.h>
- <https://raw.githubusercontent.com/OnePlusOSS/android_kernel_5.10_oneplus_mt6983/master/drivers/misc/mediatek/thermal/common/mtk_change_policy.c>
- <https://raw.githubusercontent.com/OnePlusOSS/android_kernel_5.10_oneplus_mt6983/master/drivers/misc/mediatek/ppm_v3/mtk_ppm_policy_thermal.c>
- <https://raw.githubusercontent.com/OnePlusOSS/android_kernel_5.10_oneplus_mt6983/master/drivers/misc/mediatek/ppm_v3/mtk_ppm_policy_hard_user_limit.c>
- <https://raw.githubusercontent.com/SamarV-121/android_kernel_xiaomi_mt6765/master/drivers/misc/mediatek/thermal/common/inc/mtk_thermal_policy.h>
- <https://raw.githubusercontent.com/POL0i/DuWave/master/tmp/logcat.txt>
- <https://raw.githubusercontent.com/TC999/n107_6739_66_p-linux/master/114.log>
- <https://raw.githubusercontent.com/kaminarich/Thunder-Clash/master/service.sh>
- <https://raw.githubusercontent.com/MiAzami/Creamysteam/master/system.prop>
- <https://raw.githubusercontent.com/helloklf/scheduler/master/core-v1.2.0/mt6983/vivo/powerscntbl.xml>
- <https://raw.githubusercontent.com/helloklf/scheduler/master/core-v1.2.0/mt6983/vivo/power_app_cfg.xml>
- <https://raw.githubusercontent.com/imwangwang/vivo-service/master/com/android/server/display/color/displayenhance/MTKMFRCController.java>
- <https://raw.githubusercontent.com/mt6833-dev-transsion/android_vendor_itel_P661N/master/proprietary/vendor/etc/powerscntbl.xml>
- <https://raw.githubusercontent.com/mt6833-dev-transsion/android_vendor_itel_P661N/master/proprietary/vendor/etc/powercontable.xml>
- <https://raw.githubusercontent.com/vbs-0/vendori_air/master/proprietary/etc/command.csv>
- <https://raw.githubusercontent.com/boydaihungst/tecno-reverse-engineering/master/out/out_framework_jar/sources/com/mediatek/powerhalmgr/PowerHalMgr.java>
- <https://raw.githubusercontent.com/iscle/OrangePi_4G-IOT_Android_8.1_BSP/master/frameworks/av/media/libmtkavenhancements/wifi-display/source/MtkWifiDisplaySource.cpp>
- <https://raw.githubusercontent.com/Deepflex/android_device_elephone_p9000/master/configs/.tp/thermal.conf>
- <https://raw.githubusercontent.com/r0rt1z2-dumpyard/redmi_water_dump/master/all_files.txt>
- <https://github.com/search?q=%22PERF_RES_THERMAL_POLICY%22&type=code>
- <https://github.com/search?q=%22PERF_RES_CPUFREQ_MAX_THERMAL_CLUSTER_0%22&type=code>
- <https://github.com/search?q=%22load_thm_api_start%22&type=code>
- <https://github.com/search?q=%22thermal_policy_00%22&type=code>
- <https://gist.github.com/3dfdef4a6818a5c768289c9a3b45ec6a>
- <https://genio-community.mediatek.com/t/how-to-check-and-adjust-npu-frequency-during-ai-inference/467>
- <https://raw.githubusercontent.com/Mashopy/mtkpower_hint_parser/main/refs/powerscntbl.xml>
- <https://raw.githubusercontent.com/Mashopy/mtkpower_hint_parser/main/refs/powerhint.json>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/Makefile>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/Makefile>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/devices/dev-freq-vpu.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/devices/dev-freq-mdla.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/common/apu-common.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/common/apu-dbg.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/common/apu-plat.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/power/2.5/governor/gov-user.c>
- <https://raw.githubusercontent.com/xiaomi-mediatek-devs/android_kernel_xiaomi_mt6895/lineage-22.2/drivers/misc/mediatek/apusys/mvpu/mvpu_sysfs.c>
- <https://raw.githubusercontent.com/oppo-source/android_kernel_4.14_oppo_mt6893/oppo/mt6893_r_12.0_oppo_reno7_pro_5g/drivers/misc/mediatek/performance/perf_ioctl/perf_ioctl.h>
- <https://raw.githubusercontent.com/oppo-source/android_kernel_4.14_oppo_mt6893/oppo/mt6893_r_12.0_oppo_reno7_pro_5g/drivers/misc/mediatek/apusys/midware/1.0/apusys_dbg.c>
- <https://raw.githubusercontent.com/oppo-source/android_kernel_4.14_oppo_mt6893/oppo/mt6893_r_12.0_oppo_reno7_pro_5g/drivers/misc/mediatek/apusys/midware/1.0/apusys_dbg.h>
- <https://www.cnblogs.com/hellokitty2/p/16699124.html>
- <https://api.github.com/search/repositories> — repo discovery for mt6893/mt6989/mt6983/mt6985 vendor kernels
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/aidl.cpp>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/aidl_language_y.yy>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/aidl_language.cpp>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/aidl.h>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/generate_java_binder.cpp>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/generate_ndk.cpp>
- <https://android.googlesource.com/platform/system/tools/aidl/+/refs/heads/main/generate_cpp.cpp>
- <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/IBinder.java>
- <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Binder.java>
- <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BinderProxy.java>
- <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/android_util_Binder.cpp>
- <https://github.com/GrapheneOS/platform_system_tools_aidl/blob/17/generate_java_binder.cpp>
- <https://www.cnblogs.com/ni_sy/p/15321290.html>
- <https://nvd.nist.gov/vuln/detail/CVE-2022-20040>
- <https://raw.githubusercontent.com/torvalds/linux/master/Documentation/devicetree/bindings/interconnect/mediatek,cci.yaml>
- <https://raw.githubusercontent.com/torvalds/linux/master/drivers/soc/mediatek/mtk-dvfsrc.c>
- <https://lkml.indiana.edu/2401.1/02962.html>
- <https://github.com/mlcommons/mobile_app_open/blob/master/mobile_back_tflite/cpp/backend_tflite/neuron/APUWareUtilsLib.h>
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/master/configs/perf/powercontable.xml>
- <https://github.com/begonia-dev/android_device_redmi_begonia/blob/master/configs/powerhint.json>
- <https://github.com/Ko-Hi-Dev/mt6877/blob/master/configs/perf/powercontable.xml>
- <https://github.com/xiaomi-klee-devs/android_kernel_device_modules-6.6/blob/master/drivers/misc/mediatek/helio-dvfsrc/helio-dvfsrc-sysfs.c>
- <https://github.com/xiaomi-klee-devs/android_kernel_device_modules-6.6/blob/master/arch/arm64/boot/dts/mediatek/mt6765.dts>
- <https://gist.github.com/gmillz/3dfdef4a6818a5c768289c9a3b45ec6a>
- <https://github.com/ArrowOS/android_device_mediatek_sepolicy_vndr/blob/master/basic/non_plat/mtk_hal_power.te>
- <https://github.com/vbs-0/xiaomi_air_tree/blob/air-device-tree/device/xiaomi/air/rootdir/etc/init/vendor.mediatek.hardware.mtkpower@1.0-init.rc>
- <https://github.com/FakeShell/batman/blob/master/src/device_node.c>
- <https://github.com/luigi311/batman/blob/master/src/batman>
- <https://github.com/Rem01Gaming/origami_kernel_manager>
- <https://patchwork.ozlabs.org/project/devicetree-bindings/cover/1600052684-21198-1-git-send-email-henryc.chen@mediatek.com/>
- <https://raw.githubusercontent.com/gemian/cosmo-linux-kernel-4.4/master/drivers/devfreq/helio-dvfsrc.h>
- <https://grep.app/search?q=PERF_RES_DRAM_OPP_MIN>
- <https://grep.app/search?q=PERF_RES_CPUFREQ_CCI_FREQ>
- <https://grep.app/search?q=cpufreq_cci_mode>
- <https://el-vertedero/motorola_nevada_dump/blob/main/product/etc/game_opt_config-nevada-tmo.xml>
- <https://github.com/MTK-DM-810-UNIFIED/android_hardware_mediatek> — interfaces/mtkpower/1.0,1.1,1.2/IMtkPerf.hal, IMtkPower.hal, IMtkPowerCallback.hal (authoritative HIDL signatures
- <https://github.com/LineageOS/android_hardware_mediatek> — hidl/mtkpower/1.2/default/MtkPower.cpp (implementation incl. notifyAppState/querySysInfo/setSysInfo), libmtkperf_client/mtkperf_client.c and powerhalwrap_vendor.c (exact native symbol names), configs/properties/vendor_logtag.mk (PowerHalMgrImpl, PowerHalMgrServiceImpl, PowerHalAddressUitls, PowerHalWifiMonitor log tags
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/HEAD/configs/perf/powercontable.xml> — complete PERF_RES_ name -> id -> sysfs path -> Min/Max/Default table (MT6893
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/HEAD/configs/perf/power_app_cfg.xml> — per-Package/per-Activity/per-FPS WHITELIST table (incl. PERF_RES_NET_NETD_BOOST_UID
- <https://github.com/xiaomi-mt6893-dev/android_device_xiaomi_chopin/blob/HEAD/configs/perf/powerscntbl.xml> — scenario powerhint -> PERF_RES_ data table (MTK's own LAUNCH/ACT_SWITCH/PACK_SWITCH profiles
- <https://github.com/Mashopy/mtkpower_hint_parser> — MTKPOWER_HINT_* -> AOSP hint aliases (main.py), powerscntbl.xml reference for MT6886
- <https://github.com/PQEnablers-Devices/android_hardware_mediatek> — mirror of the mtkpower HIDL + aidl/power-mediatek (Power.cpp/Power.h/types.h/power-mtk.xml
- <https://github.com/moto-common/android_device_mediatek_common> — vendor/perf/configs/*/powercontable.xml + power_app_cfg.xml for multiple MTK SoCs
- <https://github.com/JUANIMAN/PerfMTK> — real-world MTK tuning module (CPU clusters, FPSGO paths, thermal engine, UFS I/O) showing the sysfs surface a tuner actually drives
- <https://github.com/LineageOS/android_device_mediatek_sepolicy_vndr> — base/private/service_contexts: 'power_hal_mgr_service u:object_r:mtk_power_hal_mgr_service:s0', 'mtk-perfservice u:object_r:mtk_perf_service:s0'
- <https://android.googlesource.com/device/mediatek/wembley-sepolicy> — MTK sepolicy source referencing power_hal_mgr_service and libmtkperf_client
- <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/os/IBinder.java> — FIRST_CALL_TRANSACTION=1, LAST_CALL_TRANSACTION=0x00FFFFFF, PING/DUMP/SHELL_COMMAND/INTERFACE_TRANSACTION constants
- <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/os/Parcel.java> — enforceInterface line 1142; getExceptionCode/EX_SECURITY line 3339; readException semantics
- <https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/domain.te> — line 148: allow { appdomain coredomain } system_file:file { execute read open getattr map }
- <https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te> — lines 299-303 system_file; lines 318-324 vendor framework/lib readable only when 'public'
- <https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/hidden_api.cc> — ShouldDenyAccessToMemberImpl / ApiList::FromDexFlags - hidden API is decided from dex flags per member
- <https://raw.githubusercontent.com/LineageOS/android_hardware_mediatek/lineage-23.2/interfaces/hardware/mtkpower/1.0/IMtkPerf.hal> — perfLockAcquire(pl_handle, duration, boostsList, reserved), oneway perfLockRelease
- <https://raw.githubusercontent.com/LineageOS/android_hardware_mediatek/lineage-23.2/interfaces/hardware/mtkpower/1.2/IMtkPerf.hal> — perfLockReleaseSync added in 1.2
- <https://raw.githubusercontent.com/LineageOS/android_hardware_mediatek/lineage-23.2/interfaces/hardware/mtkpower/1.0/IMtkPower.hal> — querySysInfo(cmd,param); setSysInfo(type,data); mtkPowerHint/mtkCusPowerHint/notifyAppState
- <https://raw.githubusercontent.com/LineageOS/android_hardware_mediatek/lineage-23.2/libmtkperf_client/powerhalwrap_vendor.c> — PowerHal_Wrap_querySysInfo / setSysInfo / TouchBoost surface
- <https://raw.githubusercontent.com/LineageOS/android_hardware_mediatek/lineage-23.2/libmtkperf_client/mtkperf_client.c> — perf_lock_acq / perf_lock_rel ABI
- <https://docs.qualcomm.com/doc/80-PK177-134/topic/qape_example_code.html> — int x[] = { 0x40C00000, 0x1, 0x40804000, 0xFFF, 0x40804100, 0xFFF, ... } - Qualcomm MPCTLV3 perf-lock ids
- <https://www.cpu52.com/archives/314.html> — [MTK] Commands debug about CPU/GPU/DRAM/FPSGO/Thermal/Display - /proc/ppm, /proc/gpufreq, dvfsrc nodes
- <https://blog.csdn.net/m0_53696288/article/details/127670732> — PERF_RES_CPUFREQ_MIN_CLUSTER_0/1, PERF_RES_CPUFREQ_PERF_MODE, PERF_RES_CPUCORE_MIN/MAX_CLUSTER_0/1, PERF_RES_SCHED_BOOST symbolic names
- <https://ameblo.jp/momokura07/entry-12804358962.html> — live Android service list containing power_hal_mgr_service next to telephony.mtkregistry
- <https://deepwiki.com/helloklf/vtools/3.2-mediatek-platform-scripts> — Scene/vtools MTK scripts use /proc/ppm, /proc/gpufreq, /proc/mali and cpuset - no PERF_RES ids
