# GridSense

An indoor Wi-Fi and cellular coverage survey app for Android. You split the room into a grid
of equal cells, stand in the middle of each cell to take a reading, and the app turns the
readings into a heatmap. No floor plan, GPS or location fix is needed.

## How a survey runs

1. **Setup.** Name the survey, optionally note its *condition* (such as "door closed"), and
   enter the number of rows and columns. Cell A1 is the corner you start in; columns (A, B,
   C...) run across the room and rows (1, 2, 3...) run away from that corner. The cell size
   only sets the scale of the map, so a rough figure such as 1 m or two floor tiles is fine.
   Choose Wi-Fi or cellular as the primary radio, the samples per node, optionally a ping
   target, and which [latency tests](#latency-beyond-ping) to run.
2. **Grid.** Every cell has a labelled node at its centre. Grey means pending, a pulsing marker
   means the node is being logged, and a node that is done is coloured by the quality band of
   the survey's primary metric. Stand at a node, tap it and press *Start logging*; the sheet
   shows each stage of the run, and *Retake* redoes a node (long-press is a shortcut). A node
   you cannot stand on, such as one under a table, can be disabled from its sheet. The top bar
   shows how many nodes are done.
3. **Router node.** The floating button drops a router marker anywhere on the plan. Give it a
   label, pick its BSSID from a live scan list, and say how far it sits above the phone.
4. **Heatmap.** Inverse distance weighting with `p = 2` on a 0.1 m raster clipped to the grid,
   with a legend. Pick a Wi-Fi, cellular or network metric, filter by BSSID, and show or hide
   the routers. *Relative* stretches the colours over this room's range; *Fixed* colours by
   [quality band](#thresholds), so red always means poor.
5. **Analysis.** [Path loss](#path-loss), [latency beyond ping](#latency-beyond-ping) and
   [channel congestion](#channels), each with its own chart or table.
6. **Report.** A plain-language write-up of the survey, explained in [its own section](#the-report).
7. **Compare.** Two surveys of the same grid, node by node; see [Comparing surveys](#comparing-surveys).
   *Duplicate* on a survey starts a new one on the same grid for exactly this.

The share menu in a survey's top bar exports the heatmap PNG, the raw samples, nodes and
routers as CSV, everything as JSON, or the report as text. Home lists every survey with its
progress and a quality chip; each card's menu offers Duplicate, Compare with…, Export and
Delete.

## The report

The *Report* tab turns the logged samples into short findings written for a reader who has not
seen the raw data. It is rule-based, not AI: every sentence comes from a fixed threshold or
comparison in [`Insights.kt`](app/src/main/java/com/gridsense/data/Insights.kt), so the same
survey always produces the same text and it works offline. *Report text* in the share menu
hands the whole thing to any app, ready to paste into a write-up.

Only enabled nodes that have been logged are analysed. All per-node figures are medians of that
node's readings, which keeps a single outlier from skewing the result. Rated values carry a
quality chip with the band's name, using the same scale as the grid dots and the fixed heatmap.

### What each section says

| Section | What it tells you | How it is worked out |
| --- | --- | --- |
| **Overview** | How many nodes are logged, which are still to do, how many readings each node has. | Counts of enabled and logged nodes and of `LINK` rows per node. |
| **Wi-Fi coverage** | The network, band and channel; the signal range rated in words; the weakest and strongest node; any node too weak for video calls; whether the differences between nodes look real; which side the router is probably on; link speed. | Median RSSI per node against the Wi-Fi bands below. The spread is the strongest node median minus the weakest. A spread of 6 dB or less is reported as uniform coverage. Above that, the first and last row (and column) are compared, and a difference of at least 4 dB is reported as the side the router is probably on. |
| **Path loss** | Per router marker: the path-loss exponent and what it says about the room, the RSSI at 1 m, the shadowing spread, and whether the fit can be trusted. | See [Path loss](#path-loss). Only shown when a router marker exists. |
| **Bands and neighbours** | Whether your router also offers 5 GHz that the phone did not use, and hidden networks. | Scan rows with your SSID on 5 GHz, and scan rows with a blank SSID. |
| **Channels** | How many networks were heard on each band; whether a neighbour is loud enough on an overlapping channel to interfere; which channel would be quieter, if any; DFS warnings; the scan caveat. | See [Channels](#channels). On 2.4 GHz two channels overlap when they are less than 4 apart, so 1, 6 and 11 do not. An overlapping neighbour louder than -70 dBm is flagged as possible interference. |
| **Cellular** | Operator and technology; signal strength and quality rated in words; the weakest and strongest node; how often the phone switched towers. | Median RSRP per node, and medians of RSRQ and SINR, against the cellular bands below. A node is counted as switching when its readings came from more than one physical cell id (PCI). |
| **Latency, jitter and loss** | The median and worst latency, jitter and which nodes lost packets, each rated in words; or, if no ping was answered, why. Then DNS lookup time and failures, TCP connect time against the ping RTT, latency under load with a bufferbloat grade, and download goodput against the Wi-Fi link speed. | The `PING` row of each node. Latency up to 20 ms is low, up to 50 ms moderate, above that high. Jitter up to 5 ms is steady, up to 30 ms some variation, above that unsteady. A link-local IPv6 target is called out separately, because ping cannot reach it. The rest is explained in [Latency beyond ping](#latency-beyond-ping). |
| **Data quality** | How many nodes have a fresh neighbour scan, and how to get one at every node. | Shown only when some nodes fell back to `SCAN_CACHED` because of Android's limit of four scans per two minutes. |

### Thresholds

| Measure | Excellent | Very good | Good | Fair | Weak | Poor |
| --- | --- | --- | --- | --- | --- | --- |
| Wi-Fi RSSI (dBm) | ≥ -50 | ≥ -60 | ≥ -67 | ≥ -70 | ≥ -80 | below -80 |
| LTE/NR RSRP (dBm) | ≥ -80 | | ≥ -90 | ≥ -100 | | below -100 |
| RSRQ (dB) | | | ≥ -10 | ≥ -15 | | below -15 |
| SINR (dB) | ≥ 20 | | ≥ 13 | ≥ 0 | | below 0 |
| Ping latency (ms) | | | ≤ 20 | ≤ 50 | | above 50 |
| Jitter (ms) | | | ≤ 5 | ≤ 30 | | above 30 |
| Packet loss (%) | | | 0 | ≤ 1 | | above 1 |
| DNS lookup, TCP connect (ms) | | | ≤ 50 | ≤ 150 | | above 150 |
| Loaded latency (ms) | | | ≤ 50 | ≤ 100 | | above 100 |
| Bufferbloat (ms) | below 5 | | below 30 | below 60 | | 60 or more |
| Download goodput (Mbps) | ≥ 100 | | ≥ 25 | ≥ 5 | | below 5 |

-67 dBm is the figure usually quoted as the minimum for reliable video calls and streaming, so
the report lists every node below it. With ten pings per node any lost reply is 10 %, so any
loss at all rates poor. The bufferbloat grades follow Waveform's bufferbloat test. All of these
bands are common rules of thumb; vendors and operators draw them slightly differently, so cite
them as such. They live in one table, [`Quality.kt`](app/src/main/java/com/gridsense/core/Quality.kt).

### Example

This is the report from a real 3 x 3 survey of a room with 1 m cells, with network names
replaced and the Overview, link speed and data-quality lines left out for length. The current build
moves the interference lines into a Channels section, shown here with its occupancy count and
recommendation left out because that survey predates the stored channel widths. That survey was made with a build that still had the IPv6 ping bug, which the report
explains rather than reporting a meaningless 100% loss.

```
Wi-Fi coverage
- Connected to HomeNet on 2.4 GHz, channel 1.
- Signal ranges from -61 to -51 dBm across the room (median -54): good at worst, very good at best.
- Weakest node B1 (-61 dBm), strongest B3 (-51 dBm).
- No node is below -67 dBm, the usual minimum for reliable video calls and streaming, so there is no dead zone.
- The node-to-node spread is 10 dB. Readings at one spot only varied by about ±1.0 dB while logging,
  but a repeat survey can move a node by several dB depending on how the phone is held and where
  you stand, so confirm the pattern with a second pass before relying on it.

Bands and neighbours
- Your router also broadcasts HomeNet on 5 GHz (channel 44), reaching about -73 dBm here, but the
  phone stayed on 2.4 GHz.
- 2 hidden networks were also seen.

Channels
- 6 neighbouring networks heard.
- Interference looks low: the strongest neighbour, Neighbour-A, is at -86 dBm on channel 6, and
  the one that overlaps your channel is weak.
- A phone scan is a snapshot of beacons: it shows how strong neighbours are, not how busy they
  are. Real airtime use can't be measured without monitor mode.

Cellular
- Signal strength (RSRP) is -104 to -96 dBm, median -100: fair overall.
- Signal quality: RSRQ -11 dB (fair), SINR 1 dB (fair).
- The phone switched between towers (PCI 1, 211) at 4 of 9 nodes. That is typical of a weak
  indoor signal near the edge of two cells.

Latency, jitter and loss
- Every ping went unanswered because the target, fe80::..., is an IPv6 link-local address that
  ping cannot reach. Older GridSense builds picked it by mistake; update the app and re-log.
```

### Reading it carefully

- **Relative heatmap colours are relative.** In *Relative* mode the colour scale stretches from
  the weakest node to the strongest, so red means weakest in this room, not necessarily bad.
  Switch to *Fixed* for colours that mean the same in every survey, and quote the figures next
  to the image either way.
- **Small rooms show small, noisy differences.** The ±dB figure is how much readings varied at
  one spot over the few seconds of logging. It understates how much a repeat survey can differ,
  because your body, the way the phone is held and reflections all change between passes.
  Surveying the same room twice and [comparing](#comparing-surveys) the passes is the honest way
  to show repeatability.
- **"The router is probably on this side" is an inference.** It is drawn only from the signal
  gradient, which walls, furniture and reflections also shape. Mark the real router position
  with the router node if you know it.

## Path loss

The heatmap shows where the signal is; the path-loss fit tests the theory. For each router
marker the Analysis tab fits the log-distance model

```
RSSI(d) = P0 - 10 n log10(d / 1 m)
```

where `n` is the path-loss exponent and `P0` the RSSI at 1 m.

- **Inputs.** Each logged node's median RSSI for the router's BSSID, taken from the connection
  (`LINK` rows) when the phone was associated with that BSSID there, otherwise from neighbour
  scans. Distance is `sqrt(dx² + dy² + h²)`, with `h` the router's height above the phone from
  the router dialog. Nodes closer than 0.5 m are left out, because `log10(d)` runs away there.
- **Fit.** Ordinary least squares of RSSI on `10 log10(d)`, so `n` is minus the slope and `P0`
  the intercept. It also reports R², the residual standard deviation σ (the shadowing term,
  with N − 2 degrees of freedom), the node count and the distance range. An EIRP estimate is
  `P0 + FSPL(1 m)`, with `FSPL(1 m) = 20 log10(f in MHz) − 27.55`, about 40 dB at 2.4 GHz.
- **Chart.** RSSI against distance on a log axis, the fitted line, and a dashed free-space line
  (n = 2 through the same P0). Hollow dots came from scans. Tap a dot to see its node.

| n | Reading |
| --- | --- |
| below 2 | Guided propagation, such as along a corridor or with strong reflections |
| 2 to 2.5 | Close to free space or line of sight |
| 2.5 to 3.5 | Typical indoors |
| 3.5 to 4.5 | Obstructed |
| 4.5 and above | Heavily obstructed |

**Caveats.** The fit is shown with a warning when fewer than 4 nodes were usable or the farthest
node is less than twice as far as the nearest. A 3 x 3 room with 1 m cells has distances of
about 0.7 to 3.5 m, so that warning is common: treat its `n` as indicative. The EIRP is an
estimate that folds in antenna patterns and the phone's own antenna, not a transmitter
measurement.

## Channels

Channel analysis reuses the neighbour scans. Each scan row stores the channel width and the
centre frequency of the occupied block (`ScanResult.channelWidth` and `centerFreq0`); rows from
before those were stored count as 20 MHz.

- **One entry per BSSID**, with the median RSSI across the nodes that heard it and how many did.
- **Your own networks** are the connected SSID plus BSSIDs that differ from the connected one
  only in the first and/or last octet, which is how routers number their 5 GHz, guest and
  hidden backhaul radios.
- **Chart.** One per band: 2.4 GHz shows channels 1 to 13, 5 GHz the channels seen. Each
  network spans its real width at the height of its median RSSI, with yours highlighted.
- **2.4 GHz recommendation.** Each of channels 1, 6 and 11 is scored as the sum over other
  networks of `overlap × power in mW`, where `overlap` is the fraction of the candidate's
  20 MHz that the neighbour's block covers (1 on the same channel, falling linearly to 0 four
  channels away). The lowest score wins, but a change is only advised when it is at least
  3 dB (a factor of 2) quieter than your current channel.
- **5 GHz.** Co-channel and overlapping networks are listed, the least occupied 20 MHz channel
  is recommended (preferring non-DFS channels on a tie), and channels 52 to 144 are flagged as
  DFS: the router must vacate them when it detects radar, which costs a short outage.

**Caveats.** A phone scan is a snapshot of beacons. It shows how strong a neighbour is, not how
busy: a loud but idle network costs little airtime, and real utilisation can't be measured
without monitor mode. Scans throttled by Android reuse a cached list.

## Latency beyond ping

ICMP to the gateway measures only the first hop. Each survey can also run these at every node,
after the radios and the idle ping so it doesn't disturb them:

| Test | What it does | Default |
| --- | --- | --- |
| DNS lookup | Five A-record lookups through `DnsResolver` with `FLAG_NO_CACHE_LOOKUP`, so every query reaches the network's resolver. Median of the answered ones, plus failures. | On, `example.com` |
| TCP connect | Five `Socket.connect` calls with a 2 s timeout, the name resolved beforehand. The handshake costs about one round trip, so the report compares it with the ping RTT: a gap is the path beyond the ping target. | On, `1.1.1.1:443` |
| Load test | Downloads for at most 8 s or the URL's byte cap while pinging the idle target every 0.2 s (the shortest interval non-root ping allows). Gives the loaded median RTT, bufferbloat = loaded − idle, and goodput = bytes × 8 / seconds. | Off, `https://speed.cloudflare.com/__down?bytes=20000000` |

DNS and TCP add about 3 to 5 s per node and can be switched off as *Extended tests*. The load
test uses data — about 20 MB per node with the default URL, as setup shows — so it is off by
default, and on mobile data it asks once per session and is skipped unless you confirm. Every
attempt is stored as its own row (`DNS`, `TCP`, `LOADED_PING`, `DOWNLOAD`) and aggregated when
read, and one failing test never stops the others.

**Caveats.** The bufferbloat baseline is the idle ping's average. The download URL is a
third-party endpoint that may change; it is a per-survey setting for that reason. Goodput is
also capped by the internet connection, so comparing it with the Wi-Fi *link speed* mainly
shows that link speed is the radio's signalling rate, not throughput.

## Comparing surveys

A controlled comparison is the strongest experiment a coverage survey can report: router in
position A versus B, door open versus closed, 2.4 versus 5 GHz, or the same setup twice to
measure repeatability. *Duplicate* creates a new survey with the same grid, cell size, mode,
samples and tests, a new name and an empty condition; router markers are not copied.

- **Matching.** Two surveys are comparable only on the same rows and columns; nodes are matched
  on (row, col), and only nodes logged and enabled in both count. Other grids are greyed out in
  the picker with the reason. Nothing is interpolated between grids.
- **Output.** For the chosen metric, Δ = B − A per node: a difference map on a diverging
  blue/orange scale centred on 0, where blue always means better in B whichever way the metric
  runs, each node labelled with its change; a node table; and the mean Δ, mean |Δ|, the SD of
  Δ, the largest improvement and degradation, and how many nodes changed by at least the
  threshold below. The map exports as PNG and the table as CSV.
- **Repeatability.** With *Same conditions* on, the pair's mean |Δ| and SD of Δ are its
  measurement repeatability, and changes under ±2 SD (about 95 % of same-conditions noise) are
  within it. Two surveys with the same condition text count as a same-conditions pair, and
  later comparisons on that grid quote the band from such a pair.

| Metric | Counted as changed from |
| --- | --- |
| RSSI, RSRP, cellular signal, SINR | 3 dB (a factor of 2 in power) |
| RSRQ | 2 dB |
| Ping latency, jitter | 5 ms |
| DNS, TCP connect, loaded latency, bufferbloat | 10 ms |
| Packet loss | 10 % (one reply in ten) |
| Download goodput | 5 Mbps |

## What is measured at each point

| Quantity | Source |
| --- | --- |
| RSSI, BSSID, SSID, frequency, link speed | `WifiManager` connection info, polled every 500 ms. This path is not scan-throttled. |
| Cellular signal, RSRP, RSRQ, SINR, cell id, TAC, PCI, ARFCN, operator | `TelephonyManager.getAllCellInfo`, polled every 500 ms, reading the registered cell. LTE and NR report RSRP, RSRQ and SINR; WCDMA and GSM report the generic signal strength only. |
| Latency, jitter, packet loss | `ping -c 10`. The target is the per-survey override if set, otherwise the default-route gateway from `LinkProperties` on Wi-Fi and 8.8.8.8 on cellular, because carrier NAT usually makes the mobile gateway unreachable. Jitter is the mean absolute difference of consecutive RTTs. |
| Neighbour access points | One `startScan` per point, within the four-per-two-minutes throttle, with channel width, centre frequency and (Android 11+) Wi-Fi standard. When the budget is spent the cached scan list is used instead and those rows are stored as `SCAN_CACHED`. |
| DNS, TCP, load test | See [Latency beyond ping](#latency-beyond-ping). One row per attempt. |

Every reading is stored, not just the aggregates. A logging run writes one `LINK` row and one
`CELL` row per poll, one `PING` row carrying the latency, jitter and loss along with the host
that was pinged, one row per neighbour AP, and one row per DNS, TCP and loaded-ping attempt
plus a `DOWNLOAD` row when those tests run. Both radios are recorded at every point
regardless of which one the survey is nominally about.

## Permissions

Android reports the SSID as `<unknown ssid>` and the BSSID as `02:00:00:00:00:00`, and refuses
to return cell info at all, unless the app holds `ACCESS_FINE_LOCATION` and location services
are switched on. That is an Android requirement for reading radio identifiers, not something
GridSense wants for itself: the app never requests a location fix. Logging stays blocked
until the location permission, the location services switch and a readable radio are all in
place; the readiness checklist shows each one with a button to fix it.

`INTERNET` is held for the DNS, TCP and load tests. Ping runs as a subprocess and needs no
permission.

## Building

Requires JDK 17 and the Android SDK with platform 34. Built with Kotlin, Jetpack Compose
(Material 3, light and dark), navigation-compose and Room; charts are drawn on Compose `Canvas`.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The unit tests cover the pure survey mathematics (grid generation and node labels,
point-in-polygon, median, jitter, inverse distance weighting and the ping output parser), the
quality scale, the path-loss fit, channel scoring, the comparison and repeatability maths, the
latency aggregation, every rule in the report, the setup form's validation, and the database
migrations against the exported schemas, using fixtures shaped like real survey data. A test
also fails on colour literals outside the theme file. Nothing that needs a radio or a network
can run without a device.

## Data model

```
SurveyRoom(name, condition, mode, samplesPerPoint, pingHost, polygon, gridRows, gridCols, cellSizeM,
           createdAt, extendedTests, loadTest, dnsName, tcpHost, tcpPort, downloadUrl)
  GridPoint(seq, row, col, x, y, enabled, status)
    Sample(ts, kind,
           bssid, ssid, freqMhz, rssiDbm, linkSpeedMbps, channelWidthMhz, centerFreq0Mhz, wifiStandard,
           cellTech, cellDbm, cellLevel, rsrp, rsrq, sinr, cellId, tac, pci, arfcn, operator,
           pingHost, rttAvgMs, rttJitterMs, rttLossPct,
           probeMs, probeOk, bytes, durationMs, target)
  Router(x, y, bssid, label, heightDiffM)
```

Positions are metres from the corner of cell A1, at the centre of each cell. Surveys persist in
Room, so one can be closed and resumed mid-way. A point left mid-run by a killed process goes
back to pending when the survey is reopened.

Schemas are exported to [`app/schemas`](app/schemas), and every version from 4 upgrades through
an explicit migration in [`Migrations.kt`](app/src/main/java/com/gridsense/data/Migrations.kt)
that only adds nullable columns or columns with defaults, so existing surveys survive an
upgrade. There is no destructive fallback. A new column needs a version bump, its `ALTER`
statement and the exported schema; a unit test fails if any of the three is missing or the
defaults disagree. CSV exports only ever append columns, so existing column positions stay put.
