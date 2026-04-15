package com.auralis.crisisconnect.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build

@SuppressLint("MissingPermission")
fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = value
            writeCharacteristic(characteristic)
        }
    }
}

@SuppressLint("MissingPermission")
fun BluetoothGatt.readCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic
): Boolean {
    @Suppress("DEPRECATION")
    return readCharacteristic(characteristic) // boolean
}
