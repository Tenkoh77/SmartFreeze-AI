# Train SmartFreeze AI's model on Windows and install it into the Android project.
# Usage (from the repository root, in PowerShell):
#   .\scripts\train_model.ps1 -Samples "machine-learning\data\raw\samples_*.csv" -Events "machine-learning\data\raw\slowdown_events_*.csv"
#   .\scripts\train_model.ps1 -SyntheticDemo      # software test only, NOT research data
param(
    [string]$Samples = "",
    [string]$Events = "",
    [switch]$SyntheticDemo,
    [ValidateSet("fpr", "f1")][string]$ThresholdMethod = "fpr",
    [double]$TargetFpr = 0.01
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ml = Join-Path $root "machine-learning"
$venv = Join-Path $ml ".venv"

if (-not (Test-Path $venv)) {
    Write-Host "Creating virtual environment..."
    python -m venv $venv
}
$py = Join-Path $venv "Scripts\python.exe"
& $py -m pip install --upgrade pip | Out-Null
& $py -m pip install -r (Join-Path $ml "requirements.txt")

Push-Location $ml
try {
    Write-Host "Running tests..."
    & $py -m pytest
    if ($LASTEXITCODE -ne 0) { throw "pytest failed - not training." }

    $env:PYTHONPATH = Join-Path $ml "src"
    $pipelineArgs = @("-m", "smartfreeze_ml.pipeline", "--install", "--threshold-method", $ThresholdMethod, "--target-fpr", $TargetFpr)
    if ($SyntheticDemo) {
        $pipelineArgs += @("--synthetic-demo", "--reports", "reports\synthetic_demo")
    } else {
        if ($Samples -eq "") { throw "Pass -Samples (or -SyntheticDemo)." }
        $pipelineArgs += @("--samples", (Join-Path $root $Samples))
        if ($Events -ne "") { $pipelineArgs += @("--events", (Join-Path $root $Events)) }
    }
    & $py @pipelineArgs
    if ($LASTEXITCODE -ne 0) { throw "training failed" }
    Write-Host "`nModel installed into android-app. Rebuild the app and run the unit tests (ModelParityTest)."
} finally {
    Pop-Location
}
