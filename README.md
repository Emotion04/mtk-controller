# MTK God

An Android app that sets **CPU and GPU frequency limits on MediaTek SoCs without root**,
through [Shizuku](https://shizuku.rikka.app/). It talks to MediaTek's PowerHAL manager
directly, the same service the vendor's own power tuning goes through.

Aimed at devices running a MediaTek Dimensity SoC; the cluster layout, frequency tables and
available governors are all discovered at runtime rather than assumed.

## Status

**Read [docs/handoff.md](docs/handoff.md) before doing anything with this.** It is
split into verified facts, the complete list of open problems, and clearly-labelled
speculation, in that order.

Short version: the app installs and runs, the protocol work is done, and
single-frequency limiting has been observed working — but frequency control
**stops responding after several applies**, a cluster can be left **pinned with no
in-app recovery**, and a floor/ceiling **range has never been demonstrated to take
effect on hardware**. Those are the problems worth attention.

## Documentation

| | |
|---|---|
| [docs/protocol.md](docs/protocol.md) | MediaTek PowerHAL: ids, transactions, value semantics — with primary sources |
| [docs/pitfalls.md](docs/pitfalls.md) | Mistakes already made here, and the rule each one produced |
| [docs/architecture.md](docs/architecture.md) | Layers, the `CpuControl` invariants, device adaptation, testing |
| [docs/handoff.md](docs/handoff.md) | Current state, open problems, what to do next |

## Building

```bash
cd mtk-optimizer
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (`compileSdk 37`). The app is signed with the debug
keystore by default; any signature works for local use — Android requires a signature to
install at all.

## Requirements on device

- Android 13+ (`minSdk 33`)
- [Shizuku](https://shizuku.rikka.app/) running and this app authorised
- A MediaTek SoC — on anything else the app detects the absence of PowerHAL and says so
  rather than failing silently

## Helping it support your device

*Settings → 运行日志 → 复制报告* produces a plain-text snapshot: SoC and build identifiers,
the cpufreq policies in enumeration order, frequency counts, governor writability, PowerHAL
reachability, the full capability probe, and the run log.

That text is usually enough to work out how a device behaves without owning it. The goal is
that most devices need no per-device work — the app probes rather than consulting a device
list, and the report is the fallback.

## License

Not yet chosen.
