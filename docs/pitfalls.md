# Pitfalls

Every entry here cost real time. They are written as *what happened → how to avoid it*, so a
future change does not rediscover them.

---

## 1. A summary of a protocol is not a protocol

A written spec of this project's protocol existed before the implementation, and it was
**lossy**: it recorded the acquire call but not the release flags, and not the
release-before-acquire ordering. The implementation was faithful to that summary and shipped
two bugs that a single look at the primary source would have caught.

**Rule.** For any protocol boundary — binder, AIDL, ioctl, syscall — implement against the
primary source. Copy the call sequence verbatim, including flags, ordering and error
branches. A summary is for navigation, not for implementation. When they disagree or the
summary is silent, the source wins.

## 2. One unsettled read-back cannot support a conclusion

A batch of commands is applied **one at a time** on the server side, and the transaction
returns before that finishes. Read-backs taken 3–7 ms after the call showed
`min == max == the requested min`, which looked like "the API cannot express a range".

It was a **half-applied batch**. The tell was in the same data: if the ids behaved as
independent locks applied in order, the settled value would be the *last* one sent — the
observed value was the *first*.

**Rule.** After a state change, wait for it to settle before reading. `CpuControl.SETTLE_MS`
(400 ms) exists for exactly this. And be suspicious of a conclusion drawn from a single
sample, especially a universal negative.

## 3. Requests accumulate and never expire

`perfLockAcquire` is called with `duration = 0`, so a request has no lifetime. A second
acquire does **not** replace the first — both stay live. `libpowerhal` then merges every
enabled scenario:

```c
floor   = max(all floors);
ceiling = min(all ceilings);
if (ceiling < floor) ceiling = floor;    // ceiling silently raised to the floor
```

One request that was never released therefore clamps the cluster forever, and later ceiling
writes appear to be ignored.

**Consequences to design around:**
- Always release before acquiring. Abort the apply if the release fails.
- Never forget a handle. Clearing bookkeeping for a request that is still live strands it
  with no way left to release it.
- Serialise apply/release — the re-apply service and a ViewModel can otherwise interleave
  into two live handles.
- A handle cannot be recovered if lost. **The only reliable way to clear stranded requests
  is to restart the process that issued them** (restart Shizuku / reboot).

All four are enforced structurally in `CpuControl` rather than left to each call site.

## 4. Do not write a user range into the hard-limit pair

`MIN_HL` / `MAX_HL` are a separate mechanism, and setting both halves to the same value is
the *documented way to hard-lock a cluster*. Pushing a soft range there pins instead of
bounding, and the pin outlives the request. The soft pair alone expresses a range; hard
limits belong to the platform's thermal policy.

## 5. A oneway call's return value proves nothing

`perfLockRelease` is a oneway transaction. It returns a boolean that says the parcel was
handed to the driver — not that the request was released. Reading `scaling_min_freq` /
`scaling_max_freq` back and comparing against the **pre-touch baseline** is the only way to
tell a real release from a silent no-op.

Corollary: a wrong `flags`/`reply` on that call makes release a no-op while still reporting
success. See [protocol.md](protocol.md#2).

## 6. Permission bits are not writability

Deciding "can I write this node?" by reading its mode bits is wrong. A node can be `0664`
root:system and still be unwritable once SELinux and the caller's actual group membership are
accounted for. This produced a governor picker that claimed to be adjustable and silently
discarded every write.

**Rule.** Probe by writing the node's own current value back to it. That is idempotent for
every cpufreq node (`store_scaling_governor` returns early when the governor is unchanged)
and it is the only answer that reflects reality.

## 7. Blocking IPC on the main thread is invisible in a spec

`viewModelScope` runs on `Dispatchers.Main`. Every `PrivilegeManager.exec` call is a blocking
AIDL transaction whose remote side **spawns a shell process and waits for it**. A 1 Hz
sampler that did a full scan was spending tens of milliseconds per second on the main thread,
which reads to the user as "the app feels like 60 Hz".

**Rule.** Anything that touches a binder, a file or a subprocess goes through
`withContext(Dispatchers.IO)`. Nothing else belongs on the main dispatcher.

## 8. `LocalContentColor` is not provided by `MaterialTheme`

Only `Surface` / `Scaffold` publish it, and its default is `Color.Black`. An app whose root
is a `Box` therefore renders **every `Text` that does not name a colour in black** — invisible
in dark mode, and fine in light mode, which is why it survives casual testing.

Wrap the content in a `Surface(color = background, contentColor = onBackground)`.

## 9. One DataStore delegate per file name

Two `preferencesDataStore(name = "…")` delegates for the same name throw at runtime, not at
compile time. The delegates live in `data/DataStores.kt` so a new repository shares the file
instead of racing it.

## 10. Decompiler output is not equally reliable at every level

For Kotlin, a Java decompiler cannot rebuild suspend-function state machines or inline lambda
bodies — it emits dispatch stubs with the real logic missing. **Smali does contain it.**
Searching decompiler output for a call site produced nothing; searching the smali for the
same method produced the implementation in minutes.

**Rule.** When a call site cannot be found in decompiled Java, go to smali before concluding
it does not exist.

## 11. Coroutine-scoped work outlives the screen

A ViewModel scoped to a navigation back-stack entry stays alive when the user navigates away.
A 1 Hz sampler started in `init` kept reading sysfs forever, for a screen nobody was looking
at. Gate it on the screen's lifecycle (`LifecycleResumeEffect`), not on construction.

## 12. Do not ship a control whose semantics are unverified

`setPriorityByUid` / `flushPriorityRules` look like a **rule table** — system-wide, possibly
persistent, possibly affecting scheduling and network for other apps. The method names are
not enough to reason about the blast radius.

**Rule.** If the semantics are not established, do not expose it. An unavailable feature is a
smaller problem than a system-wide side effect nobody can explain.
