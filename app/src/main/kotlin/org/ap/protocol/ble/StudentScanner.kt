package org.ap.protocol.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import antiproxy.core.protocol.MIN_RSSI_SAMPLES
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Metadata for a discovered Authority advertisement, including pre-connection RSSI samples.
 */
data class DiscoveredAuthority(
    val deviceAddress: String,
    val initialRssi: Int,
    val rssiSamples: List<Int>,
    val scanRecord: ByteArray?
)

/**
 * High-performance scanner that discovers an Authority and pre-collects RSSI samples
 * during the passive broadcast phase, avoiding lengthy on-connection RSSI polling.
 */
suspend fun scanForAuthority(
    context: Context,
    beaconIds: List<ByteArray>,
    requiredSamples: Int = MIN_RSSI_SAMPLES,
    timeoutMs: Long = 10_000L
): DiscoveredAuthority? {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = bluetoothManager.adapter ?: return null
    if (!adapter.isEnabled) return null

    val scanner = adapter.bluetoothLeScanner ?: return null

    val filter = ScanFilter.Builder()
        .setServiceUuid(SERVICE_PARCEL_UUID)
        .build()

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val collectedSamples = mutableListOf<Int>()
            var targetAddress: String? = null
            var lastRecord: ByteArray? = null

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val address = result.device.address
                    // Every teacher shares the service UUID; lock on only once the scan response names our teacher.
                    // After that, plain adverts from the same address count too, so sampling stays fast.
                    if (targetAddress == null) {
                        val id = result.scanRecord?.getServiceData(SERVICE_PARCEL_UUID) ?: return
                        if (beaconIds.none { it.contentEquals(id) }) return
                        targetAddress = address
                    }

                    if (address == targetAddress) {
                        collectedSamples.add(result.rssi)
                        lastRecord = result.scanRecord?.bytes

                        if (collectedSamples.size >= requiredSamples) {
                            try {
                                scanner.stopScan(this)
                            } catch (_: Exception) { }

                            if (cont.isActive) {
                                cont.resume(
                                    DiscoveredAuthority(
                                        deviceAddress = address,
                                        initialRssi = collectedSamples.first(),
                                        rssiSamples = collectedSamples.toList(),
                                        scanRecord = lastRecord
                                    )
                                )
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            }

            try {
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }

            cont.invokeOnCancellation {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) { }
            }
        }
    }
}
