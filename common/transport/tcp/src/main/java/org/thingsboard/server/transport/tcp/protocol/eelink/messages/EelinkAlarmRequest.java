/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.tcp.protocol.eelink.messages;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.transport.tcp.protocol.eelink.EelinkProtocolConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;

/**
 * Eelink警情上报请求（帧代号0x42）
 * 
 * 数据段格式：
 * - 报警时间（6字节）：年月日时分秒
 * - 报警类型（1字节）：0x01-0x06
 * - 发生报警的设备地址（4字节，低位在前）
 * - 报警数据（16字节）：根据报警类型不同而不同
 */
@Data
@Slf4j
public class EelinkAlarmRequest {
    
    // 数据段总长度：6(时间) + 1(类型) + 4(地址) + 16(数据) = 27字节
    private static final int DATA_LENGTH = 27;
    
    // 报警类型常量
    public static final byte ALARM_TYPE_CHANNEL_VALUE = 0x01;    // 通道数据值报警
    public static final byte ALARM_TYPE_SWITCH = 0x02;           // 开关量报警
    public static final byte ALARM_TYPE_DEVICE_RUNNING = 0x03;   // 设备运行报警
    public static final byte ALARM_TYPE_POWER_SUPPLY = 0x04;     // 供电报警
    public static final byte ALARM_TYPE_CONTROL_OUTPUT = 0x05;   // 控制输出报警
    public static final byte ALARM_TYPE_EXTERNAL_EVENT = 0x06;   // 外部事件
    
    /** 报警时间 */
    private LocalDateTime alarmTime;
    
    /** 报警类型 */
    private byte alarmType;
    
    /** 发生报警的设备地址 */
    private byte[] alarmDeviceAddress;
    
    /** 报警数据（原始字节） */
    private byte[] alarmData;
    
    /**
     * 从字节数组解析警情上报请求
     * 
     * @param data 数据段字节数组
     * @return 解析后的警情上报请求对象
     * @throws IllegalArgumentException 如果数据长度不正确
     */
    public static EelinkAlarmRequest parse(byte[] data) {
        if (data == null || data.length < DATA_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Invalid alarm request data length: expected at least %d, got %d", 
                    DATA_LENGTH, data == null ? 0 : data.length));
        }
        
        EelinkAlarmRequest request = new EelinkAlarmRequest();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        
        // 解析报警时间（6字节）
        int year = 2000 + (buffer.get() & 0xFF);  // 年份基于2000
        int month = buffer.get() & 0xFF;
        int day = buffer.get() & 0xFF;
        int hour = buffer.get() & 0xFF;
        int minute = buffer.get() & 0xFF;
        int second = buffer.get() & 0xFF;
        request.alarmTime = LocalDateTime.of(year, month, day, hour, minute, second);
        
        // 解析报警类型（1字节）
        request.alarmType = buffer.get();
        
        // 解析设备地址（4字节，低位在前）
        request.alarmDeviceAddress = new byte[4];
        buffer.get(request.alarmDeviceAddress);
        
        // 解析报警数据（16字节）
        request.alarmData = new byte[16];
        buffer.get(request.alarmData);

        request.parseAlarmDetail();
        
        return request;
    }
    
    /**
     * 获取报警类型名称
     */
    public String getAlarmTypeName() {
        switch (alarmType) {
            case ALARM_TYPE_CHANNEL_VALUE:
                return "通道数据值报警";
            case ALARM_TYPE_SWITCH:
                return "开关量报警";
            case ALARM_TYPE_DEVICE_RUNNING:
                return "设备运行报警";
            case ALARM_TYPE_POWER_SUPPLY:
                return "供电报警";
            case ALARM_TYPE_CONTROL_OUTPUT:
                return "控制输出报警";
            case ALARM_TYPE_EXTERNAL_EVENT:
                return "外部事件报警";
            default:
                return "未知类型(0x" + String.format("%02X", alarmType) + ")";
        }
    }
    
    /**
     * 获取报警设备地址字符串
     */
    public String getAlarmDeviceAddressString() {
        return EelinkProtocolConfig.bytesToHex(alarmDeviceAddress);
    }

    /**
     * 解析警情详细信息
     */
    private String parseAlarmDetail() {
        switch (getAlarmType()) {
            case EelinkAlarmRequest.ALARM_TYPE_CHANNEL_VALUE:
                return parseChannelValueAlarmData();
            case EelinkAlarmRequest.ALARM_TYPE_SWITCH:
                return parseSwitchAlarmData();
            case EelinkAlarmRequest.ALARM_TYPE_DEVICE_RUNNING:
                return parseDeviceRunningAlarmData();
            case EelinkAlarmRequest.ALARM_TYPE_POWER_SUPPLY:
                return parsePowerSupplyAlarmData();
            case EelinkAlarmRequest.ALARM_TYPE_CONTROL_OUTPUT:
                return parseControlOutputAlarmData();
            case EelinkAlarmRequest.ALARM_TYPE_EXTERNAL_EVENT:
                return parseExternalEventAlarmData();
            default:
                return "未知报警类型";
        }
    }
    
    /**
     * 解析开关量报警数据（报警类型为0x02时）
     * 
     * @return 包含通道号、报警事件、开关状态的字符串
     */
    public String parseSwitchAlarmData() {
        if (alarmType != ALARM_TYPE_SWITCH || alarmData == null || alarmData.length < 6) {
            return "N/A";
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(alarmData).order(ByteOrder.LITTLE_ENDIAN);
        byte channelNo = buffer.get();
        byte alarmEvent = buffer.get();
        int switchStatus = buffer.order(ByteOrder.BIG_ENDIAN).getInt();
        // 开关量状态
        // 个、十位 控阀通道1、2开关状态(1通 0断)
        // 百、千位 控阀通道1、2接线状态(1通 0断)
        // 万、十万位控阀通道1、2命令状态（3正向 4反向）
        // int valve1Status = (switchStatus % 10000) / 100;
        // int valve2Status = (switchStatus % 10000) / 100;
        // int valve1LineStatus = (switchStatus % 10000) / 100;
        // int valve2LineStatus = (switchStatus % 10000) / 100;
        // int valve1CommandStatus = (switchStatus % 10000) / 100;
        // int valve2CommandStatus = (switchStatus % 10000) / 100;

        String eventName = getSwitchAlarmEventName(alarmEvent);
        
        return String.format("通道号=%d, 事件=%s, 状态=%08d", channelNo, eventName, switchStatus);
    }
    
    /**
     * 解析通道数据值报警（报警类型为0x01时）
     */
    public String parseChannelValueAlarmData() {
        if (alarmType != ALARM_TYPE_CHANNEL_VALUE || alarmData == null || alarmData.length < 13) {
            return "N/A";
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(alarmData).order(ByteOrder.LITTLE_ENDIAN);
        int channelNo = buffer.get() & 0xFF;
        int alarmEvent = buffer.get() & 0xFF;
        float channelValue = buffer.getFloat();
        float changeValue = buffer.getFloat();
        float limitValue = buffer.getFloat();
        
        String eventName = getChannelValueAlarmEventName(alarmEvent);
        
        return String.format("通道号=%d, 事件=%s, 当前值=%.2f, 变化值=%.2f, 限值=%.2f", 
                channelNo, eventName, channelValue, changeValue, limitValue);
    }
    
    /**
     * 解析供电报警数据（报警类型为0x04时）
     */
    public String parsePowerSupplyAlarmData() {
        if (alarmType != ALARM_TYPE_POWER_SUPPLY || alarmData == null || alarmData.length < 5) {
            return "N/A";
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(alarmData).order(ByteOrder.BIG_ENDIAN);  // 电压值使用大端序
        int alarmEvent = buffer.get() & 0xFF;
        float alarmValue = buffer.getFloat();
        
        String eventName = getPowerSupplyAlarmEventName(alarmEvent);
        
        return String.format("事件=%s, 数值=%.2f", eventName, alarmValue);
    }
    
    /**
     * 解析设备运行报警数据（报警类型为0x03时）
     */
    public String parseDeviceRunningAlarmData() {
        if (alarmType != ALARM_TYPE_DEVICE_RUNNING || alarmData == null || alarmData.length < 11) {
            return "N/A";
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(alarmData).order(ByteOrder.LITTLE_ENDIAN);
        int alarmEvent = buffer.get() & 0xFF;
        long deviceAddr = buffer.getInt() & 0xFFFFFFFFL;
        
        // 解析设备时钟（6字节）
        int year = 2000 + (buffer.get() & 0xFF);
        int month = buffer.get() & 0xFF;
        int day = buffer.get() & 0xFF;
        int hour = buffer.get() & 0xFF;
        int minute = buffer.get() & 0xFF;
        int second = buffer.get() & 0xFF;
        
        String eventName = getDeviceRunningAlarmEventName(alarmEvent);
        String deviceTime = String.format("%04d-%02d-%02d %02d:%02d:%02d", 
                year, month, day, hour, minute, second);
        
        return String.format("事件=%s, 设备地址=%08X, 设备时钟=%s", eventName, deviceAddr, deviceTime);
    }
    
    /**
     * 解析外部事件报警数据（报警类型为0x06时）
     */
    public String parseExternalEventAlarmData() {
        if (alarmType != ALARM_TYPE_EXTERNAL_EVENT || alarmData == null || alarmData.length < 3) {
            return "N/A";
        }
        
        int alarmEvent = alarmData[0] & 0xFF;
        int sequenceNo = alarmData[1] & 0xFF;
        int currentStatus = alarmData[2] & 0xFF;
        
        String statusName = currentStatus == 0 ? "断" : "通";
        
        return String.format("事件=%d, 序号=%d, 状态=%s", alarmEvent, sequenceNo, statusName);
    }
    
    /**
     * 解析控制输出报警数据（报警类型为0x05时）
     */
    public String parseControlOutputAlarmData() {
        if (alarmType != ALARM_TYPE_CONTROL_OUTPUT || alarmData == null || alarmData.length < 2) {
            return "N/A";
        }
        
        int alarmEvent = alarmData[0] & 0xFF;
        int abnormalStatus = alarmData[1] & 0xFF;
        
        String eventName = getControlOutputAlarmEventName(alarmEvent);
        String statusName = getAbnormalStatusName(abnormalStatus);
        
        return String.format("事件=%s, 异常状态=%s", eventName, statusName);
    }
    
    // 辅助方法：获取事件名称
    
    private String getSwitchAlarmEventName(int event) {
        switch (event) {
            case 1: return "断";
            case 2: return "通";
            case 3: return "通-断";
            case 4: return "断-通";
            case 5: return "无人-有人";
            case 6: return "有人-无人";
            case 11: return "通报警解除";
            case 12: return "断报警解除";
            default: return "未知(" + event + ")";
        }
    }
    
    private String getChannelValueAlarmEventName(int event) {
        switch (event) {
            case 1: return "数据值超过上限";
            case 2: return "数据值低于下限";
            case 3: return "数据值变化超限";
            case 11: return "数据值超过上限报警解除";
            case 12: return "数据值低于下限报警解除";
            case 13: return "数据值变化超限报警解除";
            default: return "未知(" + event + ")";
        }
    }
    
    private String getPowerSupplyAlarmEventName(int event) {
        if (event >= 100) {
            return getPowerSupplyAlarmEventName(event - 100) + "(解除)";
        }
        switch (event) {
            case 1: return "市电断电";
            case 2: return "电池欠压";
            case 10: return "A相失压";
            case 11: return "B相失压";
            case 12: return "C相失压";
            case 20: return "A相过压";
            case 21: return "B相过压";
            case 22: return "C相过压";
            case 30: return "A相过流";
            case 31: return "B相过流";
            case 32: return "C相过流";
            default: return "未知(" + event + ")";
        }
    }
    
    private String getDeviceRunningAlarmEventName(int event) {
        switch (event) {
            case 1: return "门开关打开";
            case 11: return "门开关闭合";
            case 2: return "叶设备时钟异常";
            default: return "未知(" + event + ")";
        }
    }
    
    private String getControlOutputAlarmEventName(int event) {
        switch (event) {
            case 1: return "阀门控制异常";
            case 2: return "水泵控制异常";
            case 3: return "控制计量异常";
            case 4: return "卷帘控制异常";
            case 5: return "卷膜控制异常";
            default: return "未知(" + event + ")";
        }
    }
    
    private String getAbnormalStatusName(int status) {
        switch (status) {
            case 1: return "水位上升速度过快";
            case 2: return "水位上升速度过慢";
            case 3: return "压力超过上限";
            case 4: return "压力低于下限";
            case 6: return "连续启动";
            case 7: return "连续停止";
            case 8: return "水位异常上升";
            case 9: return "水位异常下降";
            case 10: return "电量计量异常";
            case 11: return "水量计量异常";
            case 20: return "最大开度";
            case 21: return "最小开度";
            default: return "未知(" + status + ")";
        }
    }

    /**
     * 根据报警类型获取报警严重程度
     */
    private String getAlarmSeverity(byte alarmType) {
        switch (alarmType) {
            case EelinkAlarmRequest.ALARM_TYPE_CHANNEL_VALUE:
                return "WARNING";
            case EelinkAlarmRequest.ALARM_TYPE_SWITCH:
                return "INFO";
            case EelinkAlarmRequest.ALARM_TYPE_DEVICE_RUNNING:
                return "WARNING";
            case EelinkAlarmRequest.ALARM_TYPE_POWER_SUPPLY:
                return "CRITICAL";
            case EelinkAlarmRequest.ALARM_TYPE_CONTROL_OUTPUT:
                return "MAJOR";
            case EelinkAlarmRequest.ALARM_TYPE_EXTERNAL_EVENT:
                return "INFO";
            default:
                return "UNKNOWN";
        }
    }
}

