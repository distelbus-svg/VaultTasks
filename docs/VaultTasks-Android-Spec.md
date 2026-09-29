# VaultTasks — Android v1 Specification

New app, built from the ground up. macOS follows after Android ships (see §14). No code in this document; it defines behavior, data rules, and architecture.

**Roles:** Claude writes all code. OpenCode only runs local commands and manipulates files (build, install, git, adb). Deliverable: a signed-debug/sideloadable `.apk`. No Play Store, no paid developer account.

**Target device:** Honor 400 (MagicOS). Design for its aggressive background-process killing (§8.5).

---

## 1. Goals and non-goals

**Goals**
1. Read and write tasks stored as markdown in an Obsidian vault. The vault files are the single source of truth.
2. Create tasks with due date and optional due time, in a format the Obsidian Tasks plugin also reads (§4.1).
3. Group vault files into **spaces** (per topic).
4. Reliable notifications at the due time.
5. Month-view calendar to see and create tasks.
6. WebDAV sync of the selected files.
7. Material 3 Expressive UI.

**Non-goals (v1)**
- Recurrence (V2, see §14 — remind the user).
- Week/day calendar views.
- Replacing the existing Git + Remotely Save/OneDrive vault sync. WebDAV here is an additional per-file sync.
- Editing tags, priority, dependencies, scheduled/start dates. These are preserved verbatim (priority is shown).
- Google/Play services dependencies.

## 2. Design principles

Built from scratch; nothing carries over from any earlier app. These rules address the failure modes this kind of app is prone to:

| Risk | Rule in this spec |
|---|---|
| Duplicated tasks | The DB is a disposable cache rebuilt from files. Identity is derived (§4.3); reconciliation is a pure function with tests. |
| Late or missing notifications | Exact alarms, idempotent scheduling derived from parsed state, reschedule on all system events, in-app health check (§8). |
| UI state loss | Unidirectional data flow, immutable UI state, one repository exposing `Flow`s, no UI-owned copies of task data. |

## 3. Tech stack

- Kotlin, Jetpack Compose, single-activity, Navigation Compose.
- Material 3 with Expressive APIs (`androidx.compose.material3`; the Expressive components live in the 1.4.x line — **implementer must verify current stable/alpha artifact and API names against the actual release before use**).
- minSdk 31, targetSdk = latest stable. Confirm the Honor 400's Android version and adjust minSdk only if lower.
- Room (cache only), DataStore (settings), WorkManager (periodic rescan/sync), AlarmManager (notifications).
- DI: a hand-written `AppContainer` (no Hilt/Koin; minimal).
- WebDAV: use an existing library. Candidate: `sardine-android`. **Implementer must verify maintenance status and API in source before adopting**; fallback is OkHttp with a thin PROPFIND/GET/PUT layer if the library is unmaintained.
- Tests: JUnit5 + Turbine for flows; Robolectric only where unavoidable.

## 4. Data model and file format

### 4.1 Compatibility with the Obsidian Tasks plugin (hard requirement)

Every line the app writes must be read correctly by the Tasks plugin (Tasks Emoji Format, Tasks' default), and every line Tasks writes must be read correctly by the app. Assumption: Tasks is set to its default Emoji format, not Dataview format.

**Why this drives the format.** Tasks reads its metadata from the *end* of the line, working backwards, and stops at the first trailing text it doesn't recognize. Tasks has no time-of-day field. A line like `... 📅 2026-09-28 ⏰ 19:00` would therefore make Tasks ignore the due date, because the unknown `⏰ 19:00` sits after it.

**Line grammar**

```
<indent><bullet> [<state>] <description> [⏰ HH:mm] [Tasks signifiers...]
```

- **The time goes inside the description, before every Tasks signifier:**
  `- [ ] Call Anna ⏰ 19:00 🔼 📅 2026-09-28`
  Tasks sees `⏰ 19:00` as plain description text and still reads priority and due date. Obsidian users see the time inline. This replaces the earlier `📅 date ⏰ time` ordering; the app still *reads* that older order (Tasks will not see the date on such lines until the app rewrites them).
- `⏰ HH:mm` (24 h) is only meaningful together with `📅`. A lone `⏰` is plain description text with no time.
- State: ` ` open, `x`/`X` done, `-` cancelled. Bullets `-`, `*`, `+`, and numbered `1.`, are accepted; indentation and bullet style are preserved. Nested checkboxes are independent tasks.

**Signifiers (Tasks emoji format), all parsed and preserved**

| Emoji | Field | App behavior |
|---|---|---|
| 📅 | due date | used: display, calendar, notifications, edit |
| ⏰ | due time (app-only, in description) | used: notifications, edit |
| ✅ | done date | written on completion (setting, default on), removed on un-complete |
| ❌ | cancelled date | preserved; a `[-]` task is hidden and silent |
| ⏳ 🛫 ➕ | scheduled, start, created | preserved verbatim, not used in v1 |
| 🔺 ⏫ 🔼 🔽 ⏬ | priority | shown as an indicator; preserved; not editable in v1 |
| 🔁 🏁 | recurrence, on-completion | preserved; see recurring tasks below |
| 🆔 ⛔ | id, depends-on | preserved verbatim |

**Editing rules (keep Tasks-valid)**
- Changing a value replaces it in place. Adding a missing field inserts it at Tasks' own serialization position: description, 🆔, ⛔, priority, 🔁, 🏁, ➕, 🛫, ⏳, 📅, ❌, ✅. So `📅` goes after any ⏳ and before ❌/✅; `✅` is always last.
- Removing the due date also removes `⏰ HH:mm`.
- Never write Unicode variation selectors. Read tolerantly (strip U+FE0F when matching) but preserve the original bytes on lines the app does not edit. Preserve non-breaking spaces as found.
- Hashtags, links, and any other description text are untouched.

**Global filter.** Optional setting "Tasks global filter" (default empty; e.g. `#task`). If set, the app only treats checkbox lines containing it as tasks, and new tasks include it as the first token of the description. Match the value in the user's Tasks settings exactly.

**Recurring tasks (🔁) in v1.** Recurrence is out of scope, so the app cannot safely complete a recurring task: Tasks would spawn the next occurrence and the app would not. Recurring tasks are shown with a repeat indicator and still notify, but the completion checkbox is disabled with the hint "Complete in Obsidian". Editing text or date preserves `🔁`/`🏁`. Full support is V2.

### 4.2 Round-trip guarantee (hard requirement)

Any edit changes **only the affected line**. All other bytes in the file — other lines, line endings (LF/CRLF), trailing newline, frontmatter, encoding (UTF-8, BOM preserved if present) — are unchanged. Golden-file tests enforce this.

### 4.3 Task identity

A task is identified by `(fileRelativePath, normalizedLineText, occurrenceIndex)` where `occurrenceIndex` disambiguates identical lines within a file (nth identical line). `lineNumber` is a hint for edits, never identity.

- Reconciliation on rescan: parse file → set of tasks; diff against cache by identity; insert/update/delete. **Never append blindly.** Pure function `reconcile(cached, parsed) → changes`, unit tested with duplicate-line cases.
- Editing a task's text in Obsidian changes its identity; the old alarm is cancelled and a new one scheduled by the same reconciliation pass. This is acceptable and predictable.

### 4.4 Entities (Room, cache)

- `VaultConfig` — vault root tree URI (persisted SAF permission).
- `Space` — id, name, sort order, `defaultFile` (nullable), ordered list of files.
- `SpaceFile` — spaceId, fileRelativePath. A file may belong to multiple spaces; tasks are keyed by file, so they simply appear in each.
- `TaskEntity` — identity fields, checkbox state, description, dueDate, dueTime, rawLine, lineHint, fileContentHash.
- `FileState` — path, lastModified, size, contentHash, lastSyncedRemoteEtag, lastSyncedHash.

Everything except `VaultConfig`/`Space`/`SpaceFile` is rebuildable by "Rescan vault".

## 5. Vault access

- User picks the vault folder via SAF (`ACTION_OPEN_DOCUMENT_TREE`); persist the URI permission. Re-prompt clearly if permission is lost.
- All file I/O through `DocumentFile`/`ContentResolver`; no raw paths.
- Change detection (no `FileObserver` on SAF): rescan on app foreground, on pull-to-refresh, after each sync, and via a periodic WorkManager job (default 15 min, configurable). A file is re-parsed only if `lastModified` or `size` changed; content hash confirms.
- **Write protocol (prevents lost updates):** read the file fresh → locate the target line by identity → verify it still matches expected text → replace only that line → write → re-parse the file and reconcile. If the line no longer matches, abort the edit, rescan, and show "Task changed on disk — please retry". Serialize all writes per file with a mutex.

## 6. Spaces

- User creates/renames/reorders/deletes spaces.
- Per space: pick one or more `.md` files from the vault (file browser rooted at the vault), and an optional default file for new tasks.
- Main UI: a dropdown/selector at the top switches the active space; the last active space is remembered.
- "All spaces" view is not required in v1.
- Deleting a space never touches vault files.

## 7. Screens and behavior

### 7.1 Task list (home)
- Active space's tasks from its files, grouped: **Overdue**, **Today**, **Upcoming**, **No date**, **Done** (collapsed).
- Checkbox toggles completion (write protocol §5). Tap opens edit sheet. Swipe actions: complete, delete.
- Delete removes the task line from the file (confirm via undo snackbar; undo re-inserts at the original line index if the file is unchanged, otherwise appends to the same file).
- FAB: create task.

### 7.2 Create/edit task sheet
- Fields: description (required), due date (date picker), due time (time picker; enabled only when a date is set), **file picker limited to the active space's files** (default: space default file, else first file).
- Editing may move a task between files of the space (remove line from A, append to B, as one operation with rollback on failure).
- New tasks are appended at end of the chosen file, ensuring a preceding newline.

### 7.3 Calendar
- **Month view only.** Each day cell is tappable/clickable.
- Days with tasks show an indicator (dot/count). Today highlighted. Month navigation via swipe and arrows.
- Tapping a day opens a bottom sheet listing that day's tasks (active space) with the create-task action pre-filled with that date.
- Tasks without a date do not appear.

### 7.4 Settings
- Vault folder, rescan, spaces management, default reminder time for date-only tasks (default 09:00), reminder lead time (default 0), completion-date toggle, WebDAV config, notification health check (§8.5), sync interval.

## 8. Notifications

### 8.1 Rules
- Alarm time = due date + due time. Date-only tasks alarm at the default reminder time. Open tasks only; past times are not scheduled (overdue is shown in-app, not re-fired).
- One alarm per task, `requestCode` = stable hash of task identity → scheduling is idempotent (scheduling twice replaces, never duplicates).
- Use `AlarmManager.setExactAndAllowWhileIdle` with a `PendingIntent` to a `BroadcastReceiver`; the receiver posts the notification directly (no app UI process assumed alive).
- Permissions: `POST_NOTIFICATIONS` (runtime), exact alarm capability (`USE_EXACT_ALARM` or `SCHEDULE_EXACT_ALARM` with settings deep-link — choose per sideload context; verify current platform behavior).

### 8.2 Single scheduling entry point
`AlarmScheduler.sync(tasks)` computes the desired alarm set from the cache and diffs against the previously scheduled set (persisted), cancelling stale and setting missing ones. It is called after every reconcile, and nowhere else. No feature schedules alarms ad hoc.

### 8.3 Reschedule triggers
`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED`, `TIME_SET`, exact-alarm permission granted/revoked, app start, every rescan.

### 8.4 Notification content and actions
- Title: task description; text: due date/time and space name. Channel: "Task reminders", high importance.
- Actions: **Done** (runs write protocol in the receiver via a short foreground-safe coroutine/WorkManager expedited job) and **Snooze** (fixed options: 10 min, 1 h, tomorrow; snooze is an in-app alarm only and does not modify the file).
- Tapping opens the task's edit sheet.

### 8.5 Honor/MagicOS reliability
- Settings → "Reminder health check" screen that verifies and deep-links: notification permission, exact-alarm permission, battery optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), and text guidance for MagicOS "App launch"/auto-start and "lock in recents" settings (these have no stable public intent; show instructions, and attempt best-effort OEM intents guarded by try/catch).
- Show a persistent warning banner on the home screen while any check fails.
- Log each alarm fire (scheduled time vs actual time) to a small in-app diagnostics log (last 100) so lateness can be measured objectively.

## 9. WebDAV sync

- Config: server URL, username, password (stored in `EncryptedSharedPreferences`/Keystore-backed storage), remote base folder. "Test connection" button.
- Scope: only files assigned to at least one space. Remote path = base folder + vault-relative path.
- Per-file algorithm, using `FileState`:
  1. Local changed = current hash ≠ `lastSyncedHash`. Remote changed = current ETag ≠ `lastSyncedRemoteEtag`.
  2. Neither → skip. Local only → PUT (conditional on `If-Match` ETag when the server supports it). Remote only → GET and overwrite local via write protocol.
  3. Both → **conflict**: keep the remote version as the main file, save the local version as `<name>.conflict-<yyyyMMdd-HHmmss>.md` next to it, notify the user, and show it in a "Conflicts" list. Never silently discard either side.
  4. After success, update `FileState`, rescan the file.
- Triggers: manual "Sync now", after local edits (debounced 5 s), periodic WorkManager job (network-constrained), on app foreground.
- Missing remote file → upload; missing local file that exists remotely → download; deleted-on-one-side is **not** propagated in v1 (log and skip) to avoid data loss.

## 10. Architecture

```
ui/            Compose screens, ViewModels (immutable UiState + events)
domain/        Task, Space, TaskParser, TaskSerializer, Reconciler, DueTime rules  (pure Kotlin, no Android)
data/
  vault/       SAF file access, write protocol, FileScanner
  db/          Room cache
  sync/        WebDavClient wrapper, SyncEngine
  alarms/      AlarmScheduler, receivers
  settings/    DataStore
```

- `domain` is pure Kotlin so the parser/serializer/reconciler are trivially testable and portable to Swift (§14).
- One `TaskRepository` exposes `Flow<List<Task>>` per space; ViewModels never hold mutable copies. All mutations go through repository use-cases that run the write protocol then reconcile.
- All I/O on `Dispatchers.IO`; ViewModels expose state via `StateFlow` with `stateIn(WhileSubscribed)`.
- Time handling: `java.time`, `LocalDate`/`LocalTime` for due values (no timezone stored in files); converted with the device's current zone at scheduling time.

## 11. Design system

- Material 3 Expressive: dynamic color, expressive shapes/motion, motion-scheme spring animations for list changes, sheet transitions, and checkbox completion.
- Clean, minimal chrome; large type for task text; distinct visual treatment for overdue.
- Light/dark follow system. Edge-to-edge. Predictive back supported.
- App icon: existing SVG (convert to adaptive icon with monochrome layer for themed icons).

## 12. Testing requirements

1. **Parser/serializer:** a fresh fixture corpus covering: CRLF/LF, BOM, nested tasks, cancelled state, lone `⏰`, reordered metadata, all Tasks signifiers preserved (⏳ 🛫 ➕ 🔺–⏬ 🔁 🏁 🆔 ⛔ ❌), Tasks-valid field order after every edit (`⏰` always before all signifiers), global filter, numbered bullets, `📅 ⏰` legacy order, duplicate identical lines, empty file, no trailing newline.
2. **Round-trip golden tests:** edit one line → diff of file must equal exactly that line.
3. **Reconciler:** duplicates, edits, deletions, moves between files; assert no duplicate rows ever.
4. **AlarmScheduler:** idempotency, diff correctness, past-time exclusion, reschedule after simulated boot.
5. **SyncEngine:** in-memory fake WebDAV; all four state combinations plus conflict-copy creation.
6. **Write protocol:** concurrent write and stale-line abort.
7. Compatibility test: for each fixture line, an independent Tasks-style backwards trailing-signifier parser (test helper) must extract the same priority/due/done as the app.
8. Parser fixtures stored as language-neutral JSON (`parser-fixtures.json`: input text → expected tasks/serialized output) so the macOS port reuses them unchanged.

## 13. Build and delivery (OpenCode's part)

- Gradle build produces `app-debug.apk` (or release signed with a local self-generated keystore). Install via `adb install -r`.
- OpenCode runs: `./gradlew test`, `./gradlew assembleDebug`, adb install/logcat, and reports output back verbatim. It does not author or modify source outside patches Claude provides.

## 14. Later

**V2 backlog**
- **Recurrence** (`🔁`) — parse, complete-and-spawn-next, alarm handling. *(User asked to be reminded about this.)*
- Week/day calendar views, tags/priority UI, deletion propagation in sync.

**macOS version (after Android)**
- Native SwiftUI, macOS 27, Liquid Glass. Reuse: §4 format rules, §5 write protocol, §8.1 alarm rules (via `UNUserNotificationCenter`), §9 sync algorithm, and `parser-fixtures.json`.
- Sandbox + security-scoped bookmarks for the vault folder; polling change detection.

## 15. Decisions made without asking (veto any)

1. A file may belong to several spaces.
2. Conflict policy: remote wins as main file, local kept as a conflict copy.
3. Date-only tasks alarm at 09:00 by default (configurable).
4. Completion appends `✅ date`.
5. Cancelled (`[-]`) tasks are hidden and silent.
6. Deletions are not synced over WebDAV in v1.
7. No DI framework.
8. Time is written before the Tasks signifiers (`⏰ 19:00 📅 date`), not after, so Tasks still sees the due date.
9. Recurring tasks: shown and notified, but completion disabled in v1.
10. Priority is displayed, not editable, in v1.

## 16. Build phases (for a fresh session)

Work one phase at a time. Each phase ends with something OpenCode can build and test; do not start the next phase until the current one passes its tests and (where noted) runs on the Honor 400.

1. **Domain core** (pure Kotlin): parser, serializer, reconciler, Tasks-compatibility test helper, `parser-fixtures.json`. Gate: `./gradlew test` green.
2. **Skeleton + vault access:** Gradle project, M3 theme, SAF folder picker, file scanner, write protocol. Gate: pick the vault, see parsed tasks.
3. **Spaces + list + create/edit.**
4. **Notifications:** scheduler, receivers, Done/Snooze, health check screen. Gate: real alarm a few minutes out on the Honor 400, lateness measured via the diagnostics log.
5. **Calendar** (month view).
6. **WebDAV sync.**
7. **Polish:** Expressive motion, adaptive icon, diagnostics cleanup.

**Open item:** confirm the Honor 400's Android version (Settings → About phone); minSdk is 31 unless it turns out lower.
