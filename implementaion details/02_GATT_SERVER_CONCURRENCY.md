# GATT Server Concurrency, Channel Recycling, and Binder Pipeline

## 1. The Binder IPC Architecture Problem

### 1.1 How Android Handles GATT Events
On Android, the Bluetooth stack runs in a separate system process (`com.android.bluetooth`). When an event occurs (e.g. a student writes an ATT characteristic packet), the Bluetooth daemon makes an IPC call across the Linux Binder driver into the application's process.

```
+------------------------------------+
| Bluetooth Stack Daemon (Fluoride)  |
+------------------------------------+
                 |
        [ Binder IPC Thread ]
                 |
                 v
+------------------------------------+
| GattServerCallback (App Process)   |
|  - onCharacteristicWriteRequest   |
+------------------------------------+
```

### 1.2 The Bottleneck in the Old Implementation
Previously, `GattServerCallback.onCharacteristicWriteRequest` handled proof fragment reassembly and then **synchronously executed**:
1. Full envelope decoding (`decodeProofEnvelope`)
2. 13-step cryptographic verification (`validateProofEnvelope`), which includes:
   - SHA-256 hash calculations
   - SECP256R1 ECDSA public key signature verification (`verifier.verify()`)
3. Synchronous SQLite WAL disk transaction (`authorityStore.commitAttendance(record, syncEvent)`)
4. Status indication dispatch

**Consequence**: The Bluetooth IPC Binder thread was frozen for 30–80 milliseconds per student. While the Binder thread was locked:
- Incoming packets from other connected students could not be processed.
- New connection attempts timed out at the Link Layer.
- Android system watchdog warnings were triggered under peak burst load.

---

## 2. Remediation 1: Non-Blocking Background Pipeline

### Implementation (`GattServerCallback.kt`)
In the updated implementation, `GattServerCallback` only performs lightweight reassembly on the Binder thread. As soon as all fragments arrive, the complete envelope is dispatched to an asynchronous, supervised coroutine on `Dispatchers.IO`:

```kotlin
// In GattServerCallback.kt:
private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

when (val result = reassembler.feed(fragment)) {
    is ReassemblyResult.Complete -> {
        reassemblers.remove(device.address)
        val fullData = result.payload
        // Offload heavy ECDSA verification and DB WAL commits to IO thread pool:
        callbackScope.launch {
            onProofReceived.onProofReceived(device, fullData)
        }
    }
    // ...
}
```

**Result**: The Binder thread returns in under **0.5 milliseconds**, immediately freeing the IPC channel to acknowledge incoming ATT packets from other students.

---

## 3. Remediation 2: Channel Reclamation & Zombie Connection Reaper

### 3.1 Active Disconnection After 200 ms Grace Period
Android will not automatically disconnect a GATT central after an exchange. If the student client does not close the connection immediately, the channel remains occupied.

In `AuthorityFlow.kt`:
```kotlin
private fun disconnectAfterAck(device: BluetoothDevice) {
    val scope = sessionScope
    if (scope != null) {
        scope.launch {
            delay(200L) // Allow 200ms for status indication ATT confirmation over air
            advertiser?.disconnectClient(device)
        }
    } else {
        advertiser?.disconnectClient(device)
    }
}
```
**Why a 200 ms delay?**
The Status characteristic uses BLE **Indications** (not Notifications). An Indication requires an application-layer confirmation packet (`ATT_HANDLE_VALUE_CONF`) from the client phone. If we severed the connection at 0 ms, the indication confirmation would fail, and the student's phone would report a communication error despite attendance having been recorded. A 200 ms grace period provides ample time for the over-the-air confirmation round-trip while recycling the channel in under a fraction of a second.

### 3.2 Periodic Zombie Connection Reaper
If a student's phone loses battery, crashes, or walks out of range mid-handshake, the connection handle becomes a "zombie" connection. Left unhandled, 5–7 zombie connections completely incapacitate the GATT server.

In `AuthorityFlow.kt` and `GattServerCallback.kt`:
```kotlin
// In AuthorityFlow.kt startSession():
scope.launch {
    while (isActive) {
        delay(2000L)
        adv.cleanupStaleConnections(5000L)
    }
}

// In GattServerCallback.kt:
fun cleanupStaleConnections(timeoutMs: Long = 5_000L) {
    val now = System.currentTimeMillis()
    connectedDevices.forEach { (address, device) ->
        val connectedAt = connectionTimestamps[address] ?: now
        if (now - connectedAt > timeoutMs) {
            disconnectClient(device) // cancelConnection(device)
        }
    }
    cleanupStaleReassemblers()
}
```
Every 2 seconds, any connection older than 5 seconds is forcefully aborted via `BluetoothGattServer.cancelConnection(device)`, freeing hardware handles for incoming students.

---

## 4. Remediation 3: Advertising & Service Addition Synchronization

### 4.1 The Race Condition
`BluetoothGattServer.addService(service)` is an **asynchronous operation**. It requests the Fluoride Bluetooth daemon to register the GATT table and returns immediately. The service is only fully ready when the daemon invokes `BluetoothGattServerCallback.onServiceAdded(status, service)`.

If `BluetoothLeAdvertiser.startAdvertising()` is called before `onServiceAdded` fires:
1. Students in close physical proximity immediately scan the advertisement and initiate a GATT connection.
2. The student client calls `discoverServices()`.
3. The Bluetooth daemon has not yet finished registering the AntiProxy service.
4. The student discovers 0 services or fails with `GATT_INSUFFICIENT_RESOURCES`.

### 4.2 The Fix: `CompletableDeferred` Synchronization
In `AuthorityAdvertiser.kt`:
```kotlin
val serviceAddedDeferred = CompletableDeferred<Boolean>()
serverCallback.onServiceAddedListener = { status, _ ->
    if (status == BluetoothGatt.GATT_SUCCESS) {
        serviceAddedDeferred.complete(true)
    } else {
        serviceAddedDeferred.complete(false)
    }
}

server.addService(service)

// Wait up to 5 seconds for GATT service registration before advertising:
val serviceAdded = withTimeoutOrNull(5000L) {
    serviceAddedDeferred.await()
} ?: false

if (!serviceAdded) {
    server.close()
    error("Failed to add GATT service (timeout or GATT error)")
}

// NOW it is safe to start advertising:
adv.startAdvertising(settings, data, advertiseCallback)
```

---

## 5. Remediation 4: Thread-Safe Indications (API 33+)

### 5.1 Characteristic Clobbering
In legacy Android BLE API (`notifyCharacteristicChanged(device, characteristic, confirm)`):
- The characteristic's shared buffer (`characteristic.value = data`) is updated in-place before notification.
- When two students finish verification within milliseconds of each other, Student A's status could be overwritten by Student B's status before Student A's packet was transmitted.

### 5.2 Modern API 33+ with Lock Protection
In `GattServerCallback.kt`:
```kotlin
private val indicationLock = Any()

fun sendStatusIndication(device: BluetoothDevice, status: StatusCode): Boolean {
    val server = gattServer ?: return false
    val char = statusCharacteristic ?: return false
    val payload = byteArrayOf(status.code)

    synchronized(indicationLock) {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): thread-safe value passed directly in parameter
            server.notifyCharacteristicChanged(device, char, true, payload) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, char, true)
        }
    }
}
```

---

## 6. Remediation 5: Transfer ID Randomization & Retry Client Lifecycle

### 6.1 Transfer ID Randomization
BLE ATT fragmentation relies on a 1-byte `transferId` in the fragment header:
```
[transferId (1B)] [index (1B)] [total (1B)] [payload (...)]
```
If `transferId` is always `0`, and a student retries after dropping fragment 2 of 3:
- The server may match the retry fragments with the previous attempt's fragments in memory.
- In `StudentGattClient.kt`, each transfer now generates a cryptographically random transfer ID:
```kotlin
val transferId = secureRandom.nextInt(256).toByte()
val fragments = Fragmenter.fragment(envelopeData, maxPayloadSize, transferId)
```

### 6.2 Fresh Client on Each Retry
In `StudentFlow.kt`:
- Previously, the same `StudentGattClient` instance was reused across retries. If the underlying `BluetoothGatt` state was corrupted or hung in `STATE_CONNECTING`, all subsequent attempts instantly failed.
- The new flow closes and re-instantiates a brand new `StudentGattClient(context)` on every attempt, ensuring clean Android BLE Binder state.
