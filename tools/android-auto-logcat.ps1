param(
    [string] $PackageName = "com.iradu.music.debug"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

if (!(Test-Path $adb)) {
    throw "adb.exe not found at $adb"
}

Write-Host "Connected devices:"
& $adb devices

Write-Host ""
Write-Host "MediaLibraryService discovery:"
& $adb shell cmd package query-intent-services -a androidx.media3.session.MediaLibraryService

Write-Host ""
Write-Host "Legacy MediaBrowserService discovery:"
& $adb shell cmd package query-intent-services -a android.media.browse.MediaBrowserService

Write-Host ""
Write-Host "MEDIA_PLAY_FROM_SEARCH discovery:"
& $adb shell cmd package query-intent-services -a android.media.action.MEDIA_PLAY_FROM_SEARCH

Write-Host ""
Write-Host "Installed package service excerpt:"
& $adb shell dumpsys package $PackageName

Write-Host ""
Write-Host "Clearing logcat and following Android Auto / Assistant / Iradu Music logs..."
& $adb logcat -c
& $adb logcat -v time `
    PlayerService:I `
    MediaSessionService:I `
    MediaLibraryService:I `
    MediaSessionStub:I `
    Gearhead:D `
    AndroidAuto:D `
    Assistant:D `
    GsaVoiceInteractionSvc:D `
    SearchService:D `
    '*:S'
