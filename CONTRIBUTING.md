# 参与贡献

GM Mini 是一个完全开源的项目，任何人不分身份都可以修改、分发或用于商业用途（MIT License）。下面是最短路径。

## 环境

- JDK 17
- Android SDK：platform 34 + build-tools 34.0.0
- 不需要 Android Studio，命令行即可构建

## 构建

```bash
git clone https://github.com/<your-name>/GM-Mini.git
cd GM-Mini
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

测试基于 Robolectric，在 JVM 上真实启动四个 Activity，并覆盖：

- 宏编辑器五个 Tab 的构建
- 映射目标键点击后必须弹出选择器
- 四个 Activity 必须锁定竖屏
- GameMacro / Pocket Controller 名称归一化

新增协议解析或 UI 交互时，请同时补一条能复现问题的用例。

## 代码约定

- 纯 Java，不引入 Kotlin；UI 以 Material 3 组件 + 代码构建为主
- 新增协议命令放进 `Kr.java`，不要散落在 Activity 里
- 设备读写统一走 `BleClient`，不要绕过请求队列直接 `writeCharacteristic`
- 注释写「为什么」，不要写「是什么」；引用官方行为时标注命令字
- 提交信息用中文或英文均可，建议 `fix: ...` / `feat: ...`

## 提交 PR

1. Fork 后从 `main` 拉分支
2. 保证 `./gradlew :app:testDebugUnitTest` 全绿
3. 说明改动动机、真机验证结果（机型 / Android 版本 / 手柄固件）
4. 不要提交 `local.properties`、`build/`、任何 `.jks` / `.keystore`

## 免责声明

本项目通过 Bluetooth 抓包分析手柄的私有协议，仅用于设备互操作与学习。修改手柄配置可能导致设备异常，风险由使用者自行承担。项目与 GameMacro 官方无任何关联。
