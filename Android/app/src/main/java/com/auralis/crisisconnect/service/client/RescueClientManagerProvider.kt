package com.auralis.crisisconnect.service.client

interface RescueClientManagerProvider {
    fun getManager(): BleClientManager
}
