# reference/ — third-party material, never published

Everything in this folder is either **someone else's file** or **derived from
someone else's binary**. It exists so the protocol in `docs/protocol.md` can be
re-checked against its sources, and for nothing else.

**This folder is git-ignored on purpose.** Only this README is tracked. See the
rule at the bottom.

| path | what it is | where it came from |
|---|---|---|
| `apk/` | the reference app's APK, plus the intermediate files of unpacking it | a device the author owns |
| `dex/` | `real.dex` and conversions of it, memory dumps | extracted from `apk/` |
| `decompiled/java/` | a Java decompiler's output for that dex | generated from `dex/` |
| `decompiled/smali/` | the smali of the same dex | generated from `dex/` |
| `research/` | vendor files fetched from public repositories while researching the protocol: MediaTek kernel headers, powerhint / scentbl configs, command tables | public GitHub repositories |
| `tools/`, `libs/` | unpacking and decompiling toolchains | third-party releases |

## How to use it

- **`decompiled/smali/` is the reliable one.** A Java decompiler cannot rebuild
  Kotlin suspend-function state machines or inline lambda bodies; it emits
  dispatch stubs with the real logic missing. Smali has it. When a call site
  cannot be found in the Java output, search the smali before concluding it does
  not exist there. This is recorded as a pitfall in `docs/pitfalls.md`.
- Nothing here is needed to build or run the app. `mtk-optimizer/` is
  self-contained.

## The rule

`docs/protocol.md` records the **facts** learned from this material, with the
sources named — that part is ours and is published. The files themselves are
not: they are a third party's, and this repository is public.

If you add to this folder, keep it out of git. `git status` must never show
anything under `reference/` except this file.
