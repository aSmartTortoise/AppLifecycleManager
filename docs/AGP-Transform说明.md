# `com.android.tools.build:gradle:7.4.2` 与其中的 `Transform` 类

## 一、`com.android.tools.build:gradle` 是什么

`com.android.tools.build:gradle` 即通常所说的 **Android Gradle Plugin（AGP）**，是 Google 为 Android 项目提供的 Gradle 插件合集，由 Android Studio / Google 随 Android 工具链一起发布。它承担的工作是："让一个 Gradle 项目变成一个能构建 APK/AAB/AAR 的 Android 项目"。

主要功能面：

- 注册 `com.android.application` / `com.android.library` / `com.android.dynamic-feature` / `com.android.test` 等 Gradle 插件 ID 及对应的 DSL（`android { ... }` 配置块）。
- 扩展 Gradle 的构建模型：定义 **Variant**（productFlavor × buildType 的组合）、**SourceSet**、AIDL/RenderScript/资源编译、AAPT2 调度、Dex/R8、签名、打包、测试等完整构建流水线。
- 承载 **Transform API / Variant API / AsmClassVisitorFactory**，供第三方插件在编译期修改字节码（例如本项目的 `lifecycle-plugin`）。
- 内嵌/依赖一系列工具模块：`builder`、`builder-model`、`manifest-merger`、`aapt2`、`apksig`、`apkzlib`、`bundletool`、`transform-api`、`gradle-api`、`gradle-core` 等。

### 版本关键信息（7.4.2）

- Gradle 最低要求：**7.5**（本项目使用 Gradle 7.5 系列符合要求）。
- JDK：需要 **JDK 11+**。
- 该版本仍然保留 **Transform API**，但已标注 `@Deprecated`，官方计划在 **AGP 8.0** 移除；替代方案是 Variant API 下的 `androidComponents.onVariants { ... }` + `AsmClassVisitorFactory` / artifact transform。
- 本项目 `lifecycle-plugin` 正是基于 7.4.2 的 `Transform` 实现的（见 `LifeCycleTransform.groovy`）。

### 本地缓存位置

```
~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle/7.4.2/
    ├─ gradle-7.4.2.jar           (插件主 jar)
    ├─ gradle-7.4.2-sources.jar   (源码)
    ├─ gradle-7.4.2.pom
    └─ gradle-7.4.2.module
```

`Transform` 类实际随 **`com.android.tools.build:gradle-api:7.4.2`** 发布，缓存在：

```
~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle-api/7.4.2/
    └─ gradle-api-7.4.2-sources.jar
       └─ com/android/build/api/transform/Transform.java
```

---

## 二、`com.android.build.api.transform.Transform` 类

### 定位

`Transform` 是 AGP 提供的一个**抽象基类**，第三方 Gradle 插件通过继承它、并调用
`android.registerTransform(...)`（或 `BaseExtension.registerTransform`）把自定义的 Transform 注入到构建流水线，从而可以**在 class 被打包成 dex 之前**拿到所有 `.class` 文件与 jar，对它们做扫描、修改、过滤等处理。它是 AGP 字节码织入（AOP）的经典入口，ARouter、Hugo、Booster、热修复框架、本项目的 `lifecycle-plugin` 都基于它。

> 签名：`@Deprecated public abstract class Transform`，7.4.2 仍可用，将在 AGP 8.0 移除。

### 在构建图中的位置

- 每注册一个 Transform，AGP 会**自动生成一个对应的 Gradle Task**，并按消费/产出的类型和作用域把它串接进 `assembleDebug` / `assembleRelease` 之类的构建链。
- 上游 Transform 的输出自动成为下游 Transform 的输入，构成一条"字节码流水线"（Stream），开发者不需要自己声明 Task 依赖。
- Transform 位置：**Java/Kotlin 编译之后，Dex 生成之前**。因此它看到的是 `.class` 字节码和依赖 jar，而不是 dex/smali。

### 核心概念与 API

| 概念 | 作用 |
|---|---|
| `QualifiedContent` | 流水线里被处理的基本单元，携带 **ContentType + Scope** 标签 |
| `ContentType`（`CLASSES`、`RESOURCES` 等） | 说明流经内容的数据类型 |
| `Scope`（`PROJECT`、`SUB_PROJECTS`、`EXTERNAL_LIBRARIES`、`PROVIDED_ONLY`、`TESTED_CODE`） | 说明内容来自工程哪一层 |
| `TransformInput` | 一次 Transform 调用的输入，包含 `DirectoryInput`（工程编译产物目录）与 `JarInput`（依赖 jar） |
| `TransformOutputProvider` | 用于申领输出位置：`getContentLocation(name, types, scopes, Format.DIRECTORY/JAR)` |
| `TransformInvocation` | 新 API 的聚合参数对象，包装 `context` / `inputs` / `referencedInputs` / `outputProvider` / `isIncremental` |
| `Status` | 增量构建下每个条目的状态：`NOTCHANGED` / `ADDED` / `CHANGED` / `REMOVED` |

### 关键抽象/可覆写方法

- `String getName()` — Transform 的唯一名字，会被拼进对应 Task 的名字，例如 `transformClassesWithLifeCycleTransformForDebug`。
- `Set<ContentType> getInputTypes()` — 声明消费的数据类型。织入字节码一般返回 `TransformManager.CONTENT_CLASS`（只处理 class）。
- `Set<ContentType> getOutputTypes()` — 默认等于输入类型。
- `Set<? super Scope> getScopes()` — 声明**要消费**的范围。`SCOPE_FULL_PROJECT` 表示工程源码 + 子模块 + 外部依赖全处理；若只读不消费则返回空集，配合下面的 `getReferencedScopes()`。
- `Set<? super Scope> getReferencedScopes()` — 声明**只读引用**的范围（不会消费它，下游仍可拿到）。
- `boolean isIncremental()` — 是否支持增量构建；返回 `true` 时框架会把变更信息通过 `Status`/`getChangedFiles()` 提供给 `transform()`。即便返回 `true`，次要输入改动、参数改动等场景下仍可能被强制走全量。
- `getSecondaryFiles()` / `getSecondaryFileInputs()` / `getSecondaryFileOutputs()` / `getSecondaryDirectoryOutputs()` / `getParameterInputs()` — 声明不走流水线但会影响 Transform 结果的"外部输入/输出"；它们变化时会触发 Transform 重跑。
- `boolean isCacheable()` — 是否让产物进入 Gradle Build Cache。
- `boolean applyToVariant(VariantInfo)` — 是否对指定 Variant 启用（3.4+）。

### 核心执行方法

```java
public void transform(@NonNull TransformInvocation transformInvocation)
        throws TransformException, InterruptedException, IOException;
```

或旧签名（已标 `@Deprecated`，新版本默认委托到上面这个）：

```java
public void transform(Context context,
                      Collection<TransformInput> inputs,
                      Collection<TransformInput> referencedInputs,
                      TransformOutputProvider outputProvider,
                      boolean isIncremental) throws IOException, TransformException, InterruptedException;
```

职责：
1. 遍历 `inputs` 中的每个 `DirectoryInput` / `JarInput`；
2. 按需读取/修改内容（通常用 ASM 读写 class 字节码）；
3. **必须**把结果写到 `outputProvider.getContentLocation(...)` 返回的位置，否则后续 Task 拿不到 class，会导致编译产物缺失。官方最佳实践是"每个输入对应一个输出"，避免把多个 scope 合并成单输出，否则下游 Transform 请求小范围 scope 时会失败。

### 配合使用的注册入口

插件里一般这样注册：

```groovy
def android = project.extensions.getByType(AppExtension)   // 或 LibraryExtension
android.registerTransform(new LifeCycleTransform(project))
```

AGP 会为该 Transform 创建 Task 并插入构建链。

---

## 三、本项目里 `Transform` 是如何被用的

`lifecycle-plugin/src/main/groovy/com/wyj/plugin/LifeCycleTransform.groovy` 正是一个典型实现，其关键设置与处理：

- `getName()` = `"LifeCycleTransform"`；
- `getInputTypes()` = `TransformManager.CONTENT_CLASS`（只吃 class）；
- `getScopes()` = `TransformManager.SCOPE_FULL_PROJECT`（吃整个工程 + 子模块 + 依赖）；
- `isIncremental()` = `true`；
- 覆写的是**旧版 5 参数 `transform`**（已标 `@Deprecated`），签名如下（与基类一致，格式化后每个参数独占一行）：

  ```groovy
  @Override
  void transform(
          Context context,
          Collection<TransformInput> inputs,
          Collection<TransformInput> referencedInputs,
          TransformOutputProvider outputProvider,
          boolean isIncremental) throws IOException, TransformException, InterruptedException {
      ...
  }
  ```

  基类的新版 `transform(TransformInvocation)` 默认会把调用委托到这个旧版方法，所以覆写旧版仍能工作；但如果之后迁移到新 API，建议直接覆写 `transform(TransformInvocation)` 并从 `invocation.getInputs()` / `getOutputProvider()` 取参数，可顺带拿到 `isIncremental` 与 `context`。
- 方法体内：
  1. 遍历 `inputs` 的 `directoryInputs`，用 `ScanUtil.isTargetProxyClass` 匹配 `Jie$$*$$Proxy.class` 收集代理类名，然后用 `FileUtils.copyDirectory` **把目录完整拷贝到 `outputProvider.getContentLocation(..., Format.DIRECTORY)` 分配的位置**——对应前文"必须把内容写回输出"的约束。
  2. 遍历 `jarInputs`：先用文件绝对路径的 MD5 作为输出名后缀以避免同名冲突（`DigestUtils.md5Hex`）；只对以 `.jar` 结尾且通过 `ScanUtil.shouldProcessPreDexJar` 白名单（排除 `com.android.support`、`android/m2repository`）的 jar 调 `ScanUtil.scanJar`，把 `com/wyj/lifecycle/apt/proxy/` 下的 class 名加进列表，并把包含 `com/wyj/api/AppLifeCycleManager.class` 的那个 jar 记录到 `ScanUtil.FILE_CONTAINS_INIT_CLASS`；扫描完后统一用 `FileUtils.copyFile(jarInput.file, dest)` 把 jar 原样拷贝到输出位置（注：这里拷贝的仍是未注入前的源文件，真正的字节码改写由下一步的 `AppLikeCodeInjector` 在 `FILE_CONTAINS_INIT_CLASS` 指向的目标 jar 上就地完成）。
  3. 所有类名收齐后交给 `AppLikeCodeInjector`，用 ASM 改写 `AppLifeCycleManager.loadAppLike()` 方法，注入一串 `registerAppLike("代理类全限定名")` 调用。

> 注：当前实现**没有利用真正的增量能力**——虽然 `isIncremental()` 声明为 `true`，但 `transform` 内部没有根据 `JarInput.getStatus()` / `DirectoryInput.getChangedFiles()` 分支处理，每次都全量拷贝 + 扫描 + 注入。代价是 class 任意变动都会触发完整重跑；优点是实现简单、不会漏处理。

---

## 四、Transform API 的现状与迁移建议

- **7.0 起**：`registerTransform` 已标记 `@Deprecated`，控制台会打 deprecation 警告。
- **7.4.2**：仍然可用，但同时提供 **AsmClassVisitorFactory + androidComponents DSL** 作为替代，直接注册 `ClassVisitor`，框架接管 I/O。
- **AGP 8.0**：官方计划**完全移除** Transform API。基于本项目 `lifecycle-plugin` 的使用方式，未来若升级 AGP 到 8.x，需要把 `LifeCycleTransform` 改写为：

  ```kotlin
  androidComponents.onVariants { variant ->
      variant.instrumentation.transformClassesWith(
          AppLikeAsmFactory::class.java,
          InstrumentationScope.ALL
      ) { /* params */ }
  }
  ```

  由自定义 `AsmClassVisitorFactory` 返回 `ClassVisitor`，由框架负责扫描所有 class 并驱动访问。

---

## 五、参考链接

- Android Gradle 插件版本说明：<https://developer.android.com/studio/releases/gradle-plugin>
- Transform API 迁移指南：<https://developer.android.com/studio/releases/gradle-plugin-api-updates#transform-api>
- AGP 插件路线图：<https://developer.android.com/studio/releases/gradle-plugin-roadmap>
- 本地源码路径：`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle-api/7.4.2/.../gradle-api-7.4.2-sources.jar` → `com/android/build/api/transform/Transform.java`
