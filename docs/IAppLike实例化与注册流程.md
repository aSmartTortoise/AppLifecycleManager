# IAppLike 实现类的实例化与注册流程

## 一、参与的两层类

- **业务实现类**（当前项目共 4 个，全部用 `@AppLifeCycle` 标注并直接实现 `IAppLike`。`IAppLike` 中定义 `MAX_PRIORITY=10`、`NORM_PRIORITY=5`、`MIN_PRIORITY=1`）：
  - `app` 模块：`com.wyj.lifecycle.demo.ModuleAAppLike`（`NORM_PRIORITY` = 5）、`com.wyj.lifecycle.demo.ModuleBAppLike`（`NORM_PRIORITY` = 5）。
  - `module-1` 模块：`com.wyj.module1.ModuleCAppLike`（`MAX_PRIORITY` = 10）、`com.wyj.module1.ModuleDAppLike`（字面量 **7**，介于 `NORM_PRIORITY` 与 `MAX_PRIORITY` 之间）。
  - 注意：`app` 和 `module-1` 的 `build.gradle` 都必须各自 `apply` `annotationProcessor project(':lifecycle-apt')`，APT 才会为本模块的 4 个类分别生成代理；只在 `app` 里配置 APT 不会处理 `module-1` 的注解类。
- **代理类**：APT 编译期生成，每个业务实现类对应一个代理，名字形如 `Jie$$ModuleAAppLike$$Proxy` / `Jie$$ModuleBAppLike$$Proxy` / `Jie$$ModuleCAppLike$$Proxy` / `Jie$$ModuleDAppLike$$Proxy`（前缀 `Jie$$` 取自 `LifeCycleConfig.PROXY_CLASS_PREFIX`，后缀 `$$Proxy` 取自 `LifeCycleConfig.PROXY_CLASS_SUFFIX`），包名统一为 `com.wyj.lifecycle.apt.proxy`（`LifeCycleConfig.PROXY_CLASS_PACKAGE_NAME`）。代理类本身也 `implements IAppLike`，内部持有一个业务实现类字段，并在**自己的无参构造器里 `new` 出业务实现类**（见 `AppLikeProxyClassCreator.generateJavaCode` 第 54–56 行）。

所以真正放进 `AppLifeCycleManager.APP_LIKE_LIST` 的是代理对象，业务实现类实例是被代理对象的构造器顺带 `new` 出来的。

## 二、实例化与注册的触发点

用户在 `MainActivity` 里调用 `AppLifeCycleManager.init(context)`（`app/.../MainActivity.java:21`），这是唯一入口。`init` 内部按是否经过插件注入，走两条路径：

### 路径 A：插件注入成功（正常情况）

1. 编译打包时，`lifecycle-plugin` 的 `LifeCycleTransform` 运行（见 `LifeCycleTransform.groovy:39-98`，覆写的是 `Transform` 的旧版 5 参数 `transform(Context, Collection<TransformInput>, Collection<TransformInput>, TransformOutputProvider, boolean)`）：
   - 遍历 `directoryInputs`，用 `ScanUtil.isTargetProxyClass` 判断文件名是否同时以 `Jie$$` 开头、`$$Proxy.class` 结尾，命中则把文件名收集到 `appLikeProxyClassList`。
   - 遍历 `jarInputs`，先用 `ScanUtil.shouldProcessPreDexJar` 过滤掉 `com.android.support`、`android/m2repository` 等依赖，再用 `ScanUtil.scanJar` 扫描 jar 里 `com/wyj/lifecycle/apt/proxy` 包下的 class 名并加入同一个列表；同时识别包含 `com/wyj/api/AppLifeCycleManager.class` 的那个 jar，记到 `ScanUtil.FILE_CONTAINS_INIT_CLASS`，作为后面注入的目标。
2. 收集完成后，`new AppLikeCodeInjector(appLikeProxyClassList).execute()` 通过 ASM 改写 `AppLifeCycleManager.loadAppLike()`（原方法是空的）。改写后的逻辑等价于对**全部 4 个**代理类依次执行：

   ```java
   registerAppLike("com.wyj.lifecycle.apt.proxy.Jie$$ModuleAAppLike$$Proxy");
   registerAppLike("com.wyj.lifecycle.apt.proxy.Jie$$ModuleBAppLike$$Proxy");
   registerAppLike("com.wyj.lifecycle.apt.proxy.Jie$$ModuleCAppLike$$Proxy");
   registerAppLike("com.wyj.lifecycle.apt.proxy.Jie$$ModuleDAppLike$$Proxy");
   ```

   见 `AppLikeCodeInjector.groovy:97-106`。其中 `app` 模块的 A/B 代理类编译后位于 directoryInputs（会走 `isTargetProxyClass` 命中），`module-1` 的 C/D 代理类随该模块被打成 jar 进入 jarInputs（由 `ScanUtil.scanJar` 命中）。
3. 运行期 `init()` → `loadAppLike()` → 对上述 4 个类名依次执行 `Class.forName(name).getConstructor().newInstance()`（`AppLifeCycleManager.java:36`）。**这一步才真正实例化 4 个代理类**；每个代理构造器内部再 `new` 出对应的 `ModuleAAppLike` / `ModuleBAppLike` / `ModuleCAppLike` / `ModuleDAppLike` 业务实例。
4. `registerAppLike(IAppLike)` 把 4 个代理加入 `APP_LIKE_LIST`，并把 `REGISTER_BY_PLUGIN` 置 `true`。
5. 所有代理注册完后，`init()` 按 `getPriority()` 倒序排序，依次调用各代理的 `onCreate(context)`，代理再转发给业务实现类。本项目实际优先级：`ModuleCAppLike` = 10（`MAX_PRIORITY`）、`ModuleDAppLike` = 7、`ModuleAAppLike` = 5（`NORM_PRIORITY`）、`ModuleBAppLike` = 5（`NORM_PRIORITY`）。因此执行顺序为 **C → D → A → B**；A、B 同为 5，`Collections.sort` 的稳定性保证它们之间保持原有遍历顺序。

### 路径 B：没有启用插件的降级方案

`loadAppLike()` 是空的，`REGISTER_BY_PLUGIN` 仍为 `false`，于是走 `scanClassFile(context)`（`AppLifeCycleManager.java:89-110`）：用 `ClassUtils.getFileNameByPackageName` 扫描 APK dex 里 `com.wyj.lifecycle.apt.proxy` 包下所有类名，再 `Class.forName(...).newInstance()` 反射实例化并加入列表。实例化时机与方式本质相同，只是类名来源从"插件写死的字符串字面量"换成了"运行时扫描 dex"。

## 三、时序小结

- **编译期**：APT 生成代理类源码 → 编译为 class → 插件 ASM 把 `registerAppLike("全限定名")` 调用塞进 `loadAppLike()` 字节码。
- **运行期**：`Application`/`Activity` 调 `AppLifeCycleManager.init(context)` → `loadAppLike()` 内注入的语句依次 `Class.forName + newInstance` **实例化代理** → 代理构造器 **实例化业务 `IAppLike`** → `registerAppLike(IAppLike)` 加入静态 List → 排序 → 逐个 `onCreate`。

即：**实例化发生在 `init()` 被调用的那一刻**；**注册方式是插件在编译期注入到 `loadAppLike()` 的 `registerAppLike(className)` 调用**（降级路径则是运行时扫 dex 反射注册）。
