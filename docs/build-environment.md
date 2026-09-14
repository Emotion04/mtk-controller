# Build environment

What this project needs, what the machine it was developed on has, and how to
reproduce it. Everything here was read off the working machine, not from memory.

---

## 1. What the project requires

| | version | where it is pinned |
|---|---|---|
| JDK | **17** (project targets Java 17) | `compileOptions` in `app/build.gradle.kts` |
| Gradle | **9.4.1** | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | **9.2.1** | `gradle/libs.versions.toml` |
| Kotlin | **2.2.10** | `libs.versions.toml` (AGP's bundled Kotlin; do **not** apply `kotlin.android` separately) |
| compileSdk / targetSdk | **37** | `app/build.gradle.kts` |
| minSdk | **33** | same |
| Compose BOM | **2026.08.00** | `libs.versions.toml` |
| Build-tools | **36.0.0** | resolved by AGP |
| Shizuku API | **13.1.5** | `libs.versions.toml` |

**SDK packages that must be installed:**

```
platforms;android-37
build-tools;36.0.0
platform-tools
```

`cmdline-tools;latest` is needed to install the above; `emulator` and a system
image only if you want to run it, and are not required to build.

There is **no NDK dependency** — the app is pure Kotlin, no native code.

## 2. What the development machine has

Recorded 2026-09-12, so a new machine can be compared against it.

**OS:** Windows 11 Pro for Workstations, build `10.0.26220`.

**JDK** — Microsoft OpenJDK 17.0.10 LTS:

```
openjdk version "17.0.10" 2024-01-16 LTS
OpenJDK Runtime Environment Microsoft-8902769 (build 17.0.10+7-LTS)

JAVA_HOME = C:\Users\infpc\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot\
```

**Android SDK** at `C:/Users/infpc/AppData/Local/Android/Sdk`:

```
platforms        android-37.0
build-tools      36.0.0
platform-tools   adb 1.0.41 (37.0.1)
cmdline-tools    latest
sources          android-37.0
emulator         present
system-images    android-35/google_apis/x86_64
                 android-37.1/google_apis_playstore_ps16k/x86_64
ndk              absent
```

**Note:** `ANDROID_HOME` and `ANDROID_SDK_ROOT` are **both unset** on this
machine. Gradle finds the SDK through `local.properties` instead — which is why
that file is git-ignored and each machine creates its own.

**Gradle** — the wrapper is committed, including
`gradle/wrapper/gradle-wrapper.jar`. That jar is part of the source; if it is
ever missing, a fresh clone cannot run `./gradlew` at all. See
[pitfalls.md](pitfalls.md#12).

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

android.useAndroidX=true
android.nonTransitiveRClass=true

kotlin.code.style=official
```

## 3. Setting up a new machine

```bash
# 1. JDK 17 (any distribution; this project used Microsoft's OpenJDK build)
#    Set JAVA_HOME to it.

# 2. Android SDK — either via Android Studio, or the command line:
sdkmanager "platforms;android-37" "build-tools;36.0.0" "platform-tools"

# 3. Tell this project where that SDK is. local.properties is git-ignored,
#    so it does not exist after a clone:
cd mtk-optimizer
printf 'sdk.dir=%s\n' "<PATH_TO_ANDROID_SDK>" > local.properties
#   Windows path with forward slashes works, e.g.
#   sdk.dir=C:/Users/you/AppData/Local/Android/Sdk

# 4. Build
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

No other configuration is needed. There is no signing config to supply — debug
builds are signed with the standard debug keystore, and Android requires *a*
signature to install at all.

### Verifying a fresh setup

```bash
./gradlew tasks          # configuration succeeds
./gradlew assembleDebug  # compiles and packages
```

## 4. Running it

```bash
# Emulator, if installed:
emulator -avd <name>

# Or a real device with USB / wireless debugging enabled:
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app needs [Shizuku](https://shizuku.rikka.app/) running and authorised —
without it the CPU screen shows an availability message and every control is
disabled. Starting Shizuku over **wireless debugging** means it runs as **shell
(uid 2000), not root**, which limits what the app can do; see
[device-vivo-v2430a.md](device-vivo-v2430a.md).

## 5. Known friction

**Windows file locks.** Builds intermittently fail with

```
Permission denied
Could not add entry '...' to cache fileHashes.bin
Failed to clean up output files for task ':app:compileDebugKotlin'
```

This is a file-locking problem in the environment (Defender / the search
indexer), not a code problem — the Kotlin compilation itself has already
succeeded when it appears. **Re-running the build clears it.** If it persists,
`./gradlew --stop`, delete `mtk-optimizer/.gradle/9.4.1/fileHashes`, and build
again. Do **not** use `--rerun-tasks`; it makes the locking worse.

**Cache directories.** `.gradle/`, `build/` and `.kotlin/` are git-ignored. If a
build misbehaves after pulling, deleting `mtk-optimizer/.gradle` is safe.

**`versionCode` discipline.** Bump it in `app/build.gradle.kts` for every build
handed to someone else. Leaving it unchanged makes an install silently do
nothing while the old code keeps running, and that has already cost a full
evening of debugging the wrong build. See [pitfalls.md](pitfalls.md#14).

## 6. Not used, despite being in the version catalog

`libs.versions.toml` declares `hilt` and `ksp`. **Neither is applied** — the app
uses a hand-rolled `AppContainer` for dependency injection. They are leftover
catalog entries; ignore them unless you are deliberately migrating.
