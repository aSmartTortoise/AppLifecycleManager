# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

Demo for **automatic component lifecycle registration** in a modularized Android app. Eliminates hard-coded `BaseAppLike` instantiation in the host `Application` by combining APT (compile-time code generation) with a Gradle plugin (ASM bytecode injection at packaging time).

## Build & Run

- Build: `./gradlew assembleDebug`
- Install: `./gradlew :app:installDebug`
- Clean: `./gradlew clean`
- Java/Gradle: AGP 7.4.2, compileSdk 33, targetSdk 33, minSdk 19. Project uses `google()` + `mavenCentral()` + `jcenter()` repos — `jcenter()` is legacy and network fetches may fail; rely on cached deps.
- No unit tests are wired up beyond the default JUnit stub in `app/`.

## Module Architecture

The lifecycle system spans five modules that must be understood together. All code lives under the `com.wyj` package namespace.

1. **`lifecycle-annotation`** (Java lib) — Defines `@AppLifeCycle` annotation (`com.wyj.annotation.lifecycle`) and `LifeCycleConfig` constants. `LifeCycleConfig.PROXY_CLASS_PACKAGE_NAME` (`com.wyj.lifecycle.apt.proxy`) is the **contract package** where all generated proxies live; both APT and the Gradle plugin key off it. Do not change without updating both.

2. **`lifecycle-api`** (Android lib, package `com.wyj.api`) — Defines `IAppLike` interface (`getPriority`/`onCreate`/`onTerminate`) and `AppLifeCycleManager`. The manager holds a static `List<IAppLike>`, exposes `registerAppLike()`, and `init(Context)` sorts by priority then dispatches `onCreate`. Components implement `IAppLike`; the host `Application` calls `AppLifeCycleManager.init(this)`.

3. **`lifecycle-apt`** (Java lib, annotation processor, package `com.wyj.apt`) — `AppLikeProcessor` (registered via `@AutoService`) scans `@AppLifeCycle`-annotated classes, validates they implement `IAppLike`, and uses JavaPoet to generate one proxy class per annotated type into `LifeCycleConfig.PROXY_CLASS_PACKAGE_NAME`. Proxy class names follow `Heima$$<Original>$$Proxy`. Each proxy delegates to the original `IAppLike` impl.

4. **`lifecycle-plugin`** (Groovy Gradle plugin, package `com.wyj.plugin`, plugin ID `com.wyj.plugin.lifecycle`) — Custom Transform that runs at app packaging time. Uses ASM to scan compiled class output for classes under the proxy package, collects their names, then injects bytecode into `AppLifeCycleManager` so that calling `init()` automatically invokes `registerAppLike(new <ProxyClass>())` for every discovered proxy — no reflection, no runtime classpath scanning. The plugin is **enabled** in `app/build.gradle` via `apply plugin: 'com.wyj.plugin.lifecycle'`. It is loaded through **composite build** (`settings.gradle` uses `includeBuild('lifecycle-plugin')` with dependency substitution for `com.wyj.app:lifecyclepluginlocal`), so no manual `publishToMavenLocal` is needed.

5. **`module-1`** (package `com.wyj.module1`) + **`app`** (package `com.wyj.lifecycle.demo`) — Example component and host. `module-1` contains `IAppLike` impls annotated with `@AppLifeCycle`. `app` depends on `lifecycle-annotation`, `lifecycle-api`, `module-1`, and uses `annotationProcessor project(':lifecycle-apt')`.

## Critical Invariants

- The proxy package name in `LifeCycleConfig` is referenced from generated code AND from the Gradle plugin's ASM scanner. Keep them in sync.
- Any module that defines `@AppLifeCycle` classes must apply `annotationProcessor project(':lifecycle-apt')` in its own `build.gradle`, otherwise no proxies are generated for it.
- Annotated classes **must** implement `com.wyj.api.IAppLike` — the processor throws at compile time otherwise.
- The plugin's composite build substitution in `settings.gradle` must match the `classpath` coordinate in the root `build.gradle` (`com.wyj.app:lifecyclepluginlocal:1.0.0`).

## Reference

Background article (Chinese), linked from README: *Android组件化开发实践（五）：组件生命周期管理* on jianshu. README walks through the design rationale and APT/ASM steps in detail.
