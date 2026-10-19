# Audio Reminders (Android)

*Leia isto em outros idiomas: [Português](README.md)*

---

A native Android application built with **Kotlin** and **Jetpack Compose** (Material Design 3) for scheduled audio playback, voice recordings, and sound reminders at configurable intervals, while respecting multiple rest periods, quiet hours, weekday routines, and device state.

---

## Main Features

1. **Flexible Scheduling Profiles**
   - Custom operating windows, including schedules that cross midnight.
   - Repeat intervals from 5 to 120 minutes.
   - Weekday selection (Monday through Sunday), with detection and warnings for priority conflicts.
   - **Multiple Rest Periods:** Automatically mutes playback during configured rest windows.
   - Configurable post-rest resume policies (`NEXT_GRID_MARK`, `AT_REST_END`).
   - Playback modes: sequential, weighted random (weights 1–5), or a specific audio item.
   - Global or per-profile volume fade-in (0.5–10 seconds).

2. **Audio Library**
   - **Microphone recording:** Integrated recorder with real-time waveform visualization, timer, pause/resume, and preview playback.
   - **File import:** Local MP3, WAV, M4A, and OGG files through the system file picker.
   - **Text-to-Speech (TTS):** Native Android speech synthesis; available voices and languages depend on the device.
   - Per-item volume (0–100%) and random-selection weight.

3. **Background Scheduling and Device State**
   - **Room Database:** Local persistence for audio items, profiles, rest periods, and saved reminders.
   - **WorkManager and exact alarms:** Scheduling with `AlarmManager` and `CoroutineWorker`.
   - **Foreground service:** A `mediaPlayback` foreground service intended to support playback while the screen is locked.
   - Handling for phone calls, Android Do Not Disturb, and Bluetooth devices.
   - Battery optimization assistant with links to relevant system settings.

4. **Backup and Portability**
   - Export and import of settings, profiles, rest periods, audio metadata, and reminders as JSON.
   - **Current limitation:** JSON backups do not contain local audio file bytes. After importing on another device, audio files must be copied or imported again; references to missing files are disabled to prevent playback failures.

5. **Interface Languages**
   - Language selector for Brazilian Portuguese (PT-BR), English, and Spanish.
   - The selected language is saved locally in app preferences.

## Project Architecture

- **Language:** Kotlin
- **UI toolkit:** Jetpack Compose (Material Design 3)
- **Database:** Room
- **Preferences:** Jetpack DataStore Preferences
- **Background execution:** WorkManager, foreground service, broadcast receivers
- **Audio:** Android MediaPlayer, AudioFocusManager, TextToSpeech

## 👤 Authorship and development

Audio Reminders is a native Android application independently developed by Pablo Phillipe Cândido dos Santos to schedule and play audio reminders, voice recordings, and text-to-speech messages within configurable routines.

Generative artificial intelligence tools were used as auxiliary resources during development. The author remains responsible for the project's conception, implementation, integration, and verification.

Lattes CV: [http://lattes.cnpq.br/9500873674712528](http://lattes.cnpq.br/9500873674712528)
