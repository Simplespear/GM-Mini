# GM Mini

一个**独立实现**的 Android 蓝牙手柄工具,用于查看 **GameMacro / Pocket Controller** 系列手柄的电量、手柄信息与宏配置。

它不是对原厂 APP(com.host4.platform)的修改版,而是通过逆向其 BLE 私有协议 **从零重写** 的最小客户端:15 个 Java 文件 + 一个 manifest(约 7500 行),界面基于 AndroidX AppCompat + Material Components 1.9.0 构建。

> **开源声明**
>
> 本项目以 **MIT License** 开源。任何人都可以自由使用、复制、修改、合并、发布、再授权或销售本项目的代码，唯一要求是保留版权声明与许可证文本。欢迎 Fork、提 Issue 和 Pull Request。
>
> 本项目是**第三方独立实现**，与 GameMacro 官方（com.host4.platform）及其开发商没有任何隶属、授权或背书关系。仓库内不包含官方 APK，也不包含官方反编译代码；所有界面均为重新绘制，协议字段来自公开的蓝牙抓包与互操作性分析。
>
> **第三方素材声明**：`app/src/main/assets/manual/t50.pdf`（T50 说明书）与 `app/src/main/assets/pads/t50.png`（产品图）仅作为设备配套资料内置，版权归原权利人所有，**不在 MIT 许可范围内**。权利人如不希望被分发，提出后会立即删除。

## 与原版 APP 的区别

| 维度 | 原版 GameMacro | 本项目 GM Mini |
| --- | --- | --- |
| 定位 | 官方商业 APP，覆盖全系手柄、云宏、OTA、社区 | 个人开源项目，只服务 T50 / Pocket Controller 一只柄 |
| 代码 | 闭源，混淆 + 加固 | 全部 Java 源码公开，MIT 许可，可任意修改 |
| 体积 | 安装包较大，含多套皮肤与在线资源 | 约 7 MB，界面全部代码绘制，无网络依赖 |
| 联网 | 启动拉设备型号表、账号/云服务 | 完全离线，不申请网络权限，不上传任何数据 |
| 账号/广告 | 有账号体系与运营入口 | 没有账号、没有广告、没有统计 SDK |
| 界面 | 官方蓝紫风格，横竖屏两套布局 | Material 3 单套竖屏界面，主色 `#4A5BD8` |
| 功能范围 | 全量功能 | 只保留电量、手柄信息、连发、映射、摇杆、振动、体感、宏编辑、按键测试、校准、恢复出厂 |
| 协议 | 官方私有协议 | 同一套 Kr 协议，按官方行为重写；新增/修复项见下方版本记录 |
| 分发 | 应用商店 | GitHub 源码 + Release APK，可自行编译签名 |

**不包含**：账号登录、云宏市场、固件 OTA、社区/教程、多语言、平板/横屏布局。这些不是本项目的目标。

## 构建信息

- 包名 `com.GameMacro.mini`（2.8 起；此前的 `com.gmmini` 为旧包名）
- versionName `2.9.3` / versionCode `32`
- minSdk 26 / targetSdk 33 / compileSdk 34
- Gradle 工程（AGP 8.1.4 + Gradle 8.2），`./gradlew assembleRelease` 直接出包
- 代码约 7500 行；APK 约 7.1 MB（含 Material 库与 2.9 MB 内置说明书 PDF）

## 功能

| 功能 | 说明 |
| --- | --- |
| 扫描 / 已配对列表 | 列出全部 BLE 设备并标记「手柄」，已配对设备单独分组；手环/音箱不会被误隐藏，「重新扫描」实时补入 |
| 连接 | 原生 `BluetoothGatt` 连 LE 通道,自动发现 FF00 服务、订阅 FF02 通知、每秒心跳 |
| 电量 | 连接后自动查询,顶部大字显示;之后每 30 秒静默刷新。支持充电中 / 低电量标记 |
| 手柄信息 | project / 协议版本 / 固件版本 / 硬件版本 |
| 宏能力 | 设备是否支持宏、可用按键码列表 |
| 宏列表 | 解析宏配置(触发键码、循环、循环时长、每个子事件的保持时间/间隔/按键序列) |
| 宏信息 | 扳机行程、摇杆曲线与轴范围、振动强度、体感灵敏度 |
| 宏编辑 | 连发 1–20 Hz(A/B/X/Y/L/R/ZL/ZR)、按键映射、摇杆轴反向与交换、左右振动、体感(触发键 / 方式 / 死区 / 灵敏度 / 体感映射) |
| 设备管理 | 自动接管系统已连接的手柄、设备列表内重新扫描、取消配对（同时清理 GameMacro 与 Pocket Controller 两个系统配对身份） |
| 映射键 / 录制 | 查询映射键、开始/停止宏录制 |

界面以代码构建为主,主题、按钮、卡片、开关、滑杆、Tab、底部导航、Chip、Snackbar 全部使用 Material Components;电池、摇杆范围条、校准环、按键测试图等业务可视化仍为自绘 Canvas。运行日志只写 logcat(tag `GMMini`)。

## 协议速查

### 帧格式

请求帧(**注意 SN 在帧尾,不是 index 2**):

```
[len = content.length + 3][cmdId][content...][SN]
```

SN 是全局自增字节(`0x00 → 0xFF → 回绕`)。

响应帧(单帧):

```
[len][cmdId][SN][data...]
```

响应帧(多帧,`containSub`):

```
[len][cmdId][subId][total][index][payload...][SN]
```

`index` 从 1 到 `total`,payload 为 `index 5 .. len-2`。最后一帧 `index == total` 时拼接完成。

### 通知分发

收到 FF02 通知后按顺序处理:

1. `cmdId == 0x07` → 主动上报(例如电量推送,`data[0]` 为实际命令字)
2. 已知多帧命令(`0x77` 宏信息 / `0x79` 宏子配置 / `0x86` 支持类)按多帧重组
3. 其余按单帧解析

### 命令字

| ID | 名称 | content | 说明 |
| --- | --- | --- | --- |
| `0x01` | SetKeyBoardMode | `[mode]` | 1 = 蓝牙模式 |
| `0x31` | QueryElectricQuantity | 空 | 电量,响应 `[show][charging][chargeShow][elec][low]` |
| `0x46` / `0x47` | RecordMacroStart / Stop | 空 / `[0x01]` | 宏录制 |
| `0x50` | QueryMappingKey | `[page]` | 映射键,响应为三元组 `(type, original, mapping)` |
| `0x77` | QueryMacroInfo / ConfigInfo / Handle | `[subId, idx]` | 摇杆/扳机/振动/体感(多帧) |
| `0x79` | QueryMacroSub | `[0x01, idx]` | 宏列表(多帧) |
| `0x84` | QueryHandleInfo | `[subId, 0x00]` | 手柄信息,**subId 必须用 0x01** |
| `0x86` | 支持类查询 | `[subtype, param]` | subtype: `0x03` 宏 / `0x02` 映射 / `0x05` 录制 / `0x01` 爆发 |
| `0x88` | 心跳 | — | 固定 `04 88 01 02`,每秒一次,SN 不自增 |
| `0x90` | QueryRecordKey | `[0x02, idx]` | 可录制按键 |

### 关键偏移

**手柄信息(0x84)** — 把整帧转成大写 hex 串后按字符下标取:

| 字段 | hex 下标 |
| --- | --- |
| project 前缀 | `[8, 14)` |
| agreementVersion | `[14, 20)` |
| firmwareVersion | `[20, 28)` |
| hardwareVersion | `[28, 34)` |

若帧长 > 0x12,project 再拼 1 个字符:`帧[0x11]` 经 `mapTypeByteToChar` 映射(`0x0A → "A"`、`0x0B → "B"`)。

实测:发 `05 84 01 00 01` → 收 `12 84 01 56 06 15 03 05 02 02 51 21 5A 01 10 A0 01`
→ `project=560615 agreement=030502 fw=0251215A hw=0110A0`。

**电量(0x31)** — 响应 `08 31 01 00 01 63 00 00`:

`f[2]` showElectricity、`f[3]` charging、`f[4]` chargingShowElectricity、`f[5]` electricity、`f[6]` lowElectricity。

**宏列表(0x79)** — 多帧合并后的 payload:

```
每条宏: [value][cycle][cycleTime:4B BE][childCount]
子事件: [index][keepTime:2B BE][intervalTime:2B BE][keyCount][keys...]
```

**宏信息(0x77)** — 按 ConfigInfo 变体的偏移(发 `[0x01, idx]` 时命中):

```
hair 块: [0] = 块长, hairs = 块长/3, 每条 3 字节 (code, mode, speed)
扳机块: base = hair块长,  [base+1] = 块长
        左: +2 start, +3 termination, +4/+5 X轴起止, +6/+7 Y轴起止, +8 开关, +9 阈值
        右: +10..+17 同构
摇杆块: rb = base + 扳机块长, [rb+2] = 块长
        左 X +3/+4(轴 +5/+6, 交换 +7), Y +8/+9(轴 +10/+11, 交换 +12)
        右 X +13/+14, ...
振动块: vb = rb + 摇杆块长, [vb+3] = 块长, +4 左, +5 右
体感块: kb = vb + 振动块长, 灵敏度 = +5<<8 | +6, 其后 +7..+12 为各开关/方式/按键/死区
```

## BLE 参数

| 项 | UUID |
| --- | --- |
| Service | `0000FF00-0000-1000-8000-00805F9B34FB` |
| Write | `0000FF01-0000-1000-8000-00805F9B34FB` |
| Notify | `0000FF02-0000-1000-8000-00805F9B34FB` |
| CCCD | `00002902-0000-1000-8000-00805F9B34FB` |

连接流程:`connectGatt(TRANSPORT_LE)` → `discoverServices` → 取 FF01/FF02 → `setCharacteristicNotification` + 写 CCCD → 订阅成功后启动每秒心跳。

同一个物理接收器有两条通道:**LE 通道**名为 `GameMacro`(数据通道,本 APP 用这条),**BR/EDR 通道**名为 `Pocket Controller`(键盘/鼠标通道,原厂 APP 两个都要连)。

## 编译(Gradle)

需要 JDK 17、Android SDK(platform 34 + build-tools 34.0.0)。先在 `local.properties` 写:

```properties
sdk.dir=/path/to/Android/sdk
```

然后:

```bash
./gradlew assembleRelease        # Windows 用 gradlew.bat assembleRelease
# 产物:app/build/outputs/apk/release/app-release.apk
```

装机:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.GameMacro.mini android.permission.BLUETOOTH_SCAN
adb shell pm grant com.GameMacro.mini android.permission.BLUETOOTH_CONNECT
```

### 签名

默认走 AGP 自动生成的 `~/.android/debug.keystore`。如果那台机器写不了这个文件(受控文件夹访问等),可以传自己的 keystore 覆盖:

```bash
./gradlew assembleRelease \
  -PGMMINI_STORE_FILE=/path/to/your.jks \
  -PGMMINI_STORE_PASSWORD=store密码 \
  -PGMMINI_KEY_ALIAS=alias \
  -PGMMINI_KEY_PASSWORD=key密码
```

`release` 目前也用 debug 签名,方便直接装机测试;正式发布请换成自己的 `signingConfig`。

交付的 APK 使用构建机的 debug keystore(`alias androiddebugkey`,密码 `android`,
证书 SHA-256 `f504f6b9…`)。源码包**不附带** keystore;要沿用同一证书做后续升级,
请把自己备份的同证书 keystore 传进来:

```bash
./gradlew assembleRelease \
  -PGMMINI_STORE_FILE=/path/to/debug.keystore \
  -PGMMINI_STORE_PASSWORD=android \
  -PGMMINI_KEY_ALIAS=androiddebugkey \
  -PGMMINI_KEY_PASSWORD=android
```

### 不用 Gradle 的手工编译(备用)

```powershell
$env:JAVA_HOME='<jdk17>'
$BT='<build-tools>'           # 含 aapt2 / d8 / zipalign / apksigner
$AJ='<platform>/android.jar'
$LAM="$BT\core-lambda-stubs.jar"

# 1. 编译(必须 UTF-8;必须带 core-lambda-stubs,否则 lambda 报找不到 metafactory)
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ;$LAM" -nowarn -d build\classes (源文件...)

# 2. dex(输出目录必须已存在)
d8 --min-api 26 --lib $AJ --output build\dex (class 文件...)

# 3. 资源 + 清单
aapt2 link -o build\base.apk -I $AJ --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 33

# 4. 把 dex 放进 zip 根目录(不能带子目录)
copy build\base.apk build\unsigned.apk
copy build\dex\classes.dex build\classes.dex
cd build ; aapt add unsigned.apk classes.dex

# 5. 对齐 + 签名
zipalign -f -p 4 build\unsigned.apk build\aligned.apk
apksigner sign --ks <keystore> --ks-pass pass:<pwd> --v1-signing-enabled false `
    --v2-signing-enabled true --v3-signing-enabled true --out GMMini.apk build\aligned.apk
```

## 目录

```
settings.gradle / build.gradle / gradle.properties
gradlew / gradlew.bat / gradle/wrapper/
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/res/values/                       Material 3 主题与配色
app/src/main/res/layout/                       底部导航容器
app/src/main/res/menu/                         导航菜单
app/src/main/res/color/                        导航图标选色
app/src/main/res/drawable/                     13 个矢量图标(bluetooth / pad / gyro / edit …)
app/src/main/assets/pads/                      T50 产品图(t50.png)
app/src/main/assets/manual/                    T50 说明书(t50.pdf)
app/src/main/java/com/GameMacro/mini/
    Kr.java                   协议帧编解码 + 多帧重组
    BleClient.java            原生 GATT 客户端 + 设备过滤 + 心跳
    MainActivity.java         主界面:设备卡、手柄详情、宏配置、关于
    KeyTestActivity.java      按键测试(自绘 511×730 基准,等比缩放)
    MacroEditorActivity.java  宏编辑器(连发/映射/摇杆/振动/体感)
    ManualActivity.java       使用说明
    PadImages.java            型号表 + 产品原图采样解码 / 透明边裁剪
    MaterialDialog.java       全屏遮罩 + 居中卡片弹窗
    BatteryView.java          自绘电池图标
    CalibRingView.java        摇杆校准环形指示
    StickCalibView.java       摇杆校准可视化
    DualRangeBar.java         双端范围条
    CurveView.java            摇杆响应曲线（网格 + 可拖拽双控制点）
    KeyCodes.java             按键码 → 名称/位掩码
    Holder.java               BLE 回调用的可变引用
```

## 已知限制

- 宏写入按官方 `set*` 串行链实现,不同固件的 ACK 时机可能不同,超时 900ms 后自动继续下一条。
- 宏配置文件的名字存在原厂 APP 本地数据库里,设备不提供,所以这里显示为「宏 #1/#2…」。
- 扫码列表若过滤后为空,会退回显示全部已配对设备,避免用户找不到自己的手柄。
- 部分手柄的 LE 通道不广播,此时用「已配对」列表直接连接更可靠。

## v1.3 界面调整（基于 1.1）

- 顶部状态卡显示连接状态，并按状态着色：绿=已连接、蓝=扫描中、红=断开/失败。
- 电池电量改为大号、整行居中展示。
- 移除「已配对」「停止扫描」按钮，底部只保留「扫描设备 / 设备列表 / 断开连接」三个按钮。
- 「附近设备」「已配对设备」两个列表合并为一个「设备列表」按钮（位于扫描与断开之间），点击弹出气泡菜单，分组显示附近设备与已配对设备，点选即连接。
- 修复部分手柄扫不到的问题：`BleClient.startScan()` 会先读取已配对设备名并缓存，扫描结果用该名字兜底识别（这类手柄的 BLE 广播包不带名称）。

---

## v1.6 变更（离线可用 + 宏写入修复）

### 1. 彻底离线识别手柄（不依赖 gamemacro.cn）

官方 App 每次启动都要请求 `produce/app/keyword/list` 拉扫描关键字，断网就扫不到任何设备。
1.6 把这张表**内置进 APK**（`BleClient.OFFICIAL_BT_NAMES`，18 条官方 btName），并加强识别：

| 层级 | 判据 |
|---|---|
| 1 | 广播里带 FF00 服务 UUID（少数固件） |
| 2 | 名称命中内置官方表（GameMacro / Mobapad / CRKD / Coiorvis / IINE / QMacro …） |
| 3 | 名称命中模糊关键字（gamepad / controller / 手柄 / t50 …） |
| 4 | **已配对过的设备直接放行**（配过对就是要用的） |
| 5 | 以上都没有？设备列表里打开「显示所有蓝牙设备」强制列出 |

另外修复取名逻辑：1.5 只用 `BluetoothDevice.getName()`，未配对/未缓存时返回 null，
大量手柄因此被丢弃。1.6 改为三级兜底：
`getName()` → `ScanRecord.getDeviceName()`（广播 Local Name）→ 已配对缓存名。

### 2. 连发/宏写不进去的根因修复（键码表错误）

1.5 的 `KeyCodes` 是**猜的**（0x01~0x14），固件实际用的是：

```
A=0xA0  B=0xA1  X=0xA2  Y=0xA3  L1=0xA4  L2=0xA5  L3=0xA6
R1=0xA7 R2=0xA8 R3=0xA9  -=0xAA   +=0xAB   i/turbo=0xAE
十字: 中D0 左D1 右D2 上D3 下D4 左上D5 右上D6 左下D7 右下D8
左摇杆: E0~E8 (同方位)     右摇杆: F0~F8 (同方位)
背键: M1=0x01  M2=0x02
```

所以 0x37（连发写入）带的键码设备根本不认，直接丢弃 →
`0x77` 回读连发表长度永远是 0。1.6 换成上面的权威表后写入才真正落到设备。

键码来源（可复现，用于其它型号）：
```
GET https://gamemacro.cn/produce/app/light/detail?project=560615&type=3
```
`project` 取自手柄信息帧（T50 = 560615）。

### 3. 顺带修掉的 bug

| # | 问题 | 修复 |
|---|---|---|
| 1 | `BleClient.request()` 超时判断写成 `now-pendingAt >= timeout-5`，回调稍早调度就永不成立，pendingId 卡死 | 到点即判超时，并加 token 防止旧超时误杀新请求 |
| 2 | `write()` 整帧直写，超过 20 字节被底层截断 | 按官方规则 20 字节分包，40ms 间隔串行 |
| 3 | 断开时只改状态不 `gatt.close()`，重复连接累积句柄触发 status=133 | 断开即回收 GATT |
| 4 | `hardClose()` 后立刻 `connectGatt`，部分机型返回 133 | 延时 250ms 建连 |
| 5 | 扫描永不停止，耗电且与后续连接抢资源 | 15 秒自动停 |
| 6 | `0x77` 解析右扳机/右摇杆基址多偏 1 字节 | 对齐官方 `QueryMacroHandleReq` 的 `L+6` / `rb+25` |
| 7 | 宏支持列表显示十进制键码 | 显示键名 |

### 4. 体积说明

本工程为纯 framework 实现，**零第三方依赖**，Release APK 约 69 KB。
不含云游戏 / 社区 / 串流 / RTC / 广告 SDK——那些是官方 GameMacro 的功能（官方包 77 MB）。

### 5. 签名提示

本次构建用的是沙箱的 `~/.android/debug.keystore`（SHA-256 `f504f6b9…`），
与 1.5 的 `mod.jks`（`6fcaecb3…`）**不同**，安装前需先卸载旧版，或用你的 mod.jks 重签：

```powershell
.\gradlew assembleRelease "-PGMMINI_STORE_FILE=E:\dafeiyu\work\mod.jks"
```

---

## v1.7 界面重构（布局对齐官方设备卡）

### 1. 连接后才显示设备卡

- 顶部新增设备卡：**手柄图片 + 型号名 + 连接状态 + 电池图形与百分比**，布局参照官方 App。
- **未连接时整块隐藏**：不显示任何图片和电量，只剩「扫描设备 / 设备列表 / 断开连接」三个按钮。
- 电池用自绘 `BatteryView`：外框 + 电量条，颜色随电量变化（≥50 绿、≥20 橙、<20 红），充电时外框变蓝并显示「充电中」标签。
- 型号名：优先用蓝牙名；拿到手柄信息帧后，`project=560615` 会显示为 **T50**。

### 2. 设备列表改为锚定气泡

- 原来的 `PopupMenu` 在部分 ROM 上会跑到屏幕中间。
- 改为自绘 `PopupWindow` 气泡：**锚定「设备列表」按钮**，水平居中对齐按钮，下方空间不足自动翻到上方。
- 样式与主界面卡片完全一致：白底 / 22dp 圆角 / 1dp `#EBEDF5` 描边 / 12dp 阴影。
- 条目为两行：设备名 + MAC；分组标题「附近设备」「已配对设备」。
- 取消了「显示所有蓝牙设备」点击后的 Toast 小弹窗，改为条目右侧 ✓ 勾选状态，日志里留一行说明。

### 3. 自动接管系统已连接的手柄

以前必须手动到设备列表里点一次 GameMacro 才能看到电量，原因是：
手柄在系统蓝牙里已被连成 HID，但本 App 的 GATT 客户端不会自动挂上去。

1.7 加了两条自动路径：

| 路径 | 说明 |
|---|---|
| 启动/回前台探测 | `BluetoothManager.getConnectedDevices(GATT / HID_HOST)` 里找手柄，命中就直接接管 |
| ACL 连接广播 | 监听 `ACTION_ACL_CONNECTED`，手柄被系统连上时立刻接管（不必等用户操作） |

### 4. 「断开连接」真正断开系统连接

以前 `close()` 只关掉本 App 的 GATT，系统那条 HID 连接还在（手柄灯不灭、仍能操控）。
现在按顺序执行：

1. `gatt.disconnect()` + `gatt.close()` —— 释放本 App 的 GATT 客户端
2. 反射 `BluetoothDevice.disconnect()` —— 一次断开该设备全部 profile
3. `HID_HOST` profile proxy 的 `disconnect()` —— 系统隐藏接口补刀

断开后本轮不再自动接管；再点「扫描设备」或手动选择设备即可重新连接。

---

## v1.8 界面重构（Material 3 + 底部导航）

### 1. 扫描默认列出全部设备

「显示所有蓝牙设备」开关已删除。`BleClient` 的扫描回调不再做任何名称 / UUID 过滤，
所有广播到的 BLE 设备都会进入列表（`ScanRecord` 拿不到名字时显示「未命名设备 + MAC」）。
是否手柄交给用户判断：设备列表里命中官方 btName 或模糊关键字的条目会加 `★` 前缀，
并排在「附近设备」分组的最前面。

### 2. 设备列表气泡：背景虚化

`BubbleMenu` 改为全屏 PopupWindow：

| 层 | 实现 |
|---|---|
| 遮罩 | 全屏半透明 View（`#580A0C14`），点击即关闭 |
| 模糊 | Android 12+ 对 Activity 的 DecorView 施加 `RenderEffect` 高斯模糊（26px）；低版本仅靠遮罩变暗 |
| 气泡 | 锚定「设备列表」按钮，水平居中对齐，下方放不下自动翻到上方；边缘自动收进屏幕 |

气泡本体为 Material 3 卡片：24dp 圆角、白色 surface、无描边、elevation 阴影、行内涟漪。

### 3. 删除「断开连接」

系统侧的手柄连接是 HID profile，App 通过反射断开在多数 ROM 上会被 non-SDK 限制拦截，
保留这个按钮只会让用户误以为已经断开。按钮已移除；需要断开时请在系统蓝牙设置里操作。

### 4. Material 3 视觉

不引入 Material Components 库（保持零依赖、APK < 100 KB），全部用 framework 自绘：

- 主色 `#4A5BD8`、容器色 `#E0E4FF`、surface `#F6F5FB`、outline `#E8E6F0`
- 卡片：24dp 圆角 + 1dp outline + elevation 1
- 主操作：Filled Button（胶囊、主色填充、白字、涟漪）
- 次操作：Tonal Button（容器色填充、深蓝字）
- 工具区：Tonal 芯片（14dp 圆角、12sp）
- 标题层级：eyebrow 10sp 加宽字距 / 页面标题 30sp / 区块标题 16sp / 说明 12sp

### 5. 底部导航 + 关于页

- 底部 NavigationBar 两个条目：**设备 / 关于**，选中项有 58×32 的胶囊指示器（容器色）与主色图标文字。
- 当前默认进入「设备」页。
- 「关于」页包含：品牌与版本卡、能力说明（离线识别 / Kr 协议 / 宏编辑器 / 轻量）、
  兼容性（型号 / 通信方式 / 系统要求 / 权限）、声明（第三方工具 / 数据留存 / 风险提示）。

### 6. 修复电量恒为 1%

官方 `ElectricEventRsp.setData` 的字段定义：

```
[0]=Len  [1]=ID(0x31)  [2]=showElectricity(是否显示电量, 0/1)
[3]=charging  [4]=chargingShowElectricity  [5]=electricity(真实电量)  [6]=lowElectricity
```

1.7 及以前写成 `pct = show > 0 ? show : elec`，把 [2] 这个 0/1 开关当成电量 → 永远显示 1%。
1.8 改为取 [5]，并在其为 0/超出 100 时回退到 [4]。

同时修正主动上报的偏移：`[len][0x07][0x31][show][chg][chgShow][elec][low]`
的字段从下标 3 开始，而普通响应 `[len][0x31][show]…` 从下标 2 开始，
`showBattery(frame, base)` 现在按来源传入不同的 base。

## v2.5 当前形态（在售版本）

> 上面 v1.3 – v1.8 的章节保留为历史记录，描述的界面已不再存在。当前版本以本节为准。

### 页面结构

底部三个条目：**设备 / 宏配置 / 关于**。

| 页 | 内容 |
| --- | --- |
| 设备 | GMMINI 20sp 字标 + 「设备」16sp 标题；已连接时显示设备卡（产品原图 + 型号 + 「已连接 · 电池 · 电量」）；下方「手柄详情」五项：按键测试、陀螺仪校准、摇杆校准、固件版本、恢复出厂模式 |
| 宏配置 | 原「宏设置」迁移至此：宏列表、宏信息、扳机 / 摇杆 / 振动 / 体感、映射键、录制 |
| 关于 | 版本卡（当前 2.5）+「支持的手柄」：T50 大产品图 + 型号 + 「产品说明书」外链 |

### 按键测试

- 进页前先发 `0x01`（SetKeyBoardModeReq）把固件切到 BETA 工作模式，`0x07` 才有私有帧上来；
  因此 B / Home 不再走系统 HID，返回键与主页键被屏蔽，但按键图标照常点亮。
- 依次尝试 `{194,196} / {130,132} / {66,68} / {2,4}` 四组模式，收到私有帧即锁定，退出时切回宏模式。
- 自绘 511×730 基准、整体等比缩放；左摇杆与右摇杆统一为同心圆样式。

### 校准

两个校准都按官方流程：点一下就发开始帧，弹窗里没有「下一步」，由手柄上报结果结束。

| 项 | 开始 | 结果上报 | 取消 / 超时 |
| --- | --- | --- | --- |
| 陀螺仪校准 | `0x41 [1,0]`，弹 60 秒环形倒计时 | `0x43 / subId=1` | 发 `0x42 [1,0]`，超时提示「校准超时」 |
| 摇杆校准 | `0x55 [2,0]`，弹提示弹窗 | `0x57 / subId=2` | 发 `0x56 [2,0]`，超时同样处理 |

- 结果帧按官方 `DeviceAlignRsp` 解析：`f[4]==1` 为成功；失败时 `f[5]` 低三位分别是「摇杆未打满」「摇杆未回中」「摇杆未转动数圈」，陀螺仪失败显示「手柄未保持静止。」
- 摇杆校准的 subId `2` 是摇杆整体、`3` 是扳机，不是左右摇杆。官方 App 在摇杆校准里不发送 `0x57` 完成帧，这里也不发。
- 弹窗沿用白色卡片、居中、右上角 ×；× 和系统返回键都只发结束帧取消，并屏蔽手柄确认键以免误触中断。
- 60 秒只是兜底，收到上报会提前收尾，不会一直转满。

### 产品图

- `assets/pads/` 内是官方设备库（OSS `avatar` 字段）下载的**原始文件**，未压缩；
  目前只保留 T50（500×499），其余型号按 project 号匹配不到图时不显示图标。
- `assets/manual/t50.pdf` 是内置的 T50 说明书（2.9 MB），由 `ManualActivity` 读取渲染。
- `PadImages` 按控件像素做 `inSampleSize` 采样解码 + 12MB `LruCache`，不会把大图整张读进内存。
- `trim=true` 时先裁掉画布四周透明像素再显示（T50 的透明留白占了画布近一半），
  只裁不缩放，用于主页设备卡与关于页大图。
- 识别顺序：`0x84` 的 project 号 → 蓝牙名关键字 → 都识别不到则**不显示任何图标**。

### 设备列表

- 只显示蓝牙名称与信号强度，不加 `★` 前缀、不翻译型号、不显示产品图。
- 当前设备那一行额外带真实电量（`0x07` 电量帧的 `elec`，与主页同一份数据）。

---

## v2.9 界面重构（Material Components）

2.9 起不再坚持「零依赖自绘」，改为引入 `com.google.android.material:material:1.9.0`
与 `androidx.appcompat:appcompat:1.6.1`，用 Material 3 组件重绘整个界面。

| 位置 | 改动 |
| --- | --- |
| 主题 | `Theme.Material3.Light.NoActionBar` + 完整 M3 色板（主色仍为 `#4A5BD8`） |
| 底部导航 | `BottomNavigationView` + menu，M3 胶囊指示器，替代自绘 NavItem |
| 卡片 | `MaterialCardView`（24dp 圆角 / 1dp outline / 0dp elevation） |
| 按钮 | `MaterialButton`（Filled / Tonal / Outlined），去掉自绘缩放回弹 |
| 开关 | `MaterialSwitch` 替代自绘 `ToggleSwitch`（后者已删除） |
| 滑块 | `Slider` 替代 `SeekBar`（连发 1–20Hz、振动、体感死区/灵敏度） |
| 宏编辑器 | `MaterialToolbar` + `TabLayout` + `MaterialButtonToggleGroup` + `Chip` |
| 提示 | `Snackbar` 替代自绘浮层 |
| 弹窗 | `MaterialCardView` + `MaterialButton`，保留背景模糊与出入场动画 |

保留自绘：`BatteryView`、`DualRangeBar`、`CalibRingView`、`StickCalibView`、
按键测试手柄图、PDF 阅读页——这些是业务可视化，Material 没有等价组件。

版本号：2.8 / 28 → **2.9 / 29**；包名不变。

### v2.9.1 修复启动闪退

2.9 的 `res/layout/view_bottom_nav.xml` 把根节点写成了
`com.google.android.material.navigation.NavigationBarView`。该类是 `abstract`，
`LayoutInflater` 无法实例化，`buildUi()` 在 `setContentView()` 之前就抛
`InflateException`，表现为点图标即闪退。已改为具体子类
`com.google.android.material.bottomnavigation.BottomNavigationView`，并把高度
固定为 `78dp`。

新增 `app/src/test/.../SmokeTest.java`（Robolectric），在 JVM 上真实执行四个
Activity 的 `onCreate`：修复前该用例抛 `InflateException`，修复后 4/4 通过。


---

### v2.9.2 摇杆曲线 / 映射 / 振动

| 位置 | 改动 |
| --- | --- |
| 摇杆页 | 新增「摇杆曲线」卡片：左/右摇杆切换、默认/快速/缓慢预设、10×10 网格、两个可拖动控制点，坐标实时显示；只在改动过曲线时按 `0x3F`（sub 13/14）写回 |
| 按键映射 | 修复点箭头后的目标键无反应：`Chip` 会自己消费触摸，现在把点击直接挂在 Chip 上；目标键列表 = 设备上报 + 标准按键（含 `-` / `+`），可把 B 映射成 `-` |
| 振动页 | 由滑杆改为原版四档星级（0/25/50/75/100），点 `−` / `+` 或星标时发送 `0x45` 试振（强度 ×2.55，content 补第 3 字节 3） |
| 体感页 | 「输出模式」改名为原版叫法「体感映射」 |

版本号：2.9.1 / 30 → **2.9.2 / 31**；包名不变。

### v2.9.3 取消配对 / 竖屏 / 摇杆触顶

| 位置 | 改动 |
| --- | --- |
| 取消配对 | 以前只对 GATT 句柄调用 `removeBond`，系统里只消失「GameMacro」，「Pocket Controller」仍留在已配对列表。现在按「同一 MAC 的经典/LE 句柄 + 同系列名称」收集全部配对项（GameMacro ↔ Pocket Controller ↔ QMacro / T50），逐个断开并 `removeBond`，1.2 秒后复查补刀 |
| 屏幕方向 | 四个 Activity 全部 `screenOrientation="portrait"`，不再跟随系统横屏；删除横屏适配需求 |
| 按键测试 | 摇杆行程由 `rO - rI - 4` 改为 `rO - rI`，并对 0/255 两端做收敛，推到最大时摇杆帽沿正好贴住外圈，不再留缝 |
| 版本 | 2.9.2 / 31 → **2.9.3 / 32** |

> 说明：「GameMacro」是 App 与手柄之间的 BLE 协议/广播名，「Pocket Controller」是同一只手柄在系统蓝牙里的经典 HID 名称。两者共用一套配对信息，取消配对时必须一起清掉。

