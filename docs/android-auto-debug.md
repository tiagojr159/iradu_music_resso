# Android Auto and Assistant debug checklist

Use this when the APK builds but Iradu Music does not appear in Android Auto or does not receive Google Assistant commands.

## APK and install state

Use the Android SDK `adb.exe` directly if `adb` is not in `PATH`:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb shell dumpsys package com.iradu.music.debug
```

In `dumpsys package`, verify:

- `pkg=Package{... com.iradu.music.debug}` exists.
- `org.schabi.newpipe.player.PlayerService` exists.
- `android:exported=true` is reflected by the service being available to intent queries.
- The service has actions:
  - `androidx.media3.session.MediaLibraryService`
  - `androidx.media3.session.MediaSessionService`
  - `android.media.browse.MediaBrowserService`
  - `android.media.action.MEDIA_PLAY_FROM_SEARCH`

## PackageManager discovery

These commands prove whether Android on the phone can discover the media service before Android Auto enters the picture:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell cmd package query-intent-services -a androidx.media3.session.MediaLibraryService
& $adb shell cmd package query-intent-services -a android.media.browse.MediaBrowserService
& $adb shell cmd package query-intent-services -a android.media.action.MEDIA_PLAY_FROM_SEARCH
```

Expected result: each query should include `com.iradu.music.debug/org.schabi.newpipe.player.PlayerService`.

If these queries do not show `PlayerService`, Android Auto will not discover Iradu Music either.

## Android Auto debug/sideload requirements

For a debug APK installed outside Play Store:

1. Open Android Auto settings on the phone.
2. Tap version/build repeatedly to enable developer settings.
3. Open Android Auto developer settings.
4. Enable `Unknown sources`.
5. Clear Android Auto cache/data if it previously indexed the old APK.
6. Reconnect USB/wireless projection after reinstalling the APK.

## Logcat proof

Clear old logs, then connect Android Auto and try opening the app list plus these commands:

- `Ok Google, tocar música no Iradu Music`
- `Ok Google, próxima música`
- `Ok Google, pausar`

Run:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb logcat -v time PlayerService:I MediaSessionService:I MediaLibraryService:I MediaSessionStub:I Gearhead:D AndroidAuto:D Assistant:D GsaVoiceInteractionSvc:D SearchService:D *:S
```

Useful Iradu Music logs:

- `onGetSession: package=...`
- `Media controller connected: package=...`
- `Library root requested by ...`
- `Children requested by ...`
- `Search requested by ...`
- `Search result requested by ...`
- `Set media items requested by ...`
- `Add media items requested by ...`

Interpretation:

- If no `PlayerService` logs appear while opening Android Auto app list, Gearhead is not discovering or not attempting to bind the service.
- If `onGetSession` appears but `onConnect`/`onGetLibraryRoot` does not, Media3 is receiving the bind but the controller is not completing the session connection.
- If `onGetLibraryRoot` and `onGetChildren` appear, discovery works and remaining issues are in library content or playback commands.
- If Assistant commands do not show `onSetMediaItems`, `onAddMediaItems`, `onSearch`, or media button logs while Iradu Music is active, the command is not being routed to this session.

## Force a voice-search style intent

This checks the manifest `MEDIA_PLAY_FROM_SEARCH` path without Android Auto:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell am startservice `
  -a android.media.action.MEDIA_PLAY_FROM_SEARCH `
  -n com.iradu.music.debug/org.schabi.newpipe.player.PlayerService `
  --es query "musica"
```

Expected result: logcat should show `onStartCommand()` and `onPrepareFromSearch(...)`.
