# NetScope

An Android network discovery, subnet analysis and diagnostics application.

NetScope's differentiator is **transparency**. It does not merely display network
information — it shows where every value came from, marks what it inferred, and names
what Android refuses to reveal. A blank is never shown where a guess would do, and a
guess is never shown where an observation is expected.

> **Status: advanced diagnostics milestone.** The scanning engine, evidence model,
> Wi-Fi analyzer, routed-subnet Service Explorer, Advanced Toolkit, monitoring,
> history, manual inventory and backup/restore are implemented. Section
> "Platform / scope limits" below lists the remaining deliberate limits.

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

The built-in background monitor follows Android's WorkManager rules:

| Requested interval | NetScope behavior |
|---|---|
| 15 minutes or longer | WorkManager periodic work — deferred and **inexact**; the UI says "about every N minutes" |
| Under 15 minutes | Rejected for background monitoring instead of pretending WorkManager can honor it |

Each check is bounded. `CONNECTED`, `REFUSED/CLOSED_BUT_HOST_RESPONDED`,
`TIMEOUT/NO RESPONSE` and `NO ROUTE` remain distinct so a closed port is never
presented as an open service.


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
                  networks, history, toolkit, access, settings
```

`core-model` is a **pure JVM module** with no Android dependencies, so all subnet, CIDR,
permission and reasoning logic is unit-testable without Robolectric, an emulator or a
LAN. That is where the pure policy/maths unit tests live — including the permission matrix
for API 26 through 37 and the topology derivation. Android-facing modules add their own
tests for protocol parsing and scanner behavior.

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

## Advanced Toolkit

The **More → Advanced Toolkit** screen brings the main Network Analyzer Pro / NetX-style
diagnostics into one place while keeping NetScope's evidence and Android-limit rules:

- **Internet speed test** — real bounded HTTP download/upload throughput against
  `speed.cloudflare.com`, latency/jitter, and local speed-test history.
- **RDAP / WHOIS, ASN and public-IP geolocation** — IP, domain and AS lookups through
  RDAP plus explicit public geolocation and current public-network metadata.
- **Advanced DNS** — A/AAAA/PTR/MX/NS/SOA/TXT/SRV plus SPF, CAA, SVCB and HTTPS records,
  with an explicit `+norec` mode.
- **Bonjour / mDNS and UPnP / SSDP browser** — browse local advertised services without
  first running a full subnet sweep.
- **NetBIOS Node Status and LLMNR** — compatibility discovery for older Windows/LAN
  environments.
- **Read-only SNMP v2c** — user-supplied community string; only `GET` for
  `sysName`, `sysDescr` and `sysUpTime`. No community guessing and no `SET`.
- **Cellular snapshot** — operator, MCC/MNC, radio generation, signal and, when Android
  grants precise-location access, cached CID/LAC/TAC/CI/NCI identity fields.
- **Host monitor** — Android-compliant WorkManager checks at 15 minutes or slower and
  notifies only when state changes. TCP refusal is kept separate from an open port.
- **Read-only SSH system monitor** — fetches the host-key fingerprint before credentials,
  then runs only fixed OS/uptime/load/RAM/disk commands after explicit trust. Passwords
  remain in memory and are cleared after the run.
- **Manual inventory, favorites and JSON backup/restore** — user-created networks/devices
  are stored separately from observed scan evidence so imported data cannot masquerade
  as a network observation.

Wi-Fi analysis also reports Android security types (including WPA3/OWE/DPP/Passpoint
where exposed), cipher hints, WPS advertisement, channel width, generation and offline
OUI manufacturer matches. Nearby-AP scan permission is intentionally separate from
connected-Wi-Fi identity because Android gates the two operations differently.

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

- **Bounded concurrency.** A fixed worker pool sized from the performance profile
  (8/32/64) consumes a bounded `Channel`; the CIDR stays lazy and NetScope never
  creates one coroutine per host.
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

Public internet tools are **user initiated** and identify their endpoint in the UI.
The speed test and current public-network metadata use `speed.cloudflare.com`;
RDAP/WHOIS bootstrap lookups use `rdap.org`; public IP geolocation uses `ipwho.is`.
LAN scanning, Wi-Fi analysis, history, manual inventory and backups stay local.
No analytics SDK or account is used.

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew test              # JVM/Android unit tests; no physical LAN is required
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

## Platform / scope limits

These are deliberate limits rather than hidden stubs:

- **Intermediate traceroute hop addresses** — Android third-party apps do not expose the
  socket error queue/raw-socket capability required for a conventional hop table.
  NetScope measures hop distance and explains the limitation instead of fabricating hops.
- **iPerf protocol compatibility** — NetScope now has a real HTTP throughput test, but it
  does not bundle an iPerf native binary/library. Doing so would add native packaging and
  licence/maintenance obligations for a feature the current speed test already covers.
- **Root / device-owner packet capture or firewall control** — capability is detected,
  but normal NetScope diagnostics do not require elevated privileges.
- **Router-vendor controller APIs** — there is no automatic UniFi/OpenWrt/MikroTik
  credential integration. Generic SNMP read-only monitoring and verified read-only SSH
  system monitoring are available instead.
- **Remote destructive administration** — no password guessing, exploit probing,
  arbitrary remote shell, shutdown/reboot automation, SNMP SET, delete or configuration
  mutation is performed.
- **Tablet two-pane presentation** — layouts are responsive but remain single-pane.
- **OUI coverage** — the bundled OUI table is curated rather than the full IEEE registry;
  unknown prefixes remain `NOT DISCOVERED`.

## Licence

See [LICENSE](LICENSE).
