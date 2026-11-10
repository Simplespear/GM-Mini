# 协议参考

下面的帧格式和字段偏移来自对 T50 与手机之间蓝牙通信的观察，用来实现互操作。不同固件可能有差异，遇到对不上的包，先抓一份完整日志再改代码。

## 帧格式

请求帧（SN 在帧尾，不是 index 2）：

```
[len = content.length + 3][cmdId][content...][SN]
```

SN 是全局自增字节，`0x00 → 0xFF → 回绕`。

单帧响应：

```
[len][cmdId][SN][data...]
```

多帧响应（`containSub`）：

```
[len][cmdId][subId][total][index][payload...][SN]
```

`index` 从 1 到 `total`，payload 为 `index 5 .. len-2`。最后一帧 `index == total` 时拼接完成。

## 通知分发

收到 FF02 通知后按顺序处理：

1. `cmdId == 0x07` 是主动上报，`data[0]` 才是实际命令字
2. 已知多帧命令（`0x77` 宏信息、`0x79` 宏子配置、`0x86` 支持类）按多帧重组
3. 其余按单帧解析

## 命令字

| ID | 名称 | content | 说明 |
| --- | --- | --- | --- |
| `0x01` | SetKeyBoardMode | `[mode]` | 1 = 蓝牙模式 |
| `0x31` | QueryElectricQuantity | 空 | 电量，响应 `[show][charging][chargeShow][elec][low]` |
| `0x46` / `0x47` | RecordMacroStart / Stop | 空 / `[0x01]` | 宏录制 |
| `0x50` | QueryMappingKey | `[page]` | 映射键，响应为三元组 `(type, original, mapping)` |
| `0x77` | QueryMacroInfo / ConfigInfo / Handle | `[subId, idx]` | 摇杆 / 扳机 / 振动 / 体感（多帧） |
| `0x79` | QueryMacroSub | `[0x01, idx]` | 宏列表（多帧） |
| `0x84` | QueryHandleInfo | `[subId, 0x00]` | 手柄信息，subId 用 `0x01` |
| `0x86` | 支持类查询 | `[subtype, param]` | subtype：`0x03` 宏 / `0x02` 映射 / `0x05` 录制 / `0x01` 爆发 |
| `0x88` | 心跳 | — | 固定 `04 88 01 02`，每秒一次，SN 不自增 |
| `0x90` | QueryRecordKey | `[0x02, idx]` | 可录制按键 |

## 关键偏移

### 手柄信息（0x84）

把整帧转成大写 hex 串后按字符下标取：

| 字段 | hex 下标 |
| --- | --- |
| project 前缀 | `[8, 14)` |
| agreementVersion | `[14, 20)` |
| firmwareVersion | `[20, 28)` |
| hardwareVersion | `[28, 34)` |

帧长超过 `0x12` 时，project 再拼 1 个字符：`帧[0x11]` 经 `mapTypeByteToChar` 映射（`0x0A → "A"`、`0x0B → "B"`）。

实测：发 `05 84 01 00 01` → 收 `12 84 01 56 06 15 03 05 02 02 51 21 5A 01 10 A0 01`
→ `project=560615 agreement=030502 fw=0251215A hw=0110A0`。

### 电量（0x31）

响应 `08 31 01 00 01 63 00 00`：`f[2]` showElectricity、`f[3]` charging、`f[4]` chargingShowElectricity、`f[5]` electricity、`f[6]` lowElectricity。

### 宏列表（0x79）

多帧合并后的 payload：

```
每条宏: [value][cycle][cycleTime:4B BE][childCount]
子事件: [index][keepTime:2B BE][intervalTime:2B BE][keyCount][keys...]
```

### 宏信息（0x77）

按 ConfigInfo 变体的偏移（发 `[0x01, idx]` 时命中）：

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

连接流程：`connectGatt(TRANSPORT_LE)` → `discoverServices` → 取 FF01/FF02 → `setCharacteristicNotification` + 写 CCCD → 订阅成功后启动每秒心跳。

同一个接收器有两条通道：LE 通道名为 `GameMacro`（数据通道，本 App 用这条），BR/EDR 通道名为 `Pocket Controller`（键盘鼠标通道）。
