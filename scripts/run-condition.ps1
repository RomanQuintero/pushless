param(
    [ValidateSet('WebSocket','MQTT','Polling','ForegroundWS')][string]$Technique = 'WebSocket',
    [ValidateSet('T1','T2','T3','T6','T6Direct')][string]$Condition = 'T1',
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Serial,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Server,
    [string]$Python = "$PSScriptRoot/../.venv/Scripts/python.exe",
    [int]$Events = 3
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = "$Technique-$Condition-$(Get-Date -Format yyyyMMdd-HHmmss)"
$outputDir = "$project/experiments/results/$run"
New-Item -ItemType Directory -Force $outputDir | Out-Null
$sendResults = @()
if ($Technique -eq 'Polling') {
    for ($index = 1; $index -le $Events; $index++) {
        $json = & $Python "$project/server/send.py" "$run queued event $index" --run $run --server $Server
        if ($LASTEXITCODE) { throw 'Server send failed' }
        $sendResults += $json
    }
}
function AdbCommand([string[]]$Arguments) {
    $result = & adb -s $Serial @Arguments
    if ($LASTEXITCODE) { throw "adb failed: $Arguments" }
    return $result
}
# -S force-stops only as an explicitly recorded reset before the run, never as T6.
AdbCommand @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
AdbCommand @('shell','am','start','-S','-n','dev.pushless/.MainActivity','--es','technique',$Technique,'--es','run',$run,'--es','condition',$Condition,'--es','server',$Server) | Out-Null
Start-Sleep -Seconds 5
$pidBefore = AdbCommand @('shell','pidof','dev.pushless')
switch ($Condition) {
    T2 { AdbCommand @('shell','input','keyevent','KEYCODE_HOME') | Out-Null }
    T3 { AdbCommand @('shell','input','keyevent','KEYCODE_SLEEP') | Out-Null }
    T6 {
        AdbCommand @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        AdbCommand @('shell','am','kill','dev.pushless') | Out-Null
    }
    T6Direct {
        AdbCommand @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
        if ("$pidBefore" -notmatch '^\d+$') { throw 'Expected one numeric app PID' }
        AdbCommand @('shell','run-as','dev.pushless','kill','-9',"$pidBefore") | Out-Null
    }
}
Start-Sleep -Seconds 3
$pidAfter = & adb -s $Serial shell pidof dev.pushless
for ($index = 1; $Technique -ne 'Polling' -and $index -le $Events; $index++) {
    $json = & $Python "$project/server/send.py" "$run event $index" --run $run --server $Server
    if ($LASTEXITCODE) { throw 'Server send failed' }
    $sendResults += $json
    Start-Sleep -Seconds 1
}
$sendResults | Set-Content -Encoding utf8 "$outputDir/sent.jsonl"
Start-Sleep -Seconds 5
$receipts = AdbCommand @('shell','run-as','dev.pushless','cat','files/results.jsonl')
$receipts | Set-Content -Encoding utf8 "$outputDir/results.jsonl"
@{run=$run; technique=$Technique; condition=$Condition; reset='am start -S before run'; pid_before=$pidBefore; pid_after=$pidAfter; exported_at=(Get-Date).ToString('o'); note='T6 is am kill; T6Direct is debug-app-UID SIGKILL through run-as, not force-stop. Valid killed state only if PID disappears. Notification submissions do not verify visible display.'} |
    ConvertTo-Json | Set-Content -Encoding utf8 "$outputDir/state.json"
& $Python "$project/experiments/summarize.py" --journal "$project/server/data/events.sqlite" --receipts "$outputDir/results.jsonl" --run $run --technique $Technique --csv "$outputDir/summary.csv"
if ($LASTEXITCODE) { throw 'Summary failed' }
Write-Output "Evidence: $outputDir"
