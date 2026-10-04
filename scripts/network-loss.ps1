param([ValidateSet('WebSocket','MQTT','ForegroundWS')][string]$Technique = 'WebSocket', [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Serial)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = "$Technique-T5-$(Get-Date -Format yyyyMMdd-HHmmss)"
$outputDir = "$project/experiments/results/$run"
New-Item -ItemType Directory -Force $outputDir | Out-Null
function DeviceCommand([string[]]$Arguments) {
    $result = & adb -s $Serial @Arguments
    if ($LASTEXITCODE) { throw "adb failed: $Arguments" }
    return $result
}
function SendEvent([string]$Payload) {
    & "$project/.venv/Scripts/python.exe" "$project/server/send.py" $Payload --run $run |
        Add-Content -Encoding utf8 "$outputDir/sent.jsonl"
    if ($LASTEXITCODE) { throw 'Send failed' }
}
try {
    DeviceCommand @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
    DeviceCommand @('shell','am','start','-S','-n','dev.pushless/.MainActivity','--es','technique',$Technique,'--es','run',$run,'--es','condition','T5') | Out-Null
    Start-Sleep -Seconds 5
    SendEvent 'Before Wi-Fi loss'
    Start-Sleep -Seconds 1
    DeviceCommand @('shell','svc','wifi','disable') | Out-Null
    Start-Sleep -Seconds 3
    DeviceCommand @('shell','cmd','wifi','status') | Set-Content -Encoding utf8 "$outputDir/wifi-disabled.txt"
    for ($index = 1; $index -le 3; $index++) { SendEvent "During Wi-Fi loss $index"; Start-Sleep -Seconds 1 }
    DeviceCommand @('shell','run-as','dev.pushless','cat','files/results.jsonl') | Set-Content -Encoding utf8 "$outputDir/before-restore.jsonl"
} finally {
    & adb -s $Serial shell svc wifi enable
}
Start-Sleep -Seconds 12
DeviceCommand @('shell','cmd','wifi','status') | Set-Content -Encoding utf8 "$outputDir/wifi-restored.txt"
SendEvent 'After Wi-Fi restore'
Start-Sleep -Seconds 5
DeviceCommand @('shell','run-as','dev.pushless','cat','files/results.jsonl') | Set-Content -Encoding utf8 "$outputDir/after-restore.jsonl"
& "$project/.venv/Scripts/python.exe" "$project/experiments/summarize.py" --journal "$project/server/data/events.sqlite" --receipts "$outputDir/after-restore.jsonl" --run $run --technique $Technique --csv "$outputDir/summary.csv"
Write-Output "Evidence: $outputDir"
