# NetScope

An Android network discovery, subnet analysis and diagnostics application.

NetScope's differentiator is **transparency**. It does not merely display network
information — it shows where every value came from, marks what it inferred, and names
what Android refuses to reveal. A blank is never shown where a guess would do, and a
guess is never shown where an observation is expected.

> **Status: milestone M1.** The scanning engine, evidence model, Wi-Fi analyzer,
> toolbox, subnet analyzer and history are implemented and working. Section
> "Not implemented" below lists exactly what is not.

---

## The evidence model

Every discovered property is stored as an `Evidence<T>`:

```kotlin
Evidence(
    value,                    // null exactly when the property is unavailable
    source,                   // LINK_PROPERTIES, MDNS, TCP_PROBE, OUI, INFERENCE, ...
    confidence,               // HIGH, MEDIUM, LOW, UNKNOWN
    observedAtEpochMillis,
    detail,                   // human-readable justification, shown on tap
    unavailability,           // why it is missing, when it is
)
```

The UI renders the source next to the value, tags anything derived with an `INFERRED`
badge, and renders absence as one of `NOT DISCOVERED`, `RESTRICTED BY ANDROID`,
`NOT APPLICABLE`, `PERMISSION REQUIRED`, `LOCATION SERVICES REQUIRED` or `NOT CHECKED`.

When two sources disagree, `EvidenceResolver` decides: a user label always wins, then
higher confidence, then a more direct source, then recency. It is never "last writer
wins".

---

## What Android actually allows

These are platform facts, verified against the compile SDK rather than assumed. NetScope
surfaces each of them in the UI instead of hiding them.

| Capability | Reality | What NetScope does |
|---|---|---|
| **ICMP ping** | No public raw-socket API. `InetAddress.isReachable()` is *not* a ping — it commonly degrades to a TCP connect to port 7. | Uses `android.system.Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` — a genuine unprivileged ICMP socket. Availability is probed at runtime (the kernel's `ping_group_range` can exclude the app) and every result is labelled `ICMP`, `TCP connect` or `fallback`. |
| **Traceroute** | TTL can be set, but reading the ICMP Time Exceeded reply needs the socket error queue (`IP_RECVERR` + `MSG_ERRQUEUE`), which `android.system.OsConstants` does not expose, or a raw socket, which needs root. | Measures hop **distance** to the destination accurately, shows unidentified hops as `*`, and states plainly in the UI why the middle of the path is blank. It does not print a fabricated hop table. |
| **Own MAC address** | Not readable. The API returns `02:00:00:00:00:00`. | Shows `RESTRICTED BY ANDROID` with an explanation. |
| **Remote MAC addresses** | `/proc/net/arp` is not readable by third-party apps. | Only recorded when a device volunteers one (e.g. in an SSDP payload). Never synthesised, and a vendor is never guessed without a real MAC. |
| **Randomised MACs** | Locally administered addresses carry no manufacturer information. | Detected via the `0x02` bit and never submitted to an OUI lookup. |
| **Routing table** | `/proc/net/route` is not readable. `LinkProperties.getRoutes()` returns only the routes attached to each `Network` object — a subset of the kernel table. | Uses `LinkProperties` as the authoritative source, labels every row with its origin, and states in the UI that a destination missing from the table may still be routed by the default gateway. |
| **Wi-Fi scan throttling** | Foreground apps get roughly four `startScan()` calls per two minutes since API 28. | Never polls. Registers for the scan-results broadcast, serves cached results, shows the **age** of every result, and predicts when the next scan is permitted. |
| **Location services** | On many releases scan results are empty unless location services are on system-wide, even with the permission granted. | Detected and reported as `LOCATION SERVICES REQUIRED` with a settings deep-link — never as "no networks found". |
| **Local network permission** | Newer releases gate local network access behind `ACCESS_LOCAL_NETWORK`, enforced for apps targeting the level that introduced it. TCP, UDP, mDNS and SSDP all fall under it. | Declared in the manifest and resolved **by name at runtime**, because this build compiles against an older SDK. When it is required and missing, LAN scanning reports `PERMISSION REQUIRED` and names the features that keep working; it never returns silently empty. |
| **Native binaries** | Executing a binary from app data storage is blocked (W^X). | No bundled binaries. See "Not implemented". |

### One place for the permission rules

Release-specific `if (SDK_INT >= ...)` checks scattered through view models are
untestable and easy to get subtly wrong, so every permission rule lives in a single pure
object, `PermissionPolicy` in `core-model`:

```kotlin
PermissionPolicy.canReadWifiInfo(state)
PermissionPolicy.canScanAccessPoints(state)
PermissionPolicy.canScanLan(state)
PermissionPolicy.canUseMdns(state)
PermissionPolicy.canUseSsdp(state)
```

The capabilities are separate because Android gates them separately — reading the
connected network, listing access points and reaching other hosts each have their own
rules, and collapsing them into one boolean is what produces an app that shows an empty
list with no explanation. `PermissionInspector` is a thin Android adapter that observes
the platform into a `PlatformState`; the rules themselves never touch the framework and
are unit-tested for API 26 through 37, including levels this compile SDK predates.

**Graceful degradation.** Denying local network access disables LAN discovery and
nothing else. The subnet calculator, interface and route information, DNS lookups, the
Wi-Fi analyzer and the public IP lookup involve no local network traffic and remain
available; the error message says so explicitly.

### Host monitoring intervals

`MonitorSchedulingPolicy` encodes what Android actually permits rather than passing a
user-chosen interval to WorkManager and hoping:

| Requested interval | Mechanism |
|---|---|
| 15 minutes or longer | WorkManager periodic work — deferred and **inexact**; the UI says "about every N minutes" |
| Under 15 minutes | A foreground monitoring session the user starts while the app is visible, with a persistent notification |
| Under 15 minutes, started from the background on Android 12+ | Rejected with an explanation — a foreground service cannot be started from the background there |
| Under 5 seconds | Rejected — no extra information, significant battery cost |

---

## Architecture

```
core-model        pure JVM — Evidence, CIDR/IPv4/IPv6 maths, route matching,
                  device merging, scan comparison, reachability reasoning
core-network      Android — ConnectivityManager, ICMP/TCP probes, scan engine,
                  mDNS, SSDP, DNS, port scanning, Wi-Fi, permissions
core-database     Room — profiles, sessions, devices, observations, services, routes
core-ui           Material 3 dark-first theme and the evidence-rendering composables
app               feature packages: dashboard, devices, wifi, tools, subnets,
                  networks, history, settings
```

`core-model` is a **pure JVM module** with no Android dependencies, so all subnet, CIDR,
permission and reasoning logic is unit-testable without Robolectric, an emulator or a
LAN. That is where the 123 unit tests live — including the permission matrix for every
API level from 26 to 37 and the topology derivation.

**Deviation from the specification:** the spec lists eleven separate `feature-*` Gradle
modules. They are implemented as packages inside `app` instead. The four `core-*`
modules — which are what actually enforce the testability and layering boundaries — are
real Gradle modules. Eleven additional modules would have multiplied build time and
Hilt/Compose configuration without changing the architecture, and the spec itself asks
for "Clean Architecture principles, not ceremony".

---

## Reaching a host

The subnet analyzer samples a handful of addresses to answer "is anything there".
Two things go further:

- **Reach a specific host** runs a battery of techniques against one address — ICMP
  echo, TCP connect across common ports, an HTTP response, reverse DNS — and reports
  which one worked. One success proves reachability; all of them failing does not prove
  the opposite, and the summary says so rather than declaring the host dead.
- **Scan this subnet** hands the target to the same discovery engine the Devices screen
  uses, so a routed subnet can be enumerated once it is shown to be reachable.

## Service Explorer

Subnet analysis now leads directly into access. From the Subnet Analyzer, enter a routed
target such as `10.0.7.0/24` and choose **Explore services**; from a device detail page,
choose **Explore services** for that host.

The explorer uses a bounded worker pool to test common access ports across the target and
groups confirmed-open endpoints by host. It recognises FTP/FTPS, SSH/SFTP, Telnet,
HTTP/HTTPS, SMB, AFP, RTSP, IPP, NFS, RDP, VNC and raw printer endpoints. No passwords
are attempted during discovery: an OPEN result means only that the TCP service accepted
a connection.

Where Android has a compatible handler, NetScope can hand the service URI to the
appropriate client app. URIs can always be copied. Plain FTP additionally has a built-in
**read-only browser**: username/password are kept only in memory, passive mode is used,
directory contents can be navigated, and no upload/delete/rename command exists. The UI
warns that FTP itself is unencrypted and recommends SFTP/FTPS where available.

The subnet service scan is deliberately capped at 4096 hosts per run and feeds work
through a bounded Channel instead of allocating one coroutine per host/port.

## Export

Results export to JSON or CSV from the Devices screen. Every exported property carries
its `source` and `confidence`, and an absent value exports the reason it is absent, so a
consumer can distinguish "we looked and found nothing" from "Android would not say".
The top-level JSON keys are `schemaVersion`, `network`, `interfaces`, `routes`,
`subnets`, `devices`, `services` and `scanMetadata`.

Files are written to the cache directory and shared through a content URI, so no storage
permission is needed and nothing is left in shared storage. Nothing leaves the device
until a destination is chosen in the system share sheet.

## Topology

The dashboard shows the logical picture — this device, its default gateway, and each
subnet Android exposes a route to, marked `CONNECTED` or `ROUTED` with the route that
proves it. The default route is deliberately excluded: it covers every destination, so
drawing it as a branch would suggest the whole internet is a neighbouring subnet.

Layer 2 is not shown, because it is not knowable from Layer 3 scanning. A subnet with no
recorded scan shows "not scanned yet" rather than a zero.

## The scan engine

Phases: network enumeration → route analysis → host discovery → name resolution →
service discovery → fingerprinting → comparison with history.

- **Bounded concurrency.** A `Semaphore` sized from the performance profile (8/32/64),
  never one coroutine per host.
- **Incremental results.** Devices stream to the UI over a `Flow` as they are found.
- **Instant STOP.** The scan runs in a scope the controller owns; cancelling it
  propagates through structured concurrency and releases every socket and the multicast
  lock through `finally` blocks.
- **Smart Scan** (default) probes the gateway, this device, previously seen hosts and
  DHCP-typical low addresses before sweeping the remainder.
- **Large ranges** (`/16` or wider) require a confirmation dialog stating the host count.
- **Non-private ranges** require a separate, explicit scope confirmation.

A silent host is recorded as `NO RESPONSE`, never `OFFLINE`. Hosts that never answer are
not listed at all, because listing them would imply knowledge the app does not have.

**Multicast discovery** (mDNS via JmDNS, SSDP) holds a `WifiManager.MulticastLock` for
exactly the duration of the browse, reference-counted and released in a `finally` block.

---

## Security posture

All data from network devices is treated as hostile input: bounded datagram and banner
sizes, header count and length caps, control-character stripping before display **and**
before logging, and no rendering of remote content in a WebView.

NetScope performs **no** exploitation, credential testing, default-credential probing,
vulnerability scanning, deauthentication, packet injection or captive-portal bypass.
The port scanner reports whether a port accepts a connection, and nothing more.

## Privacy

No account, no cloud service, no analytics SDK. Scan results, SSIDs, BSSIDs, hostnames
and device inventories stay on the device and are excluded from cloud backup and device
transfer.

Exactly one feature contacts anything outside the local network — the public IP lookup.
It is off by default, requires an explicit tap, and names the endpoint
(`https://api.ipify.org`) in the UI before the request is made.

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew test              # 79 unit tests, no device or LAN needed
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

That file is git-ignored. When it is absent the release build falls back to the debug
key so the project still builds on a fresh clone — such a build is fine for testing and
must not be distributed as if it were signed by the project's own key.

**Demo mode** (Settings → Developer) replaces scan results with a fixed topology —
`10.0.2.1` router, `10.0.2.12` PC, `10.0.2.18` printer, `10.0.2.24` NAS, `10.0.2.37`
this device, and a routed `10.0.7.0/24` — so the whole UI can be exercised with no LAN.
Demo data carries a `DEMO DATA` source chip on every value.

## Renaming the app

The name lives in exactly two places: `app_name` in `app/src/main/res/values/strings.xml`
and the `applicationId`/`namespace` in `app/build.gradle.kts`. No Kotlin source contains
the literal.

---

## Not implemented

Stated plainly rather than stubbed:

- **Intermediate traceroute hops** — not possible without the socket error queue or root.
  Hop distance is measured instead, and the UI explains why.
- **iPerf** — would require shipping a native library in `jniLibs` and reproducing its
  licence notice. Not bundled.
- **Speed test** — deliberately omitted rather than shipped as a PHY-link-speed reading
  dressed up as throughput.
- **NetBIOS and LLMNR discovery** — the evidence sources are modelled but no prober is
  wired up yet; mDNS and SSDP cover the same ground on modern networks.
- **Root and managed-device modes** — detected and reported as capability levels, but no
  elevated operations are implemented. Nothing in the app requires root.
- **Router/controller integrations** (UniFi, OpenWrt, MikroTik, SNMP) — M3.
- **WorkManager host monitor and new-device notifications** — M2. The scheduling rules
  for the monitor are implemented and tested in `MonitorSchedulingPolicy`; the monitor
  itself is not built yet. The notification preference exists but currently gates nothing,
  and the WorkManager dependency was removed so the app does not request `WAKE_LOCK`,
  `RECEIVE_BOOT_COMPLETED` or `FOREGROUND_SERVICE` for a feature it does not have.
- **Tablet list-detail layouts** — the layouts are responsive but do not yet use a
  two-pane list-detail presentation.
- **OUI database** ships as a curated ~234-entry subset, not the full IEEE registry. An
  unlisted prefix reports `NOT DISCOVERED`.

## Licence

See [LICENSE](LICENSE).
