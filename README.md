# GM Mini

T50 手柄的非官方 Android 配置工具。不依赖原版 App，代码从零写起。

## 和原版 App 的区别

|  | 原版 App | GM Mini |
| --- | --- | --- |
| 来源 | 厂商发布 | 个人项目，源码公开 |
| 源码 | 不公开 | MIT 许可，可修改、可再分发、可商用 |
| 联网 | 有联网和账号功能 | 不申请网络权限，全程离线 |
| 账号 | 有账号体系 | 没有 |
| 屏幕 | 横竖屏两套布局 | 只做竖屏 |
| 功能范围 | 全系手柄、云宏、固件升级、社区 | 单手柄配置：电量、按键测试、连发、映射、摇杆曲线、振动、体感、宏编辑、校准、恢复出厂 |
| 分发 | 应用商店 | GitHub Release，也可自行编译签名 |

原版有、这里没有的：账号登录、云宏市场、固件 OTA、社区和教程、多语言、平板布局。没做，不是漏了。

支持的手柄只有一只：T50。它在系统蓝牙里会以两个名字出现，`GameMacro` 是数据通道，`Pocket Controller` 是键鼠通道，两个都配对才能用全部功能。

## 安装

到 [Releases](https://github.com/Simplespear/GM-Mini/releases) 下载 APK 直接安装，Android 8.0（API 26）以上。

自己编译看 [CONTRIBUTING.md](CONTRIBUTING.md)，协议和实现细节看 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 许可

代码是 MIT，见 [LICENSE](LICENSE)。

内置的 T50 说明书（`app/src/main/assets/manual/t50.pdf`）和产品图（`app/src/main/assets/pads/t50.png`）是厂商资料，版权归原权利人，不在 MIT 范围内。权利人如果不想被分发，告知后立刻删除。

本项目与手柄厂商及其关联公司没有隶属、授权或背书关系。文中出现的产品名称与商标归各自权利人所有，仅用于说明兼容性。
