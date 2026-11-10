# Native Windows Vivado 2026.1 bridge invoked by the WSL fail-closed driver.
# This PowerShell entry NEVER authenticates to AMD, installs software,
# registers GitHub runners, or claims DDR calibration/FPGA Debian login.
param(
    [Parameter(Mandatory = $true)][ValidateSet('Logical', 'Physical')][string]$Phase,
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$Reference,
    [Parameter(Mandatory = $true)][string]$RtlDir,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [string]$VivadoBin = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

try {
    foreach ($path in @($Repository, $Reference, $RtlDir)) {
        if (-not (Test-Path -LiteralPath $path -PathType Container)) {
            throw "missing shared Windows directory: $path"
        }
    }
    if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
        throw 'Git for Windows is required on PATH (Vivado Tcl checks pinned upstream Git blob)'
    }
    if ($VivadoBin -eq '') {
        $command = Get-Command vivado.bat -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            throw 'Vivado 2026.1 not on Windows PATH; supply the full vivado.bat path'
        }
        $VivadoBin = $command.Source
    }
    if (-not (Test-Path -LiteralPath $VivadoBin -PathType Leaf)) {
        throw "Vivado Windows launcher not found: $VivadoBin"
    }
    # Windows .bat launchers cannot use a WSL UNC working directory.
    Set-Location -LiteralPath $Repository
    $env:NEXYS_REFERENCE_DIR = $Reference
    $env:NEXYS_PRODUCT_RTL_DIR = $RtlDir
    $env:NEXYS_OUTPUT_DIR = $OutputDir

    if ($Phase -eq 'Logical') {
        if (Test-Path -LiteralPath $OutputDir) {
            throw 'logical Vivado output must not exist before project creation'
        }
        $script = Join-Path $Repository 'tools\fpga\nexys_video_product_bd.tcl'
    } else {
        if (-not (Test-Path -LiteralPath (Join-Path $OutputDir 'LOGICAL_BD_ONLY.txt') -PathType Leaf)) {
            throw 'physical implementation requires a validated logical BD'
        }
        $script = Join-Path $Repository 'tools\fpga\nexys_video_product_physical.tcl'
    }
    if (-not (Test-Path -LiteralPath $script -PathType Leaf)) {
        throw "pinned Vivado Tcl source absent: $script"
    }
    Write-Output "NEXYS_WINDOWS_VIVADO_PHASE: $Phase"
    & $VivadoBin -mode batch -nojournal -nolog -source $script
    if ($LASTEXITCODE -ne 0) {
        throw "Vivado $Phase phase failed (exit $LASTEXITCODE)"
    }
} catch {
    [Console]::Error.WriteLine("NEXYS_WINDOWS_VIVADO_FAILED: $($_.Exception.Message)")
    exit 1
}
exit 0
