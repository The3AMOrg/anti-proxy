# AntiProxy

AntiProxy is an Android application for taking classroom attendance over Bluetooth Low Energy (BLE) without needing internet connectivity.

The goal is to verify that students are physically present in the room when marking attendance, preventing remote proxy attendance (e.g. sharing nonces, links, or QR codes over messaging apps).

---

## How It Works

1. **Instructor starts a session**:
   - The instructor's phone acts as a BLE advertiser and GATT server.
   - It generates a temporary session with a random nonce and signs it with an asymmetric key pair.

2. **Student discovers the session**:
   - The student's phone scans for the instructor's BLE advertisement.
   - While scanning, it collects a burst of RSSI (signal strength) readings to estimate signal consistency before connecting.

3. **Verification over BLE GATT**:
   - The student connects to the instructor's phone over BLE.
   - The student reads the signed session, verifies the instructor's signature, and creates an attendance proof containing the session nonce, their device key ID, and the RSSI stats.
   - The student signs this proof using their private key stored in the Android Keystore (backed by TEE or StrongBox if available).
   - The proof is sent to the instructor over the GATT connection.

4. **Instructor confirms & records**:
   - The instructor verifies the student's signature against enrolled public keys, checks the nonce, validates the RSSI against a threshold, and checks for duplicate submissions.
   - If valid, the record is saved to a local SQLite database on the instructor's phone, and a 1-byte success code is sent back.
   - The BLE connection disconnects immediately to free the channel for other students.

---

## Repository Structure

The project is split into two modules:

- **`core/`**: A pure Kotlin Multiplatform module containing protocol codecs, wire formats, crypto interfaces, and attendance verification logic. It has no Android dependencies and runs unit tests on the JVM.
- **`app/`**: The Android application containing the BLE scanner, GATT client/server, Android Keystore signing, SQLite database, background service, and UI.

Detailed design docs, wire formats, and threat models are in the [`docs/`](docs/) directory:
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): Architecture, module layout, and component interaction.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): GATT characteristics, wire formats, byte layouts, and status codes.
- [`docs/SECURITY.md`](docs/SECURITY.md): Cryptographic choices, threat model, and verification checks.
- [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md): Notes on classroom RF conditions and GATT connection handling.

---

## Building

Requires JDK 17+ and the Android SDK.

```bash
# Run unit tests in the core module
./gradlew :core:jvmTest

# Build debug APK
./gradlew :app:assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Testing on Physical Devices

To test with two Android phones:

1. Install the APK on both devices.
2. Grant Bluetooth / Nearby Devices and Location permissions when prompted.
3. **Phone 1 (Instructor)**:
   - Select the **Instructor / Authority** tab.
   - Tap **Copy Authority Public Key** (shares the public key as Base64).
4. **Phone 2 (Student)**:
   - Select the **Student Node** tab.
   - Tap **Pair Authority Public Key** and paste the instructor's key.
   - Tap **Copy My Public Key** and share it back to Phone 1.
5. **Phone 1 (Instructor)**:
   - Tap **Enroll Student Public Key** and paste the student's key.
   - Tap **Start Session**.
6. **Phone 2 (Student)**:
   - Tap **Check In / Mark Attendance**.
   - If in range, the app will scan, connect briefly, submit the signed proof, and confirm attendance. Phone 1 will update the attendee count and show the student in the list.
