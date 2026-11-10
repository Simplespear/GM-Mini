# GMMini 3.0 源码与构建说明

本包是 **GMMini 3.0** 的 Android Studio / Gradle 工程源码。界面基于
**AndroidX AppCompat + Material Components 1.9.0**，主界面、宏编辑器、弹窗与提示
均为 Material 3 组件；本版修复取消配对残留、锁定竖屏、摇杆测试触顶，并以 MIT
许可证开源。

## 1. 版本与产物

| 项 | 值 |
|---|---|
| 包名（applicationId / namespace） | `com.GameMacro.mini`（未变） |
| APP 名称 | `GM Mini`（未变） |
| versionName / versionCode | `3.0` / `33` |
| 编译 | AGP 8.1.4 + Gradle 8.2，compileSdk 34，build-tools 34.0.0，minSdk 26，targetSdk 33 |
| 依赖 | `androidx.appcompat:appcompat:1.6.1`、`com.google.android.material:material:1.9.0` |
| 语言 | 纯 Java 8 |
| 签名 | 构建机 debug keystore（alias `androiddebugkey`，密码 `android`）<br>证书 SHA-256 `F5:04:F6:B9:…:1B:DC` |
| APK 体积 | 约 7.1 MB（Material/AppCompat 与资源约 4.2 MB，说明书 PDF 2.9 MB） |

## 2. 本版界面改动

### 主题

- `themes.xml` 改为 `Theme.Material3.Light.NoActionBar`，定义完整 M3 色板：
  主色 `#4A5BD8`、`primaryContainer #E0E4FF`、surface `#F6F5FB`、
  outline `#E2DFEC`、error `#B3261E`。
- 统一定义 `Widget.GM.Card`（24dp 圆角 / 1dp 描边 / 0dp 阴影）与
  `Widget.GM.Button`（26dp 全圆角 / 14sp / 无默认 inset）。
- 状态栏、导航栏浅色，`windowLightStatusBar/NavigationBar` 已设置。

### 主界面

| 元素 | 改造 |
|---|---|
| 底部导航 | 自绘 NavItem → `NavigationBarView` + `menu/nav_main.xml`，M3 胶囊指示器 |
| 卡片 | 自绘 GradientDrawable → `MaterialCardView`（Outlined 风格） |
| 按钮 | framework Button → `MaterialButton`（Filled / Tonal），去掉自绘缩放回弹，保留 ripple |
| 映射模式开关 | 自绘 `ToggleSwitch` → `MaterialSwitch` |
| 提示 | 自绘浮层 → `Snackbar`，锚定底部导航 |
| 弹窗 | `MaterialDialog` 卡片改为 `MaterialCardView`，文字按钮改为 `MaterialButton` |
| 字阶 | eyebrow 11sp/0.18 字距、页面标题 26sp、区块标题 15sp、正文 13–14sp |

### 宏编辑器

| 元素 | 改造 |
|---|---|
| 顶部 | 新增 `MaterialToolbar`（返回箭头 + 配置名 + 副标题） |
| Tab | 自绘 TextView 行 → `TabLayout`（M3 指示器，5 个固定 tab） |
| 卡片 | `MaterialCardView` |
| 开关 | `ToggleSwitch` → `MaterialSwitch`（轴反向 / 交换 / 体感开关） |
| 滑杆 | `SeekBar` → `Slider`（连发 1–20Hz、振动 0–100%、体感死区/灵敏度） |
| 分段 | 自绘胶囊 → `MaterialButtonToggleGroup`（体感触发方式） |
| 按键 | 自绘 TextView → `Chip`（映射键、体感触发键、体感模式） |
| 底部 | 取消/应用 → Outlined / Filled `MaterialButton` |

### 保留的自绘部件

`BatteryView`（电池）、`DualRangeBar`（摇杆起点/终点范围条）、
`CalibRingView` / `StickCalibView`（校准可视化）、`KeyTestActivity` 的
511×730 手柄按键图、`ManualActivity` 的 PDF 渲染与自绘返回箭头。
这些是业务可视化或全屏画布，Material 没有等价组件，保持 Canvas 绘制，
配色已与 M3 主题对齐。

## 3. 版本改动记录

### 3.1 2.9.3 本版改动

| 位置 | 改动 |
| --- | --- |
| 取消配对 | 旧实现只对 GATT（BLE）句柄 `removeBond`，系统只忘掉 `GameMacro`，仍保留 `Pocket Controller`。现按「同 MAC 的 BREDR/LE 句柄 + 同系列名称」收集全部配对项，逐个断开并解绑，1.2 s 后复查补刀 |
| 竖屏 | 四个 Activity 增加 `android:screenOrientation="portrait"`，不再响应横屏 |
| 按键测试 | 摇杆行程由 `rO-rI-4` 改为 `rO-rI`，0/255 两端做收敛，推到最大时帽沿正好贴住外圈 |
| 开源 | 新增 `LICENSE`（MIT）、`CONTRIBUTING.md`、`.gitignore`；README 增加「与原版 APP 的区别」与开源声明 |

### 3.2 2.9.2 摇杆曲线 / 映射 / 振动

| 位置 | 改动 |
| --- | --- |
| 摇杆页 | 新增「摇杆曲线」卡片：左/右摇杆切换、默认/快速/缓慢预设、10×10 网格、两个可拖拽控制点，坐标实时显示。默认 (33,34)(66,67)、快速 (20,66)(33,80)、缓慢 (66,20)(80,33)，与设备侧的方法编号 13/14 对应 |
| 曲线协议 | 新增 `Kr.setRockerCurve(method, pts)`：`0x3F` + content `[method, count, count, x0,y0,x1,y1]`，method 13/14 = 左/右；只有用户改过曲线才写入 |
| 按键映射 | 修复「点箭头后的目标键没反应」：Material `Chip` 会自己消费触摸，事件不再冒泡到整行，现把点击挂到两个 Chip 上；目标键候选改为「设备 0x86/sub=2 上报 + 标准按键」，保证 `-` / `+` 可选，例如 B → − |
| 振动页 | 滑杆改为原版四档星级（0/25/50/75/100），`−` / `+` 与星标都可调；每次调整发送 `0x45` 试振，content 为 `[左,右,3]`，强度 = 百分比 ×2.55 |
| 体感页 | 「输出模式」改为原版叫法「体感映射」 |

回归测试新增：`mappingCellOpensPicker` 用真实 `MotionEvent` 点在目标键上，断言选择器弹出；`macroAllTabsBuild` 逐个构建五个 Tab。

## 4. 2.9.1 修复：启动闪退

2.9 首次发布版本一启动就闪退，原因在底部导航布局：

```xml
<!-- 错误：NavigationBarView 是 abstract class，LayoutInflater 无法实例化 -->
<com.google.android.material.navigation.NavigationBarView ... />
```

`LayoutInflater` 对抽象类调用构造函数会抛 `InflateException`，而这段充气发生在
`setContentView()` 之前，所以进程在显示第一帧前就退出。已改为具体子类：

```xml
<com.google.android.material.bottomnavigation.BottomNavigationView ... />
```

同时把导航栏高度固定为 `78dp`（原先 `wrap_content` 在部分机型上会把文字标签压掉）。

**验证方式**：工程内含 Robolectric 冒烟测试 `app/src/test/.../SmokeTest.java`，
在 JVM 上真实执行四个 Activity 的 `onCreate` 与布局充气：

```bash
./gradlew :app:testDebugUnitTest --tests "com.GameMacro.mini.SmokeTest"
```

- 修复前（根节点为 `NavigationBarView`）：`mainActivityStarts` 抛 `InflateException`；
- 修复后：4 个用例全部通过（0 失败）。

运行该测试需要联网下载 Robolectric 依赖；只编 APK 不需要。

## 5. 构建

需要 JDK 17 + Android SDK（platform 34、build-tools 34.0.0），首次构建需联网下载
Material/AppCompat 依赖。先写 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

然后：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

沿用交付 APK 的证书：

```bash
./gradlew assembleRelease \
  -PGMMINI_STORE_FILE=/path/to/debug.keystore \
  -PGMMINI_STORE_PASSWORD=android \
  -PGMMINI_KEY_ALIAS=androiddebugkey \
  -PGMMINI_KEY_PASSWORD=android
```

## 6. 构建校验结果

`./gradlew clean assembleRelease` 通过；APK 元数据：

```
package: name='com.GameMacro.mini' versionCode='33' versionName='3.0'
application-label:'GM Mini'
launchable-activity: name='com.GameMacro.mini.MainActivity'
Signer #1 certificate SHA-256: f504f6b95db46f6c28847dfcfcd81d0523baa4f7277334c465784e0780c91bdc
```

## 7. 目录结构

```
.
├── app/
│   ├── build.gradle                        Material/AppCompat 依赖、版本、签名
│   └── src/main/
│       ├── AndroidManifest.xml             蓝牙权限、四个 Activity
│       ├── assets/pads/                    T50 产品图
│       ├── assets/manual/                  T50 说明书 PDF
│       ├── res/values/                     Material 3 主题、配色、字符串
│       ├── res/layout/view_bottom_nav.xml  BottomNavigationView 容器
│       ├── res/menu/nav_main.xml           底部导航菜单
│       ├── res/color/gm_nav_tint.xml       导航图标/文字选色
│       ├── res/drawable/                   矢量图标（含 Material 返回箭头）
│       └── java/com/GameMacro/mini/
│           ├── MainActivity.java           设备 / 宏配置 / 关于 + 底部导航
│           ├── MacroEditorActivity.java    宏编辑器（Toolbar + TabLayout + Slider + Chip）
│           ├── KeyTestActivity.java        按键测试（自绘 511×730）
│           ├── ManualActivity.java         说明书 PDF 阅读
│           ├── BleClient.java              GATT、扫描、自动接管、取消配对
│           ├── Kr.java                     Kr 协议编解码、多帧重组
│           ├── MaterialDialog.java         M3 弹窗（MaterialCardView + MaterialButton）
│           ├── PadImages.java              型号表 + 产品图解码
│           ├── BatteryView.java            自绘电池
│           ├── CalibRingView.java          校准环
│           ├── StickCalibView.java         摇杆校准可视化
│           ├── DualRangeBar.java           双端范围条
│           ├── CurveView.java              摇杆曲线（网格 + 可拖拽双点）
│           ├── KeyCodes.java               键码表
│           └── Holder.java                 BLE 回调引用
├── gradle/wrapper/
├── gradlew / gradlew.bat
├── build.gradle / settings.gradle / gradle.properties
├── README.md
├── CONTRIBUTING.md
├── LICENSE（MIT）
└── DELIVERY_NOTES.md
```

## 8. 已知限制 / 未验证项

- 沙箱内没有安卓设备与手柄，本版只验证了编译、资源链接、APK 元数据与签名；
  界面在真机上的实际排版（尤其 5 个 Tab 的窄屏宽度、Chip 换行、Toolbar 副标题）
  与所有蓝牙交互仍需真机确认。
- Material 1.9.0 的 `Theme.Material3` 要求 AppCompat 主题；四个 Activity 均已改为
  `AppCompatActivity`，旧的自绘 `ToggleSwitch` 已删除。
- 首次构建必须联网；后续可加 `--offline`（依赖已缓存）。
- `com.GameMacro.mini` 含大写字母，可正常安装，但上架 Google Play 需改为全小写。
- 取消配对依赖 `BluetoothDevice.removeBond()`（@hide，反射调用）。Android 13+ 对
  隐藏 API 有白名单限制，个别 ROM 可能只解绑当前 transport；代码已对同 MAC 的
  BREDR/LE 句柄和 Pocket Controller 名称各下发一次，并在 1.2 s 后复查。若系统仍
  保留配对，App 会退回打开系统蓝牙设备详情页。
- 摇杆「触顶」只修正了绘制行程；真机若仍到不了 255，需要 `adb logcat` 确认固件
  上报的最大值（`← [07]` 帧的 byte 2–5）。
