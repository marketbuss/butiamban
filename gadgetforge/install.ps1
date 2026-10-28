# gadgetforge/install.ps1 — one-liner for Windows PCs
#
# usage from the repo root:
#   .\gadgetforge\install.ps1 animalcompany
#
# requires: git, adb (platform-tools in PATH), curl. optional: gh, frida
param(
    [string]$Name = "animalcompany",
    [string]$Pkg = "com.DeadlyCrocCorp.AnimalCompany",
    [string]$Branch = "claude/ollie-bench-deployment-u4k02l",
    [string]$RemoteOrigin = "origin"
)
$ErrorActionPreference = "Stop"

function Say($msg)  { Write-Host "[gf] $msg" -ForegroundColor Cyan }
function Die($msg)  { Write-Host "[err] $msg" -ForegroundColor Red; exit 1 }

Say "0. check tools"
foreach ($t in @("adb","git","curl")) {
    if (-not (Get-Command $t -ErrorAction SilentlyContinue)) { Die "$t missing" }
}

Say "1. check device"
adb start-server | Out-Null
$dev = (adb devices | Select-String '\sdevice$' | Select-Object -First 1) -replace '\s.*',''
if (-not $dev) { Die "no Quest connected or usb debugging not allowed" }
Say "  device: $dev"

Say "2. pull Store apk ($Pkg)"
$apkPath = (adb shell pm path $Pkg 2>$null | ForEach-Object { $_ -replace '^package:','' } | Select-Object -First 1).Trim()
if (-not $apkPath) { Die "$Pkg not installed on the Quest. install from Store first." }
Say "  path on device: $apkPath"
adb pull $apkPath "gadgetforge/input/$Name.apk" | Out-Null

Say "3. push to CI"
git add "gadgetforge/input/$Name.apk" "gadgetforge/scripts/mod.js" 2>$null
git commit -m "gadgetforge: patch $Name" | Out-Null
git push $RemoteOrigin $Branch

Say "4. wait for CI build"
$remoteUrl = (git config --get "remote.$RemoteOrigin.url").Trim()
$repo = $remoteUrl -replace '^(git@|https://)github.com[:/]','' -replace '\.git$',''
while ($true) {
    $r = (curl -s "https://api.github.com/repos/$repo/actions/runs?branch=$Branch&per_page=1" | ConvertFrom-Json).workflow_runs[0]
    Say "  ci: $($r.status) $($r.conclusion)"
    if ($r.status -eq "completed") {
        if ($r.conclusion -eq "success") { break } else { Die "ci failed: $($r.conclusion)" }
    }
    Start-Sleep -Seconds 20
}

Say "5. fetch patched apk"
git fetch $RemoteOrigin apk-builds
git show "$RemoteOrigin/apk-builds:build/gadgetforge/$Name.gadget.apk" > "$Name.gadget.apk"

Say "6. install on Quest"
adb uninstall $Pkg 2>$null | Out-Null
adb install -r "$Name.gadget.apk"

Say "7. launch"
adb shell monkey -p $Pkg -c android.intent.category.LAUNCHER 1 | Out-Null

if (Get-Command frida -ErrorAction SilentlyContinue) {
    Say "8. forward frida + attach (ctrl+c when done)"
    adb forward tcp:27042 tcp:27042
    Start-Sleep -Seconds 2
    frida -U -n Gadget -l gadgetforge/scripts/mod.js
} else {
    Say "8. frida CLI not installed — pip install frida-tools, then:"
    Say "   adb forward tcp:27042 tcp:27042; frida -U -n Gadget -l gadgetforge/scripts/mod.js"
}

Say "done. edit /sdcard/Download/animalcompany.cfg on the Quest to retune live."
