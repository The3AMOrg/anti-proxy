# High-Demand Throughput, BLE Physics, and Slot Scheduling

## 1. Executive Summary & Problem Formulation
In a classroom of **150 students**, all attempting attendance simultaneously when a professor starts a session, naive BLE architectures experience catastrophic failure:
1. **Radio Contention**: 150 phones attempting simultaneous BLE scans and GATT connections saturate the 3 BLE advertising channels (Channels 37, 38, 39) and cause high packet drop rates.
2. **Peripheral Hardware Limit**: A mobile phone acting as a BLE GATT Server (the Authority/Professor's phone) can maintain **at most 4 to 7 concurrent active central connections** in its Bluetooth controller (Qualcomm, Broadcom, or MediaTek chipsets).
3. **Queue Starvation**: If a single verification handshake takes ~1.5 seconds, 7 concurrent channels can theoretically clear at most:
   $$\text{Throughput} = \frac{7 \text{ channels}}{1.5 \text{ seconds}} \approx 4.67 \text{ students/second}$$
   If 150 students try to connect in a 20-second window, the incoming demand rate is $7.5 \text{ students/sec}$, exceeding maximum capacity by 60%. Excess connection requests are dropped by the Bluetooth daemon (`GATT_CONN_FAIL` / status `133`).

---

## 2. RF Physics and Hardware Constraints

### 2.1 The 4–7 Connection Channel Bottleneck
When an Android device opens a `BluetoothGattServer`, its underlying Link Layer allocates hardware state machines (connection handles) for each connected Central (student).
- **Physical limit**: Most modern Android smartphones (Pixel, Samsung Galaxy, OnePlus) support a maximum of **7 active GATT connections**. Some budget chipsets support as few as **4**.
- **The "Zombie Channel" problem**: If a student connects, reads the session characteristic, but experiences an RF packet drop or app freeze before writing the proof envelope, the connection handle remains occupied until the Link Layer supervision timeout expires (typically **20 seconds**). During this time, that connection channel is dead to other students.

### 2.2 Airtime and Transaction Hold Time
A complete AntiProxy student verification transaction consists of:
1. **BLE Advertising Scan & Connection Initiation**: ~150–300 ms
2. **ATT MTU Negotiation & Service Discovery**: ~100–200 ms
3. **Session Characteristic Read**: ~50–100 ms
4. **Local Hardware Keystore Signing (StrongBox/TEE)**: ~150–350 ms
5. **ATT Write (Fragmented Proof Envelope ~187 bytes)**: ~150–250 ms
6. **Server Verification & SQLite WAL Commit**: ~20–50 ms
7. **Status Indication & Confirmation Handshake**: ~50–100 ms
8. **Disconnection and Channel Tear-down**: ~50–100 ms

$$\text{Total Channel Occupancy Time } (T_{\text{hold}}) \approx 1.2\text{s} - 1.6\text{s}$$

---

## 3. Mathematical Analysis: 20-Second vs. 60-Second Windows

### 3.1 The Failure of the 20-Second Window (`NUM_SLOTS = 200`)
With $N = 150$ students and a window duration $W = 20\text{s}$ divided into 200 slots of $\Delta t = 100\text{ms}$:
- Mean arrival rate: $\lambda = \frac{150}{20} = 7.5 \text{ students/second}$.
- Mean service time per channel: $\mu^{-1} \approx 1.5\text{s}$.
- Maximum system service rate across $c = 6$ hardware channels:
  $$c \cdot \mu = \frac{6}{1.5} = 4.0 \text{ students/second}$$
- Traffic Intensity / Utilization ($\rho$):
  $$\rho = \frac{\lambda}{c \cdot \mu} = \frac{7.5}{4.0} = 1.875 > 1.0$$
Because $\rho > 1.0$, the queue grows unbounded. Connection queues overflow, leading to cascading connection drops (`GATT_ERROR 133`), packet collisions, and complete channel starvation.

### 3.2 The Stabilized 60-Second Window (`NUM_SLOTS = 600`)
By extending the verification window to $W = 60\text{s}$ with $S = 600$ slots:
- Mean arrival rate: $\lambda = \frac{150}{60} = 2.5 \text{ students/second}$.
- System service rate ($c = 6$ channels): $c \cdot \mu = 4.0 \text{ students/second}$.
- Traffic Intensity ($\rho$):
  $$\rho = \frac{2.5}{4.0} = 0.625 < 1.0$$
With $\rho = 0.625$, the system operates in an $M/M/c$ queueing regime well below saturation. The probability of having all 6 channels saturated simultaneously drops exponentially.

```
Incoming Demand vs Channel Service Rate:
---------------------------------------------------------------------------------
20s Window (UNSTABLE):
Demand:  [ 7.5 students/sec ] ====> [Capacity: 4.0/s] ===> OVERFLOW (Dropped 3.5/s)
                                      💥 GATT 133 Errors & System Collapse

60s Window (STABLE):
Demand:  [ 2.5 students/sec ] ====> [Capacity: 4.0/s] ===> HEADROOM (1.5/s buffer)
                                      ✅ Smooth Throughput & Low Latency
---------------------------------------------------------------------------------
```

---

## 4. Remediation Implementation Details

### 4.1 Slot Scheduling Configuration (`Types.kt`)
In `core/src/commonMain/kotlin/antiproxy/core/protocol/Types.kt`:
```kotlin
// Increased from 200 slots (20s) to 600 slots (60s)
const val NUM_SLOTS: Int = 600
const val SLOT_DURATION_MS: Long = 100L
const val DEFAULT_VERIFICATION_WINDOW_SECONDS: Int = 60
```
- Each student uniformly selects a slot $k \in [0, 599]$.
- Connection start time is delayed by $k \times 100\text{ms}$.
- This decouples the 150 concurrent triggers into a smooth, uniformly distributed arrival process over 1 full minute.

### 4.2 Retry Scheduler with Bounded Exponential Backoff & Jitter
When an RF collision or transient disconnect occurs, students must not retry simultaneously (the "thundering herd" problem).
`RetryScheduler` computes:
$$\text{delay\_slots} = \min(2^r, \text{RETRY\_MAX\_SLOTS}) + \text{Uniform}(0, \text{RETRY\_JITTER\_BOUND})$$
Where:
- $r$ is the retry attempt index ($0 \le r < 4$)
- $\text{RETRY\_MAX\_SLOTS} = 32$ (3.2 seconds base cap)
- $\text{RETRY\_JITTER\_BOUND} = 8$ (up to 800 ms random decorrelation)
- $\text{MAX\_RETRY\_COUNT} = 4$ attempts

```
Retry Progression Table:
+-------+-------------------+--------------------+------------------------+
| Retry | Base Slots (2^r)  | Jitter Slots (0..8)| Total Delay Window     |
+-------+-------------------+--------------------+------------------------+
|   0   |   1 (100 ms)      | 0 .. 8 (0..800 ms) | 100 ms .. 900 ms       |
|   1   |   2 (200 ms)      | 0 .. 8 (0..800 ms) | 200 ms .. 1,000 ms     |
|   2   |   4 (400 ms)      | 0 .. 8 (0..800 ms) | 400 ms .. 1,200 ms     |
|   3   |   8 (800 ms)      | 0 .. 8 (0..800 ms) | 800 ms .. 1,600 ms     |
+-------+-------------------+--------------------+------------------------+
```

---

## 5. Intuitive Examples & Scenarios

### Scenario A: The Rush at the Start of Class
- **10:00:00 AM**: Professor presses "Start Attendance Session".
- **Old Behavior**: All 150 phones immediately detect the BLE packet and rush to connect within 5 seconds. The professor's phone accepts 6 connections; the other 144 connections fail immediately with `GATT 133`. Because `StudentFlow` previously did not throw a retryable transport exception on connection failure, all 144 students showed a red "Failed" screen and complained to the professor.
- **New Behavior**:
  - Student A picks slot 42 -> waits $4.2\text{s}$.
  - Student B picks slot 189 -> waits $18.9\text{s}$.
  - Student C picks slot 512 -> waits $51.2\text{s}$.
  - When Student A connects, the handshake finishes in $1.3\text{s}$. The professor's phone records attendance, sends the ACK indication, and actively severs the link at $1.5\text{s}$.
  - The freed connection handle is immediately taken by Student D (slot 15, delayed to $1.5\text{s}$).
  - All 150 students clear smoothly within the 60-second window.

### Scenario B: Transient Radio Interference & Auto-Recovery
- Student 88 connects at $t = 8.8\text{s}$. A burst of 2.4 GHz Wi-Fi traffic drops the MTU exchange packet.
- In `StudentGattClient`, the MTU failure now throws `GattTransportException("MTU request failed: 133")`.
- `StudentFlow` catches `GattTransportException`, closes the corrupted client, instantiates a fresh `StudentGattClient`, checks `RetryScheduler.computeDelayMs(retryCount = 0)`, delays for 400 ms (base 100ms + 300ms jitter), and reconnects at $t = 9.2\text{s}$.
- The second attempt succeeds without user intervention.
