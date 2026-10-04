param([Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Serial)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = "MQTT-duplicates-$(Get-Date -Format yyyyMMdd-HHmmss)"
$outputDir = "$project/experiments/results/$run"
New-Item -ItemType Directory -Force $outputDir | Out-Null
& adb -s $Serial shell am start -S -n dev.pushless/.MainActivity --es technique MQTT --es run $run --es condition synthetic-duplicate
if ($LASTEXITCODE) { throw 'Launch failed' }
Start-Sleep -Seconds 5
$eventText = & "$project/.venv/Scripts/python.exe" "$project/server/send.py" 'Duplicate-ID check' --run $run
if ($LASTEXITCODE) { throw 'Send failed' }
$eventText | Set-Content -Encoding utf8 "$outputDir/sent.jsonl"
$eventId = ($eventText | ConvertFrom-Json).event.id
Start-Sleep -Seconds 1
& "$project/.venv/Scripts/python.exe" "$project/server/replay.py" $eventId
if ($LASTEXITCODE) { throw 'Replay failed' }
Start-Sleep -Seconds 3
& adb -s $Serial shell run-as dev.pushless cat files/results.jsonl | Set-Content -Encoding utf8 "$outputDir/results.jsonl"
if ($LASTEXITCODE) { throw 'Export failed' }
& "$project/.venv/Scripts/python.exe" "$project/experiments/summarize.py" --journal "$project/server/data/events.sqlite" --receipts "$outputDir/results.jsonl" --run $run --technique MQTT --csv "$outputDir/summary.csv"
if ($LASTEXITCODE) { throw 'Summary failed' }
Write-Output "Evidence: $outputDir"
