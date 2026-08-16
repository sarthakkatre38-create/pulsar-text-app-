# Pulsar Text — custom text over Bluetooth to the NS160 cluster

## What this is
A minimal Android app that pairs with your bike's Bluetooth module, opens a
classic SPP (RFCOMM) socket, and writes a text payload — intended to make the
cluster's caller-ID field show a custom short string.

## Before you build/run: verify the protocol
The code assumes the cluster speaks classic Bluetooth SPP and reads an
`AT+CLIP` unsolicited result code, same as a car kit reading caller ID. This
is a *guess* based on common cheap-module behavior — it is not confirmed for
the NS160. Two things could make it wrong:

1. **Transport type.** The module might be BLE (GATT), not classic SPP. Check
   with `nRF Connect` (BLE scanner) — if the bike shows up as a BLE
   peripheral with GATT services, this app's classic-socket approach won't
   connect at all and you'd need a GATT write instead.
2. **Payload format.** Even over SPP, the actual bytes the official Bajaj app
   sends might not be plain AT+CLIP text — could be a proprietary binary
   frame with a checksum, device ID, etc.

### How to check
1. On your phone: **Settings > System > Developer options > Enable Bluetooth
   HCI snoop log**.
2. Open the official Bajaj app, pair, and trigger an incoming call/SMS
   display on the cluster.
3. Pull the log: `adb bugreport` (or the snoop log file directly, path
   varies by Android version, often `/sdcard/btsnoop_hci.log`).
4. Open it in **Wireshark** and filter on `btrfcomm` or `btatt` depending on
   transport. Look at the actual bytes written to the bike right when the
   caller-ID text appears.
5. Update `sendCustomText()` in `BikeBluetoothService.kt` to match what you
   observe.

Skipping this step means the app may connect successfully but the bike will
just ignore the payload, since it won't match what the firmware expects.

## Project layout
- `MainActivity.kt` — UI, permission requests, binds to the service
- `BikeBluetoothService.kt` — owns the RFCOMM socket, connect/send logic
- `BikeNotificationListener.kt` — optional stub for later triggering off real
  phone notifications (not wired up yet)

## Permissions
- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (Android 12+)
- `ACCESS_FINE_LOCATION` (Android 11 and below, required for classic BT
  device discovery/name resolution)
- `POST_NOTIFICATIONS` (Android 13+, for the foreground service notification)
- `BIND_NOTIFICATION_LISTENER_SERVICE` (only if you wire up the optional
  listener — requires the user to manually grant notification access in
  system settings, cannot be requested via a runtime permission dialog)

## Build
Open in Android Studio (Hedgehog or newer), let Gradle sync, run on a device
with the bike already paired in system Bluetooth settings.

## Known limitations
- Max string length and character set are dictated by the cluster's segment
  display, not by this app — expect roughly 8–10 uppercase A–Z characters,
  matching the caller-ID field's real limit.
- If the cluster is BLE-only, this SPP approach needs to be replaced with
  `BluetoothGatt` write calls to whatever characteristic the sniff reveals.
- No pairing/bonding UI is included — pair the bike in system Bluetooth
  settings first, this app only looks for an already-bonded device with
  "pulsar", "bajaj", or "ns160" in its name.
