# GridSense: next iteration spec

Audience: an engineer or coding agent picking this repo up cold. It covers four new analysis
features and a UI restructure. Read the whole document before starting; the phases at the end
say what order to build in.

## 1. Where the project is now

GridSense is an Android app for a Computer Networks course project: an indoor Wi-Fi and cellular
coverage survey. The surveyor splits a room into an n x m grid, stands at the centre of each cell
(a "node", labelled A1, B1, ... from the starting corner) and logs it. Each logging run takes about
10 seconds and stores raw samples. The app draws an inverse-distance-weighted heatmap and writes a
rule-based plain-language report.

Stack: Kotlin, Jetpack Compose (BOM 2024.09.00, Material 3), Room 2.6.1 with KSP, single
activity, minSdk 29, compileSdk and targetSdk 34, AGP 8.5.2, Kotlin 2.0.20, Gradle 8.7 via the
wrapper. No GPS or location fix is ever used; `ACCESS_FINE_LOCATION` is held only because Android
requires it to read Wi-Fi and cell identifiers.

Build and test:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

There is no emulator or device in the build environment. Everything that can be pure Kotlin must
be pure Kotlin with JVM unit tests; that is the main way correctness is checked.

### Code map

| Path | Role |
| --- | --- |
| `core/Geometry.kt` | `Pt`, bounds, point-in-polygon, median, jitter (mean absolute difference), IDW. |
| `core/Grid.kt` | Grid node generation, outline, `nodeLabel` (A1 style). |
| `core/Metric.kt` | `Metric` enum (label, unit, higherIsBetter, band) and `SurveyMode`. |
| `core/Ping.kt` | Ping output parser and `PingResult`. |
| `data/Entities.kt` | Room entities `SurveyRoom`, `GridPoint`, `Sample`, `Router`, sample `KIND_*` constants. |
| `data/SurveyDao.kt`, `data/Db.kt` | DAO and database (currently version 4). |
| `data/Aggregate.kt` | Per-node metric values, raster for the heatmap, colour ramp. |
| `data/Insights.kt` | The rule-based report (`buildInsights`). Fully unit tested. |
| `net/LinkReader.kt` | Wi-Fi connection info, gateway (IPv4 only), ping target choice. |
| `net/CellReader.kt` | Serving cell from `TelephonyManager.getAllCellInfo`. |
| `net/Scanner.kt` | Neighbour scans within Android's 4-per-2-minute throttle. |
| `net/Pinger.kt`, `net/Readiness.kt` | Ping subprocess; permission and radio readiness. |
| `export/Exporter.kt` | PNG, CSV and JSON export and share. |
| `ui/SurveyViewModel.kt` | All screen state, setup, the logging run, routers. |
| `ui/*Screen.kt`, `ui/*Pane.kt` | Home, Setup, Permission, Grid (with tabs), Heatmap, Report. |
| `ui/Plan.kt`, `ui/HeatmapScene.kt` | Plan transform and drawing shared by screen and PNG export. |

### What a logging run stores

Per node, `samplesPerPoint` polls at 500 ms, each writing one `LINK` row (Wi-Fi) and one `CELL`
row (serving cell); one `PING` row (`ping -c 10` to the IPv4 gateway on Wi-Fi, 8.8.8.8 on
cellular, or a per-survey override; avg, jitter, loss, host); and one row per neighbour AP from a
scan (`SCAN`, or `SCAN_CACHED` when throttled). Keep this raw-sample approach for everything new:
store per-attempt rows, aggregate at read time.

### What real data looks like

A real 3 x 3 survey of a home room with 1 m cells gave Wi-Fi RSSI from -62 to -50 dBm on
2.4 GHz channel 1, a link speed of 88 to 194 Mbps, the same router's 5 GHz radio at about
-71 dBm, six neighbours at -76 to -86 dBm, LTE RSRP -96 to -110 dBm with SINR 0 to 6 dB and two
serving cells, and only the first four nodes with fresh scans. Two passes of the same room
disagreed on which node was weakest. Use numbers of this shape for test fixtures.

## 2. Hard constraints

1. **Do not lose existing surveys.** The database currently uses
   `fallbackToDestructiveMigration()`, so any schema change wipes the user's real data. Before
   any schema change, remove the destructive fallback, turn on `exportSchema` with a `schemas/`
   directory committed to the repo, and write explicit `Migration` objects (`ALTER TABLE ... ADD
   COLUMN`, nullable or with defaults) from version 4 onward. A survey created on the current
   build must survive the upgrade with all its samples.
2. **No positioning features.** Step counting, ARCore and GPS were all tried and removed. The
   grid is the positioning method.
3. **Keep the report rule-based.** No network calls and no LLM; the same data must give the same
   text. New findings go into `Insights.kt` style pure functions with unit tests.
4. **No chart or UI libraries beyond AndroidX.** Charts are drawn with Compose `Canvas`, like the
   heatmap. `androidx.navigation:navigation-compose` and `material-icons-extended` are fine.
5. **Pure logic in `core/` or `data/`, with JVM unit tests.** Every formula, threshold and
   recommendation in this spec needs tests, including degenerate inputs.
6. **Don't break exports.** New columns are appended to CSV rows, and new fields are added to
   JSON. Existing column order stays the same.
7. **Mobile data is not free.** Anything that downloads must be opt-in, must say how much it
   uses, and must be off by default on cellular.
8. **Work on a branch** (`next-iteration`), one commit per coherent step, and open a pull request
   against `main`. Do not push to `main`. Keep the build and all tests green at every commit.

## 3. Feature A: path-loss model fit

**Why.** The heatmap shows where the signal is; a path-loss fit tests the theory. The
log-distance model is

```
RSSI(d) = P0 - 10 * n * log10(d / d0),   d0 = 1 m
```

where `n` is the path-loss exponent (2 in free space, roughly 2 to 4 indoors) and `P0` is the
RSSI at 1 m. Fitting it to survey data gives the report a measured-versus-theory result.

**Inputs.** A survey with at least one router marker (`Router`: x, y, bssid, label). For each
router, take each logged, enabled node's median RSSI for that router's BSSID: `LINK` rows with
that BSSID first, otherwise `SCAN`/`SCAN_CACHED` rows with that BSSID. Distance is the
horizontal distance from the node to the router. Add an optional height difference to `Router`
(`heightDiffM`, default 0, entered on the router dialog as "router height above the phone"), and
use `sqrt(dx^2 + dy^2 + h^2)`.

**Fit.** Ordinary least squares of RSSI on `x = 10 * log10(d)`: slope `b`, intercept `a`, so
`n = -b` and `P0 = a`. Also report R², the residual standard deviation σ (the shadowing term,
in dB), the node count N and the distance range. Exclude nodes closer than 0.5 m, because the
log term blows up there.

**Validity.** Show the fit with a clear warning when N < 4 or when `max(d) / min(d) < 2`. A
3 x 3 room with 1 m cells has distances from about 0.7 to 3.5 m, so this warning will be common.
Say so plainly in the UI and the report rather than presenting a shaky `n` as fact.

**Interpretation bands.** Below 2 means guided propagation, such as along a corridor or with
strong reflections. 2 to 2.5 is close to free space or line of sight. 2.5 to 3.5 is typical
indoors. 3.5 to 4.5 is obstructed. Above 4.5 is heavily obstructed. Optionally, estimate EIRP as
`P0 + FSPL(1 m, f)` with `FSPL(1 m) = 20 log10(f_MHz) - 27.55` (about 40 dB at 2.4 GHz). Label
it as an estimate.

**UI.**
- A scatter plot of RSSI against distance on a log axis, with the fitted line and a free-space
  reference line (n = 2 through the same P0).
- A small table of n, P0, σ, R², N and the distance range, plus the interpretation sentence.
- Tapping a point identifies its node.
- If there is no router marker, an empty state explains how to place one.

**Report.** Add a "Path loss" section with n, the band, σ and the validity warning when it
applies.

**Tests.**
- Synthetic data generated with a known n and P0 recovers both to within 0.01.
- Added noise gives the expected σ.
- Fewer than 2 usable nodes returns no fit.
- Points closer than 0.5 m are excluded.
- The validity flags trip correctly.
- LINK readings take precedence over SCAN readings for the same BSSID.

## 4. Feature B: comparing two surveys

**Why.** A controlled comparison is the strongest experiment a coverage project can report:
router in position A versus B, door open versus closed, 2.4 versus 5 GHz on the same grid, or
the same setup twice to measure repeatability. The two passes of the real room above disagreed,
and this feature is how to quantify that.

**Supporting changes.**
- Add `condition: String` (default empty) to `SurveyRoom`, editable at setup and later. It holds
  the experimental condition, for example "door closed".
- Add **Duplicate survey** on Home. It creates a new survey with the same grid, cell size, mode,
  samples and ping target, and a new name and condition. All nodes start pending.

**Matching.** Two surveys are comparable when they have the same rows and columns. Match nodes
on `(row, col)`. Only compare nodes that are logged and enabled in both. If the grids differ,
explain why the surveys can't be compared, and don't attempt interpolation.

**Output.** For a chosen metric, compute Δ = B − A per node, then show:
- A difference heatmap with a diverging colour scale centred on 0. Its sign must respect
  `higherIsBetter`, so "better in B" always gets the same colour whatever the metric.
- A node table: label, A, B, Δ.
- A summary: mean Δ, mean |Δ|, the largest improvement and the largest degradation with their
  nodes, and how many nodes changed by at least a threshold. The threshold is 3 dB for RSSI and
  RSRP; pick and document sensible defaults for the other metrics.
- A **repeatability** reading. When the user marks the pair as "same conditions", headline the
  mean |Δ| and the standard deviation of Δ as the measurement repeatability. Later comparisons
  can quote it ("changes under ±X dB are within repeatability").

**Entry points.** From Home, choose *Compare*, then pick two surveys, with incompatible ones
greyed out and the reason shown. Also from a survey's overflow menu, via *Compare with…*.

**Export.** A PNG of the difference map with a legend, and a CSV of the node table.

**Tests.**
- Matching on (row, col).
- Unlogged nodes on either side are excluded.
- The sign follows `higherIsBetter`.
- Summary numbers on fixtures.
- Incompatible grids are rejected.
- The repeatability maths.

## 5. Feature C: channel congestion analysis

**Why.** Neighbour scans are already collected. Turning them into a channel occupancy chart and
a channel recommendation covers co-channel and adjacent-channel interference, a core course
topic.

**Supporting data.**
- Store `channelWidthMhz` (from `ScanResult.channelWidth`) and `centerFreq0Mhz` on scan rows.
  Optionally store `wifiStandard` (API 30+, nullable on 29).
- Older rows lack these, so assume 20 MHz.

**Deduplication.**
- One entry per BSSID: median RSSI across the nodes that saw it, and the number of nodes that
  saw it.
- Identify the user's own networks: the connected SSID, plus BSSIDs on the same router. Treat
  BSSIDs that differ from the connected one only in the first or last octet as the same device.
  Hidden networks fall under this rule, and the real data had two of them.

**Chart.**
- One chart per band. 2.4 GHz shows channels 1 to 13. 5 GHz shows the channels actually seen.
- Each network is drawn as a shape spanning its real width, with height set by its median RSSI.
- The user's own network is highlighted.
- Labels show the SSID, or "(hidden)".

**2.4 GHz recommendation.**
- Score each candidate channel c in {1, 6, 11} as Σ over other networks of
  `overlap(c, ch) × power_mW(rssi)`.
- `overlap` is the fractional spectral overlap of two 20 MHz channels 5 MHz apart per channel
  step: 1 at Δ = 0, falling linearly to 0 at |Δ| ≥ 4. Use the stored widths when they are
  present.
- Recommend the lowest score. Only advise a change when it beats the current channel by at least
  3 dB, which is a factor of 2. Otherwise say the current channel is fine.

**5 GHz.**
- List co-channel and overlapping networks.
- Recommend the least occupied channel.
- Note that channels 52 to 144 are DFS channels: the router may vacate them on radar detection,
  which costs a short outage.

**Caveats to state.** A phone scan is a snapshot of beacons. It shows signal strength, not how
busy a neighbour is, and real airtime utilisation can't be measured without monitor mode.

**Report.** Add a "Channels" section with the occupancy summary, the recommendation and the
caveat. Fold the existing interference sentences from "Bands and neighbours" into it rather than
duplicating them.

**Tests.**
- Overlap at Δ = 0 to 5.
- dBm to mW conversion.
- Scoring on fixtures, including a case where channel 1 is clearly best and one where the
  current channel is already best.
- Own-network detection, including hidden BSSIDs.
- 5 GHz DFS flagging.
- Missing width defaults to 20 MHz.

## 6. Feature D: latency beyond ping

**Why.** ICMP to the gateway measures only the first hop. Real experience depends on DNS, TCP
setup, and especially queueing under load (bufferbloat).

**Permissions.** Add `android.permission.INTERNET`. The app currently has no socket access; ping
works only because it runs as a subprocess.

**Survey settings.** Add these to setup and store them on `SurveyRoom`:
- **Extended tests** (DNS and TCP; on by default). These add about 3 to 5 seconds per node.
- **Load test** (off by default, and forced off with a warning when the active network is
  cellular unless the user confirms). The UI states the data used per node, for example
  "about 20 MB per node".
- Fields for the DNS name, the TCP host and port, and the download URL, with defaults.

**Measurements.**
- **DNS lookup time.**
  - Use `android.net.DnsResolver` (API 29) with `FLAG_NO_CACHE_LOOKUP`, so that each query
    reaches the network's resolver.
  - Query A records for a configurable name (default `example.com`) 5 times.
  - Record each time and each failure.
  - Report the median and the failure count.
- **TCP connect time.**
  - Use `java.net.Socket().connect()` with a 2 s timeout to a configurable host and port
    (default `1.1.1.1:443`), 5 attempts.
  - Record each attempt.
  - The handshake costs about one round trip, so the report should compare it with the ICMP
    RTT.
- **Latency under load.**
  - Start an HTTP download from a configurable URL (default
    `https://speed.cloudflare.com/__down?bytes=20000000`; it is a third-party endpoint and may
    change, so it must be configurable).
  - Run the download for at most 8 s, and cap it at the byte count.
  - Meanwhile ping the same target as the idle ping with `-i 0.2`. Non-root Linux ping does not
    allow a shorter interval.
  - Record the loaded RTTs, the bytes received and the duration.
  - Derive the loaded median RTT, bufferbloat as loaded minus idle median, and download goodput
    in Mbps.
  - Grade bufferbloat: below 5 ms excellent, below 30 ms good, below 60 ms fair, otherwise poor.
    These are Waveform-style grades; cite them as such.

**Storage.**
- New sample kinds `DNS`, `TCP`, `LOADED_PING` and `DOWNLOAD`, one row per attempt.
- New nullable columns: `probeMs`, `probeOk`, `bytes`, `durationMs`, `target`. Reuse
  `pingHost`/`rtt*` only where the meaning is identical.
- Aggregate at read time.

**Logging run.**
- Show named stages in the sheet: sampling radios, ping, scan, DNS, TCP, load test. Show a
  progress indicator per stage, not one fake 10 s ring.
- A failure in one stage must not abort the others.

**Heatmap metrics.** DNS ms, TCP connect ms, loaded latency ms, bufferbloat ms and download Mbps.
Add each with the correct `higherIsBetter`.

**Report.** Extend "Latency, jitter and loss":
- DNS median and failures.
- TCP connect against ICMP RTT.
- Loaded against idle RTT, with the bufferbloat grade.
- Goodput against the reported link speed. This also answers "link speed isn't throughput".

**Tests.**
- Aggregation of per-attempt rows.
- Bufferbloat maths and grading.
- Goodput from bytes and duration.
- Report sentences for success, partial failure, total failure and a skipped load test.
- Settings defaults.

The network calls themselves can't be unit tested here. Keep them thin wrappers around pure
functions that are tested.

## 7. UI restructure: one cohesive app

The screens were built one at a time while the positioning approach changed three times, and it
shows. Fix the structure first, so that the new features land on the new foundation.

### Problems in the current code

- **No back handling at all.** The phone's back button exits the app from every screen, mid-setup
  included. Navigation is a hand-rolled `Screen` sealed class driven by the ViewModel.
- **No theme.** `MaterialTheme {}` runs with the default scheme, and `themes.xml` uses the
  platform `Theme.Material.Light`. There is no dark mode, no brand colour and no typography
  scale.
- **Colours are hardcoded in 22 places** across `GridScreen`, `HeatmapScene`, `SetupScreen` and
  `Plan`. The same "quality" idea is drawn differently on the grid dots, the heatmap and the
  legend.
- **No icons anywhere.** Navigation uses text buttons ("Rooms", "Cancel"). Both floating action
  buttons pass `icon = {}`.
- **Actions are undiscoverable.**
  - Deleting a survey is a long-press on its card.
  - Retaking a node is a long-press on the grid.
  - Exports are a row of three outlined buttons at the bottom of the heatmap tab.
- **Status messages are inconsistent.**
  - Transient notes appear in a hand-made yellow banner with a "Hide" button.
  - Blocking states are red text.
  - Nothing uses a snackbar.
- **The survey top bar crams** "name   6 / 9 done" into one title string.
- **Heatmap controls** are outlined buttons pretending to be dropdowns, followed by a lone
  switch.
- **The node sheet** is a column of loose text lines with no hierarchy.
- **The permission screen** shows status as "OK" and "No" text.

### Target structure

**Navigation.**
- Use `navigation-compose` with real routes: Home, Setup (new, or edit and duplicate),
  Survey/{id}, Compare/{a}/{b}, and Permissions.
- System back must behave on every screen. Open sheets and dialogs close first, Setup returns to
  Home without saving (confirm if the form was edited), and Survey returns to Home.
- The ViewModel stops owning navigation. Either keep one ViewModel scoped per destination where
  that is cleaner, or keep the shared one for survey data only.

**Information architecture.**
- **Home.** A list of surveys, each showing:
  - name and condition,
  - grid size and date,
  - a progress bar (logged / total),
  - a quality chip for the primary metric median.

  Each survey has an overflow menu with Duplicate, Compare with…, Export and Delete (with
  confirmation). The FAB is "New survey" with an icon. Home has a proper empty state.
- **Survey.**
  - A top app bar with a back arrow, the survey name as the title, and "6 of 9 nodes · Wi-Fi" as
    the subtitle.
  - The bar's actions are Share/Export (one menu: PNG, CSV, JSON, report text) and an overflow
    with Edit details, Duplicate, Compare with… and Delete.
  - A bottom navigation bar with four destinations: **Grid**, **Heatmap**, **Analysis** (path
    loss, channels, latency, each as a section or sub-tab) and **Report**.
- **Compare.** Its own screen: the two surveys' names and conditions, a metric picker, the
  difference map, the summary and the node table.

**Design system.**
- **Theme.**
  - Add a `GridSenseTheme` with a defined Material 3 light and dark `ColorScheme`, following the
    system setting.
  - Change `themes.xml` to a `NoActionBar` DayNight parent.
  - Enable edge to edge with correct insets.
- **One quality scale.** Signal quality colours live in one place, as semantic tokens such as
  excellent through poor plus "no data". They are used by:
  - the grid dots,
  - the heatmap's fixed-scale mode (below),
  - legends,
  - quality chips in the sheet, Home and the report.

  No `Color(0x...)` literals outside the theme file.
- **Spacing and shape.** 4/8/12/16/24 dp spacing and consistent card shapes throughout.
- **Shared components**, so every screen is assembled from the same parts:
  - `ScreenScaffold`
  - `SectionCard`
  - `MetricRow` (label, value, unit, quality chip)
  - `QualityChip`
  - `EmptyState`
  - `StatusBanner` (for blocking states only; transient notes use a snackbar)
  - `ConfirmDialog`
  - `StageProgress` (for the logging run)

**Screen-level fixes.**
- **Setup.**
  - The form is split into sections: Survey (name, condition), Grid (rows, columns, cell size,
    live preview), and Radios and tests (primary radio, samples, ping target, the extended and
    load test settings from Feature D).
  - Validation messages sit under fields, the keyboard's Next and Done actions work, and Create
    is disabled with the reason shown.
- **Grid.**
  - Node labels are drawn on the plan.
  - A legend explains pending, logging, done and disabled.
  - Done nodes use the shared quality colours.
  - Retake moves into the sheet as a visible button. Long-press can stay as a shortcut.
  - The router FAB gets an icon and explains placement mode with a snackbar.
- **Node sheet.**
  - A header with the node label and a status chip.
  - Metric rows grouped as Wi-Fi, Cellular and Network, each with a quality chip.
  - The primary action button is pinned.
  - While logging, it shows `StageProgress`.
- **Heatmap.**
  - Metric choice as chips grouped by family (Wi-Fi, Cellular, Network).
  - The BSSID filter is a proper exposed dropdown, shown only for Wi-Fi metrics.
  - A **Relative / Fixed scale** toggle. Fixed uses the threshold bands, so red really means
    poor, which fixes the "red means weakest in this room" problem the report currently has to
    warn about.
  - Exports move to the top bar menu.
- **Report.** Section cards with an icon each and quality chips on rated values. The share
  action moves to the top bar.
- **Permissions.** A checklist: one row per requirement, with an icon, a state and an inline
  action button where one applies.

**Accessibility.**
- Content descriptions on every icon button.
- Touch targets of at least 48 dp.
- Colour never the only signal: quality chips carry words, and the map has labels.
- Text scales with the system font size without clipping in the sheet or the cards.

**Out of scope.** Moving all strings into `strings.xml` and translation.

## 8. Phases and acceptance

Build in this order, one commit per bullet or smaller, keeping the build and tests green.

1. **Foundation.**
   - Database migration infrastructure: no destructive fallback, exported schemas, and a 4→5
     migration even if empty.
   - Theme, quality tokens and shared components.
   - navigation-compose with working back.
   - Restyle the existing screens onto it.

   *Acceptance:* existing features behave exactly as before; back works everywhere; no colour
   literals outside the theme; a survey made on the old build is still there after upgrading.
2. **Feature C, channels.** It is the smallest and reuses data that already exists. It adds
   width and centre frequency to scan rows through a migration.
3. **Feature A, path loss.** It adds router height through a migration.
4. **Feature B, comparison.** It adds condition and duplicate.
5. **Feature D, latency.** It adds the settings, new sample kinds and the INTERNET permission.
6. **README.** Document each feature the way the existing Report section is documented: what it
   shows, how it is computed, the thresholds, and the caveats.

**Done means:**
- The app builds.
- All unit tests pass.
- Every formula and rule in this document has tests.
- There are no destructive migrations.
- Exports include the new data.
- The report covers each new feature.
- The PR description lists what was built, what was deliberately left out, and anything that
  could not be verified without a device. Because no device is available, that last list
  includes all on-device behaviour.
