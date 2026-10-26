# Run the relay server directly on Windows (no Docker).
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$port = $env:YG_PORT
if ([string]::IsNullOrWhiteSpace($port)) { $port = '8787' }
$dataDir = $env:YG_DATA_DIR
if ([string]::IsNullOrWhiteSpace($dataDir)) { $dataDir = '.\data' }

$env:PYTHONIOENCODING = 'utf-8'
Write-Host "YG Relay -> http://127.0.0.1:$port  (data: $dataDir)"
python serve.py --host 0.0.0.0 --port $port --data-dir $dataDir
