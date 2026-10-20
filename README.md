# Daily Logger

_Formerly HealthCal. The package name is still `dev.kapil.healthcal`, so installing over the old app keeps its permissions, settings and calendar events._

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

**Today's sleep marker** — one all-day event on today's date, with the same title as your most recent night's sleep event. It is there for a watch face: a watch only keeps calendar events that have not ended, so it never has the real sleep event. The app keeps just one marker and moves it forward each day. A nap never replaces the night, and after midnight it keeps showing last night until a new night syncs. It is written to the sleep calendar whenever sleep export is on.

**Workouts** — event from start to end, titled e.g. `Run | Dur: 45 mins | Start: 6:10 pm End: 6:55 pm`, with type, times, duration and source in notes.

Events are marked **Free** so they don't block your availability. The last line `[hc:…]` is how the app recognises its own events; leave it in place.

## Places and drives

Turn on **Log places and drives** (step 4 in the app) and you also get:

| Event | Example |
|---|---|
| Markers | `Left home | At: 8:00 am`, `Arrived office | At: 9:00 am`, `Left office`, `Arrived home` |
| Work | `At work | Dur: 8 hours 30 mins | Start: 9:00 am End: 5:30 pm` |
| Gym | `Gym | Dur: 55 mins | Start: ... End: ...` (visits under 10 minutes are ignored as drive-bys) |
| Commute by car | `Commute to work by car | Dur: 45 mins | Start: 8:00 am End: 8:45 am` |
| Commute by shuttle | `Commute home by shuttle | Dur: 38 mins | Start: 4:58 pm End: 5:36 pm`: the time your phone was on the shuttle Wi-Fi |
| Door to door, by shuttle | `Work to home via shuttle and car | Dur: 1 hour 56 mins | Start: 4:55 pm End: 6:51 pm`: left one place to arrived at the other, including any stops |

A shuttle trip gives overlapping events: door to door, the shuttle ride, and a separate `Drive` for any car leg to or from the stop. A car-only commute stays one event.
| Drive | `Drive | Dur: 23 mins | Start: ... End: ...` for car trips that aren't a car commute (including the drive to or from the shuttle stop) |

Changing the title format doesn't create duplicates: the next sync or Backfill renames existing events in place.

**How each is detected (no polling):**

- **Home, office, gym:** a geofence and/or the place's Wi-Fi. Geofences give exact leave times; Wi-Fi gives fast arrivals. Set both when you can. For a Wi-Fi-only place, leaving is inferred when your car connects or you join the shuttle Wi-Fi.
- **Car:** check your car's Bluetooth. Connect = drive start, disconnect = drive end.
- **Shuttle:** add the shuttle Wi-Fi name. Your phone must have joined that network before. The ride starts when the phone joins it and ends at the first sign you got off: your car connecting, the phone joining another Wi-Fi, or a check every 3 minutes (run only while you are on the shuttle) finding the phone off the shuttle Wi-Fi. A drop of up to 10 minutes counts as the same ride.

Leaving and coming back within 10 minutes counts as never having left, so GPS jitter doesn't create junk events. New signals show up in the calendar within about 2 minutes (11 minutes for a "left" event, to rule out a quick return). The **Recent signals** list in the app shows exactly what was logged, which helps when tuning radius or Wi-Fi names.

**Setup:** grant precise location, then **Allow all the time**, then nearby devices. Pick a calendar. For each place, stand there and tap **Set to where I am now** (or add its Wi-Fi). Check your car. Add the shuttle Wi-Fi.

### Washer and dryer (LG ThinQ)

With notification access allowed (the **Allow notification access** button under places), a
`Washer running | At: 2:54 pm` event appears in the places calendar as soon as ThinQ says "Washing started.",
blocked out for your usual cycle length (an hour until a few loads have finished). When "Washing done" or "The
cycle has finished" arrives, the same event becomes `Washer | Dur: 52 mins | Start: 2:54 pm End: 3:46 pm`
(or `Dryer | ...`). Repeated
notifications count once, a "problem with the washer" notification is noted in the event, and "laundry has not
been removed" reminders are ignored. Turn on ThinQ's start and finish notifications for each appliance. Only LG
ThinQ's notifications are read; every other app's are ignored. If a start never gets a finish within 5 hours, a
`Washer started | At: ...` marker is written instead.

Place history only exists from the day you turn this on; Health Connect data can be backfilled, location history can't.

## Build and install (about 10 minutes)

1. Install Android Studio (latest stable).
2. **File → Open** this project folder. Let Gradle sync. If Studio offers to upgrade AGP/Kotlin/dependencies, accept.
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

## Home-screen widget

**Health this week** shows the last 7 days of sleep, workouts, steps and
calories, in dark colours. It reads Health Connect directly, so it does not
wait for the calendar to sync.

- **Small (the default 4 x 3):** one metric at a time. Tap a tab to switch
  between Sleep, Workouts, Steps and Calories.
- **Tall:** stretch it to about 4 x 5 and all four show as rows.
- **Tap a bar** to see that day's numbers; tap ↻ to re-read Health Connect;
  tap the big number to open Daily Logger.

To add it: long-press the home screen, **Widgets**, **Daily Logger**. Steps and
calories need two extra Health Connect permissions: open Daily Logger and tap
**Grant Health Connect access** once after updating. The widget refreshes
after every sync, every 30 minutes, and when you tap ↻.

## Quick log

Log things that leave no data behind: coffee, a meal, a snack, water, alcohol, meds, intimate, a nap, mood, plus
tiles you add. Each entry becomes a 15-minute event, `Coffee | At: 3:54 am`, with any note in the description.

- **Widget:** add **Quick log** from the home-screen widgets. Tap a tile to log it now: the tile greys out
  with a ✓ straight away, a "Logging Coffee…" line appears, and the tile ignores further taps for 2 minutes, so
  a double tap logs once. A "Coffee logged" line with
  **Edit** and **Undo** shows for 15 seconds. Each tile also shows today's count.
- **Log tab:** tap a tile to pick when (now, 15/30/60 minutes ago, or a time), how long, and a note. Tap any
  entry to change or delete it; the calendar event follows. Switch tiles on or off, add your own, and choose
  the calendar (it defaults to the places calendar).

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

**How long it took.** Each event in the **Events** tab says when it happened, how long after that Daily Logger
wrote it to the calendar, and how long the phone then took to upload it to Google, for example
`Happened 8:24 am · in calendar 3 min later · on Google 11 min after that`. A card at the top gives the typical
delays over the last 7 days and the slowest one. Only events written after this update are timed. Google's
copy reaching your watch or other devices is a further step the phone can't see.

Open the **Events** tab and read the card for that calendar:

| Card says | Fix |
|---|---|
| Lives only on the phone | Pick a Google calendar in Setup, then Backfill |
| Sync is off for this calendar | Tap **Turn on sync** |
| Hidden in your calendar apps | Tap **Show it** |
| Calendar sync is off for your account | Tap **Sync now**, or Settings → Accounts and backup → your account → Account sync → Calendar |
| Waiting to upload | Tap **Sync now** |

If sleep shows up but workouts don't, check Setup: the workout picker may point at a different calendar. Setup warns you when they differ and offers **Use the sleep calendar for workouts**.

## Files

| File | Job |
|---|---|
| `HealthReader.kt` | Reads sleep + exercise sessions, and daily steps + calories, from Health Connect |
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
| `WidgetWeek.kt` | Builds the widget's 7-day week and all its wording (unit-tested) |
| `WidgetData.kt` | Reads the week from Health Connect and caches it for quick taps |
| `Laundry.kt` | Turns LG ThinQ notifications into washer and dryer cycles (unit-tested) |
| `ThinQListener.kt` | Reads LG ThinQ notifications only |
| `Latency.kt`, `UploadWatch.kt` | Time each new event from happening to on Google |
| `QuickLog.kt`, `QuickLogWidget.kt`, `LogTab.kt` | Quick log entries, widget and tab |
| `HealthWidget.kt` | The home-screen widget: tabbed when small, rows when tall |
