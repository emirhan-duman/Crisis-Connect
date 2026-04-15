package com.auralis.crisisconnect.service.scan

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.util.LinkedHashMap

/**
 * Process-wide BLE scan coordinator.
 * Keeps a single hardware scan active and multiplexes results to request owners.
 */
object BleScanCoordinator {

    interface Listener {
        fun onScanResult(callbackType: Int, result: ScanResult)
        fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        fun onScanFailed(errorCode: Int)
    }

    private data class Request(
        val owner: String,
        val scanner: BluetoothLeScanner,
        val mode: Int,
        val filters: List<ScanFilter>?,
        val listener: Listener,
        val paused: Boolean,
    )

    private data class ActiveConfig(
        val scanner: BluetoothLeScanner,
        val mode: Int,
        val filterSignature: String,
    )

    private val lock = Any()
    private val requests = LinkedHashMap<String, Request>()
    private var callback: ScanCallback? = null
    private var activeConfig: ActiveConfig? = null

    fun registerOrUpdate(
        owner: String,
        scanner: BluetoothLeScanner,
        mode: Int,
        filters: List<ScanFilter>?,
        listener: Listener
    ): Boolean {
        val normalizedOwner = owner.trim()
        if (normalizedOwner.isEmpty()) {
            return false
        }
        synchronized(lock) {
            requests[normalizedOwner] = Request(
                owner = normalizedOwner,
                scanner = scanner,
                mode = mode,
                filters = filters,
                listener = listener,
                paused = false
            )
            return rebuildLocked()
        }
    }

    fun setPaused(owner: String, paused: Boolean): Boolean {
        val normalizedOwner = owner.trim()
        if (normalizedOwner.isEmpty()) {
            return false
        }
        synchronized(lock) {
            val current = requests[normalizedOwner] ?: return false
            if (current.paused == paused) {
                return true
            }
            requests[normalizedOwner] = current.copy(paused = paused)
            return rebuildLocked()
        }
    }

    fun unregister(owner: String) {
        val normalizedOwner = owner.trim()
        if (normalizedOwner.isEmpty()) {
            return
        }
        synchronized(lock) {
            requests.remove(normalizedOwner)
            rebuildLocked()
        }
    }

    fun isActive(owner: String): Boolean {
        val normalizedOwner = owner.trim()
        if (normalizedOwner.isEmpty()) {
            return false
        }
        synchronized(lock) {
            val request = requests[normalizedOwner] ?: return false
            return !request.paused && callback != null
        }
    }

    @SuppressLint("MissingPermission")
    private fun rebuildLocked(): Boolean {
        val activeRequests = requests.values.filter { !it.paused }
        if (activeRequests.isEmpty()) {
            stopScanLocked()
            return true
        }

        val scanner = activeRequests.first().scanner
        val mode = activeRequests.maxByOrNull { scanModeRank(it.mode) }?.mode
            ?: ScanSettings.SCAN_MODE_BALANCED
        val mergedFilters = mergeFilters(activeRequests)
        val filterSignature = filterSignature(mergedFilters)
        val config = activeConfig
        val shouldReuse = config != null &&
            config.scanner === scanner &&
            config.mode == mode &&
            config.filterSignature == filterSignature &&
            callback != null
        if (shouldReuse) {
            return true
        }

        stopScanLocked()

        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val listeners = synchronized(lock) {
                    requests.values
                        .filter { !it.paused && matchesAnyFilter(it.filters, result) }
                        .map { it.listener }
                }
                listeners.forEach { it.onScanResult(callbackType, result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val listenersByRequest = synchronized(lock) {
                    requests.values
                        .filter { !it.paused }
                        .map { request ->
                            request.listener to results.filter { result ->
                                matchesAnyFilter(request.filters, result)
                            }
                        }
                        .filter { it.second.isNotEmpty() }
                }
                listenersByRequest.forEach { (listener, filtered) ->
                    listener.onBatchScanResults(filtered)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val listeners = synchronized(lock) {
                    requests.values
                        .filter { !it.paused }
                        .map { it.listener }
                }
                listeners.forEach { it.onScanFailed(errorCode) }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .build()
        return runCatching {
            scanner.startScan(mergedFilters, settings, newCallback)
            callback = newCallback
            activeConfig = ActiveConfig(
                scanner = scanner,
                mode = mode,
                filterSignature = filterSignature
            )
            true
        }.getOrElse {
            val listeners = activeRequests.map { it.listener }
            listeners.forEach { it.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR) }
            callback = null
            activeConfig = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanLocked() {
        val existingCallback = callback
        val scanner = activeConfig?.scanner
        if (existingCallback != null && scanner != null) {
            runCatching { scanner.stopScan(existingCallback) }
        }
        callback = null
        activeConfig = null
    }

    private fun mergeFilters(activeRequests: List<Request>): List<ScanFilter>? {
        val hasUnfiltered = activeRequests.any { it.filters.isNullOrEmpty() }
        if (hasUnfiltered) {
            return null
        }
        return activeRequests
            .asSequence()
            .flatMap { it.filters.orEmpty().asSequence() }
            .distinctBy { it.toString() }
            .toList()
            .ifEmpty { null }
    }

    private fun filterSignature(filters: List<ScanFilter>?): String {
        if (filters.isNullOrEmpty()) {
            return "ALL"
        }
        return filters.joinToString(separator = "|") { it.toString() }
    }

    private fun scanModeRank(mode: Int): Int {
        return when (mode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY -> 3
            ScanSettings.SCAN_MODE_BALANCED -> 2
            ScanSettings.SCAN_MODE_LOW_POWER -> 1
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> 0
            else -> 1
        }
    }

    private fun matchesAnyFilter(filters: List<ScanFilter>?, result: ScanResult): Boolean {
        if (filters.isNullOrEmpty()) {
            return true
        }
        return filters.any { filter -> matchesFilter(filter, result) }
    }

    private fun matchesFilter(filter: ScanFilter, result: ScanResult): Boolean {
        val deviceAddress = filter.deviceAddress
        if (deviceAddress != null) {
            val resultAddress = result.device?.address ?: return false
            if (!deviceAddress.equals(resultAddress, ignoreCase = true)) {
                return false
            }
        }

        val serviceUuid = filter.serviceUuid
        if (serviceUuid != null) {
            val resultUuids = result.scanRecord?.serviceUuids ?: return false
            val mask = filter.serviceUuidMask
            val matches = resultUuids.any { candidate ->
                matchesServiceUuid(serviceUuid, mask, candidate)
            }
            if (!matches) {
                return false
            }
        }
        return true
    }

    private fun matchesServiceUuid(
        expected: ParcelUuid,
        expectedMask: ParcelUuid?,
        candidate: ParcelUuid
    ): Boolean {
        if (expectedMask == null) {
            return expected.uuid == candidate.uuid
        }
        val maskMsb = expectedMask.uuid.mostSignificantBits
        val maskLsb = expectedMask.uuid.leastSignificantBits
        return (expected.uuid.mostSignificantBits and maskMsb) ==
            (candidate.uuid.mostSignificantBits and maskMsb) &&
            (expected.uuid.leastSignificantBits and maskLsb) ==
            (candidate.uuid.leastSignificantBits and maskLsb)
    }
}
