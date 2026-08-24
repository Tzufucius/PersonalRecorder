# Personal Recorder

[English](README.md) | [简体中文](README.zh-CN.md)

Personal Recorder is a local-first Android notification recorder. It collects notification events on the device, keeps the live dataset in Room, and can write long-term JSONL archives. A private GitHub repository is an optional remote archive and restore source; the Room database is never uploaded.

## Features

- Notification listener collection with ongoing and group-summary filtering.
- Local Room storage for real-time records and fast browsing.
- Filters by application, date, hour, allowlist, and blocklist.
- Statistics for today, the last 7 days, and the last 30 days.
- A fixed 24-hour stacked chart showing the application source of each hour.
- JSONL half-day archives for durable, portable history.
- Optional GitHub private-repository sync, restore, conflict handling, and progress reporting.
- Background runtime diagnostics for notification access, battery restrictions, and vendor-specific guidance.
- English and Simplified Chinese resources with locale-aware dates and plurals.

## Screenshots

These screenshots were captured from the current Debug APK on a connected device. Notification contents and account/repository identifiers were redacted locally before committing.

| Records | Statistics | Settings |
| --- | --- | --- |
| ![Records](docs/images/records.png) | ![Statistics](docs/images/statistics.png) | ![Settings](docs/images/settings.png) |

## Data and synchronization

Room is the live on-device source. The archive writer exports JSONL files grouped by half day, while daily manifests describe the available archive parts. GitHub synchronization is optional and uses a private repository as an archive and restore endpoint. Restore keeps local data separate until conflicts are resolved; it does not change the archive format or the notification collection rules.

## Privacy and security

Notification text and metadata remain on the device unless the user configures an archive remote. Use a private GitHub repository, protect the access token, and review archive contents before sharing them. The application does not provide end-to-end encryption for archive files, so repository access controls remain important.

## Limitations

- Android notification access and vendor battery policies can affect collection reliability.
- Background work is subject to Android and OEM scheduling limits.
- GitHub sync requires a configured private repository and network access.
- The current release has no in-app language switcher; the Android system locale selects resources.

## Build and run

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

Install the generated `app/build/outputs/apk/debug/app-debug.apk` on an Android device, grant notification access, and configure archive settings only when remote synchronization is needed.

## Verification

The commands above were executed for this change. JVM tests, Debug assembly, and 21 connected Android tests completed successfully on the connected Huawei ADY-AL00 device. The Debug APK was installed and the three primary pages were opened through ADB; no Personal Recorder `FATAL EXCEPTION`, `NoSuchMethodError`, or Vico crash was observed in the app process log.

## Roadmap

- Improve archive inspection and conflict review workflows.
- Add more detailed diagnostics for OEM background restrictions.
- Continue expanding locale coverage and accessibility validation.
