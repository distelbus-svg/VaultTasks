# VaultTasks

Android (then macOS) task app that reads/writes tasks in an Obsidian vault. See `docs/VaultTasks-Android-Spec.md`.

## Phase 1 — domain core (pure Kotlin/JVM)

Requires **JDK 21** (Gradle 8.11.1 does not run on JDK 25). Set `JAVA_HOME` accordingly.

```
./gradlew :domain:test
```
