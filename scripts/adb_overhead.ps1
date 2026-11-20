# Sample SmartFreeze AI's own memory (PSS) and CPU via ADB while it is monitoring.
# Usage: .\scripts\adb_overhead.ps1 -Minutes 30 -EverySeconds 30 -Out research\results\overhead_10s.csv
# Requires: phone connected with USB debugging; Android SDK platform-tools (adb) on PATH.
param([int]$Minutes = 30, [int]$EverySeconds = 30, [string]$Out = "research\results\overhead.csv",
      [string]$Package = "com.buse.smartfreeze")
$ErrorActionPreference = "Stop"
"timestamp,total_pss_kb,cpu_percent" | Out-File -Encoding ascii $Out
$end = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $end) {
    $mem = adb shell dumpsys meminfo $Package | Select-String "TOTAL PSS:\s+(\d+)|TOTAL\s+(\d+)" | Select-Object -First 1
    $pss = if ($mem) { ($mem.Matches[0].Groups | Where-Object { $_.Success -and $_.Value -match '^\d+$' } | Select-Object -First 1).Value } else { "" }
    $cpuLine = adb shell dumpsys cpuinfo | Select-String $Package | Select-Object -First 1
    $cpu = if ($cpuLine -and $cpuLine.Line -match '([\d\.]+)%') { $Matches[1] } else { "" }
    "$(Get-Date -Format o),$pss,$cpu" | Out-File -Append -Encoding ascii $Out
    Start-Sleep -Seconds $EverySeconds
}
Write-Host "Saved $Out"
Write-Host "Battery comparison: run 'adb shell dumpsys batterystats --reset' before each 1-hour session, then"
Write-Host "'adb shell dumpsys batterystats $Package > research\results\batterystats_<session>.txt' afterwards."
