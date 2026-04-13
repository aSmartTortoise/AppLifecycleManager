# Android 13 适配计划

## 一、项目现状

| 项目配置项 | 当前值 |
|-----------|--------|
| AGP (Android Gradle Plugin) | 7.4.2 |
| Gradle | 7.5.1 |
| compileSdkVersion | 27 |
| targetSdkVersion | 27 |
| minSdkVersion | 19 |
| Support Library | com.android.support:appcompat-v7:27.1.1 |
| Java 兼容级别 (app/library) | 默认(1.7) |
| Java 兼容级别 (plugin/apt) | 1.7 / 11 |
| Transform API | 旧版 `com.android.build.api.transform` (已在 AGP 8.0 移除) |

## 二、适配目标

- **compileSdk** 升级到 **33** (Android 13)
- **targetSdk** 升级到 **33**
- **minSdk** 保持 **19** (不变)
- 迁移至 **AndroidX**
- 确保 Gradle Plugin（ASM 注入）兼容新版 AGP

## 三、详细步骤

### 步骤 1：升级 Gradle 与 AGP

AGP 7.4.2 已支持 compileSdk 33，因此 **AGP 和 Gradle 版本可以暂不升级**。如果后续需要 AGP 8.x 特性再做升级。

当前 AGP 7.4.2 + Gradle 7.5.1 的组合可以直接编译 compileSdk 33 的项目。

> 注意：如果升级到 AGP 8.0+，Transform API 将被移除，lifecycle-plugin 模块需要重写为基于 AsmClassVisitorFactory 的实现，这是一项较大的改动，建议作为独立阶段处理。

### 步骤 2：迁移到 AndroidX (Support Library -> AndroidX)

这是最关键的一步。Android 13 要求使用 AndroidX，旧的 `com.android.support` 库不再提供新版本。

#### 2.1 启用 AndroidX 和 Jetifier

在 `gradle.properties` 中添加：

```properties
android.useAndroidX=true
android.enableJetifier=true
```

- `android.useAndroidX=true` — 项目使用 AndroidX 命名空间
- `android.enableJetifier=true` — 自动将第三方依赖中的 Support Library 引用转换为 AndroidX

#### 2.2 替换依赖

| 模块 | 旧依赖 | 新依赖 |
|------|--------|--------|
| app | `com.android.support:appcompat-v7:27.1.1` | `androidx.appcompat:appcompat:1.4.2` |
| app | `com.android.support.constraint:constraint-layout:1.1.3` | `androidx.constraintlayout:constraintlayout:2.1.4` |
| app | `com.android.support.test:runner:1.0.2` | `androidx.test:runner:1.4.0` |
| app | `com.android.support.test.espresso:espresso-core:3.0.2` | `androidx.test.espresso:espresso-core:3.4.0` |
| lifecycle-api | `com.android.support:appcompat-v7:27.1.1` | `androidx.appcompat:appcompat:1.4.2` |
| lifecycle-api | `com.android.support.test:runner:1.0.2` | `androidx.test:runner:1.4.0` |
| lifecycle-api | `com.android.support.test.espresso:espresso-core:3.0.2` | `androidx.test.espresso:espresso-core:3.4.0` |
| module-1 | `com.android.support:appcompat-v7:27.1.1` | `androidx.appcompat:appcompat:1.4.2` |
| module-1 | `com.android.support.test:runner:1.0.2` | `androidx.test:runner:1.4.0` |
| module-1 | `com.android.support.test.espresso:espresso-core:3.0.2` | `androidx.test.espresso:espresso-core:3.4.0` |

#### 2.3 替换 Java 源码中的 import

| 文件 | 旧 import | 新 import |
|------|-----------|-----------|
| `app/.../MainActivity.java` | `android.support.v7.app.AppCompatActivity` | `androidx.appcompat.app.AppCompatActivity` |
| `lifecycle-api/.../DefaultThreadFactory.java` | `android.support.annotation.NonNull` | `androidx.annotation.NonNull` |
| `app/.../ExampleInstrumentedTest.java` | `android.support.test.InstrumentationRegistry` | `androidx.test.platform.app.InstrumentationRegistry` |
| `app/.../ExampleInstrumentedTest.java` | `android.support.test.runner.AndroidJUnit4` | `androidx.test.ext.junit.runners.AndroidJUnit4` |
| `module-1/.../ExampleInstrumentedTest.java` | 同上 | 同上 |
| `lifecycle-api/.../ExampleInstrumentedTest.java` | 同上 | 同上 |

#### 2.4 替换 build.gradle 中的 testInstrumentationRunner

所有 Android 模块 (`app`, `lifecycle-api`, `module-1`) 中：

```groovy
// 旧
testInstrumentationRunner "android.support.test.runner.AndroidJUnitRunner"
// 新
testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
```

### 步骤 3：升级 compileSdk 和 targetSdk

修改以下模块的 `build.gradle`：

| 模块 | 修改项 |
|------|--------|
| `app/build.gradle` | `compileSdkVersion 33`, `targetSdkVersion 33` |
| `lifecycle-api/build.gradle` | `compileSdkVersion 33`, `targetSdkVersion 33` |
| `module-1/build.gradle` | `compileSdkVersion 33`, `targetSdkVersion 33` |

### 步骤 4：AndroidManifest.xml 适配

#### 4.1 `android:exported` 属性

Android 12 (API 31) 起，含 `<intent-filter>` 的组件**必须**显式声明 `android:exported`。

修改 `app/src/main/AndroidManifest.xml` 中的 MainActivity：

```xml
<activity android:name=".MainActivity"
    android:exported="true">
```

### 步骤 5：lifecycle-plugin 兼容性确认

lifecycle-plugin 使用旧版 Transform API (`com.android.build.api.transform.Transform`)。在 AGP 7.4.2 下该 API 仍可用（标记为 deprecated 但未移除），因此本次适配**无需修改 plugin 代码**。

需要确认的事项：
- ASM 9.2 已支持 Java 17 字节码，兼容性无问题
- Transform 扫描和注入逻辑与 SDK 版本无关，不受影响

> **后续风险提示**：如果未来升级到 AGP 8.0+，必须将 Transform 重写为 `AsmClassVisitorFactory` 方式，这是一个独立的较大改动。

### 步骤 6：proguard 文件名更新（可选）

AGP 7.x 推荐使用新的默认混淆文件名：

```groovy
// 旧
proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
// 新（推荐，包含优化规则）
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

此步骤为可选项，不影响编译。

### 步骤 7：编译验证

```bash
./gradlew clean assembleDebug
```

重点关注：
1. AndroidX 依赖解析是否成功
2. 源码中的 import 是否都已更新
3. lifecycle-plugin 的 Transform 是否正常执行
4. APK 是否可以在 Android 13 设备/模拟器上正常运行

## 四、修改文件清单

| 文件路径 | 修改内容 |
|---------|---------|
| `gradle.properties` | 添加 AndroidX 和 Jetifier 开关 |
| `app/build.gradle` | compileSdk/targetSdk 升到 33，替换 AndroidX 依赖，更新 testRunner |
| `lifecycle-api/build.gradle` | compileSdk/targetSdk 升到 33，替换 AndroidX 依赖，更新 testRunner |
| `module-1/build.gradle` | compileSdk/targetSdk 升到 33，替换 AndroidX 依赖，更新 testRunner |
| `app/src/main/AndroidManifest.xml` | MainActivity 添加 `android:exported="true"` |
| `app/.../MainActivity.java` | import 改为 AndroidX |
| `lifecycle-api/.../DefaultThreadFactory.java` | import 改为 AndroidX |
| `app/.../ExampleInstrumentedTest.java` | import 改为 AndroidX |
| `module-1/.../ExampleInstrumentedTest.java` | import 改为 AndroidX |
| `lifecycle-api/.../ExampleInstrumentedTest.java` | import 改为 AndroidX |

## 五、风险与注意事项

1. **jcenter() 仓库已停止服务** — AndroidX 依赖托管在 Google Maven 仓库 (`google()`)，已在 repositories 中配置。但如果有其他依赖仅在 jcenter 上存在，可能需要添加 `mavenCentral()` 作为备选。
2. **Jetifier 性能** — `android.enableJetifier=true` 会在编译时转换第三方库的引用，略微增加构建时间。如果项目中没有依赖旧 Support Library 的第三方库，后续可以关闭。
3. **Transform API 废弃** — 当前 AGP 7.4.2 仍支持，但升级 AGP 8.0+ 时需要重写 lifecycle-plugin。
4. **Android 13 运行时权限** — 本项目是 lifecycle 管理的 demo，不涉及通知、媒体等需要新权限的功能，暂无需处理 `POST_NOTIFICATIONS` 等新权限。
