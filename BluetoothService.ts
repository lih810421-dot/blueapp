import { Buffer } from 'buffer'; // Base64转换
import { Alert, PermissionsAndroid, Platform } from 'react-native';
import { BleManager, Characteristic, Device } from 'react-native-ble-plx';
import { BLE_NOTIFY_CHAR_UUID, BLE_SERVICE_UUID, BLE_WRITE_CHAR_UUID, CONNECT_TIMEOUT_MS, RECONNECT_DELAY_MS, RECONNECT_RETRY, SCAN_DURATION_MS } from '../constants/config'; // 统一配置

export interface BluetoothDevice {
  id: string;
  name: string | null;
  rssi: number;
  isConnected: boolean;
}

export enum DeviceMode {
  E = 'E',    // 经济模式
  N = 'N',    // 标准模式
  S = 'S',    // 运动模式
  S_PLUS = 'S+', // 超级运动模式
  R = 'R'     // 倒车模式
}

export interface DeviceCommand {
  header: string;
  command: string;
  data?: string;
  footer: string;
}

export interface DeviceResponse {
  header: string;
  status: string;
  data: string;
  footer: string;
}

class BluetoothService {
  private manager: BleManager;
  private connectedDevice: Device | null = null;
  private writeCharacteristic: Characteristic | null = null;
  private readCharacteristic: Characteristic | null = null;
  private readServiceUuid: string | null = null; // 通知特征所属服务UUID #
  private readCharUuid: string | null = null; // 通知特征UUID #
  private speedPollTimer: any = null; // 速度轮询 #
  private notificationBufferHex: string = ''; // 通知帧缓冲 #
  private concatenatedNotifyHex: string = ''; // 连续NOTIFY十六进制累积（原始拼接） #
  private notificationSubscription: { remove: () => void } | null = null; // 通知订阅句柄 #
  private disconnectionSubscription: { remove: () => void } | null = null; // 断开连接监听句柄 #

  // 添加回调函数类型定义
  private onModeChangeCallback?: (mode: DeviceMode) => void;
  private onSpeedUpdateCallback?: (speed: number) => void;
  private onVoltageUpdateCallback?: (voltage: number) => void;
  private onPressureUpdateCallback?: (pressure: number) => void;
  private onDeviceDisconnectedCallback?: () => void;
  private onRawNotifyCallback?: (fragmentHex: string, concatenatedHex: string) => void; // 原始NOTIFY片段/累积回调 #
  
  // 用于去重日志的上一次数据
  private lastLoggedMode: DeviceMode | null = null;
  private lastLoggedSpeed: number | null = null;
  private lastLoggedPressure: number | null = null;
  private lastLoggedNotifyHex: string = ''; // 上一次NOTIFY的hex

  // 控制指令映射
  private readonly COMMANDS = {
    [DeviceMode.E]: '7E7F00FBFD',      // 经济模式
    [DeviceMode.N]: '7E7F01FBFD',      // 标准模式
    [DeviceMode.S]: '7E7F02FBFD',      // 运动模式
    [DeviceMode.S_PLUS]: '7E7F03FBFD', // 超级运动模式
    [DeviceMode.R]: '7E7F04FBFD',      // 倒车模式
  };

  // 请求指令
  private readonly REQUEST_SPEED = '7E7F61FBFD';  // 请求转速
  private readonly REQUEST_VOLTAGE = '7E7F50FBFD'; // 请求电压
  private readonly REQUEST_DATA = '7E7F50FBFD';    // 请求数据（模式、转速、压力）

  // 状态确认指令
  private readonly STATUS_RESPONSES = {
    '7E7F670000FBFD': DeviceMode.E,
    '7E7F67015EFBFD': DeviceMode.N,
    '7E7F6702BCFBFD': DeviceMode.S,
    '7E7F6703E2FBFD': DeviceMode.S_PLUS,
    '7E7F670461FBFD': DeviceMode.R,
  };

  constructor() {
    this.manager = new BleManager();
  }

  // 日志时间戳
  private now(): string { const d = new Date(); const p=(n:number)=>String(n).padStart(2,'0'); return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3,'0')}`; }
  private isSystemService(uuid?: string): boolean { const u=(uuid||'').toUpperCase(); return u.startsWith('00001800') || u.startsWith('00001801'); } // 过滤GAP/GATT #
  private isSystemChar(uuid?: string): boolean { const u=(uuid||'').toUpperCase(); return u.startsWith('00002A00') || u.startsWith('00002A01'); } // 过滤DeviceName/Appearance #

  // 初始化蓝牙服务
  async initialize(): Promise<boolean> {
    try {
      const state = await this.manager.state();
      if (state !== 'PoweredOn') {
        Alert.alert('蓝牙未开启', '请开启蓝牙后重试');
        return false;
      }

      if (Platform.OS === 'android') {
        const granted = await this.requestAndroidPermissions();
        if (!granted) {
          Alert.alert('权限不足', '需要蓝牙和位置权限才能正常使用');
          return false;
        }
      }

      return true;
    } catch (error) {
      console.error('蓝牙初始化失败:', error);
      return false;
    }
  }

  // 请求Android权限
  private async requestAndroidPermissions(): Promise<boolean> {
    try {
      const sdk = Number(Platform.Version) || 0; // Android API #
      const permissions = sdk >= 31
        ? [PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT]
        : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION]; // <31 需定位 #

      const results = await PermissionsAndroid.requestMultiple(permissions);
      
      return Object.values(results).every(
        result => result === PermissionsAndroid.RESULTS.GRANTED
      );
    } catch (error) {
      console.error('权限请求失败:', error);
      return false;
    }
  }

  // 扫描设备
  async startScan(onDeviceFound: (device: BluetoothDevice) => void): Promise<void> {
    try {
      const devices = new Map<string, BluetoothDevice>();

      this.manager.startDeviceScan(null, null, (error, device) => {
        if (error) {
          console.error(`[${this.now()}] [SCAN] 扫描错误:`, error);
          return;
        }

        if (device && device.name) {
          const bluetoothDevice: BluetoothDevice = {
            id: device.id,
            name: device.name,
            rssi: device.rssi || -100,
            isConnected: false,
          };

          // 过滤重复设备
          if (!devices.has(device.id)) {
            devices.set(device.id, bluetoothDevice);
            console.log(`[${this.now()}] [SCAN] 发现设备 name=${bluetoothDevice.name} id=${bluetoothDevice.id} rssi=${bluetoothDevice.rssi}`);
            onDeviceFound(bluetoothDevice);
          }
        }
      });

      // 配置化扫描时长
      setTimeout(() => {
        this.stopScan();
      }, SCAN_DURATION_MS);
    } catch (error) {
      console.error('开始扫描失败:', error);
    }
  }

  // 停止扫描
  stopScan(): void {
    this.manager.stopDeviceScan();
  }

  // 连接设备
  async connectDevice(deviceId: string): Promise<boolean> {
    const tryOnce = async (): Promise<boolean> => {
      this.stopScan(); // 停止扫描 #
      const device = await Promise.race([
        this.manager.connectToDevice(deviceId), // 连接 #
        new Promise<Device>((_, rej) => setTimeout(() => rej(new Error('CONNECT_TIMEOUT')), CONNECT_TIMEOUT_MS)) as Promise<Device>, // 超时 #
      ]);
      console.log(`[${this.now()}] [GATT] 已连接, 开始发现服务/特征 -> device=${deviceId}`);
      await device.discoverAllServicesAndCharacteristics(); // 发现服务 #

      const svcUuid = (u: string|undefined) => (u||'').toUpperCase(); // 规范化 #
      const allServices = await device.services();
      const targetService = allServices.find(s => svcUuid(s.uuid) === BLE_SERVICE_UUID.toUpperCase()); // 定位服务 #
      const services = (targetService ? [targetService] : allServices).filter(s => !this.isSystemService(s.uuid)); // 优先目标服务并过滤系统服务 #

      let writeChar: Characteristic | null = null, readChar: Characteristic | null = null;
      for (const service of services) {
        const chars = await service.characteristics();
        // 优先按UUID精确匹配 #
        const w = chars.find(c => svcUuid(c.uuid) === BLE_WRITE_CHAR_UUID.toUpperCase());
        const n = chars.find(c => !this.isSystemChar(c.uuid) && svcUuid(c.uuid) === BLE_NOTIFY_CHAR_UUID.toUpperCase());
        writeChar = writeChar || w || chars.find(c => c.isWritableWithResponse || c.isWritableWithoutResponse) || null; // 回退 #
        if (!readChar) {
          const pick = n || chars.find(c => (!this.isSystemChar(c.uuid) && (c.isNotifiable || (c as any).isIndicatable))); // 仅可通知/指示且非系统特征 #
          if (pick) { readChar = pick; this.readServiceUuid = svcUuid(service.uuid); this.readCharUuid = svcUuid(pick.uuid); }
        }
      }
      // 若在目标服务未找到通知特征，尝试在所有服务中再次匹配配置的通知UUID
      if (!readChar) {
        for (const s of allServices.filter(s=>!this.isSystemService(s.uuid))) {
          const chars = await s.characteristics();
          const n = chars.find(c => !this.isSystemChar(c.uuid) && svcUuid(c.uuid) === BLE_NOTIFY_CHAR_UUID.toUpperCase());
          if (n && (n.isNotifiable || (n as any).isIndicatable)) { readChar = n; this.readServiceUuid = svcUuid(s.uuid); this.readCharUuid = svcUuid(n.uuid); break; }
        }
      }
      // 仍然找不到，则在所有服务中找第一个可通知/指示的特征
      if (!readChar) {
        for (const s of allServices.filter(s=>!this.isSystemService(s.uuid))) {
          const chars = await s.characteristics();
          const anyN = chars.find(c => (!this.isSystemChar(c.uuid) && (c.isNotifiable || (c as any).isIndicatable)));
          if (anyN) { readChar = anyN; this.readServiceUuid = svcUuid(s.uuid); this.readCharUuid = svcUuid(anyN.uuid); break; }
        }
      }
      this.writeCharacteristic = writeChar;
      this.readCharacteristic = readChar;

      // 优先使用设备级订阅，提升兼容性
      if (this.readServiceUuid && this.readCharUuid && (this.readCharacteristic?.isNotifiable || (this.readCharacteristic as any)?.isIndicatable)) {
        try {
          console.log(`[${this.now()}] [NOTIFY] 订阅通知 via device monitor service=${this.readServiceUuid} char=${this.readCharUuid}`);
          this.notificationSubscription = this.manager.monitorCharacteristicForDevice(
            device.id,
            this.readServiceUuid,
            this.readCharUuid,
            (error, characteristic) => {
              // 安全的错误处理：断开连接时会触发错误，需要静默处理
              if (error) {
                // 检查设备是否已断开，如果已断开则静默忽略错误
                if (!this.connectedDevice || !this.notificationSubscription) {
                  console.log(`[${this.now()}] [NOTIFY] 设备已断开，忽略订阅错误`);
                  return;
                }
                console.error(`[${this.now()}] [NOTIFY] 订阅错误:`, error);
                return;
              }
              // 安全处理通知数据
              if (characteristic?.value) {
                try {
                  this.handleNotification(characteristic.value);
                } catch (e) {
                  console.error(`[${this.now()}] [NOTIFY] 处理通知数据失败:`, e);
                }
              }
            }
          );
        } catch (e) {
          console.warn(`[${this.now()}] [NOTIFY] 设备级订阅失败，回退到特征订阅`, e);
          if (this.readCharacteristic) {
            this.notificationSubscription = this.readCharacteristic.monitor((error, characteristic) => {
              // 安全的错误处理
              if (error) {
                if (!this.connectedDevice || !this.notificationSubscription) {
                  console.log(`[${this.now()}] [NOTIFY] 设备已断开，忽略订阅错误`);
                  return;
                }
                console.error(`[${this.now()}] [NOTIFY] 特征订阅错误:`, error);
                return;
              }
              // 安全处理通知数据
              if (characteristic?.value) {
                try {
                  this.handleNotification(characteristic.value);
                } catch (e) {
                  console.error(`[${this.now()}] [NOTIFY] 处理通知数据失败:`, e);
                }
              }
            });
          }
        }
      } else if (this.readCharacteristic?.isNotifiable || (this.readCharacteristic && (this.readCharacteristic as any).isIndicatable)) {
        this.notificationSubscription = this.readCharacteristic.monitor((error, characteristic) => {
          // 安全的错误处理
          if (error) {
            if (!this.connectedDevice || !this.notificationSubscription) {
              console.log(`[${this.now()}] [NOTIFY] 设备已断开，忽略订阅错误`);
              return;
            }
            console.error(`[${this.now()}] [NOTIFY] 特征订阅错误:`, error);
            return;
          }
          // 安全处理通知数据
          if (characteristic?.value) {
            try {
              this.handleNotification(characteristic.value);
            } catch (e) {
              console.error(`[${this.now()}] [NOTIFY] 处理通知数据失败:`, e);
            }
          }
        });
      } else {
        console.warn(`[${this.now()}] [NOTIFY] 未找到可订阅的通知特征 (service/char uuid 缺失或不支持)`);
      }

      this.connectedDevice = device; // 记录连接设备 #
      
      // 添加设备断开监听器（非常重要！防止意外断开导致崩溃）
      try {
        this.disconnectionSubscription = device.onDisconnected((error, disconnectedDevice) => {
          console.log(`[${this.now()}] [DISCONNECT_EVENT] 设备意外断开 name=${disconnectedDevice?.name} error=${error?.message}`);
          // 清理资源，但不触发UI回调（因为可能是主动断开）
          this.handleUnexpectedDisconnection();
        });
        console.log(`[${this.now()}] [DISCONNECT_EVENT] 断开监听器已注册`);
      } catch (e) {
        console.warn(`[${this.now()}] [DISCONNECT_EVENT] 注册断开监听器失败:`, e);
      }
      
      console.log(`[${this.now()}] [GATT] 设备连接成功 name=${device.name}`);
      const ok = !!(this.writeCharacteristic && this.readCharacteristic);
      if (ok) {
        console.log(`[${this.now()}] [CMD] 请求当前模式 7E7F67FBFD`);
        await this.requestModeStatus(); // 获取当前模式 #
        console.log(`[${this.now()}] [POLL] 启动转速轮询 200ms`);
        this.startSpeedPolling(); // 开启转速轮询 #
      }
      return ok;
    };

    for (let i = 0; i <= RECONNECT_RETRY; i++) {
      try {
        const ok = await tryOnce();
        if (ok) return true; // 成功 #
      } catch (e) {
        console.warn('连接重试', i, e);
        if (i === RECONNECT_RETRY) break; // 达上限 #
        await new Promise(r => setTimeout(r, RECONNECT_DELAY_MS)); // 等待重试 #
      }
    }
    return false;
  }

  // 断开设备连接（全新重写，更安全的断开流程）
  async disconnectDevice(): Promise<void> {
    console.log(`[${this.now()}] [DISCONNECT] 开始断开连接...`);
    
    // 1. 保存设备ID，后续断开用
    const deviceId = this.connectedDevice?.id;
    const deviceName = this.connectedDevice?.name;
    
    if (!deviceId) {
      console.log(`[${this.now()}] [DISCONNECT] 没有已连接的设备`);
      return;
    }
    
    // 2. 立即停止轮询（防止继续发送命令）
    if (this.speedPollTimer) {
      clearInterval(this.speedPollTimer);
      this.speedPollTimer = null;
      console.log(`[${this.now()}] [DISCONNECT] 轮询已停止`);
    }
    
    // 3. 立即清空所有引用，让所有回调函数早期返回
    const savedNotificationSubscription = this.notificationSubscription;
    const savedDisconnectionSubscription = this.disconnectionSubscription;
    
    this.connectedDevice = null;
    this.writeCharacteristic = null;
    this.readCharacteristic = null;
    this.readServiceUuid = null;
    this.readCharUuid = null;
    this.notificationSubscription = null;
    this.disconnectionSubscription = null;
    this.notificationBufferHex = '';
    this.concatenatedNotifyHex = '';
    console.log(`[${this.now()}] [DISCONNECT] 引用已清空（回调将自动忽略）`);
    
    // 4. 短暂延迟，让所有正在执行的回调完成
    await new Promise(resolve => setTimeout(resolve, 150));
    
    // 5. 断开设备连接（系统会自动清理订阅）
    try {
      console.log(`[${this.now()}] [DISCONNECT] 正在断开设备 ${deviceName} (${deviceId})`);
      await this.manager.cancelDeviceConnection(deviceId);
      console.log(`[${this.now()}] [DISCONNECT] 设备已断开`);
    } catch (error) {
      console.warn(`[${this.now()}] [DISCONNECT] 断开失败（可能已断开）:`, error);
    }
    
    // 6. 延迟后再清理订阅句柄（避免过早清理导致系统回调崩溃）
    await new Promise(resolve => setTimeout(resolve, 100));
    
    try {
      if (savedNotificationSubscription) {
        savedNotificationSubscription.remove();
        console.log(`[${this.now()}] [DISCONNECT] 通知订阅已清理`);
      }
    } catch (e) {
      console.warn(`[${this.now()}] [DISCONNECT] 清理通知订阅失败:`, e);
    }
    
    try {
      if (savedDisconnectionSubscription) {
        savedDisconnectionSubscription.remove();
        console.log(`[${this.now()}] [DISCONNECT] 断开监听已清理`);
      }
    } catch (e) {
      console.warn(`[${this.now()}] [DISCONNECT] 清理断开监听失败:`, e);
    }
    
    // 7. 清除所有回调
    this.onModeChangeCallback = undefined;
    this.onSpeedUpdateCallback = undefined;
    this.onVoltageUpdateCallback = undefined;
    this.onPressureUpdateCallback = undefined;
    console.log(`[${this.now()}] [DISCONNECT] 回调已清除`);
    
    console.log(`[${this.now()}] [DISCONNECT] 断开完成`);
  }

  // 处理意外断开连接（全新重写，与主动断开保持一致）
  private handleUnexpectedDisconnection(): void {
    console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 处理意外断开...`);
    
    // 1. 停止轮询
    if (this.speedPollTimer) {
      clearInterval(this.speedPollTimer);
      this.speedPollTimer = null;
      console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 轮询已停止`);
    }
    
    // 2. 保存订阅句柄后立即清空引用
    const savedNotificationSubscription = this.notificationSubscription;
    const savedDisconnectionSubscription = this.disconnectionSubscription;
    
    this.connectedDevice = null;
    this.writeCharacteristic = null;
    this.readCharacteristic = null;
    this.readServiceUuid = null;
    this.readCharUuid = null;
    this.notificationSubscription = null;
    this.disconnectionSubscription = null;
    this.notificationBufferHex = '';
    this.concatenatedNotifyHex = '';
    console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 引用已清空`);
    
    // 3. 清除所有回调（在触发断开回调之前）
    this.onModeChangeCallback = undefined;
    this.onSpeedUpdateCallback = undefined;
    this.onVoltageUpdateCallback = undefined;
    this.onPressureUpdateCallback = undefined;
    
    // 4. 延迟清理订阅（避免正在执行的回调崩溃）
    setTimeout(() => {
      try {
        if (savedNotificationSubscription) {
          savedNotificationSubscription.remove();
          console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 通知订阅已清理`);
        }
      } catch (e) {
        console.warn(`[${this.now()}] [UNEXPECTED_DISCONNECT] 清理通知订阅失败:`, e);
      }
      
      try {
        if (savedDisconnectionSubscription) {
          savedDisconnectionSubscription.remove();
          console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 断开监听已清理`);
        }
      } catch (e) {
        console.warn(`[${this.now()}] [UNEXPECTED_DISCONNECT] 清理断开监听失败:`, e);
      }
    }, 100);
    
    // 5. 触发断开回调（如果有）
    if (this.onDeviceDisconnectedCallback) {
      try {
        this.onDeviceDisconnectedCallback();
      } catch (e) {
        console.error(`[${this.now()}] [UNEXPECTED_DISCONNECT] 回调错误:`, e);
      }
    }
    
    console.log(`[${this.now()}] [UNEXPECTED_DISCONNECT] 清理完成`);
  }

  // 发送模式控制指令
  async setDeviceMode(mode: DeviceMode): Promise<boolean> {
    const command = this.COMMANDS[mode];
    if (!command) {
      console.error('无效的设备模式:', mode);
      return false;
    }

    return await this.sendCommand(command);
  }

  // 发送转速校准指令
  async setDeviceSpeed(speed: number): Promise<boolean> {
    if (speed < 0 || speed > 9999) {
      console.error('转速值超出范围 (0-9999):', speed);
      return false;
    }

    // 转换为4位十六进制字符串
    const speedHex = speed.toString(16).padStart(4, '0').toUpperCase();
    const command = `7E7F61${speedHex}FBFD`;
    
    return await this.sendCommand(command);
  }

  // 发送十六进制指令
  private async sendCommand(hexCommand: string): Promise<boolean> {
    try {
      if (!this.connectedDevice || !this.writeCharacteristic) {
        console.error('设备未连接或写入特征值不可用');
        return false;
      }

      // 将十六进制字符串转换为字节数组
      const bytes = this.hexStringToBytes(hexCommand);
      const base64Data = this.bytesToBase64(bytes);

      console.log(`[${this.now()}] [TX] -> HEX=${hexCommand} len=${bytes.length}`);
      await this.writeCharacteristic.writeWithResponse(base64Data);
      console.log(`[${this.now()}] [TX] OK`);
      return true;
    } catch (error) {
      console.error(`[${this.now()}] [TX] FAIL:`, error);
      return false;
    }
  }

  // 设置状态更新回调
  setOnModeChangeCallback(callback: (mode: DeviceMode) => void): void {
    this.onModeChangeCallback = callback;
  }

  setOnSpeedUpdateCallback(callback: (speed: number) => void): void {
    this.onSpeedUpdateCallback = callback;
  }

  setOnVoltageUpdateCallback(callback: (voltage: number) => void): void {
    this.onVoltageUpdateCallback = callback;
  }

  setOnPressureUpdateCallback(callback: (pressure: number) => void): void {
    this.onPressureUpdateCallback = callback;
  }

  setOnDeviceDisconnectedCallback(callback: () => void): void {
    this.onDeviceDisconnectedCallback = callback;
  }

  // 设置原始NOTIFY回调（用于兼容非标准帧/直接在UI显示原始数据）
  setOnRawNotifyCallback(callback: (fragmentHex: string, concatenatedHex: string) => void): void {
    this.onRawNotifyCallback = callback;
  }

  // 解析转速hex（使用小端序）
  private parseSpeedFromHex(hexString: string): number {
    const speedHex = hexString.substring(6, 14); // 8个字符
    const bytes = [];
    for (let i = 0; i < speedHex.length; i += 2) {
      bytes.push(parseInt(speedHex.substr(i, 2), 16));
    }
    // 小端序：(byte[3] << 24) | (byte[2] << 16) | (byte[1] << 8) | byte[0]
    return (bytes[3] << 24) | (bytes[2] << 16) | (bytes[1] << 8) | bytes[0];
  }

  // 解析电压hex（使用小端序）
  private parseVoltageFromHex(hexString: string): number {
    const voltageHex = hexString.substring(6, 14); // 8个字符
    const bytes = [];
    for (let i = 0; i < voltageHex.length; i += 2) {
      bytes.push(parseInt(voltageHex.substr(i, 2), 16));
    }
    // 小端序解析
    return (bytes[3] << 24) | (bytes[2] << 16) | (bytes[1] << 8) | bytes[0];
  }

  // 解析综合数据（从末尾开始：int模式、int转速、float压力）
  private parseDataFromHex(hexString: string): { mode: number; speed: number; pressure: number } | null {
    try {
      // 移除帧头和帧尾：7E7F50...FBFD
      if (!hexString.startsWith('7E7F50') || !hexString.endsWith('FBFD')) {
        console.warn(`[${this.now()}] [PARSE] 帧头尾不匹配 head=${hexString.slice(0,6)} tail=${hexString.slice(-4)} full=${hexString}`);
        return null;
      }
      
      const dataHex = hexString.substring(6, hexString.length - 4); // 移除7E7F50和FBFD
      console.log(`[${this.now()}] [PARSE] dataHex=${dataHex} (len=${dataHex.length/2} bytes)`);
      const bytes = [];
      for (let i = 0; i < dataHex.length; i += 2) {
        bytes.push(parseInt(dataHex.substr(i, 2), 16));
      }

      // 从末尾开始解析，每4个字节一个值
      if (bytes.length < 12) {
        console.warn(`[${this.now()}] [PARSE] 数据长度不足 len=${bytes.length} rawDataHex=${dataHex}`);
        return null;
      }

      // 从末尾提取最后12个字节（3个int32/float32值）
      const dataBytes = bytes.slice(-12);
      console.log(`[${this.now()}] [PARSE] 截取最后12字节=${this.bytesToHexString(dataBytes)} (len=12)`);
      
      // 按照格式：从后往前解析
      // 最后8字符（4字节）= 模式
      // 往前8字符（4字节）= 转速
      // 再往前8字符（4字节）= 压力
      
      // 解析模式（int，最后4个字节，小端序）
      const modeBytes = dataBytes.slice(8, 12);
      const mode = (modeBytes[3] << 24) | (modeBytes[2] << 16) | (modeBytes[1] << 8) | modeBytes[0];
      console.log(`[${this.now()}] [PARSE] 模式 modeBytes(LE)=${this.bytesToHexString(modeBytes)} -> mode=${mode}`);

      // 解析转速（int，中间4个字节，小端序）
      const speedBytes = dataBytes.slice(4, 8);
      const speed = (speedBytes[3] << 24) | (speedBytes[2] << 16) | (speedBytes[1] << 8) | speedBytes[0];
      console.log(`[${this.now()}] [PARSE] 转速 speedBytes(LE)=${this.bytesToHexString(speedBytes)} -> speed=${speed}`);

      // 解析压力（float，前4个字节，小端序）
      const pressureBytes = dataBytes.slice(0, 4);
      const pressureView = new DataView(new ArrayBuffer(4));
      pressureBytes.forEach((byte, i) => pressureView.setUint8(i, byte));
      const pressure = pressureView.getFloat32(0, true); // true = 小端序
      console.log(`[${this.now()}] [PARSE] 压力 pressureBytes(LE)=${this.bytesToHexString(pressureBytes)} -> pressure=${pressure}`);

      return { mode, speed, pressure };
    } catch (error) {
      console.error(`[${this.now()}] [PARSE] 数据解析失败:`, error);
      return null;
    }
  }

  // 模式值转换为DeviceMode
  private modeValueToDeviceMode(modeValue: number): DeviceMode | null {
    const modeMap: { [key: number]: DeviceMode } = {
      0: DeviceMode.E,
      1: DeviceMode.N,
      2: DeviceMode.S,
      3: DeviceMode.S_PLUS,
      4: DeviceMode.R,
    };
    return modeMap[modeValue] || null;
  }

  // 处理设备响应
  private handleDeviceResponse(base64Data: string): void {
    // 检查设备是否已断开，避免处理无效数据
    if (!this.connectedDevice) {
      console.log(`[${this.now()}] [RX] 设备已断开，忽略响应`);
      return;
    }
    
    try {
      const bytes = this.base64ToBytes(base64Data);
      const hexString = this.bytesToHexString(bytes);
      
      // 解析响应数据
      if (hexString.startsWith('7E7F67') && hexString.endsWith('FBFD')) {
        // 状态确认响应
        const mode = this.STATUS_RESPONSES[hexString as keyof typeof this.STATUS_RESPONSES];
        if (mode) {
          // 只在模式变化时输出日志
          if (mode !== this.lastLoggedMode) {
            console.log(`[${this.now()}] [PARSE] 模式确认 | hex=${hexString} | 解析: mode=${mode}`);
            this.lastLoggedMode = mode;
          }
          // 触发模式更新回调
          if (this.onModeChangeCallback && this.connectedDevice) {
            try {
              this.onModeChangeCallback(mode);
            } catch (error) {
              console.error(`[${this.now()}] [CALLBACK] 模式回调错误:`, error);
            }
          }
        }
      } else if (hexString.startsWith('7E7F61') && hexString.endsWith('FBFD')) {
        // 转速数据响应
        const speed = this.parseSpeedFromHex(hexString);
        // 只在转速变化时输出日志
        if (speed !== this.lastLoggedSpeed) {
          console.log(`[${this.now()}] [PARSE] 转速响应 | hex=${hexString} | 解析: speed=${speed}`);
          this.lastLoggedSpeed = speed;
        }
        // 触发转速更新回调
        if (this.onSpeedUpdateCallback && this.connectedDevice) {
          try {
            this.onSpeedUpdateCallback(speed);
          } catch (error) {
            console.error(`[${this.now()}] [CALLBACK] 转速回调错误:`, error);
          }
        }
      } else if (hexString.startsWith('7E7F50') && hexString.endsWith('FBFD')) {
        // 综合数据响应（模式、转速、压力）
        const data = this.parseDataFromHex(hexString);
        if (data) {
          // 检查是否有任何数据变化
          const mode = this.modeValueToDeviceMode(data.mode);
          const modeChanged = mode !== this.lastLoggedMode;
          const speedChanged = data.speed !== this.lastLoggedSpeed;
          const pressureChanged = data.pressure !== this.lastLoggedPressure;
          
          // 只在数据变化时输出日志
          if (modeChanged || speedChanged || pressureChanged) {
            const changes = [];
            if (modeChanged) changes.push(`mode=${mode}`);
            if (speedChanged) changes.push(`speed=${data.speed}`);
            if (pressureChanged) changes.push(`pressure=${data.pressure.toFixed(2)}`);
            console.log(`[${this.now()}] [PARSE] 综合数据 | hex=${hexString}`);
            console.log(`[${this.now()}] [PARSE] 解析结果 | ${changes.join(', ')} | 完整数据: mode=${mode}, speed=${data.speed}, pressure=${data.pressure.toFixed(2)}`);
            
            // 更新缓存
            if (mode) this.lastLoggedMode = mode;
            this.lastLoggedSpeed = data.speed;
            this.lastLoggedPressure = data.pressure;
          }
          
          // 触发模式更新
          if (mode && this.onModeChangeCallback && this.connectedDevice) {
            try {
              this.onModeChangeCallback(mode);
            } catch (error) {
              console.error(`[${this.now()}] [CALLBACK] 模式回调错误:`, error);
            }
          }
          
          // 触发转速更新
          if (this.onSpeedUpdateCallback && this.connectedDevice) {
            try {
              this.onSpeedUpdateCallback(data.speed);
            } catch (error) {
              console.error(`[${this.now()}] [CALLBACK] 转速回调错误:`, error);
            }
          }
          
          // 触发压力更新
          if (this.onPressureUpdateCallback && this.connectedDevice) {
            try {
              this.onPressureUpdateCallback(data.pressure);
            } catch (error) {
              console.error(`[${this.now()}] [CALLBACK] 压力回调错误:`, error);
            }
          }
        } else {
          console.log(`[${this.now()}] [PARSE] 数据解析失败 hex=${hexString}`);
        }
      } else {
        console.log(`[${this.now()}] [PARSE] 未识别帧 head=${hexString.slice(0,4)} tail=${hexString.slice(-4)} full=${hexString}`);
      }
    } catch (error) {
      console.error(`[${this.now()}] [RX] 解析失败:`, error);
    }
  }

  // 通知粘包拆帧
  private handleNotification(base64Data: string): void {
    // 严格检查设备连接状态和订阅状态，避免断开后处理通知
    if (!this.connectedDevice || !this.notificationSubscription) {
      console.log(`[${this.now()}] [NOTIFY] 设备已断开或订阅已取消，忽略通知`);
      return;
    }
    
    try {
      const hex = this.bytesToHexString(this.base64ToBytes(base64Data)); // 转十六进制 #
      
      // 只在 hex 内容变化时才输出日志
      if (hex !== this.lastLoggedNotifyHex) {
        console.log(`[${this.now()}] [NOTIFY] hex=${hex} (changed)`);
        this.lastLoggedNotifyHex = hex;
      }
      
      // 记录原始连续NOTIFY拼接（便于调试/上层一次性读取）
      this.concatenatedNotifyHex += hex;
      // 通知上层原始片段与当前累积（兼容从机非标准格式时在UI直接显示）
      if (this.onRawNotifyCallback && this.connectedDevice) {
        try {
          this.onRawNotifyCallback(hex, this.concatenatedNotifyHex);
        } catch (e) {
          console.error(`[${this.now()}] [CALLBACK] 原始NOTIFY回调错误:`, e);
        }
      }
      this.notificationBufferHex += hex; // 追加 #
      const HEAD = '7E7F', TAIL = 'FBFD'; // 帧界定 #
      while (true) {
        const hs = this.notificationBufferHex.indexOf(HEAD);
        const te = this.notificationBufferHex.indexOf(TAIL, hs + HEAD.length);
        if (hs < 0 || te < 0) break; // 不完整 #
        const frame = this.notificationBufferHex.slice(hs, te + TAIL.length); // 完整帧 #
        console.log(`[${this.now()}] [FRAME] 检测到完整帧，开始解析...`);
        this.notificationBufferHex = this.notificationBufferHex.slice(te + TAIL.length); // 滑动 #
        const b64 = this.bytesToBase64(this.hexStringToBytes(frame)); // 回转base64复用解析 #
        this.handleDeviceResponse(b64); // 解析一帧 #
      }
      if (this.notificationBufferHex.length > 4096) this.notificationBufferHex = this.notificationBufferHex.slice(-512); // 防炸内存 #
    } catch (error) {
      console.error(`[${this.now()}] [NOTIFY] 处理通知失败:`, error);
    }
  }

  // 工具方法：十六进制字符串转字节数组
  private hexStringToBytes(hexString: string): number[] {
    const bytes: number[] = [];
    for (let i = 0; i < hexString.length; i += 2) {
      bytes.push(parseInt(hexString.substr(i, 2), 16));
    }
    return bytes;
  }

  // 工具方法：字节数组转十六进制字符串
  private bytesToHexString(bytes: number[]): string {
    return bytes.map(byte => byte.toString(16).padStart(2, '0').toUpperCase()).join('');
  }

  // 工具方法：字节数组转Base64
  private bytesToBase64(bytes: number[]): string { return Buffer.from(bytes).toString('base64'); }

  // 工具方法：Base64转字节数组
  private base64ToBytes(base64: string): number[] { return Array.from(Buffer.from(base64, 'base64')); }

  // 请求当前模式
  async requestModeStatus(): Promise<boolean> { return await this.sendCommand('7E7F67FBFD'); }
  // 请求当前转速
  async requestSpeed(): Promise<boolean> { return await this.sendCommand(this.REQUEST_SPEED); }
  // 请求当前电压
  async requestVoltage(): Promise<boolean> { return await this.sendCommand(this.REQUEST_VOLTAGE); }
  // 请求综合数据（模式、转速、压力）
  async requestData(): Promise<boolean> { return await this.sendCommand(this.REQUEST_DATA); }
  
  // 获取最近一次会话自开始/上次清理以来连续收到的NOTIFY十六进制拼接值
  getConcatenatedNotifyHex(): string {
    return this.concatenatedNotifyHex;
  }

  // 清空连续NOTIFY十六进制拼接值
  clearConcatenatedNotifyHex(): void {
    this.concatenatedNotifyHex = '';
  }

  // 开启数据轮询（300ms）
  private startSpeedPolling(): void {
    if (this.speedPollTimer) { clearInterval(this.speedPollTimer); this.speedPollTimer = null; }
    this.speedPollTimer = setInterval(async () => {
      // 检查设备是否仍然连接
      if (this.connectedDevice && this.writeCharacteristic) {
        await this.requestData(); // 改为请求综合数据
      } else {
        // 设备已断开，停止轮询
        if (this.speedPollTimer) {
          clearInterval(this.speedPollTimer);
          this.speedPollTimer = null;
        }
      }
    }, 300); // 300ms轮询 #
  }

  // 获取连接状态
  isConnected(): boolean {
    return this.connectedDevice !== null;
  }

  // 获取连接的设备信息
  getConnectedDevice(): Device | null {
    return this.connectedDevice;
  }

  // 销毁服务
  async destroy(): Promise<void> {
    console.log(`[${this.now()}] [DESTROY] 开始销毁服务...`);
    
    try {
      this.stopScan();
    } catch (e) {
      console.warn(`[${this.now()}] [DESTROY] 停止扫描失败:`, e);
    }
    
    try {
      await this.disconnectDevice();
    } catch (e) {
      console.warn(`[${this.now()}] [DESTROY] 断开设备失败:`, e);
    }
    
    try {
      this.manager.destroy();
      console.log(`[${this.now()}] [DESTROY] 服务销毁完成`);
    } catch (e) {
      console.warn(`[${this.now()}] [DESTROY] 销毁管理器失败:`, e);
    }
  }
}

export default new BluetoothService();

