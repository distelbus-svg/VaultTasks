# VaultTasks

Android (then macOS) task app that reads/writes tasks in an Obsidian vault. See `docs/VaultTasks-Android-Spec.md`.

## Build

Requires **JDK 21** (`export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) and an Android SDK
(`local.properties` → `sdk.dir=...`; platform 37 + build-tools 36.1). Gradle 9.6.1, AGP 9.4.1, Kotlin 2.4.20.

```
./gradlew :domain:test          # pure Kotlin/JVM: parser, serializer, reconciler, scanner, write protocol
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Status

- Phase 1 — domain core (parser, serializer, reconciler, fixtures). Done.
- Phase 2 — skeleton + vault access: `domain/.../vault` (FS seam, `FileScanner`, `VaultWriter`), `app` (SAF file system,
  M3 dynamic-color theme, folder picker, per-file task list, checkbox toggle through the write protocol).
  Gate: pick the vault on the Honor 400 and see parsed tasks.
- Phase 3 — spaces + list + create/edit: `Space`/`SpacesData` and `TaskGrouping` in `domain`, `VaultWriter.move`
  (edit + move between files of a space, rolled back on failure), scan scope limited to files assigned to spaces;
  `app` has the space switcher, Overdue/Today/Upcoming/No date/Done list, swipe complete/delete with undo,
  create/edit sheet (date, time, file picker) and the space manager/editor. Spaces persist as JSON in DataStore
  (no Room: the task cache is in memory, the vault files are the source of truth).
  Gate: create a space, assign files, create/edit/complete/delete tasks on the Honor 400 and check the vault files.
