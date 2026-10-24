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
   metric. Stand at a node, tap it and press *Start logging*; long-press to retake it. A node
   you cannot stand on, such as one under a table, can be disabled from its sheet. The title
   bar carries a done / total counter.
3. **Router node.** The floating button drops a router marker anywhere on the plan. Give it a
   label and pick its BSSID from a live scan list.
4. **Heatmap.** Inverse distance weighting with `p = 2` on a 0.1 m raster clipped to the grid,
   with a colour ramp and a legend. Switch between Wi-Fi RSSI, the cellular signal
   quantities, latency, jitter and loss, filter by BSSID, and show or hide the routers. Export
   the PNG, or the raw samples, nodes and routers as CSV or JSON.

5. **Report.** A plain-language write-up of the survey, worked out from the logged samples
   with fixed rules, so the same data always gives the same text. It rates Wi-Fi and cellular
   against common thresholds (-67 dBm for streaming, RSRP/RSRQ/SINR bands), names the weakest
   and strongest nodes, says whether the node-to-node spread is bigger than measurement noise
   and which side the router is probably on, points out an unused 5 GHz band, overlapping
   neighbours and hidden networks, notices tower switching, explains failed pings, and flags
   cached scans. *Share report as text* sends it to any app for pasting into a write-up.

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

The unit tests cover the pure survey mathematics: grid generation and node labels,
point-in-polygon, median, jitter, inverse distance weighting and the ping output parser.

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
