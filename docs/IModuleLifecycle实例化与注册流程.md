# IModuleLifecycle 实现类的实例化与注册流程

## 一、参与的两层类

- **业务实现类**（当前项目共 4 个，全部用 `@ModuleLifecycle` 标注并直接实现 `IModuleLifecycle`。`IModuleLifecycle` 中定义 `MAX_PRIORITY=10`、`NORM_PRIORITY=5`、`MIN_PRIORITY=1`）：
  - `app` 模块：`com.wyj.lifecycle.demo.ModuleALifecycle`（`NORM_PRIORITY` = 5）、`com.wyj.lifecycle.demo.ModuleBLifecycle`（`NORM_PRIORITY` = 5）。
  - `module-1` 模块：`com.wyj.module1.ModuleCLifecycle`（`MAX_PRIORITY` = 10）、`com.wyj.module1.ModuleDLifecycle`（字面量 **7**，介于 `NORM_PRIORITY` 与 `MAX_PRIORITY` 之间）。
  - 注意：`app` 和 `module-1` 的 `build.gradle` 都必须各自 `apply` `annotationProcessor project(':lifecycle-apt')`，APT 才会为本模块的 4 个类分别生成代理；只在 `app` 里配置 APT 不会处理 `module-1` 的注解类。
- **代理类**：APT 编译期生成，每个业务实现类对应一个代理，名字形如 `Jie$$ModuleALifecycle$$Proxy` / `Jie$$ModuleBLifecycle$$Proxy` / `Jie$$ModuleCLifecycle$$Proxy` / `Jie$$ModuleDLifecycle$$Proxy`（前缀 `Jie$$` 取自 `LifeCycleConfig.PROXY_CLASS_PREFIX`，后缀 `$$Proxy` 取自 `LifeCycleConfig.PROXY_CLASS_SUFFIX`），包名统一为 `com.wyj.lifecycle.apt.proxy`（`LifeCycleConfig.PROXY_CLASS_PACKAGE_NAME`）。代理类本身也 `implements IModuleLifecycle`，内部持有一个业务实现类字段，并在**自己的无参构造器里 `new` 出业务实现类**（见 `ModuleLifecycleProxyClassCreator.generateJavaCode` 第 54–56 行）。

所以真正放进 `ModuleLifecycleManager.MODULE_LIFECYCLE_LIST` 的是代理对象，业务实现类实例是被代理对象的构造器顺带 `new` 出来的。

## 二、实例化与注册的触发点

用户在 `MainActivity` 里调用 `ModuleLifecycleManager.init(context)`（`app/.../MainActivity.java:21`），这是唯一入口。`init` 内部按是否经过插件注入，走两条路径：

### 路径 A：插件注入成功（正常情况）

1. 编译打包时，`lifecycle-plugin` 的 `LifeCycleTransform` 运行（见 `LifeCycleTransform.groovy:39-98`，覆写的是 `Transform` 的旧版 5 参数 `transform(Context, Collection<TransformInput>, Collection<TransformInput>, TransformOutputProvider, boolean)`）：
   - 遍历 `directoryInputs`，用 `ScanUtil.isTargetProxyClass` 判断文件名是否同时以 `Jie$$` 开头、`$$Proxy.class` 结尾，命中则把文件名收集到 `proxyClassList`。
   - 遍历 `jarInputs`，先用 `ScanUtil.shouldProcessPreDexJar` 过滤掉 `com.android.support`、`android/m2repository` 等依赖，再用 `ScanUtil.scanJar` 扫描 jar 里 `com/wyj/lifecycle/apt/proxy` 包下的 class 名并加入同一个列表；同时识别包含 `com/wyj/api/ModuleLifecycleManager.class` 的那个 jar，记到 `ScanUtil.FILE_CONTAINS_INIT_CLASS`，作为后面注入的目标。
2. 收集完成后，`new ModuleLifecycleCodeInjector(proxyClassList).execute()` 通过 ASM 改写 `ModuleLifecycleManager.loadModuleLifecycle()`（原方法是空的）。改写后的逻辑等价于对**全部 4 个**代理类依次执行：

   ```java
   registerModuleLifecycle("com.wyj.lifecycle.apt.proxy.Jie$$ModuleALifecycle$$Proxy");
   registerModuleLifecycle("com.wyj.lifecycle.apt.proxy.Jie$$ModuleBLifecycle$$Proxy");
   registerModuleLifecycle("com.wyj.lifecycle.apt.proxy.Jie$$ModuleCLifecycle$$Proxy");
   registerModuleLifecycle("com.wyj.lifecycle.apt.proxy.Jie$$ModuleDLifecycle$$Proxy");
   ```

   见 `ModuleLifecycleCodeInjector.groovy:97-106`。其中 `app` 模块的 A/B 代理类编译后位于 directoryInputs（会走 `isTargetProxyClass` 命中），`module-1` 的 C/D 代理类随该模块被打成 jar 进入 jarInputs（由 `ScanUtil.scanJar` 命中）。
3. 运行期 `init()` → `loadModuleLifecycle()` → 对上述 4 个类名依次执行 `Class.forName(name).getConstructor().newInstance()`（`ModuleLifecycleManager.java:36`）。**这一步才真正实例化 4 个代理类**；每个代理构造器内部再 `new` 出对应的 `ModuleALifecycle` / `ModuleBLifecycle` / `ModuleCLifecycle` / `ModuleDLifecycle` 业务实例。
4. `registerModuleLifecycle(IModuleLifecycle)` 把 4 个代理加入 `MODULE_LIFECYCLE_LIST`，并把 `REGISTER_BY_PLUGIN` 置 `true`。
5. 所有代理注册完后，`init()` 按 `getPriority()` 倒序排序，依次调用各代理的 `onCreate(context)`，代理再转发给业务实现类。本项目实际优先级：`ModuleCLifecycle` = 10（`MAX_PRIORITY`）、`ModuleDLifecycle` = 7、`ModuleALifecycle` = 5（`NORM_PRIORITY`）、`ModuleBLifecycle` = 5（`NORM_PRIORITY`）。因此执行顺序为 **C → D → A → B**；A、B 同为 5，`Collections.sort` 的稳定性保证它们之间保持原有遍历顺序。

### 路径 B：没有启用插件的降级方案

`loadModuleLifecycle()` 是空的，`REGISTER_BY_PLUGIN` 仍为 `false`，于是走 `scanClassFile(context)`（`ModuleLifecycleManager.java:89-110`）：用 `ClassUtils.getFileNameByPackageName` 扫描 APK dex 里 `com.wyj.lifecycle.apt.proxy` 包下所有类名，再 `Class.forName(...).newInstance()` 反射实例化并加入列表。实例化时机与方式本质相同，只是类名来源从"插件写死的字符串字面量"换成了"运行时扫描 dex"。

## 三、时序小结

- **编译期**：APT 生成代理类源码 → 编译为 class → 插件 ASM 把 `registerModuleLifecycle("全限定名")` 调用塞进 `loadModuleLifecycle()` 字节码。
- **运行期**：`Application`/`Activity` 调 `ModuleLifecycleManager.init(context)` → `loadModuleLifecycle()` 内注入的语句依次 `Class.forName + newInstance` **实例化代理** → 代理构造器 **实例化业务 `IModuleLifecycle`** → `registerModuleLifecycle(IModuleLifecycle)` 加入静态 List → 排序 → 逐个 `onCreate`。

即：**实例化发生在 `init()` 被调用的那一刻**；**注册方式是插件在编译期注入到 `loadModuleLifecycle()` 的 `registerModuleLifecycle(className)` 调用**（降级路径则是运行时扫 dex 反射注册）。

## 四、UML 时序图

见同目录下 [`IModuleLifecycle注册流程-时序图.puml`](./IModuleLifecycle注册流程-时序图.puml)，包含"编译期"与"运行期"两张 PlantUML 时序图。使用 IntelliJ / Android Studio 的 **PlantUML integration** 插件，或本地 `plantuml.jar` / VS Code 的 **PlantUML** 扩展可直接渲染。

## 五、本项目使用的关键技术：APT 与 ASM

本项目能做到"组件不改 Application 也能自动注册生命周期回调"，靠的是**编译期代码生成（APT）**与**编译期字节码织入（ASM）**两项技术的组合。它们都发生在打包 apk 之前，运行时几乎没有额外开销。

### 5.0 为什么要这么做：消除"新增组件必须改 Application"这件事

这里的 **Application** 特指宿主模块（`app`）的应用入口类——继承 `android.app.Application` 的那个类（在本 demo 里为了方便观察日志，等价触发点退化到了 `MainActivity.onCreate`）。**组件**指 `module-1` 这类业务子模块。

#### 传统做法的痛点

没有注册框架时，新增一个需要初始化的子模块，通常只能让宿主的 `Application.onCreate()` 去调它：

```java
// 宿主 app 模块里的 MyApplication.java
@Override
public void onCreate() {
    super.onCreate();
    new ModuleALifecycle().onCreate(this);
    new ModuleBLifecycle().onCreate(this);
    new ModuleCLifecycle().onCreate(this);   // ← 每加一个新模块就要在这里加一行
    new ModuleDLifecycle().onCreate(this);   // ← 同上
}
```

由此带来四个问题：

1. **宿主反向依赖子模块**：`app` 必须知道每个子模块的 Lifecycle 类名、包路径、调用顺序，违反"宿主应该对组件一无所知"的模块化原则。
2. **修改扩散**：每加/减一个模块都要改 `Application` 类，多人并行开发容易产生合并冲突。
3. **容易漏注册**：新人加完模块忘了回头改 `Application`，组件就成了"死代码"，bug 到上线才被发现。
4. **可复用性差**：同一个组件被多个 app 复用时，每个 app 都要各自维护一份一模一样的注册清单。

#### 本项目方案

宿主只写一行、永不再改：

```java
// 宿主 Application.onCreate()（本 demo 退化到 MainActivity.onCreate 中触发）
ModuleLifecycleManager.init(context);
```

之后新增一个子模块的流程：

- 在子模块里写一个 `@ModuleLifecycle public class ModuleELifecycle implements IModuleLifecycle { ... }`；
- 该子模块 `build.gradle` 声明 `annotationProcessor project(':lifecycle-apt')`；
- 剩下的事情——生成代理类、在 `ModuleLifecycleManager.loadModuleLifecycle()` 里追加 `registerModuleLifecycle(...)` 字节码——全部由 APT + ASM 在编译期自动完成。

**宿主 `Application` 的代码一行都不用动**，就能把新组件的生命周期串进来。这就是"组件不改 Application 也能自动注册生命周期回调"的含义。

### 5.1 APT（Annotation Processing Tool，注解处理器）

#### 是什么

APT 是 Java 编译器自带的一个扩展点，允许开发者编写 `javax.annotation.processing.AbstractProcessor` 的子类，在 **`javac` 扫描完源码、生成 class 文件之前**，拿到所有被指定注解修饰的 `Element`（类、方法、字段等），并通过 `Filer` API 往源码/资源目录里**写入新的 Java 源文件**。新写入的源文件会加入同一轮编译，无须开发者手动维护。

常见 Android 框架（ButterKnife、Dagger、Room、ARouter、Glide、EventBus3）都用 APT 为每个目标类生成一个"辅助类"。

#### 它解决的问题

1. **消除反射 / 运行时扫描带来的性能损耗**：把"一个模块里有哪些组件、它们的元数据是什么"这件事从运行时挪到编译期，apk 启动时直接按"硬编码"的类名加载。
2. **消除样板代码**：本项目中如果没有 APT，开发者要么在每个组件里手写一个"把自己加到列表"的静态块，要么由 host `Application` 手动 `new ModuleALifecycle()` / `new ModuleBLifecycle()` ... 维护注册表。APT 代替开发者写这些样板。
3. **强制合约 / 编译期校验**：`ModuleLifecycleProcessor` 会在 `process()` 中检查被 `@ModuleLifecycle` 注解的类是否实现了 `IModuleLifecycle`，不合规直接抛 `RuntimeException` 让编译失败——问题在编译期就暴露，而不是运行时崩溃。

#### 本项目怎么用 APT

- 模块：`lifecycle-apt`（纯 Java lib，不依赖 Android）。
- 入口类：`com.wyj.apt.ModuleLifecycleProcessor`，通过 `@AutoService(Processor.class)` 被 `javac` 发现（Google 的 `auto-service` 会自动生成 `META-INF/services/javax.annotation.processing.Processor`）。
- 支持的注解：`com.wyj.annotation.lifecycle.ModuleLifecycle`。
- 处理流程：
  1. `getSupportedAnnotationTypes()` 告知编译器只处理 `@ModuleLifecycle`。
  2. 每一轮编译在 `process()` 里拿到所有被注解的类元素，做接口校验。
  3. 对每个类委托 `ModuleLifecycleProxyClassCreator.generateJavaCode()` 拼接出代理类源码，通过 `processingEnv.getFiler().createSourceFile(...)` 写到 `build/generated/source/.../com/wyj/lifecycle/apt/proxy/Jie$$*$$Proxy.java`。
- 调用方接入：使用方（`app`、`module-1`）在各自 `build.gradle` 里声明 `annotationProcessor project(':lifecycle-apt')`，否则 APT 不会对该模块的注解类生效。
- 生成的代理类用途：统一实现 `IModuleLifecycle`、拥有**公开无参构造器**，供后续 ASM 注入的 `Class.forName(name).newInstance()` 能无差别地反射创建。

> 本项目用字符串拼接来生成代码；工业级方案通常改用 **JavaPoet**（生成 Java）或 **KotlinPoet**（生成 Kotlin），可避免转义错误并提供类型安全的 API。

#### APT 的局限

- 只能**新增**文件，**不能修改**已有类的源码或字节码。
- 只能处理 Java/Kotlin 源码中标注的元素，拿不到第三方 jar 里的类。
- 所以"把 `registerModuleLifecycle` 调用塞进 `ModuleLifecycleManager.loadModuleLifecycle()` 方法体"这种"改别人代码"的需求，必须借助 ASM。

### 5.2 ASM（字节码操作框架）

#### 是什么

ASM 是 OW2 开源的 Java 字节码读写框架，直接操作 `.class` 文件的结构（常量池、方法、指令）。API 分两层：

- **Core API**：以访问者模式（Visitor）流式读写字节码，性能高、内存占用小，但需要理解 JVM 字节码指令。
- **Tree API**：把整个 class 加载成对象树，便于任意改写，代价是内存占用高。

相较于 Javassist 基于字符串 DSL 的方式，ASM 离底层更近、速度更快，也是 Android Gradle Plugin 内部、Kotlin 编译器、MockK、Booster、ARouter 插件等的首选。

#### 它解决的问题

1. **修改无源码的类**：对 jar 里的 `ModuleLifecycleManager.class`、`android.jar` 里的类、第三方库的类都能改。
2. **在已有方法里注入逻辑**：比如本项目在 `loadModuleLifecycle()` 方法体内**逐条插入** `registerModuleLifecycle("…Proxy")` 调用；Hugo 在方法前后插入日志；热修复往方法里插入"跳转到补丁"的字节码。
3. **条件织入**：编译期按类名/注解/继承关系决定改哪些类，运行时看不到任何 hook、反射扫描或动态代理开销。
4. **把分散的元信息"喂给"一个集中入口**：本项目把散落在各模块的代理类名列表，一次性写死到 `ModuleLifecycleManager.loadModuleLifecycle()` 里，省掉运行时的 dex 扫描（降级路径 `scanClassFile` 才需要）。

#### 本项目怎么用 ASM

- 入口：Gradle 插件 `lifecycle-plugin` 通过自定义 `Transform`（`LifeCycleTransform`）在**所有 class 编译完成、打包成 dex 之前**拿到全部 class 与依赖 jar。
- 扫描：`ScanUtil` 按目录/jar 分别扫描 `com/wyj/lifecycle/apt/proxy/Jie$$*$$Proxy.class`，同时记录包含 `com/wyj/api/ModuleLifecycleManager.class` 的宿主 jar 到 `FILE_CONTAINS_INIT_CLASS`。
- 注入：`ModuleLifecycleCodeInjector` 的关键字节码操作（`ModuleLifecycleCodeInjector.groovy:71-114`）：
  - `ClassReader` 读取宿主 jar 内的 `ModuleLifecycleManager.class`。
  - `ClassWriter(COMPUTE_MAXS)` 让 ASM 自动计算操作数栈与局部变量表大小，避免手算 max stack。
  - `ClassVisitor.visitMethod` 拦截 `loadModuleLifecycle` 方法，用自定义 `LoadModuleLifecycleMethodAdapter`（继承 `AdviceAdapter`）在 `onMethodEnter()` 阶段，对每个代理类名发射两条指令：
    - `visitLdcInsn(fullName)`：将常量字符串压栈，对应字节码 `LDC "com.wyj.lifecycle.apt.proxy.Jie$$*$$Proxy"`。
    - `visitMethodInsn(INVOKESTATIC, "com/wyj/api/ModuleLifecycleManager", "registerModuleLifecycle", "(Ljava/lang/String;)V", false)`：生成静态方法调用。
  - 将改写后的 class 写回临时 jar，再 `renameTo` 覆盖原 jar。
- 这样改写后，运行时 `init()` 调 `loadModuleLifecycle()` 就会依次触发 4 条 `registerModuleLifecycle(String)`，反射实例化每个代理。

#### ASM 的局限

- **写错一字节就崩**：需要对 JVM 指令集与栈帧有基本了解；`COMPUTE_MAXS` / `COMPUTE_FRAMES` 可以帮忙但并非万能。
- **依赖 AGP 的 Transform API**：AGP 7.x 仍保留但已 `@Deprecated`，**AGP 8.0 会移除**，届时本项目的 `lifecycle-plugin` 需要迁移到 `androidComponents.onVariants { ... transformClassesWith(AsmClassVisitorFactory, ...) }` 的新 API（参见 `docs/AGP-Transform说明.md`）。

### 5.3 二者如何配合

| 步骤 | 角色 | 输入 | 产物 |
|---|---|---|---|
| ① 注解处理 | APT (`ModuleLifecycleProcessor`) | `@ModuleLifecycle` 注解的业务类 | 每个业务类对应的 `Jie$$*$$Proxy.java` 源码 |
| ② 编译 | `javac` | 业务类 + 生成的代理类源码 | `.class` 文件 |
| ③ 扫描 | Gradle Plugin (`LifeCycleTransform` + `ScanUtil`) | 所有 class / 依赖 jar | 代理类全限定名列表 + 宿主 jar 位置 |
| ④ 字节码注入 | ASM (`ModuleLifecycleCodeInjector`) | 宿主 jar 内的 `ModuleLifecycleManager.class` + 代理类名列表 | 改写后的 `loadModuleLifecycle()` 字节码 |
| ⑤ 运行 | `ModuleLifecycleManager.init()` | 改写后的 `loadModuleLifecycle()` | 实例化代理 → 业务类 → 排序 → `onCreate` |

**一句话总结**：
> **APT 负责"造代理类"，ASM 负责"把代理类名写进管理器的方法体"。两者协作把模块的注册行为从"运行时反射/扫描"搬到"编译期硬编码"，达到零反射扫描成本的自动注册。**
