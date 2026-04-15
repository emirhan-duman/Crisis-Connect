package com.auralis.crisisconnect.data

import androidx.room.TypeConverter
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult

object Converters {
    @TypeConverter
    @JvmStatic
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    @JvmStatic
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)

    @TypeConverter
    @JvmStatic
    fun fromMessageDeliveryStatus(value: MessageDeliveryStatus?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun toMessageDeliveryStatus(value: String?): MessageDeliveryStatus? =
        value?.let { MessageDeliveryStatus.valueOf(it) }

    @TypeConverter
    @JvmStatic
    fun fromCallDirection(value: CallDirection): String = value.name

    @TypeConverter
    @JvmStatic
    fun toCallDirection(value: String): CallDirection = CallDirection.valueOf(value)

    @TypeConverter
    @JvmStatic
    fun fromCallResult(value: CallResult): String = value.name

    @TypeConverter
    @JvmStatic
    fun toCallResult(value: String): CallResult = CallResult.valueOf(value)
}
