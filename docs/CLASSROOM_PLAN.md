# AntiProxy: Classrooms, Seamless Joining & Instant Attendance

Status: Phases 1–6 and 8 done, Phase 7 mostly done (see its section). QR codes and Extend are deferred.

UI reference: the AttendX app (`D:\Code-Projects\AttendX`) — its `MaterialExpressiveTheme` +
`MotionScheme.expressive()` setup and its rule of using Material 3 Expressive components
everywhere. AttendX is a reference only; nothing is changed there.

---

## Goal

Make the teacher ↔ student connection as easy as Google Classroom, without weakening the
anti-proxy guarantees:

1. A teacher creates a classroom and shares a **join code or link** (WhatsApp or any app).
2. A student taps the link (or enters the code) and sees
   **"You were invited to *Physics 101* by *Dr. X*"** → **Join**.
3. Joining syncs the roster on both phones **and exchanges the keys** — no more manual
   copy/paste of public keys.
4. When the teacher takes attendance, a student who opens the app is scanned for the
   teacher's BLE broadcast immediately and sees **"Dr. X is taking attendance"** →
   **Mark attendance**.

## Decisions (agreed)

| Question | Decision |
|---|---|
| Push notification when the student's app is closed | **No** — app-open only (stays on a free backend plan) |
| Proof of presence in the first release | **Full AntiProxy protocol** (signed session, RSSI evidence, replay checks) |
| Who can create classrooms | **Anyone signed in** |
| Backend | **Open** — see Phase 3 |

---

## Phases

### Phase 1 — Hardening (done)
- **Store lookup bug fixed.** `AndroidAuthorityStore` wrote IDs as BLOBs but queried with
  hex text, so every lookup failed (`UNKNOWN_SESSION`). Fixed with `hex(column) = ?`;
  regression test `AndroidAuthorityStoreTest` fails on the old code and passes on the new.
- **Device tests uninstall the app.** `connectedDebugAndroidTest` uninstalls AntiProxy after
  each run, wiping keys and rosters. Run device tests on the emulator or a spare phone only.
- **Session drop at 11:37:55** — not a bug; it was stopped by hand.
- **RSSI thresholds need a real-room calibration.** Every test so far had both phones next to
  each other (≈ −36 dBm). The acceptance limits in `RssiPolicy` stay adjustable and must be
  tuned in an actual lecture room before release.

### Phase 2 — Compose + Material 3 Expressive UI (done)
Replace the 834-line View-based `MainActivity` with Compose, keeping all behaviour.
- `MaterialExpressiveTheme`, `MotionScheme.expressive()`, dynamic colour (Android 12+) with a
  seed fallback, light/dark.
- Role switch as a connected `ToggleButton` button group.
- `LargeFlexibleTopAppBar`, Expressive button shapes/sizes, `LoadingIndicator` /
  `ContainedLoadingIndicator`, `LinearWavyProgressIndicator` for session time left.
- Snackbars instead of Toasts; Compose `AlertDialog` for key entry (temporary until Phase 4
  removes manual keys).
- Plain-language messages for every rejection code instead of `UNKNOWN_SESSION`-style names.

### Phase 3 — Backend + accounts
**Backend: Firebase Spark** (free, never pauses). Chosen over Supabase/Appwrite, whose free
projects pause after 7 days idle.

Project setup (done 2026-10-10, via the Firebase CLI):
- Project `antiproxy-52424`, owned by `thefreecodesyndicate@gmail.com` — pass
  `--account thefreecodesyndicate@gmail.com` (the CLI's default account is a different one).
- Android app `org.ap.protocol` with the debug SHA-1 and SHA-256 registered.
- Firestore `(default)` in **asia-south1** (Mumbai), rules from `firestore.rules` deployed.
- **Still manual:** enable the Google sign-in provider in the console (the CLI cannot create
  the OAuth client), then download `google-services.json` into `app/`.

App work:
- Google sign-in (Credential Manager). Name from the Google profile, editable.
- Role choice on first run: **I'm a teacher / I'm a student** (changeable later).

### Phase 4 — Classrooms, join codes, key exchange (done)

**Shipped** (`classroom/`, `ui/ClassroomScreens.kt`):
- Teacher: create classrooms, join code shown large (read character by character by TalkBack),
  **Share invite** split button (share sheet; menu: Copy code, Get a new code), live student
  list, approve a student's new phone, remove a student.
- Student: Join a classroom → code entry (accepts lower case, spaces, the dash) → invite dialog
  "You were invited to *X* by *Y*" → Join. Leave a classroom.
- Key exchange on join replaced the manual copy/paste cards: the teacher's phone keeps its
  roster equal to the approved keys of current members (removed students and replaced phones
  are revoked); the student's phone pins each classroom's teacher key.
- **Edit name** (account menu): updates the Firebase profile, refreshes the ID token, then
  rewrites the name on the profile, the teacher's classrooms and memberships, because the
  rules compare stored names with the token.
- Offline safety: membership "removed" and key revocation act only on server-confirmed data,
  so a cold cache can't delete a student's classroom or revoke the offline roster. Listeners
  re-subscribe with backoff after errors (fixes a create-then-listen permission race).

**Verified on the two phones:** create classroom → join by code → both rosters update live →
check-in accepted with no manual keys → teacher removes student → check-in refused ("removed
this phone") → rejoin by code → check-in accepted.

**Bug found and fixed on the way:** the student's BLE client only started waiting for the
teacher's result after the proof write finished, so a fast reply was dropped, the client timed
out, retried, and showed "already marked present" for a check-in that had succeeded
(`StudentGattClient`: the result listener is now armed before writing).

**Not yet verified live:** Edit name, Get a new code, Leave, Share sheet, and the new-phone
approval flow (needs a second device for the same account). Rules for all of them are tested.

**Added after the first pass:** teacher **Delete classroom** (confirm dialog, removes all students).
The classroom is marked `deleted` (a tombstone, so students can read why) and its members and
join code are removed. A student sees "You were removed from *X*. Reason: classroom was deleted by
*Y*." once; OK drops their list entry. Verified live: student in the class is shown the notice
while the app is open. Rules tests cover it (36 passing).

**Deferred:** show/scan QR (needs CameraX + ML Kit; join links in Phase 5 cover the same need),
Bluetooth-off/permission banner (Phase 6).

#### Original plan
- Teacher creates a classroom → 6-character code without confusable characters
  (no 0/O, 1/I/L), e.g. `K7M-4QX`.
- Share via the Android share sheet (`ACTION_SEND`), plus Copy code, Show QR, Reset code.
- Student enters/pastes/scans a code, or taps a link → invite dialog → Join.
- **Key exchange on join:**
  - The classroom record holds the teacher's authority public key; the student's app pins it
    on Join (replaces "Pair Authority Public Key").
  - The student's hardware-backed device public key goes into their member record; the
    teacher's app caches it (replaces "Enroll Student Public Key").
  - New phone → new key → teacher sees "Riya switched devices" and approves.

Data model (Firestore naming; maps 1:1 to tables if Supabase is chosen):
```
users/{uid}                                name, photo
joinCodes/{code}                           → classroomId (claimed atomically)
classrooms/{id}                            name, teacherUid, teacherName, authorityPublicKey
classrooms/{id}/members/{uid}              name, devicePublicKey, joinedAt, deviceApproved
users/{uid}/classrooms/{id}                student's own list
classrooms/{id}/sessions/{sid}             ACTIVE / ENDED, startedAt, endsAt
classrooms/{id}/sessions/{sid}/records/{uid}   uploaded from the teacher's phone
```
Rules: a student can only add themselves, only with a valid code; only the teacher can start
or end sessions, remove members or approve devices. Every rule gets a test.

**Written and tested** (ahead of the app code): `firestore.rules` + `firestore-tests/rules.test.js`,
29 tests. Run with `cd firestore-tests; npm install; npm test` — starts the local emulator under
the `demo-antiproxy` project, so no Firebase login is needed. Names on classrooms and members
must match the Google account (`request.auth.token.name`), so invites can't be spoofed.

### Phase 5 — Join links (done)

**Shipped:**
- Link: `https://antiproxy-52424.web.app/join/A5AXM2` (Firebase Hosting, `hosting/public`;
  `/join/**` rewrites to `join.html`). Firebase Dynamic Links is shut down, so Hosting is used.
- `hosting/public/.well-known/assetlinks.json` lists the **debug** SHA-256 fingerprint.
  `firebase.json` overrides Firebase's default ignore of dot-folders, or `.well-known` isn't deployed.
- Manifest: `autoVerify` intent filter for `/join/` on the host; `singleTop` so a tapped link
  reaches a running app through `onNewIntent`.
- The link waits until the user is signed in and on the Student role, then opens the invite
  dialog. On the Instructor role it shows "Switch to Student, then open the invite link again."
- Share invite now sends the link plus the typed code.
- `join.html` shows the code (text only, never HTML) and says to open the app or type the code.
  It has no download link: the app isn't on a store yet.

**Verified on the phones:** `pm get-app-links` reports `antiproxy-52424.web.app: verified` on
both; a cold tap opens "Join Physics 101? You were invited to Physics 101 by …"; a warm tap
with an unused code shows "No classroom uses that code"; the link on the teacher's phone shows
the switch message. Unit tests for the link parser pass.

**Verified later:** a link tapped while signed out waits on the sign-in screen and opens the
invite dialog after sign-in; the join page in Chrome shows the code and instructions; the share
sheet carries the join link and the typed code.

**Not done:** release fingerprint in `assetlinks.json` (no release signing config yet; add the
release SHA-256 next to the debug one before shipping). QR codes remain deferred.

### Phase 6 — Instant attendance (done, Extend and BLE-on-open deferred)

**Shipped:**
- Teacher: **Take attendance** / **End attendance** on the classroom screen. Taking it writes an
  `ACTIVE` session (5 min) to `classrooms/{id}/sessions` and starts the BLE broadcast. Shows
  "Attendance running · N present" and a time-left bar.
- Each verified check-in is written to `sessions/{sid}/records/{uid}` by the teacher's phone. The
  device key is matched to a roster member. Firestore queues the write while offline (this is the outbox).
- Bluetooth off: Take attendance shows "Turn on Bluetooth to take attendance." and opens no session.
- Student: any approved classroom with an open session shows **"<Teacher> is taking attendance"**
  with Mark attendance / Not now. Mark attendance runs the normal BLE check-in ("Attendance marked.").
  The prompt comes from the Firestore listener, so it works wherever the app is open.
- The session ends when the 5 minutes are up. Students also check `endsAt`, so a dead teacher phone
  can't leave a prompt open forever.

**Verified on the phones:** take attendance → student prompt → Mark attendance → check-in accepted,
teacher shows 1 present, and the record write completed. Bluetooth-off guard. Time-left bar shows.
One prompt stayed on screen after a Mark tap once and didn't come back on retry; not reproduced since.

**Deferred, on purpose:**
- **Extend:** the BLE session has a fixed signed expiry. Restarting it starts a new session and resets
  the count. Decide how extension should work before building it.
- **BLE scan on app open:** the Firestore listener already shows the prompt on open. The BLE advert
  doesn't say which class is taking attendance, so a scan can't name the class.
- Permissions asked in context (the app still asks at onboarding); a wider Bluetooth banner.

### Phase 7 — Accessibility pass + tests (mostly done)

**Done and checked on the phones (2026-10-10):**
- 200 % font: home, class (both tabs), Settings, join and class forms read without clipping. Fixed
  on the way: role and theme buttons stack at large sizes, tabs become scrollable instead of
  breaking "Attendance", the account row stacks, card text keeps clear of the artwork icon.
- Touch targets: a script over the accessibility tree (`uiautomator`) found nothing under 48 dp and
  no unlabeled control on home, class, People and Settings. It caught the extended FAB missing
  its label (a Material alpha bug); fixed with an explicit description.
- "Remove animations": the sign-in check appears already drawn. Material's own motion is not gated.
- Time left: the bar ticks every second; TalkBack hears it once a minute.
- Haptics: confirm / reject buzz when a check-in finishes.
- Permissions are asked when an attendance action needs them, not at launch. Bluetooth off gives
  "Turn on Bluetooth…"; Settings shows Bluetooth and Nearby devices with one-tap fixes.
- Tests: 40 rules tests, 10 JVM unit tests (join codes, artwork choice).

**Not done:** a real TalkBack walk-through (labels were checked from the tree, not by ear),
Extend (WCAG 2.2.1, see Phase 6), device tests (emulator only).

Original checklist:
- Never colour alone for status: icon + text everywhere.
- TalkBack: join code read character by character; attendance prompt is a live region and
  takes focus; headings marked; icons labelled.
- Touch targets ≥ 48 dp; primary actions use Medium/Large Expressive button sizes.
- Layouts verified at 200 % font scale.
- Honour "remove animations".
- Time-limited attendance shows and announces the time left; the teacher can extend it
  (WCAG 2.2.1).
- Every error offers a fix ("Bluetooth is off — Turn on").
- Haptic confirmation when attendance is marked.
- Tests: JVM unit tests, backend rules tests, device tests on the emulator/spare phone only.

### Phase 8 — Classroom-style home, class screen, People, Settings (done)
Derived from Google Classroom (reviewed on the phone on 2026-10-10), rebuilt with M3 Expressive.

What Classroom does, and what we take:
| Classroom | AntiProxy |
|---|---|
| Home: one card per class with header artwork, name, section, teacher; ⋮ menu (Hide, Unenrol) | Same cards for the current role. Student ⋮: Unenroll. Teacher ⋮: Share invite, Copy link, Edit, Delete. A "Taking attendance" chip on cards with a live session |
| + button: Join class / Create class | Extended FAB: "Join class" (student) or "New class" (teacher) |
| Class: banner, then Stream / Classwork / People | Banner (artwork, name, section, room), then tabs **Attendance** / **People** |
| People: Teachers, then Classmates (photo or letter avatar) | **Teacher** section, then **Students · N**. Teacher sees approve/remove; students see classmates (rules change) |
| About: name, section, room, subject | Section and room on the classroom (shown on banner and cards) |
| Settings: account, change name, theme, about | Account (photo, name, email, Edit name, Sign out), **Use AntiProxy as** (moved off the home screen), Theme, Bluetooth and permissions, About |
| Header: theme gallery or uploaded photo | Built-in artwork themes drawn in Compose. No photo upload: Cloud Storage needs the paid plan |
| Avatar from the Google account | Same: photo URL from the sign-in token, letter avatar as fallback |

**Built and checked on both phones (2026-10-10):** home cards with artwork, teacher avatar and ⋮
menus; class screen with banner and Attendance / People tabs; edit class (section, room, artwork)
reaching students live; People showing teacher and students with Google photos or letter avatars;
Settings with role, theme, Bluetooth status; a full attendance round through the new screens
(teacher sees "1 present" with the student's name). The old Instructor/Student switch and the
standalone Start/Stop card are gone from the home screen.

Data: classrooms gain `section`, `room`, `theme`, `teacherPhotoUrl`; members gain `photoUrl`.
Rules check photo URLs against the sign-in token (`request.auth.token.picture`), so nobody can
point their avatar at an arbitrary URL. Students can read the member list of their own classes.

## Known limits
- No prompt while the student's app is closed (decided: no push notifications).
- Android 10–11 needs Location permission for BLE scanning; Android 12+ does not.
- Proof strength depends on calibrated RSSI limits (Phase 1 note).
