package com.example.blueapp.bluetooth

/**
 * BLE UUID 配置（可选）
 *
 * 参考你旧版 `BluetoothService.ts` 的策略：优先按 UUID 精确匹配（Service / Write / Notify），
 * 找不到再回退到“第一个可写 / 第一个可通知（排除系统特征）”。
 *
 * 如果你已经明确设备的 UUID，把下面 3 个值填上即可。
 * 不填（保持 null）也能工作，但兼容性取决于设备的服务/特征暴露方式。
 */
object BleUuidConfig {
    /** 目标 Service UUID（可选） */
    val PREFERRED_SERVICE_UUID: String? = null

    /** 写入特征值 UUID（可选） */
    val PREFERRED_WRITE_CHAR_UUID: String? = null

    /** Notify/Indicate 特征值 UUID（可选） */
    val PREFERRED_NOTIFY_CHAR_UUID: String? = null
}

