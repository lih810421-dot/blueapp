# 蓝牙助手 (BlueApp)

基于 Android Jetpack Compose 开发的低功耗蓝牙（BLE）通信应用，用于连接并控制支持 BLE 协议的硬件设备。

## 📱 功能特性

### ✅ 已实现功能

#### 1. 蓝牙管理
- ✅ 蓝牙状态检测（自动检测蓝牙是否开启）
- ✅ 运行时权限请求（Android 12+ 适配）
- ✅ BLE 设备扫描（支持信号强度显示）
- ✅ 设备连接/断开
- ✅ 自动服务发现和特征值解析

#### 2. 数据通信
- ✅ **周期性数据获取**：每 300ms 自动发送数据请求指令 `7E 7F 50 FB FD`
- ✅ **传感器数据解析**：自动解析设备返回的传感器数据结构
  - 10路模拟量输入/输出（VoltA-E In/Out）
  - 频率信号输入
  - 压力值（kPa/Bar/PSI 三种单位显示）
  - 发动机转速（RPM）
  - 当前工作模式
- ✅ **实时数据展示**：
  - 仪表盘式转速显示（支持 0-15000 RPM）
  - 压力显示卡片（带历史趋势图）
  - 当前模式指示

#### 3. 设备控制

##### 工作模式切换
支持 5 种工作模式，点击即可切换：

| 模式 | 指令 | 说明 |
|-----|------|------|
| E | `7E 7F 00 FB FD` | 经济模式 |
| N | `7E 7F 01 FB FD` | 标准模式 |
| S | `7E 7F 02 FB FD` | 运动模式 |
| S+ | `7E 7F 03 FB FD` | 超级运动模式 |
| R | `7E 7F 04 FB FD` | 倒车模式 |

##### 转速校准
- ✅ 支持输入目标转速（0-9999 RPM）
- ✅ 快捷调节按钮（±100、±500）
- ✅ 动态范围限制（根据压力值自动调整允许的最大转速）
- ✅ 发送转速校准指令：`7E 7F 60 [RPM值4字节] FB FD`

#### 4. 用户界面
- ✅ 现代化 Material Design 3 设计
- ✅ 科技感渐变效果和玻璃态卡片
- ✅ 实时连接状态指示
- ✅ 友好的错误提示和操作引导
- ✅ 流畅的动画效果

#### 5. 异常处理
- ✅ 蓝牙关闭检测和提示
- ✅ 权限缺失检测和引导
- ✅ 连接断开自动返回
- ✅ 数据解析错误处理
- ✅ 通信超时处理

## 🔧 技术实现

### 核心架构

```
BlueApp
├── BleProtocol.kt          # 协议定义和数据解析
├── BleManager.kt           # BLE 通信管理器
├── BluetoothAssistantApp.kt # 主应用逻辑
├── BluetoothScanScreen.kt  # 设备扫描界面
├── DeviceControlScreen.kt  # 设备控制界面
└── TechUi.kt              # UI 组件库
```

### 数据结构

#### 传感器数据（STRUCT_SENSOR_MSG）

```kotlin
data class SensorData(
    val voltAIn: Float,      // 模拟A输入
    val voltAOut: Float,     // 模拟A输出
    val voltBIn: Float,      // 模拟B输入
    val voltBOut: Float,     // 模拟B输出
    val voltCIn: Float,      // 模拟C输入
    val voltCOut: Float,     // 模拟C输出
    val voltDIn: Float,      // 模拟D输入
    val voltDOut: Float,     // 模拟D输出
    val voltEIn: Float,      // 模拟E输入
    val voltEOut: Float,     // 模拟E输出
    val freIn: Float,        // 频率信号输入
    val freOut: Float,       // 压力值（4字节）
    val rpm: Int,            // 发动机转速（4字节）
    val modeNum: Int,        // 当前工作模式（4字节）
)
```

**数据格式**：小端序（Little Endian）
**数据大小**：12个float（48字节）+ 2个int（8字节）= 56字节

### 通信协议

#### 指令格式

所有指令均以 `7E 7F` 开头，`FB FD` 结尾：

1. **数据获取指令**（每300ms发送一次）
   ```
   7E 7F 50 FB FD
   ```

2. **模式切换指令**
   ```
   7E 7F [模式号] FB FD
   ```
   模式号：00(E), 01(N), 02(S), 03(S+), 04(R)

3. **转速校准指令**
   ```
   7E 7F 60 [RPM值4字节小端序] FB FD
   ```

#### 数据返回格式

设备通过 BLE Notify 返回数据，格式为：
```
7E 7F 30 [传感器数据56字节] ...
```

可能分多个包返回，应用会自动缓冲并解析完整数据包。

## 🚀 使用方法

### 开发环境要求
- Android Studio Hedgehog | 2023.1.1 或更高版本
- JDK 11 或更高版本
- Android SDK API 26+ （Android 8.0+）
- 目标设备支持 BLE（蓝牙 4.0+）

### 安装步骤

1. **克隆项目**
   ```bash
   git clone <repository-url>
   cd BlueApp
   ```

2. **打开项目**
   使用 Android Studio 打开项目

3. **同步依赖**
   等待 Gradle 自动下载依赖

4. **运行应用**
   连接 Android 设备或启动模拟器，点击运行

### 使用流程

1. **授予权限**
   - 首次启动时，应用会请求蓝牙和位置权限
   - 如果蓝牙未开启，会提示开启蓝牙

2. **扫描设备**
   - 点击中央蓝色扫描按钮开始扫描
   - 等待设备出现在列表中

3. **连接设备**
   - 点击目标设备卡片
   - 等待连接和服务发现完成
   - 状态变为"就绪"后即可使用

4. **查看数据**
   - 转速：仪表盘实时显示
   - 压力：卡片显示当前值和历史趋势
   - 模式：右侧卡片显示当前工作模式

5. **控制设备**
   - **切换模式**：点击模式按钮（E/N/S/S+/R）
   - **校准转速**：输入目标转速或使用快捷按钮，点击"发送"

6. **断开连接**
   - 点击左上角返回按钮
   - 自动断开连接并返回扫描页面

## 📋 权限说明

应用需要以下权限：

### Android 12 及以上
- `BLUETOOTH_SCAN` - 扫描 BLE 设备
- `BLUETOOTH_CONNECT` - 连接 BLE 设备
- `ACCESS_FINE_LOCATION` - 位置权限（BLE 扫描所需）

### Android 11 及以下
- `BLUETOOTH` - 基础蓝牙功能
- `BLUETOOTH_ADMIN` - 蓝牙管理
- `ACCESS_FINE_LOCATION` - 位置权限（BLE 扫描所需）

**注意**：位置权限是 Android 系统要求的，用于 BLE 扫描，应用不会实际获取位置信息。

## 🔍 调试日志

应用使用 `BleManager` 标签输出详细日志，可通过以下命令查看：

```bash
adb logcat -s BleManager
```

日志包含：
- 扫描状态
- 连接过程
- 服务发现结果
- 发送的指令（【TX】）
- 接收的数据（【RX】）
- 错误信息

## 🛠️ 高级配置

### 修改轮询间隔

在 `BleProtocol.kt` 中修改：
```kotlin
const val POLL_INTERVAL_MS = 300L  // 默认 300ms
```

### 修改转速范围

在 `DeviceControlScreen.kt` 中修改：
```kotlin
val maxRpm = 15000  // 最大转速
val minRpm = 1      // 最小转速
```

### 自定义动态限制规则

在 `TargetRpmInput` 函数中修改压力与转速的关系：
```kotlin
val dynamicMax = when {
    bar >= 3.30f -> 6000   // 压力 ≥ 3.30 bar，限制 6000 RPM
    bar >= 2.80f -> 9000   // 压力 ≥ 2.80 bar，限制 9000 RPM
    else -> maxRpm         // 其他情况，使用最大值
}
```

## 📝 开发说明

### 添加新的传感器数据

1. 在 `BleProtocol.kt` 的 `SensorData` 中添加字段
2. 在 `parseSensorData()` 中添加解析逻辑
3. 在 `DeviceControlScreen.kt` 中添加显示组件

### 添加新的控制指令

1. 在 `BleProtocol.kt` 中定义指令常量
2. 在 `BleManager.kt` 中添加发送方法
3. 在 UI 层调用新方法

## 🐛 已知问题

1. **数据包分片**：如果设备返回的数据包过大，可能需要调整缓冲区逻辑
2. **蓝牙缓存**：某些设备可能需要清除蓝牙缓存才能正常连接
3. **Android 14+**：可能需要额外的权限适配

## 🔮 后续改进计划

- [ ] 添加数据日志记录功能
- [ ] 支持多设备同时连接
- [ ] 添加数据图表分析
- [ ] 支持自定义协议配置
- [ ] 添加设备配对功能
- [ ] 支持固件升级（OTA）

## 📄 许可证

本项目仅供学习和参考使用。

## 👤 作者

BlueApp 开发团队

## 🙏 致谢

- Android Jetpack Compose
- Material Design 3
- Kotlin Coroutines
- Android BLE API

---

**注意**：使用本应用前，请确保您的硬件设备支持本文档中描述的 BLE 通信协议。
