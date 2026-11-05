param([string]$Device = 'emulator-5554')

$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw 'Only a dedicated emulator may run this suite.' }
$repoRoot = Split-Path $PSScriptRoot -Parent
$android = Join-Path $PSScriptRoot 'android.ps1'
$reportRoot = Join-Path $repoRoot ('.local/validation/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

function Invoke-Device([string[]]$Arguments) {
    $result = & $android -Tool adb -ToolArgs (@('-s', $Device) + $Arguments)
    return ($result -join "`n").Trim()
}
function Test-Classes([string]$Classes, [string]$Name) {
    $result = Invoke-Device @('shell', 'am', 'instrument', '-w', '-e', 'class', $Classes,
        'dev.sjtu.allinone.debug.test/androidx.test.runner.AndroidJUnitRunner')
    Set-Content -LiteralPath (Join-Path $reportRoot "$Name.txt") -Value $result -Encoding utf8
    if ($result -notmatch '(?m)^OK \(\d+ tests?\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "$Name failed; see $reportRoot"
    }
    Write-Host "$Name passed"
}

$hardware = Invoke-Device @('shell', 'getprop', 'ro.hardware')
if ($hardware -notin @('ranchu', 'goldfish')) { throw "Unexpected emulator hardware: $hardware" }
Invoke-Device @('install', '-r', (Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'))
Invoke-Device @('install', '-r', (Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))

$normal = @('AppSmokeTest', 'TimetableNavigationTest', 'IdsProbeTest', 'LocalWorkflowTest', 'TimetablePersistenceTest',
    'WidgetHostTest', 'RoomUiTest', 'AcademicUiTest', 'ReminderDeliveryTest#notificationDeliveryAndCompletion',
    'ReminderDeliveryTest#legacyScheduledReminderSurvivesUpgrade')
Test-Classes (($normal | ForEach-Object { "dev.sjtu.allinone.$_" }) -join ',') 'features'
Test-Classes 'dev.sjtu.allinone.WidgetClockTest' 'clock'
Test-Classes 'dev.sjtu.allinone.SessionStorageTest#saveSyntheticSession' 'session-save'
Invoke-Device @('shell', 'am', 'force-stop', 'dev.sjtu.allinone.debug')
Test-Classes 'dev.sjtu.allinone.SessionStorageTest#restoreSyntheticSessionAndCheckIsolation' 'session-restore'

try {
    Invoke-Device @('shell', 'pm', 'revoke', 'dev.sjtu.allinone.debug', 'android.permission.POST_NOTIFICATIONS')
    Test-Classes 'dev.sjtu.allinone.ReminderDeliveryTest#deniedPermissionDoesNotDeliver' 'notification-denied'
} finally {
    Invoke-Device @('shell', 'pm', 'grant', 'dev.sjtu.allinone.debug', 'android.permission.POST_NOTIFICATIONS')
}

$wifiEnabled = (Invoke-Device @('shell', 'settings', 'get', 'global', 'wifi_on')) -eq '1'
$dataEnabled = (Invoke-Device @('shell', 'settings', 'get', 'global', 'mobile_data')) -eq '1'
try {
    Invoke-Device @('shell', 'svc', 'wifi', 'disable')
    Invoke-Device @('shell', 'svc', 'data', 'disable')
    Test-Classes 'dev.sjtu.allinone.OfflineCacheTest' 'offline'
} finally {
    if ($wifiEnabled) { Invoke-Device @('shell', 'svc', 'wifi', 'enable') }
    if ($dataEnabled) { Invoke-Device @('shell', 'svc', 'data', 'enable') }
}

Test-Classes 'dev.sjtu.allinone.RestartPersistenceTest#prepareRestartFixture' 'reboot-prepare'
Invoke-Device @('reboot')
Invoke-Device @('wait-for-device')
$deadline = [DateTime]::UtcNow.AddSeconds(60)
while ((Invoke-Device @('shell', 'getprop', 'sys.boot_completed')) -ne '1') {
    if ([DateTime]::UtcNow -gt $deadline) { throw 'Emulator did not boot in time.' }
    Start-Sleep -Seconds 1
}
Test-Classes 'dev.sjtu.allinone.RestartPersistenceTest#verifyRestartFixture' 'reboot-restore'
Test-Classes 'dev.sjtu.allinone.DozeReminderTest#prepareDozeReminder' 'doze-prepare'
try {
    Invoke-Device @('shell', 'dumpsys', 'battery', 'unplug')
    Invoke-Device @('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    $idle = Invoke-Device @('shell', 'dumpsys', 'deviceidle', 'force-idle')
    Set-Content -LiteralPath (Join-Path $reportRoot 'doze-state.txt') -Value $idle -Encoding utf8
    if ($idle -notmatch 'Now forced in to deep idle mode') { throw 'Emulator did not enter idle.' }
    Start-Sleep -Seconds 12
} finally {
    Invoke-Device @('shell', 'dumpsys', 'deviceidle', 'unforce')
    Invoke-Device @('shell', 'dumpsys', 'battery', 'reset')
    Invoke-Device @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
}
Test-Classes 'dev.sjtu.allinone.DozeReminderTest#verifyDozeReminder' 'doze-restore'
Invoke-Device @('pull', '/sdcard/Android/data/dev.sjtu.allinone.debug/files/widget-courses-test.png',
    (Join-Path $reportRoot 'widget-courses.png'))
Invoke-Device @('pull', '/sdcard/Android/data/dev.sjtu.allinone.debug/files/academic-test.png',
    (Join-Path $reportRoot 'academic.png'))
Write-Host "All emulator checks passed. Reports: $reportRoot"
