# VaultTasks — macOS v1 Specification

Android is finished. This spec describes the macOS app as a native port of the **as-built** Android app (read from the final source, not from `VaultTasks-Android-Spec.md`, which is partly outdated, see §2). No code in this document.

**Roles:** Claude writes all code and runs all commands (build, test, git) directly in this repo. OpenCode is not used. Deliverable: a locally signed `VaultTasks.app`. No paid Apple Developer account, no notarization, no App Store.

**Target:** macOS 27 (the Mac runs it), Swift 6, SwiftUI, Liquid Glass. Personal use only.

---

## 1. Goals and non-goals

**Goals**
1. Feature parity with the final Android app on the vault format, spaces, categories, notes, recurrence, reminders, month calendar, WebDAV sync and ICS export.
2. Byte-identical task-file behavior to Android: the same vault must be editable from both without drift. Enforced by sharing `parser-fixtures.json` unchanged.
3. A Mac-native UI (sidebar, toolbar, keyboard, menu commands, Settings window) in Liquid Glass, not a phone UI in a window.
4. Reliable reminders without a running UI.

**Non-goals (v1)**
- Week/day calendar views, tag/priority editing, deletion propagation in sync.
- Syncing app settings (spaces, colors, orders, reminder leads) between devices. They are device-local, as on Android.
- Replacing Git + Remotely Save/OneDrive vault sync. WebDAV stays an additional per-file sync.
- iCloud, push, widgets, App Intents, EventKit (see §16).

## 2. As-built Android behavior (supersedes the Android spec)

The Android spec was written before the app was finished. Where they differ, **the as-built column is normative for macOS**.

| Topic | Android spec said | As built (port this) |
|---|---|---|
| Persistence | Room DB as cache | No DB. Scan results in memory, small JSON cache for fast start (`VaultCache`), spaces/settings in a key-value store |
| Conflicts | Remote wins, local saved as `.conflict-*` copy | **Newest modification time wins**, ties go to the server; no copies; conditional PUT; deletions never propagated |
| Sync UI | Sync now | Check (read-only status per file), Sync (two-way), plus silent auto-sync: app open, app close (5 s delay), daily |
| Notification actions | Done, Snooze 10 min / 1 h / tomorrow | **Done** and **Move to tomorrow** (opens the editor with the date preset to tomorrow; no timed snooze) |
| Recurrence | V2 | **Shipped**: completion writes the done line plus the next occurrence (notes copied). Only the rules `Recurrences.parse` understands are completable; others stay untouched ("complete in Obsidian") |
| Notes | not specified | Sub-bullets directly under the task line (one bullet per note line); never touch Tasks signifiers or identity |
| Categories | not specified | The **last word** of the description when it is a `#tag`. Color is device-local. Per-space "group by category" flag, remembered |
| Ordering | file order | Manual drag order per space and section (date bucket or category), plus manual category order per space; unknown tasks sort last |
| Remind before | global lead time | **Per task**, device-local, keyed by `path + NUL + description` (survives date/time/state edits). Choices: at time, 5/10/15/30 min, 1 h, 1/2 days, 1 week |
| Completion date | setting | Always written (`✅`), removed on un-complete |
| Calendar export | none | `.ics` of open tasks due today or later only, stable UIDs, notes as description, category, RRULE where expressible, alarm matching the task's reminder |
| Notification dismissal | none | Delivered reminders whose task is no longer open+dated are removed after every reconcile |
| Images | requested | **Dropped.** Do not implement |

Everything in Android spec §4.1 (Tasks-plugin line grammar, field order, `⏰ HH:mm` inside the description before all signifiers, tolerant reading of legacy `📅 … ⏰ …` order, variation selectors, NBSP, global filter) and §4.2 (edit changes only the affected line; LF/CRLF, BOM, trailing newline preserved) is **unchanged and still normative**.

## 3. Reuse strategy

**Decision: port `domain` to Swift 1:1; share the contract through `parser-fixtures.json`.**
- Android's `domain` module is pure Kotlin (no Android types) and already has the platform seams `VaultFileSystem`, `RemoteStore`, `LocalFiles`, `SyncRecords`, `AlarmBackend`, `AlarmStore`. The macOS app implements those seams; the logic is ported file by file.
- Rejected: Kotlin Multiplatform / Kotlin Native. It adds a second toolchain and interop layer to a ~2,000-line domain, and the user wants a native SwiftUI app. A fixture-verified port is cheaper and keeps both sides debuggable.
- Rejected: reuse of the old macOS plan's SwiftData. Files are the source of truth and Android showed in-memory scan + JSON cache is enough (several hundred tasks rescan in about 1 s).

**File map (Kotlin → Swift, same names, `VaultTasksCore` package)**

| Kotlin (`domain/`) | Swift |
|---|---|
| `Model`, `VaultText`, `TaskParser`, `TaskSerializer` | same names |
| `Notes`, `Categories`, `Recurrence` (`Recurrences`, `RecurrenceEdit`), `Spaces` (`Space`, `SpacesData`) | same names |
| `Reconciler`, `TaskGrouping`, `TaskCalendar`, `IcsExport` | same names |
| `alarms/Alarms` (`AlarmPlanner`, `AlarmSyncer`, protocols) | `Alarms/` |
| `vault/FileScanner`, `VaultWriter`, `VaultFileSystem` | `Vault/` |
| `sync/SyncEngine`, `PropfindParser`, `Remote` | `Sync/` |

Android-only layers (Room-less repository, SAF, AlarmManager receivers, WorkManager, Compose UI) are **reimplemented**, not ported (§5–§9).

### 3.1 Port rules (Swift pitfalls that would silently break byte-exactness)

1. **Never parse on `Character`.** Swift treats `\r\n` as one `Character`, so splitting on `"\n"` misses CRLF; `U+FE0F` and combining marks merge into the previous grapheme. All parsing, line splitting, offsets and emoji matching operate on `unicodeScalars` or UTF-8 bytes.
2. **Never use `String ==` for identity or "unchanged" checks.** Swift compares by canonical equivalence (`"é" == "e\u{301}"`), Kotlin by code units. Compare `Array(s.unicodeScalars)` or UTF-8 bytes. `TaskId.normalizedText` is therefore a scalar-wise value.
3. **Regex:** if `Regex` is used, set unicode-scalar matching semantics; prefer hand-written scanners for the line grammar.
4. **Dates:** Foundation has no `LocalDate`/`LocalTime`. Core defines `LocalDate` (y-m-d) and `LocalTime` (h:m), `Comparable`, `Codable`, ISO strings. Due values are never `Date`. Conversion to an instant happens only at scheduling and ICS time, in the current zone.
5. **Hashes:** SHA-256 via CryptoKit. `AlarmPlanner.hashCode` = first 4 bytes big-endian as `Int32(bitPattern:)`, collision probing in sorted-key order, exactly as Kotlin.
6. **Sorting:** every comparator keeps its explicit final tie-breakers (`path`, `lineIndex`). Do not rely on sort stability.
7. **English rule vocabulary:** `Recurrences` parses English weekday/unit words regardless of the Mac's locale (use a fixed English table, not `Locale`-dependent formatting). Display dates use the user's locale; week start comes from `Calendar.current.firstWeekday`.
8. **Blocking → async:** the Kotlin seams are blocking. In Swift, `RemoteStore` and `LocalFiles.atomically` are `async`, `SyncEngine` is `async`. Behavior and ordering are otherwise identical.

## 4. Tech stack and build

- Swift 6 (strict concurrency), SwiftUI, Observation (`@Observable`), macOS 27 SDK. **Implementer must verify every Liquid Glass / new-SDK API name against the installed Xcode before use** (`glassEffect`, `GlassEffectContainer`, glass button styles are the macOS 26 names).
- Repo layout (monorepo, next to the Android project):
  - `macos/VaultTasksCore/`: Swift package, Foundation + CryptoKit only. `swift test` runs without Xcode UI. This is where the gates live.
  - `macos/VaultTasks/`: app target (SwiftUI + platform seams).
  - `macos/project.yml`: XcodeGen spec so no `.pbxproj` is hand-written. **Verify XcodeGen is installed (`brew`) in Phase 0; fallback is one Xcode-created project checked in.**
- DI: a hand-written `AppContainer`, as on Android. No third-party packages at all.
- Signing: ad-hoc / "Sign to Run Locally". Built locally means no quarantine flag, so no Gatekeeper prompt. Phase 0 verifies sandbox + notifications work under this signing.
- Entitlements: App Sandbox, `files.user-selected.read-write`, `files.bookmarks.app-scope`, `network.client`. Nothing else in v1.
- Fixtures: `parser-fixtures.json` stays in `domain/src/test/resources/` as the single source. A script copies it into the Swift test resources; a test fails if the copy differs from the original (hash compare when `../domain` exists).
- Tests: Swift Testing or XCTest, whichever the installed Xcode supports best (decide in Phase 0). Core tests must run via `swift test`.

## 5. Vault access

- The user picks the vault folder with `.fileImporter` (directories). The app stores an **app-scoped security-scoped bookmark** in its settings.
- **Bookmark lifecycle** (the old plan's "staleness" item, now concrete):
  1. On launch, resolve the bookmark with security scope. If `isStale`, immediately create a fresh bookmark from the resolved URL and store it.
  2. Call `startAccessingSecurityScopedResource()` once and hold it for the app's lifetime; stop only when the vault changes or the app quits.
  3. If resolving fails or the folder is gone, state becomes `PERMISSION_LOST` (same state machine as Android: `LOADING`, `NO_VAULT`, `PERMISSION_LOST`, `READY`) with a clear re-pick prompt. Spaces are kept.
- `BookmarkVaultFileSystem` implements `VaultFileSystem` with `FileManager` on real paths. `listMarkdown()` skips dot-directories (`.obsidian`, `.git`, `.trash`). Reads decode strictly as UTF-8; invalid UTF-8 refuses the edit (same as Android).
- **Write = atomic replace** (temp file in the same directory, then replace), then the existing read-back verification. Justification: Obsidian, Git and Remotely Save read the vault concurrently, and an atomic replace never exposes a half-written file; SAF on Android could not do this, a normal path can. The stat check (`mtime` + size unchanged since the read) before replacing is kept, so the write protocol is Android's: read fresh, locate by identity, verify, replace one line, write, read back, re-parse; `Stale` aborts with "Task changed on disk, please retry".
- **Change detection: FSEvents on the vault root** (debounced 500 ms), plus rescan on app activation, after each sync, on manual refresh (⌘R). A file is re-parsed only if `mtime`/size changed (`FileScanner` unchanged). Android had to poll because SAF has no observer; macOS does, so use it. **Phase 3 spike must confirm FSEvents delivers inside the sandbox for the bookmarked folder; fallback is a 30 s stat poll with the same debounced rescan.**
- All vault operations go through one `VaultRepository` **actor** (replaces Android's `opMutex`): scan, write, and sync's `atomically` blocks are serialized there. The UI observes an `@Observable` snapshot of `RepoState`; it never owns task data.

## 6. Local state (all device-local)

| Data | Store | Notes |
|---|---|---|
| Vault bookmark, global filter, default reminder time (09:00) | `UserDefaults` | |
| Spaces + active space | JSON in Application Support (`spaces_v1`) | Same `SpacesData` operations as Android |
| Group-by-category per space, task orders, category orders, category colors | JSON in Application Support | Orders are lists of **task keys** per space + section (`overdue`/`today`/`upcoming`/`nodate`, or `cat:<name>`); category order is a list of names per space; colors are name → sRGB. **Task key** (`TaskKeys` in `TaskListScreen.kt`): `path | stableText | n`, where `stableText` is `TaskId.normalizedText` with the checkbox mark normalized to `[ ]` and any `✅ yyyy-MM-dd` removed (so a key survives completing or reopening), and `n` numbers identical lines of the same file in line order. Port `CATEGORY_PALETTE` from `Components.kt` |
| Reminder leads | JSON (`leads_v1`) | Keyed by `AlarmPlanner.leadKey`; pruned only for keys it observed missing (as `LeadStore.remove`) |
| Scan cache | JSON, keyed by vault bookmark + global filter | Shown instantly at launch, then verified by a stat-gated scan |
| Alarm store (what was last scheduled) | JSON | `AlarmStore` |
| WebDAV password | Keychain (generic password, no access group) | URL/user/base folder in `UserDefaults` |
| Sync records | JSON | Cleared when URL or base folder changes (as `SyncRepository.save`) |

Writes are atomic and failures never corrupt the previous file (decode errors fall back to empty state, never crash).

## 7. Notifications

Rules are Android's (§8.1/§8.2 of its spec, as built): alarm = due date + due time − per-task lead; date-only tasks use the default time; open tasks only; past times are not scheduled; `AlarmPlanner.plan` and `AlarmSyncer.sync` are the **only** scheduling path, called after every reconcile.

macOS specifics:
- **Backend:** `UNUserNotificationCenter` with `UNCalendarNotificationTrigger` from wall-clock `DateComponents` (no time zone set, so the Mac's current zone applies at fire time and a zone change needs no reschedule). Identifier = `String(code)`, so re-adding replaces and never duplicates. Delivered by the system while the app is not running.
- **Pending-request cap:** the system caps pending notifications per app (64 on iOS; **verify the macOS limit in Phase 0**). Core adds `AlarmPlanner.window(limit:)`: schedule only the soonest N (default 60) and top up on every sync, app activation and a daily timer. The window is applied to the desired set before `AlarmSyncer.sync`, so an alarm pushed out of the window by a sooner task is cancelled and comes back on the next top-up.
- **Actions** (one `UNNotificationCategory`): **Done** (background, runs `setDone` through the repository headlessly, including recurrence) and **Move to tomorrow** (foreground; opens the editor with date preset to tomorrow, recurrence and notes untouched via `Keep`/`nil`). Clicking the notification opens the task's editor. The delegate must load the repository first, as Android's receivers do via `ensureLoaded()`.
- **Dismissal:** after every reconcile, remove delivered notifications whose code is not in the live set (`removeDeliveredNotifications`). This also fixes the Android bug where in-app completion left the reminder visible.
- **Resilience (the Mac's equivalent of the Honor health check):** force re-set all alarms on launch, on `NSWorkspace.didWakeNotification`, on `NSSystemClockDidChange` and on time-zone change; Reminders screen shows authorization state with a button that opens System Settings (Notifications; **verify the URL scheme on macOS 27**), pending count versus cap, the next five scheduled, and a "Send test notification in 10 s" button. A small diagnostics log (last 100) compares each delivered notification's date against its scheduled time (read from `getDeliveredNotifications` on activation) so lateness is measurable, as on Android.
- **Sleep:** **Phase 5 gate must test a reminder that comes due while the Mac sleeps** (delivered on wake? how late?). If unreliable, document it in the Reminders screen rather than hide it.
- No time-sensitive interruption level (needs a provisioned entitlement). Banner style/Focus are the user's system settings.
- The notification icon is the app icon; the Android status-bar icon asset is not used.

## 8. WebDAV sync

Same engine and semantics as Android (`SyncEngine`, §2 table): scope = files assigned to at least one space; per-file records (content hash + remote token); newest wins on a two-sided change, ties to the server; PUT is conditional (`If-Match` strong ETag, `If-None-Match: *` for create, synthetic `lm|modified|size` token and manual check when the server has no ETag); missing-locally and deleted-on-server are skipped and reported.

- **Client:** own thin `URLSession` client (PROPFIND depth 0, GET, conditional PUT, MKCOL), mirroring `WebDavClient`. A `URLSessionTaskDelegate` **refuses redirects** and reports "use the final URL". Rejected: `skjiisa/WebDAV-Swift` or similar; Android already proved the thin client, and the engine needs ETag/`If-Match`/no-redirect behavior that libraries rarely expose. Timeouts 15 s connect, 30 s read, 60 s write.
- **UI:** server form with Test connection, Check (read-only per-file status), Sync (two-way), last report (pulled/pushed/skipped/failed). No silent overwrite: the status list is always available before syncing.
- **Auto-sync:** on app activation, ~5 s after the app resigns active (debounced), daily via `NSBackgroundActivityScheduler` while the app is running. File-watcher events only trigger a rescan, never a sync. Minimum gap between automatic runs (as `autoSync(minGapMs)`), skipped when a manual sync is running or no server is set. After any pull: force rescan, which re-syncs alarms.
- macOS cannot run background syncs when the app is not running; that is accepted (the app can be a login item, §9.5).

## 9. Screens and behavior

### 9.1 Window and navigation
- Single main window, `NavigationSplitView`. **Sidebar:** the spaces (drag to reorder, rename, delete via context menu; "New space…" at the bottom), then **Calendar**. Selecting a space shows its task list; this replaces Android's space dropdown. The last active space is remembered. Deleting a space never touches vault files.
- Settings window (⌘,) with tabs: Vault, Spaces, Reminders, Sync, Calendar export (§9.5).
- Menu commands and shortcuts: New Task ⌘N, Refresh ⌘R (rescan, plus sync if a server is set), Delete ⌫ with Undo ⌘Z (undo of delete uses `VaultWriter.restore`), Return opens the editor on the selected task, Space toggles done.
- Banner at the top of the content while the vault is `NO_VAULT`/`PERMISSION_LOST`, notifications are not authorized, or files were unreadable on the last scan.

### 9.2 Task list
- Active space's tasks, grouped **Overdue, Today, Upcoming, No date, Done** (Done collapsed) via `TaskGrouping` (a timed task is overdue once its minute has passed, an untimed one at midnight; cancelled tasks are hidden).
- **Group by category** toggle in the toolbar, remembered per space. When on, **open** tasks group under `#tag` headers (tasks without one under "No category", last), keeping their date order inside each category; **Done stays one collapsed "Done · N" section in both modes**. Category order is manual (a reorder sheet, only offered while grouped), per space, unknown categories alphabetical after the ordered ones. Headers show a count pill; category headers use the category color, Overdue uses the error color.
- **Manual order:** drag a task within its own section only (date or category decides where it belongs); order persists as task keys (§6); tasks without a stored position keep their normal order after the placed ones. Switching spaces starts a fresh list scrolled to the top.
- **Completion linger:** a task just completed (non-recurring) stays in its old section, struck through, for the same short delay as Android's `LINGER_MS`, then animates out into Done. This keeps a toggled row animating in place instead of vanishing.
- Row: checkbox, title, notes preview (one line), due date/time, category chip, priority indicator, repeat indicator, file name when the space has more than one file, overdue treatment. Checkbox runs the write protocol; recurring tasks use `Task.completable` (disabled with the hint "Complete in Obsidian" when the rule is not supported). Empty state "All clear".
- Swipe actions and context menu: complete, edit, move to tomorrow, delete (undo toast with ⌘Z).

### 9.3 Create / edit task
Presented as a sheet. Fields, top to bottom:
1. Title (required, single line; newlines become spaces). The trailing `#tag` is not shown here; the category has its own control.
2. **Notes**: multi-line (3 to 8 lines visible), directly under the title; stored as sub-bullets (`Notes`); unchanged notes are never rewritten.
3. Category: chips **None**, each existing category of the space (with its color), **+ New**. The new-category dialog has a name field (`#` prefix, normalized by `Categories.clean`) and the **same 10-swatch palette as Android** with a live preview chip; the chosen color is stored device-local.
4. Due date (button opens a date picker) with **Today** and **Tomorrow** shortcuts while empty and **Clear** once set. Clearing the date also clears the time and the repeat.
5. Shown only once a date is set (animated unfold): due time (picker + Clear); **Repeat** chips Never, Daily, Weekdays, Weekly, Monthly, Yearly, plus **Custom…** (every N days/weeks/months/years); **Remind me** chips (At time, 5/10/15/30 min, 1 h, 1/2 days, 1 week before; saved as 0 without a date). An existing rule the editor has no chip for (specific weekdays, "when done", anything unparsed) is shown by its label and **kept untouched** unless the user picks another repeat (`RecurrenceEdit.Keep` until the repeat control is touched).
6. File: a picker limited to the active space's files (default: space default file, else first); a plain label when the space has one file. Changing it uses `VaultWriter.move` (rollback on failure).
Save returns task fields, target file and lead minutes.
New tasks are appended at the end of the chosen file, preceded by a newline if needed. A calendar day preselects the date.

### 9.4 Calendar
- **Month view only**, each day cell clickable; week start from the system locale. Days with tasks show a dot/count; today highlighted; navigation by arrows, trackpad swipe and ⌘←/⌘→.
- Clicking a day opens a side panel (popover on narrow windows) with that day's tasks (`TaskCalendar.byDay`: open before done, then time, untimed last) and a New Task action with the date prefilled. Undated tasks do not appear.

### 9.5 Settings
- **Vault:** folder (re-pick), rescan, Tasks global filter (default empty; must match the Tasks plugin exactly).
- **Spaces:** create/rename/reorder/delete, file list picker rooted at the vault (`.md` files), default file.
- **Reminders:** default time for date-only tasks, authorization and health (§7), test notification.
- **Sync:** §8.
- **Calendar export:** §11.
- **General:** launch at login (`SMAppService`, default off; **verify it works under local signing in Phase 0**) and an optional menu bar item (count of overdue + today, quick add). Both are Phase 8 and can be vetoed (§15).

## 10. Design system

- Native SwiftUI structure first: let the macOS 27 SDK give sidebar, toolbar and sheets their Liquid Glass automatically. Add custom glass (`glassEffect` in a `GlassEffectContainer`) only to floating controls (day panel, undo toast, add button), never to task rows or text-heavy content.
- Type: SF Pro (system). Large, clear task titles; secondary text for date/notes. Distinct overdue color treatment; category colors as small chips, never as full-row backgrounds.
- Motion: spring animations for checkbox completion, row insert/remove, reorder, section collapse, sheet and calendar-month transitions; `matchedGeometryEffect` where an element moves between states. Respect Reduce Motion. Animations must not alter or delay writes (UI state is derived from the repository snapshot, so a slow animation cannot desynchronize data).
- Light/dark follow the system; accent from the app icon.
- App icon: master art in `/Volumes/Samsung/Fotos/VaultTask/` (`VaulTasks.png/.svg`) into the asset catalog's `AppIcon`; layered Icon Composer icon is an optional Phase 8 upgrade (**verify tooling**).

## 11. Calendar export (ICS)

- Port `IcsExport` unchanged (same UID scheme, RFC 5545 line folding, alarm from lead/default time, RRULE where `Recurrences.rrule` can express it; "when done" exports as a plain repeat). Exported set: open tasks due today or later only.
- Delivery: write to a temporary `.ics`, then open it with Calendar (`com.apple.iCal`). Calendar asks which calendar to add to; stable UIDs mean re-importing updates the events instead of duplicating them (**verify this update behavior in Phase 7**). A "Save as…" button offers the file instead.
- Rejected for v1: EventKit direct write. It would handle updates and deletions better but needs the calendar entitlement and permission flow plus diff logic; listed in §16.

## 12. Architecture

```
VaultTasksCore/ (SwiftPM; Foundation + CryptoKit only)
  Domain/   Model, LocalDate/LocalTime, VaultText, TaskParser, TaskSerializer, Notes, Categories,
            Recurrence, Spaces, Reconciler, TaskGrouping, TaskCalendar, IcsExport
  Alarms/   AlarmPlanner (+window), AlarmSyncer, AlarmBackend, AlarmStore
  Vault/    VaultFileSystem, FileScanner, VaultWriter
  Sync/     SyncEngine, PropfindParser, RemoteStore, LocalFiles, SyncRecords
VaultTasks/ (app target)
  Data/     BookmarkVaultFileSystem, VaultRepository (actor), VaultCache, FSEventsWatcher,
            SettingsStore, LeadStore, UNAlarmBackend, NotificationActions, WebDavClient,
            KeychainStore, SyncRepository, AutoSync
  UI/       AppContainer, MainWindow, Sidebar, TaskList, TaskEditor, Calendar, Settings, Reminders, Components
```

- One `VaultRepository` actor owns scanning, writes and the `LocalFiles` lock; all mutations (UI, notification actions, sync pulls) go through it, then reconcile, then `AlarmSyncer.sync`. Nothing else schedules alarms.
- UI state is immutable value snapshots derived from the repository; no view owns a copy of task data (the Android rule that fixed "UI disappearing").
- `domain` has no AppKit/SwiftUI import; this is enforced by the package boundary.

## 13. Testing requirements

1. **Fixtures:** the shared `parser-fixtures.json` (all `parseCases` and `editCases`, including whole-file edit equality and the independent Tasks-style reader check via a Swift port of `TasksStyleParser`) passes unchanged.
2. **Port every Kotlin test file** with its cases: `FixtureTest`, `VaultTextTest`, `ReconcilerTest`, `SpacesAndGroupingTest`, `TaskCalendarTest`, `AlarmsTest`, `FileScannerTest`, `VaultWriterTest`, `FinalFeaturesTest`, `PropfindParserTest`, `SyncEngineTest` (with ports of `FakeVaultFileSystem` and an in-memory fake `RemoteStore`).
3. **Swift-specific regression tests** for §3.1: CRLF files, lone `\r`, BOM, combining characters in identity, `U+FE0F` and NBSP preserved on untouched lines, emoji at line end, canonical-equivalent but byte-different task lines treated as different identities.
4. **New logic:** `AlarmPlanner.window` (cap, deterministic order, top-up diff), delivered-notification cleanup set, FSEvents debouncer (pure), bookmark state machine (stale, resolve failure) behind a protocol fake, async `SyncEngine` with injected interleavings (server change between check and PUT).
5. **Cross-platform check (manual gate):** edit a task on the Mac, open the same vault file on Android and in Obsidian with the Tasks plugin; the file diff is exactly one line and Tasks still reads priority/due/done.

## 14. Build phases

One phase at a time; every phase ends with a built `VaultTasks.app` (from Phase 3) and its gate passed before the next starts. Claude runs `swift test` / `xcodebuild` itself and reports real output.

0. **Toolchain and spikes** (throwaway app + checks): `xcodebuild -version`, XcodeGen availability, new-SDK API names, test framework choice. Prove under local signing + sandbox: bookmark survives relaunch (including stale handling), a calendar-trigger notification fires with actions while the app is closed, macOS pending-notification limit, `SMAppService` registration, FSEvents inside the sandbox. Gate: all spikes pass or have a documented fallback from this spec.
1. **Core A:** `LocalDate/LocalTime`, `VaultText`, `TaskParser`, `TaskSerializer`, `Notes`, `Categories`, `Recurrence`, `Reconciler`. Gate: `swift test` green including every fixture case.
2. **Core B:** `Spaces`, `TaskGrouping`, `TaskCalendar`, `IcsExport`, alarms (+window), `FileScanner`, `VaultWriter`, `SyncEngine`, `PropfindParser`, fakes. Gate: `swift test` green, all ported tests present.
3. **App skeleton and vault access:** container, bookmark picker, repository actor, cache, FSEvents, read-only task list. Gate: pick the vault, see tasks, relaunch without re-picking, an edit made in Obsidian appears within about 2 s.
4. **Spaces, list, editor:** sidebar, grouping, categories and colors, notes, recurrence, per-task lead, reorder, undo delete, file moves. Gate: cross-platform check (§13.5).
5. **Notifications:** backend, window top-up, actions, dismissal, Reminders screen, diagnostics. Gate: real reminders minutes out, Done and Move to tomorrow work with the app closed, completed-in-app reminders disappear, relaunch and wake behavior verified, sleep test recorded.
6. **Calendar:** month view and day panel.
7. **WebDAV, auto-sync, ICS export.** Gate: sync against the real server with explicit Check before Sync; two-sided edit resolved by newest-wins without data loss; ICS imported into Calendar and re-imported without duplicates.
8. **Polish:** Liquid Glass and motion pass, icon, optional menu bar item and launch at login, diagnostics cleanup.

## 15. Decisions made without asking (veto any)

1. Port to Swift with shared fixtures; no Kotlin Multiplatform.
2. No SwiftData; in-memory snapshot plus JSON cache (overrides the earlier macOS plan).
3. FSEvents watcher with rescan fallbacks instead of poll-only.
4. Atomic-replace writes (temp file then replace).
5. Own `URLSession` WebDAV client, no third-party package.
6. ICS handed to Calendar; EventKit deferred.
7. Sidebar navigation (spaces + Calendar) instead of Android's dropdown and tab bar.
8. Notification window of 60 pending requests with top-up (limit to be verified).
9. Menu bar item and launch at login offered in Phase 8, default off.
10. English UI only; dates and week start follow the system locale.
11. App settings stay device-local; no cross-device settings sync.
12. Category colors use Android's fixed 10-swatch palette (parity); a free-form color picker is not added.

## 16. Later

- EventKit two-way calendar sync (updates and deletions handled properly).
- Share spaces/colors/orders between devices via a small file in the synced vault area.
- Week/day calendar views, tag and priority editing, deletion propagation in sync.
- App Intents / Shortcuts quick add, widgets.
