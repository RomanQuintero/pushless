param([ValidateSet('build','install','launch','clear','logs','export','devices','services')][string]$Action = 'build', [string]$Serial)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }
switch ($Action) {
    build {
        Push-Location "$project/android"
        try { & ./gradlew.bat assembleDebug; if ($LASTEXITCODE) { throw 'Build failed' } }
        finally { Pop-Location }
    }
    install { & adb @adbArgs install -r "$project/android/app/build/outputs/apk/debug/app-debug.apk" }
    launch { & adb @adbArgs shell am start -n dev.pushless/.MainActivity }
    clear { & adb @adbArgs shell pm clear dev.pushless }
    logs { & adb @adbArgs logcat 'Pushless:I' '*:S' }
    export {
        $outputDir = "$project/experiments/results"
        New-Item -ItemType Directory -Force $outputDir | Out-Null
        $lines = & adb @adbArgs shell run-as dev.pushless cat files/results.jsonl
        if ($LASTEXITCODE) { throw 'Export failed; install a debug APK and generate a log first' }
        $lines | Set-Content -Encoding utf8 "$outputDir/results.jsonl"
    }
    devices { & adb devices -l }
    services { & adb @adbArgs shell dumpsys activity services dev.pushless }
}
if ($LASTEXITCODE) { throw "Command failed with exit code $LASTEXITCODE" }
