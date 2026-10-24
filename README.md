# GridSense

An indoor Wi-Fi and cellular coverage survey app for Android. You split the room into a grid
of equal cells, stand in the middle of each cell to take a reading, and the app turns the
readings into a heatmap. No floor plan, GPS or location fix is needed.

## How a survey runs

1. **Setup.** Name the survey and enter the number of rows and columns. Cell A1 is the corner
   you start in; columns (A, B, C...) run across the room and rows (1, 2, 3...) run away from
   that corner. The cell size only sets the scale of the map, so a rough figure such as 1 m
   or two floor tiles is fine. Choose Wi-Fi or cellular as the primary radio, the samples per
   node, and optionally a ping target.
2. **Grid.** Every cell has a node at its centre. Grey means pending, a pulsing marker means
   the node is being logged, and a node that is done is coloured by the survey's primary
   metric's quality band. Stand at a node, tap it and press *Start logging*; the sheet shows
   each stage of the run, and *Retake* redoes a node (long-press is a shortcut). A node you
   cannot stand on, such as one under a table, can be disabled from its sheet. The top bar
   shows how many nodes are done.
3. **Router node.** The floating button drops a router marker anywhere on the plan. Give it a
   label and pick its BSSID from a live scan list.
4. **Heatmap.** Inverse distance weighting with `p = 2` on a 0.1 m raster clipped to the grid,
   with a legend. Pick Wi-Fi RSSI, the cellular signal quantities, latency, jitter or loss,
   filter by BSSID, and show or hide the routers. *Relative* stretches the colours over this
   room's range; *Fixed* colours by quality band, so red always means poor. The top bar's
   share menu exports the PNG, the raw samples, nodes and routers as CSV or JSON, or the
   report as text.
5. **Report.** A plain-language write-up of the survey, explained in [the next section](#the-report).

## The report

The *Report* tab turns the logged samples into short findings written for a reader who has not
seen the raw data. It is rule-based, not AI: every sentence comes from a fixed threshold or
comparison in [`Insights.kt`](app/src/main/java/com/gridsense/data/Insights.kt), so the same
survey always produces the same text and it works offline. *Report text* in the share menu
hands the whole thing to any app, ready to paste into a write-up.

Only enabled nodes that have been logged are analysed. All per-node figures are medians of that
node's readings, which keeps a single outlier from skewing the result.

### What each section says

| Section | What it tells you | How it is worked out |
| --- | --- | --- |
| **Overview** | How many nodes are logged, which are still to do, how many readings each node has. | Counts of enabled and logged nodes and of `LINK` rows per node. |
| **Wi-Fi coverage** | The network, band and channel; the signal range rated in words; the weakest and strongest node; any node too weak for video calls; whether the differences between nodes look real; which side the router is probably on; link speed. | Median RSSI per node against the Wi-Fi bands below. The spread is the strongest node median minus the weakest. A spread of 6 dB or less is reported as uniform coverage. Above that, the first and last row (and column) are compared, and a difference of at least 4 dB is reported as the side the router is probably on. |
| **Bands and neighbours** | Whether your router also offers 5 GHz that the phone did not use; hidden networks; how many neighbouring networks were heard and whether any is loud enough on an overlapping channel to interfere. | Scan rows. Channels come from the centre frequency. On 2.4 GHz, two channels overlap when they are less than 5 apart, so channels 1, 6 and 11 do not overlap. An overlapping neighbour louder than -70 dBm is flagged as possible interference. |
| **Cellular** | Operator and technology; signal strength and quality rated in words; the weakest and strongest node; how often the phone switched towers. | Median RSRP per node, and medians of RSRQ and SINR, against the cellular bands below. A node is counted as switching when its readings came from more than one physical cell id (PCI). |
| **Latency, jitter and loss** | The median and worst latency, jitter and which nodes lost packets, each rated in words; or, if no ping was answered, why. | The `PING` row of each node. Latency up to 20 ms is low, up to 50 ms moderate, above that high. Jitter up to 5 ms is steady, up to 30 ms some variation, above that unsteady. A link-local IPv6 target is called out separately, because ping cannot reach it. |
| **Data quality** | How many nodes have a fresh neighbour scan, and how to get one at every node. | Shown only when some nodes fell back to `SCAN_CACHED` because of Android's limit of four scans per two minutes. |

### Thresholds

| Measure | Excellent | Very good | Good | Fair | Weak | Poor |
| --- | --- | --- | --- | --- | --- | --- |
| Wi-Fi RSSI (dBm) | ≥ -50 | ≥ -60 | ≥ -67 | ≥ -70 | ≥ -80 | below -80 |
| LTE/NR RSRP (dBm) | ≥ -80 | | ≥ -90 | ≥ -100 | | below -100 |
| RSRQ (dB) | | | ≥ -10 | ≥ -15 | | below -15 |
| SINR (dB) | ≥ 20 | | ≥ 13 | ≥ 0 | | below 0 |

-67 dBm is the figure usually quoted as the minimum for reliable video calls and streaming, so
the report lists every node below it. These bands are common rules of thumb; vendors and
operators draw them slightly differently, so cite them as such.

### Example

This is the report from a real 3 x 3 survey of a room with 1 m cells, with network names
replaced and the Overview, link speed and data-quality lines left out for length. That survey was made with a build that still had the IPv6 ping bug, which the report
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
- Interference looks low: the strongest neighbour, Neighbour-A, is at -86 dBm on channel 6, and
  the one that overlaps your channel is weak.

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

- **The heatmap colours are relative.** The colour scale stretches from the weakest node to the
  strongest, so red means weakest in this room, not necessarily bad. Quote the dBm figures next
  to the image.
- **Small rooms show small, noisy differences.** The ±dB figure is how much readings varied at
  one spot over the few seconds of logging. It understates how much a repeat survey can differ,
  because your body, the way the phone is held and reflections all change between passes.
  Surveying the same room twice and comparing is the honest way to show repeatability.
- **"The router is probably on this side" is an inference.** It is drawn only from the signal
  gradient, which walls, furniture and reflections also shape. Mark the real router position
  with the router node if you know it.

## What is measured at each point

| Quantity | Source |
| --- | --- |
| RSSI, BSSID, SSID, frequency, link speed | `WifiManager` connection info, polled every 500 ms. This path is not scan-throttled. |
| Cellular signal, RSRP, RSRQ, SINR, cell id, TAC, PCI, ARFCN, operator | `TelephonyManager.getAllCellInfo`, polled every 500 ms, reading the registered cell. LTE and NR report RSRP, RSRQ and SINR; WCDMA and GSM report the generic signal strength only. |
| Latency, jitter, packet loss | `ping -c 10`. The target is the per-survey override if set, otherwise the default-route gateway from `LinkProperties` on Wi-Fi and 8.8.8.8 on cellular, because carrier NAT usually makes the mobile gateway unreachable. Jitter is the mean absolute difference of consecutive RTTs. |
| Neighbour access points | One `startScan` per point, within the four-per-two-minutes throttle. When the budget is spent the cached scan list is used instead and those rows are stored as `SCAN_CACHED`. |

Every reading is stored, not just the aggregates. A logging run writes one `LINK` row and one
`CELL` row per poll, one `PING` row carrying the latency, jitter and loss along with the host
that was pinged, and one row per neighbour AP. Both radios are recorded at every point
regardless of which one the survey is nominally about.

## Permissions

Android reports the SSID as `<unknown ssid>` and the BSSID as `02:00:00:00:00:00`, and refuses
to return cell info at all, unless the app holds `ACCESS_FINE_LOCATION` and location services
are switched on. That is an Android requirement for reading radio identifiers, not something
GridSense wants for itself: the app never requests a location fix. Logging stays blocked
until the location permission, the location services switch and a readable radio are all in
place.

## Building

Requires JDK 17 and the Android SDK with platform 34.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The unit tests cover the pure survey mathematics (grid generation and node labels,
point-in-polygon, median, jitter, inverse distance weighting and the ping output parser) and
every rule in the report, using fixtures shaped like real survey data.

## Data model

```
SurveyRoom(name, mode, samplesPerPoint, pingHost, polygon, gridRows, gridCols, cellSizeM, createdAt)
  GridPoint(seq, row, col, x, y, enabled, status)
    Sample(ts, kind,
           bssid, ssid, freqMhz, rssiDbm, linkSpeedMbps,
           cellTech, cellDbm, cellLevel, rsrp, rsrq, sinr, cellId, tac, pci, arfcn, operator,
           pingHost, rttAvgMs, rttJitterMs, rttLossPct)
  Router(x, y, bssid, label)
```

Positions are metres from the corner of cell A1, at the centre of each cell. Surveys persist in
Room, so one can be closed and resumed mid-way. A point left mid-run by a killed process goes
back to pending when the survey is reopened.
