package com.example.blueapp.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 传感器数据结构
 * 对应功能文档中的 STRUCT_SENSOR_MSG
 */
data class SensorData(
    val voltAIn: Float = 0f,      // 模拟A输入
    val voltAOut: Float = 0f,     // 模拟A输出
    val voltBIn: Float = 0f,      // 模拟B输入
    val voltBOut: Float = 0f,     // 模拟B输出
    val voltCIn: Float = 0f,      // 模拟C输入
    val voltCOut: Float = 0f,     // 模拟C输出
    val voltDIn: Float = 0f,      // 模拟D输入
    val voltDOut: Float = 0f,     // 模拟D输出
    val voltEIn: Float = 0f,      // 模拟E输入
    val voltEOut: Float = 0f,     // 模拟E输出
    val freIn: Float = 0f,        // 频率信号输入
    val freOut: Float = 0f,       // 压力值（4字节）
    val rpm: Int = 0,             // 发动机转速（4字节）
    val modeNum: Int = 0,         // 当前工作模式（4字节）
)

/**
 * 工作模式枚举
 */
enum class WorkMode(val modeNum: Int, val command: ByteArray, val displayName: String) {
    E(0, byteArrayOf(0x7E.toByte(), 0x7F, 0x00, 0xFB.toByte(), 0xFD.toByte()), "E 经济"),
    N(1, byteArrayOf(0x7E.toByte(), 0x7F, 0x01, 0xFB.toByte(), 0xFD.toByte()), "N 标准"),
    S(2, byteArrayOf(0x7E.toByte(), 0x7F, 0x02, 0xFB.toByte(), 0xFD.toByte()), "S 运动"),
    S_PLUS(3, byteArrayOf(0x7E.toByte(), 0x7F, 0x03, 0xFB.toByte(), 0xFD.toByte()), "S+ 超级运动"),
    R(4, byteArrayOf(0x7E.toByte(), 0x7F, 0x04, 0xFB.toByte(), 0xFD.toByte()), "R 倒车");

    companion object {
        fun fromModeNum(num: Int): WorkMode? = entries.find { it.modeNum == num }
    }
}

/**
 * BLE 协议工具类
 */
object BleProtocol {
    // 数据获取指令：7E 7F 50 FB FD
    val CMD_GET_DATA = byteArrayOf(0x7E.toByte(), 0x7F, 0x50, 0xFB.toByte(), 0xFD.toByte())

    // 数据轮询间隔（毫秒）
    const val POLL_INTERVAL_MS = 300L

    /**
     * 生成转速校准指令
     * 格式：7E 7F 60 [RPM值4字节] FB FD
     * @param rpm 目标转速 (0-9999)
     * @return 指令字节数组
     */
    fun createRpmCalibrationCommand(rpm: Int): ByteArray {
        val rpmClamped = rpm.coerceIn(0, 9999)
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(rpmClamped)
        val rpmBytes = buffer.array()

        return byteArrayOf(
            0x7E.toByte(),
            0x7F,
            0x60,
            rpmBytes[0],
            rpmBytes[1],
            rpmBytes[2],
            rpmBytes[3],
            0xFB.toByte(),
            0xFD.toByte()
        )
    }

    /**
     * 解析传感器数据
     * @param data 接收到的字节数组
     * @return 解析后的传感器数据，如果解析失败返回null
     */
    fun parseSensorData(data: ByteArray): SensorData? {
        try {
            // 检查数据头：7E 7F 30
            if (data.size < 3 || data[0] != 0x7E.toByte() || data[1] != 0x7F.toByte() || data[2] != 0x30.toByte()) {
                return null
            }

            // 完整数据应该是多个包，总共约 96 字节
            // 根据示例：【RX】7E 7F 30 00 00... (分多次返回)
            // 我们需要收集完整数据包
            
            // 预期数据结构大小：12个float（48字节） + 2个int（8字节） = 56字节 + 3字节头 = 59字节
            // 但示例中显示数据可能更长，我们按实际协议处理
            
            if (data.size < 59) {
                // 数据不完整，等待更多数据
                return null
            }

            val buffer = ByteBuffer.wrap(data, 3, data.size - 3).order(ByteOrder.LITTLE_ENDIAN)

            return SensorData(
                voltAIn = buffer.float,
                voltAOut = buffer.float,
                voltBIn = buffer.float,
                voltBOut = buffer.float,
                voltCIn = buffer.float,
                voltCOut = buffer.float,
                voltDIn = buffer.float,
                voltDOut = buffer.float,
                voltEIn = buffer.float,
                voltEOut = buffer.float,
                freIn = buffer.float,
                freOut = buffer.float,  // 压力值
                rpm = buffer.int,
                modeNum = buffer.int,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 字节数组转十六进制字符串（用于日志）
     */
    fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
