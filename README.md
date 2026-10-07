# HealthCal

A tiny personal Android app that copies **sleep** and **workouts** from Health Connect (where Google Health / Pixel Watch data lands) into any calendar on your phone. Everything runs on-device; events reach Google Calendar through normal account sync.

## What lands in your calendar

**Sleep** — event from bedtime to wake time, titled `Sleep | Dur: 7 hours 5 mins | Start: 11:12 pm End: 6:54 am` (Dur is time asleep). Notes:

```
Bedtime 11:12 PM, woke 6:54 AM
Time in bed: 7h 42m
Asleep: 7h 05m

Deep: 1h 18m in 3 segments
  • 12:40 AM – 1:15 AM (35m)
  • 2:02 AM – 2:31 AM (29m)
  • 3:40 AM – 3:54 AM (14m)
REM: 1h 30m
Light: 4h 17m
Awake: 37m

Source: Google Health
[hc:sleep:…]
```

**Workouts** — event from start to end, titled e.g. `Run | Dur: 45 mins | Start: 6:10 pm End: 6:55 pm`, with type, times, duration and source in notes.

Events are marked **Free** so they don't block your availability. The last line `[hc:…]` is how the app recognises its own events; leave it in place.

## Places and drives

Turn on **Log places and drives** (step 4 in the app) and you also get:

| Event | Example |
|---|---|
| Markers | `Left home | At: 8:00 am`, `Arrived office | At: 9:00 am`, `Left office`, `Arrived home` |
| Work | `At work | Dur: 8 hours 30 mins | Start: 9:00 am End: 5:30 pm` |
| Gym | `Gym | Dur: 55 mins | Start: ... End: ...` (visits under 10 minutes are ignored as drive-bys) |
| Commute | `Commute to work by shuttle | Dur: 1 hour | Start: 8:00 am End: 9:00 am` |
| Drive | `Drive | Dur: 23 mins | Start: ... End: ...` for car trips that aren't part of a commute |

Changing the title format doesn't create duplicates: the next sync or Backfill renames existing events in place.

**How each is detected (no polling):**

- **Home, office, gym:** a geofence and/or the place's Wi-Fi. Geofences give exact leave times; Wi-Fi gives fast arrivals. Set both when you can. For a Wi-Fi-only place, leaving is inferred when your car connects or you join the shuttle Wi-Fi.
- **Car:** check your car's Bluetooth. Connect = drive start, disconnect = drive end.
- **Shuttle:** add the shuttle Wi-Fi name. Any commute where the phone joined it is labeled "by shuttle". Your phone must have joined that network before.

Leaving and coming back within 10 minutes counts as never having left, so GPS jitter doesn't create junk events. New signals show up in the calendar within about 2 minutes (11 minutes for a "left" event, to rule out a quick return). The **Recent signals** list in the app shows exactly what was logged, which helps when tuning radius or Wi-Fi names.

**Setup:** grant precise location, then **Allow all the time**, then nearby devices. Pick a calendar. For each place, stand there and tap **Set to where I am now** (or add its Wi-Fi). Check your car. Add the shuttle Wi-Fi.

Place history only exists from the day you turn this on; Health Connect data can be backfilled, location history can't.

## Build and install (about 10 minutes)

1. Install Android Studio (latest stable).
2. **File → Open** this `HealthCal` folder. Let Gradle sync. If Studio offers to upgrade AGP/Kotlin/dependencies, accept.
   - If `connect-client:1.1.0` doesn't resolve, change it in `app/build.gradle.kts` to the newest 1.1.x.
3. On your Fold: **Settings → About phone → tap Build number 7×**, then **Developer options → Wireless debugging** on.
4. In Studio, pair the phone (Device Manager → Pair using Wi-Fi), pick it, press **Run ▶**.

## First-time setup in the app

1. **Grant Health Connect access** and allow all four: Sleep, Exercise, background, and history.
2. **Grant calendar access**, then pick a calendar for sleep and one for workouts (same or different).
   - Tip: create a "Health" calendar in Google Calendar on the web first. It shows up here after your phone syncs.
3. Pick a **backfill start date** and tap **Backfill and repair**.
4. Turn on **Sync automatically** (every 6 hours, re-checks the last 7 days).
5. Optional: pull down Quick Settings → edit → drag **Sync health** into your tiles for one-tap syncing.

Also check that Google Health is writing to Health Connect:
**Settings → Security & privacy → Privacy controls → Health Connect → App permissions → Google Health** → allow Sleep and Exercise (write).

## How sync and repair work

Every run is a reconcile, not a blind export:

| Situation | What happens |
|---|---|
| Record has no event | Event is inserted |
| Event exists but times/stages changed | Event is updated |
| Same record has 2+ events | Oldest is kept, extras deleted |
| Everything matches | Nothing is touched |

So **Backfill and repair** is safe to run as often as you like; it only fills gaps and fixes drift. If you delete an event by hand, the next sync puts it back (uncheck that data source if you don't want it).

**Data sources:** after the first sync, every app writing sleep/workouts to Health Connect is listed. Uncheck any you don't want (e.g. if Strava or Sleep Cycle also writes and you get duplicates).

## The three tabs

| Tab | What it shows |
|---|---|
| **Setup** | Permissions, calendar pickers, data sources, places, sync buttons |
| **Found** | Every sleep session and workout the last scan read from Health Connect, plus place signals. Each row shows the exact event title, which app wrote it, which calendar it goes to, and what happened: Added, Updated, In calendar, Source ignored, or Couldn't write. Filter by Sleep, Workouts or Places. |
| **Events** | The events actually sitting in your calendars, with their exact titles. Each calendar gets a status card that says whether its events have reached Google, and offers **Sync now** or **Turn on sync** when they haven't. Each event shows **On Google**, **Waiting to upload**, or **Phone only**. |

Colors: sleep is indigo, workouts are teal, places are blue, everywhere in the app.

## If events don't show up in Google Calendar

Open the **Events** tab and read the card for that calendar:

| Card says | Fix |
|---|---|
| Lives only on the phone | Pick a Google calendar in Setup, then Backfill |
| Sync is off for this calendar | Tap **Turn on sync** |
| Hidden in your calendar apps | Tap **Show it** |
| Calendar sync is off for your account | Tap **Sync now**, or Settings → Passwords & accounts → your account → Account sync → Calendar |
| Waiting to upload | Tap **Sync now** |

If sleep shows up but workouts don't, check Setup: the workout picker may point at a different calendar. Setup warns you when they differ and offers **Use the sleep calendar for workouts**.

## Files

| File | Job |
|---|---|
| `HealthReader.kt` | Reads sleep + exercise sessions from Health Connect |
| `EventFormatter.kt` | Builds title/notes (deep segments, stage totals, workout type) |
| `CalendarRepo.kt` | Lists calendars; finds/inserts/updates/deletes tagged events |
| `SyncEngine.kt` | The reconcile logic above |
| `SyncWorker.kt` | Background job, manual runs, 6-hour schedule, quick places runs |
| `Places.kt` | Home / office / gym definitions and their settings |
| `PlaceMonitor.kt` | Arms geofences and the Wi-Fi watcher; lists Bluetooth devices |
| `Receivers.kt` | Logs geofence, Wi-Fi, car Bluetooth and boot events |
| `SignalLog.kt` | Small on-device SQLite log of those signals |
| `PlacesEngine.kt` | Turns signals into markers, stays, commutes and drives |
| `CalendarReconciler.kt` | Shared insert / fix / dedupe / clean-up logic |
| `PlacesSection.kt` | The places and drives settings UI |
| `SyncTileService.kt` | Quick Settings tile |
| `MainActivity.kt` | Header, tabs and the Setup tab |
| `FoundTab.kt` | The Found tab |
| `EventsTab.kt` | The Events tab and calendar upload checks |
| `ScanReport.kt` | Saves the last scan for the Found tab |
| `Theme.kt` | Cool color palette, per-kind colors, shared row and label styles |
