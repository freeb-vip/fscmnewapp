param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Package = 'com.fscm.app.demo.debug',
    [int]$Iterations = 3
)

$ErrorActionPreference = 'Stop'
function Invoke-Adb {
    $result = & $Adb -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0 -or ($result -match 'Security exception|Error:')) {
        throw ($result -join "`n")
    }
    return $result
}

# Run only on a disposable Android 9 emulator; never clear an existing user's data.
if ((Invoke-Adb shell getprop ro.build.version.sdk).Trim() -ne '28' -or
    (Invoke-Adb shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'This regression check requires an Android 9 (API 28) emulator.'
}
if ($Iterations -lt 1) { throw 'Iterations must be positive.' }
$activity = "$Package/com.google.samples.apps.nowinandroid.MainActivity"
$service = "$Package/com.google.samples.apps.nowinandroid.fscm.device.TerminalFindService"
$startedAt = (Invoke-Adb shell date '+%m-%d_%H:%M:%S.000').Trim().Replace('_', ' ')

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell am start -W -n $activity | Out-Null
    Start-Sleep -Seconds 12
    $processBefore = (Invoke-Adb shell pidof $Package).Trim()

    # Exercise a foreground start with no session, repeated starts and a queued stop.
    # run-as uses the debug app UID so the service can remain non-exported.
    Invoke-Adb shell run-as $Package am start-foreground-service --user 0 -n $service | Out-Null
    Invoke-Adb shell run-as $Package am start-foreground-service --user 0 -n $service | Out-Null
    Invoke-Adb shell run-as $Package am startservice --user 0 -n $service -a com.fscm.app.action.STOP_TERMINAL_SERVICE | Out-Null
    Start-Sleep -Seconds 12
    $processAfter = (Invoke-Adb shell pidof $Package).Trim()
    if (!$processBefore -or $processBefore -ne $processAfter) {
        throw "App process exited or restarted during iteration $iteration."
    }
    $crashes = Invoke-Adb logcat -d -b crash -T $startedAt
    if ($crashes -match [regex]::Escape($Package)) {
        throw ($crashes -join "`n")
    }
    Write-Output "PASS iteration ${iteration}: cold start and foreground service start/stop (PID $processAfter)"
}
