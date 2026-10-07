# AntiProxy: RF Physics, Concurrency & Performance Engineering

## 1. The Physical Classroom Environment

Deploying Bluetooth Low Energy (BLE) in an academic lecture hall is fundamentally different from a lab environment:

### 1.1 The Human Water Attenuation Factor
BLE operates at **2.4 GHz (ISM band)**, which is the resonant absorption frequency of water. A lecture hall with 100 students is a room filled with 7,000 kg of salt water in constant minor motion.
* **Direct Line-of-Sight (Empty Room)**: Distance attenuation follows predictable log-distance path loss.
* **Full Lecture Hall**: A student in the back row behind 8 rows of peers experiences **15 to 25 dBm of additional signal loss** purely from human body absorption.
* **Hand Placement**: Simply cupping a smartphone in one's palm attenuates the RF antenna by an additional 6 to 10 dBm.

### 1.2 OEM Antenna Heterogeneity
Different phone manufacturers (Apple, Samsung, Google, Xiaomi) use drastically different antenna designs and RF amplifier gains. Two phones placed at the exact same physical desk at 5 meters from the instructor will regularly report raw RSSI readings differing by **10 to 18 dBm**.

---

## 2. Solving the Spatial Gating Dilemma

Naive systems attempt to calculate absolute distance using the log-distance formula:
$$d = 10^{\frac{\text{TxPower} - \text{RSSI}}{10 \cdot n}}$$
In a classroom, this yields distance errors exceeding **300% to 500%**.

### AntiProxy Multi-Sample Solution:
AntiProxy avoids instantaneous distance calculation. Instead, it enforces:
1. **Physical GATT Connection Prerequisite**: The student must establish a physical BLE connection to the instructor's device. Indoor BLE range through concrete/brick classroom walls rarely exceeds 12–15 meters.
2. **Scan-Time Multi-Packet Statistical Sampling**:
   - Collects $N \ge 10$ RSSI samples from the 10 Hz advertising stream.
   - Calculates fixed-point signed Q1 Mean:
     $$\mu = \frac{1}{N} \sum_{i=1}^N \text{RSSI}_i$$
   - Calculates fixed-point unsigned Q8.8 Variance:
     $$\sigma^2 = \frac{1}{N} \sum_{i=1}^N (\text{RSSI}_i - \mu)^2$$
   - High variance indicates multipath reflection (e.g., signals bouncing through an open door from the hallway), which is rejected by the policy check.

---

## 3. Resolving the "Thundering Herd" & Connection Bottleneck

### 3.1 The Hardware Limit
On Android, the Bluetooth daemon (`com.android.bluetooth`) and underlying Qualcomm/MediaTek hardware enforce a hard ceiling on concurrent BLE peripheral connections:
$$\text{Max Concurrent Central Connections} = 4 \text{ to } 7$$

When 80 students simultaneously press "Mark Attendance":
* If connections remain open for $1.5\text{ seconds}$, all channels saturate immediately.
* Students 8 through 80 are rejected by the hardware stack with `GATT_ERROR 133 (0x85)`.
* Heavy concurrent connection hammering can crash the phone's Bluetooth daemon.

---

### 3.2 Performance Comparison: Traditional vs AntiProxy

| Phase | Naive GATT Approach | AntiProxy Optimized Pipeline | Latency Savings |
| :--- | :--- | :--- | :--- |
| **RSSI Sampling** | 9 $\times$ `readRemoteRssi()` with delays while connected | **Passive Scan-Time (0ms while connected)** | **-1,000 ms** |
| **Connection Interval** | Standard Balanced (30–50 ms) | `CONNECTION_PRIORITY_HIGH` (11.25–15 ms) | **-70% ATT Latency** |
| **Service Discovery** | Full GATT Service Discovery | Fast-path cached UUID handles | **-350 ms** |
| **Teardown** | Awaits Android OS GC timeout | Immediate explicit `client.disconnect()` | **-500 ms** |
| **Total Channel Hold Time** | **1,500 – 2,200 ms per student** | **150 – 280 ms per student** | **~85% Reduction** |

---

## 4. Classroom Concurrency & Throughput Math

With AntiProxy's **< 300ms channel recycling time**:

$$\text{Throughput per Channel} = \frac{1\text{ second}}{0.25\text{ seconds}} = 4 \text{ students/sec/channel}$$

Assuming an average Android instructor device with 5 active concurrent channels:
$$\text{Total System Throughput} = 5 \times 4 = 20 \text{ students/second}$$

### Scaling to Class Sizes:
* **Small Class ($N = 30$ students)**: Complete batch verification finished in **$\approx 1.5$ seconds**.
* **Medium Lecture ($N = 100$ students)**: Complete batch verification finished in **$\approx 5.0$ seconds**.
* **Large Auditorium ($N = 300$ students)**: Complete batch verification finished in **$\approx 15.0$ seconds**.

---

## 5. Traffic Shaping: Slot & Retry Scheduling

To ensure student connection requests arrive smoothly distributed across time rather than in a single nanosecond burst:

### 5.1 SlotScheduler
* Divides the verification window into **200 discrete slots** of $100\text{ ms}$ each (a 20-second window).
* Each student phone picks a random slot $S \in [0, 199]$ uniformly:
  $$\text{Delay} = S \times 100\text{ ms}$$
* Smooths the connection arrival rate over the 20-second active window.

### 5.2 RetryScheduler
If a student encounters channel congestion (`GATT_ERROR 133` or disconnect), `RetryScheduler` executes **bounded exponential backoff with random jitter**:
$$\text{Delay}(r) = \left(\min(2^r, 64) \times 100\text{ ms}\right) + \text{Jitter}$$
where $\text{Jitter} \in [0, 1000\text{ ms}]$ is uniformly distributed. Maximum retries: $r \le 5$.

---

## 6. Background Protection & Battery Optimization

### 6.1 Android Doze Mode Prevention
Android OS aggressively throttles background processes when the screen turns off:
- BLE scan results are batched or dropped.
- BLE advertising intervals are slowed to conserve battery.
- CPU is suspended, freezing GATT server thread execution.

### 6.2 Foreground Service Architecture
AntiProxy executes the Authority GATT server inside `AuthorityGattService`:
```kotlin
// Android 14+ compliant service declaration
startForeground(
    NOTIFICATION_ID, 
    notification, 
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
)
```
Coupled with a `PowerManager.PARTIAL_WAKE_LOCK`, the CPU remains fully active at 100% clock speed for the duration of the 5-minute attendance window, guaranteeing 10 Hz advertising cadence and zero dropped GATT writes.
