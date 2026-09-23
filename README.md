# GridSense

An indoor Wi-Fi and cellular coverage survey app for Android. Positions come from ARCore
motion tracking, starting at a corner of the room you pick yourself, with manual placement on
the plan as a fallback. The app works in buildings with no floor plan and never touches GPS or
any other location fix.

## How a survey runs

1. **Settings.** Name the survey, choose whether it is mainly about Wi-Fi or cellular, set the
   samples per point, and optionally override the ping target. Then pick how to get the room
   outline: walk it with AR, or type the width and length of a rectangular room.
2. **Measure the outline (AR).** You never have to reach a corner, so tables along the walls
   do not matter. Face any wall, aim the crosshair at its left-hand corner, anywhere up the
   edge where the two walls meet, and press *Mark corner*; ARCore measures where the line of
   sight hits the wall. That corner becomes (0, 0). Aim at the right-hand corner of the same
   wall next: that wall becomes the +y axis and +x points back into the room. Carry on round
   the room to your right. To finish, aim at corner 1 again and press *Close*: how far that
   second measurement lands from the first is the closure error. Corners are kept exactly as
   measured and the figure is stored with the survey.
3. **Mark the points.** Walk to each survey position and press *Mark here*. When AR is not
   available or not trusted, the manual tools take over:
   - *Tap to add* places a point wherever you tap on the plan.
   - *I'm here* corrects drift: tap where you really are and every later AR position shifts
     to match. Tapping near a corner snaps to it exactly.
   - *Align* re-establishes the whole frame by aiming at corner 1 and then corner 2, which is
     what to do if ARCore restarts and loses its world.
   - Long-press and drag moves any corner or point.

   With the typed rectangle you can still use AR for the points: press *Align* and aim at the
   two ends of the length wall. Logging only becomes available once the layout is finished.
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

ARCore tracks the phone from visual features in the camera image combined with the motion
sensors. It is not affected by stride length or by the magnetic interference that ruins
compass headings indoors, and drift over a room is typically tens of centimetres. Tracking
fails on blank walls, in dim light, and when you move fast, so keep the camera pointed at
something with texture and walk steadily. The app is locked to portrait so a rotation cannot
restart the session mid-survey.

Aimed corners come from ARCore hit tests. On phones with the Depth API they hit any visible
surface, plain walls included. Without it they need a detected wall plane or a tracked
feature point, so aim at a spot with some texture, such as a socket, a poster edge or where
the walls meet the ceiling, and sweep the camera over the wall first. Each measurement
reports its distance; aiming from further than a few metres costs accuracy.

Every point records whether ARCore placed it or you did (`source` is `AR` or `MANUAL`, and a
dragged point becomes `MANUAL`). The outline records `AR`, `MANUAL` or `MIXED` for an AR walk
whose corners were later dragged. Report these along with the closure error; together they
say how much of the geometry was measured and how much was judged by eye.

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
GridSense wants for itself: the app never requests a location fix. `CAMERA` is used only by
ARCore for tracking; no image is stored. ARCore is declared optional, so the app installs on
phones without it and falls back to manual placement. Logging stays blocked until the location
permission, the location services switch and a readable radio are all in place.

## Building

Requires JDK 17 and the Android SDK with platform 34.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The unit tests cover the pure survey mathematics: point-in-polygon, the mapping from ARCore's
world onto the room frame, re-anchoring, closure distance, median, jitter, inverse distance
weighting and the ping output parser. ARCore itself only runs on a real phone.

## Data model

```
SurveyRoom(name, mode, samplesPerPoint, pingHost, polygon, outlineSource, closureErrorM, createdAt)
  GridPoint(seq, x, y, source, enabled, status)
    Sample(ts, kind,
           bssid, ssid, freqMhz, rssiDbm, linkSpeedMbps,
           cellTech, cellDbm, cellLevel, rsrp, rsrq, sinr, cellId, tac, pci, arfcn, operator,
           pingHost, rttAvgMs, rttJitterMs, rttLossPct)
  Router(x, y, bssid, label)
```

Positions are metres from the origin corner. Surveys persist in Room, so one can be closed and
resumed mid-way, although the layout itself has to be finished in one go because ARCore's frame
of reference is not saved. A point left mid-run by a killed process goes
back to pending when the survey is reopened.
