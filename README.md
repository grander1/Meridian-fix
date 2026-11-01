# MeridianFix

An LSPosed/Xposed module that fixes **The Meridian** (`com.otomaru.themeridian`)
crashing on Android 15+ after a system update.

> The Meridian 在 Android 15+ 系统上启动崩溃的 LSPosed/Xposed 修复模块。

---

## The Problem

The Meridian is a Unity game built in 2017 (`targetSdk=25`, `armeabi-v7a`).
After an Android 15 system update it crashes instantly on launch:

```
java.lang.NoSuchMethodError: public default void
  android.content.ServiceConnection.onServiceConnected(
    android.content.ComponentName,
    android.os.IBinder,
    android.app.IBinderSession)
    at bitter.jnibridge.JNIBridge.invoke(Native Method)
    at $Proxy7.onServiceConnected(Unknown Source)
    at android.app.LoadedApk$ServiceDispatcher.doConnected(LoadedApk.java:2712)
    at android.app.LoadedApk$ServiceDispatcher$RunConnection.run(LoadedApk.java:2748)
```

### Root cause

Android 15 changed how `ServiceConnection` callbacks are dispatched. The system now
may call a **3-argument** variant:

```java
onServiceConnected(ComponentName name, IBinder service, IBinderSession session)
```

Unity's `bitter.jnibridge` generates its `ServiceConnection` implementation via
`java.lang.reflect.Proxy` **on the native side**. That proxy only implements the
traditional 2-argument method, and its method table is compiled into `libmain.so`.

When the system dispatches with 3 arguments, the native bridge looks up the method
signature, finds no match, and throws `NoSuchMethodError` -- taking the whole
process down.

Because the signature lives in native code, no amount of cache clearing,
reinstalling, or repackaging will fix it. It has to be patched at the framework
layer.

---

## How the fix works

The module hooks `android.app.LoadedApk$ServiceDispatcher#doConnected` and
intercepts the dispatch before it reaches the proxy:

```
system calls doConnected (3-arg signature)
        |
        v
  MeridianFix intercepts
        |
        v
  is the connection a Unity $Proxy?  --no--> pass through to original
        | yes
        v
  conn.onServiceConnected(name, service)   // call the 2-arg version manually
        |
        v
  JNIBridge finds a matching signature -> no crash
```

Connections that are not Unity proxies are forwarded to the original method
untouched, so other behaviour is unaffected.

---

## Requirements

- Android 14/15/16 (the bug appears on 15+)
- A rooted device with **Vector** or **LSPosed**
- The Meridian installed (`com.otomaru.themeridian`)

---

## Install

1. Download `MeridianFix.apk` from [Releases](../../releases) or the repo root.
2. Install it.
3. Open your Xposed manager (**Vector** / **LSPosed**).
4. Enable the **MeridianFix** module.
5. Make sure its scope includes `com.otomaru.themeridian`.
6. Force-stop The Meridian and launch it again.

> The APK is self-signed with a throwaway key. If you would rather not trust a
> random APK, build it yourself from source -- see below.

---

## Build from source

### Prerequisites

- JDK 21
- Android build-tools 35 (`aapt2`, `d8`, `zipalign`, `apksigner`)
- Android platform 35 (`android.jar`)
- A keystore of your own

### One-shot build

Edit the three path variables at the top of `build.sh` so they point at your
local SDK, then:

```bash
bash build.sh
```

This performs all seven steps and produces a signed `MeridianFix.apk`.

### Manual build

```bash
# 1. compile (stubs provide Android + Xposed signatures at compile time only)
javac -nowarn -d out $(find stub -name "*.java") src/com/meridian/fix/MeridianFix.java

# 2. class -> dex   (ONLY out/com, never out/stub)
d8 --min-api 21 --output dexout $(find out/com -name "*.class")

# 3. compile resources
aapt2 compile --dir res -o res_compiled/

# 4. link
#    -I <android.jar>  -A assets  --manifest AndroidManifest.xml
aapt2 link -o base.apk -I $ANDROID_JAR --manifest AndroidManifest.xml \
    -A assets res_compiled/values_arrays.arsc.flat --auto-add-overlay

# 5. add dex
cp base.apk unsigned.apk
(cd dexout && zip -q ../unsigned.apk classes.dex)

# 6. align
zipalign -f 4 unsigned.apk aligned.apk

# 7. sign
apksigner sign --ks mykey.jks --out MeridianFix.apk aligned.apk
```

---

## Project layout

```
AndroidManifest.xml          module manifest (xposedmodule metadata)
assets/xposed_init           entry class declaration
res/values/arrays.xml        default scope array
src/com/meridian/fix/
    MeridianFix.java         the hook logic
stub/                        COMPILE-TIME ONLY placeholders -- never into the dex
    android/content/*        ComponentName, ServiceConnection
    android/os/IBinder.java
    de/robv/android/xposed/* Xposed API signatures
build.sh                     one-shot build script
```

### Why the stubs exist

The build environment has no Android SDK jar and the official `api-82.jar`
mirrors are dead, so the handful of classes the module touches are declared
locally. They are used **only** by `javac` and must never be compiled into the
dex -- a stub `android.content.ServiceConnection` inside the APK would shadow
the real one at runtime.

---

## Gotcha list

1. **Never dex the stubs.** Compile `out/com` only.
2. **`aapt2` / `zipalign` are x86-64 ELF binaries.** On arm64 hosts run them
   through `qemu-x86_64 -L <x86_64 glibc root>`, or use a native aarch64 build
   of build-tools.
3. **d8 warns about missing Xposed types.** Harmless -- they are supplied by
   the Xposed framework at runtime.
4. **The keystore must never be committed.** `.gitignore` already covers it.

---

## Troubleshooting

Grab the log:

```bash
logcat -d | grep -E "MeridianFix|NoSuchMethodError" | tail -50
```

The module prints diagnostic lines:

| Log line | Meaning |
|---|---|
| `[MeridianFix] injected into ...` | module loaded into the target process |
| `[MeridianFix] found doConnected params=N` | hook attached |
| `[MeridianFix] >>> downgrade to 2-arg` | compat path taken (success) |

No `MeridianFix` lines at all -> the module is not being injected; check the
scope in your Xposed manager.

---

## Compatibility notes

- Targets **only** `com.otomaru.themeridian`; nothing else is affected.
- Verified on Android 15 (ColorOS). Reported cause also applies to Android 14+
  builds that ship the 3-argument `ServiceConnection` variant.
- If the app is someday rebuilt against a modern SDK, this module becomes
  unnecessary.

---

## Disclaimer

This project contains **no code from The Meridian**. It only hooks a standard
Android framework class to restore backwards compatibility for an app whose
native bridge cannot adapt to a changed system signature.

It is not affiliated with, endorsed by, or supported by the app's developer.
Use at your own risk -- hooking framework internals carries inherent risk.

---

## License

[MIT](LICENSE)
