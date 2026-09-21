# GridSense

An indoor Wi-Fi and cellular coverage survey app for Android. Positions come from the phone
counting your steps, starting at a corner of the room you pick yourself, so the app works in
buildings with no floor plan and never touches GPS or any other location fix.

## How a survey runs

1. **Settings.** Name the survey, choose whether it is mainly about Wi-Fi or cellular, set the
   samples per point, and optionally override the ping target. Calibrate your stride by walking
   a distance you have measured, because every position in the survey is derived from your step
   count.
2. **Walk the walls.** Stand in the corner you want as the origin, face into the room, and
   start. That corner becomes (0, 0) and the direction you are facing becomes the +y axis. Walk
   the perimeter, marking each corner as you reach it, then return to where you started and
   close the outline. The app reports how far dead reckoning thinks you are from the origin,
   which is the drift accumulated over the whole loop. The corners are kept exactly as
   measured and that figure is stored with the survey.
3. **Mark the points.** Walk to each position you want to survey and mark it. If the live
   marker drifts away from where you actually are, re-anchor it to any corner you are standing
   on. Logging only becomes available once the layout is finished.
4. **Grid.** The plan shows the outline and the points. Grey means pending, a pulsing marker
   means the point is being logged, and a point that is done is coloured by the survey's
   primary metric. Tap a point to open its sheet and press *Start logging*; long-press a point
   to retake it. The title bar carries a done / total counter.
5. **Router node.** The floating button drops a router marker anywhere on the plan. Give it a
   label and pick its BSSID from a live scan list.
6. **Heatmap.** Inverse distance weighting with `p = 2` on a 0.1 m raster clipped to the room
   outline, with a colour ramp and a legend. Switch between Wi-Fi RSSI, the cellular signal
   quantities, latency, jitter and loss, filter by BSSID, and show or hide the routers. Export
   the PNG, or the raw samples, points and routers as CSV or JSON.

## Positioning, and what it costs you

Dead reckoning advances your position by one stride every time the step detector fires, in the
direction the rotation vector sensor reports. Hold the phone flat with its top edge pointing
the way you walk.

The dominant error is heading, not distance. Indoors the magnetometer is pulled by steel studs,
lift shafts, electrical risers and metal door frames, so expect position error on the order of
one to three metres over a room-sized walk. Re-anchoring at a corner you are physically
standing on clears the drift accumulated so far and is the main way to keep it bounded. Quote
the closure error from the perimeter walk in your report; it is an honest measure of how good
the geometry is.

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
GridSense wants for itself: the app never requests a location fix. `ACTIVITY_RECOGNITION` is
needed separately, because that is what lets the phone report each step. The first launch
explains all of this and logging stays blocked until the permissions, the location services
switch and a readable radio are all in place.

## Building

Requires JDK 17 and the Android SDK with platform 34.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The unit tests cover the pure survey mathematics: point-in-polygon, dead-reckoning step
displacement, stride calibration, closure distance, median, jitter, inverse distance weighting
and the ping output parser.

## Data model

```
SurveyRoom(name, mode, stepLengthM, samplesPerPoint, pingHost, polygon, closureErrorM, createdAt)
  GridPoint(seq, x, y, enabled, status)
    Sample(ts, kind,
           bssid, ssid, freqMhz, rssiDbm, linkSpeedMbps,
           cellTech, cellDbm, cellLevel, rsrp, rsrq, sinr, cellId, tac, pci, arfcn, operator,
           pingHost, rttAvgMs, rttJitterMs, rttLossPct)
  Router(x, y, bssid, label)
```

Positions are metres from the origin corner. Surveys persist in Room, so one can be closed and
resumed mid-way, although the layout walk itself has to be finished in a single session because
the tracker's frame of reference is not saved. A point left mid-run by a killed process goes
back to pending when the survey is reopened.
