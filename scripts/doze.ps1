param([ValidateSet('WebSocket','MQTT','ForegroundWS')][string]$Technique = 'WebSocket', [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Serial)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = "$Technique-T4-$(Get-Date -Format yyyyMMdd-HHmmss)"
$outputDir = "$project/experiments/results/$run"
New-Item -ItemType Directory -Force $outputDir | Out-Null
function DeviceCommand([string[]]$Arguments) {
    $result = & adb -s $Serial @Arguments
    if ($LASTEXITCODE) { throw "adb failed: $Arguments" }
    return $result
}
try {
    DeviceCommand @('shell','am','start','-S','-n','dev.pushless/.MainActivity','--es','technique',$Technique,'--es','run',$run,'--es','condition','T4') | Out-Null
    Start-Sleep -Seconds 5
    DeviceCommand @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
    DeviceCommand @('shell','input','keyevent','KEYCODE_SLEEP') | Out-Null
    DeviceCommand @('shell','dumpsys','battery','unplug') | Out-Null
    DeviceCommand @('shell','dumpsys','deviceidle','force-idle') | Set-Content -Encoding utf8 "$outputDir/force-idle.txt"
    DeviceCommand @('shell','dumpsys','deviceidle') | Set-Content -Encoding utf8 "$outputDir/deviceidle.txt"
    for ($index = 1; $index -le 3; $index++) {
        & "$project/.venv/Scripts/python.exe" "$project/server/send.py" "$run event $index" --run $run |
            Add-Content -Encoding utf8 "$outputDir/sent.jsonl"
        if ($LASTEXITCODE) { throw 'Send failed' }
        Start-Sleep -Seconds 1
    }
    Start-Sleep -Seconds 5
    DeviceCommand @('shell','run-as','dev.pushless','cat','files/results.jsonl') | Set-Content -Encoding utf8 "$outputDir/before-restore.jsonl"
} finally {
    & adb -s $Serial shell dumpsys deviceidle unforce
    & adb -s $Serial shell dumpsys battery reset
    & adb -s $Serial shell input keyevent KEYCODE_WAKEUP
}
Start-Sleep -Seconds 5
DeviceCommand @('shell','run-as','dev.pushless','cat','files/results.jsonl') | Set-Content -Encoding utf8 "$outputDir/after-restore.jsonl"
& "$project/.venv/Scripts/python.exe" "$project/experiments/summarize.py" --journal "$project/server/data/events.sqlite" --receipts "$outputDir/before-restore.jsonl" --run $run --technique $Technique --csv "$outputDir/before.csv"
& "$project/.venv/Scripts/python.exe" "$project/experiments/summarize.py" --journal "$project/server/data/events.sqlite" --receipts "$outputDir/after-restore.jsonl" --run $run --technique $Technique --csv "$outputDir/after.csv"
Write-Output "Evidence: $outputDir"
