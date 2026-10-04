param([string]$Python = "$PSScriptRoot/../.venv/Scripts/python.exe")
$ErrorActionPreference = 'Stop'
& $Python "$PSScriptRoot/../server/server.py"
if ($LASTEXITCODE) { throw 'Server exited with an error' }
