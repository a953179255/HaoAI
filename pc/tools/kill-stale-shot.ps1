# Reap leftover harness processes from a previous ui-shot run.
# A headless Edge whose profile starts with haoai-shot- keeps port 9339 alive; the next
# run's probe then succeeds against THAT browser and screenshots come from its old tabs.
# A haoai-pc server still listening in the harness port range (8737-8790) is a zombie too.
# It prints one line per category it actually swept, so "nothing was reaped" stays silent
# and "the sweeper itself broke" shows up as missing lines rather than as a mystery failure.
# ASCII only: this file must survive any code page.
function Sweep($label, $filter, $test) {
  $ids = @(Get-CimInstance Win32_Process -Filter "Name='$filter'" |
    Where-Object { & $test $_ } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $_.ProcessId })
  if ($ids.Count) { Write-Output ("swept {0}: {1}" -f $label, $ids.Count) }
}

Sweep 'headless-edge' 'msedge.exe' { param($p) $p.CommandLine -like '*haoai-shot*' }

Sweep 'harness-server' 'java.exe' {
  param($p)
  ($p.CommandLine -like '*haoai-pc*' -or $p.CommandLine -like '*haoai-pc.bat*') -and
  $p.CommandLine -match '--port 8[78][0-9][0-9]'
}

# The preview panel drives a browser under haoai-browser-profile-<port>.
# Edge detaches from the launcher, so a killed run can leave that window behind for good.
Sweep 'preview-edge' 'msedge.exe' { param($p) $p.CommandLine -like '*haoai-browser-profile*' }

# The mock gateway is the third process in every run, and ui-shot.sh only reaps it from an
# EXIT trap -- which never fires when the shell itself is SIGKILLed (a stopped audit). The
# orphans keep 8791-8890 occupied, free_port then walks past the whole range, and a later
# run dies with "no free port" while ten python processes sit there doing nothing.
# Match on the script path, never on "python": other agents run python too.
Sweep 'mock-gateway' 'python.exe' { param($p) $p.CommandLine -like '*mock-openai.py*' }

# Every run makes a fresh state root (haoai-uishot-HHMMSS) and deletes it in its EXIT trap --
# but on Windows the just-killed java server still holds that directory as its current one,
# so `rm -rf` fails and the trap deliberately ignores it (a green run must not turn red over
# cleanup). The result is a slow leak: 84 of them were lying around after one audit.
# Reap the ones that are clearly abandoned: older than an hour (a playbook takes < 3 minutes,
# so nothing live is that old) and only under this exact prefix.
# They go to the Recycle Bin, not the void -- these are throwaway harness dirs, but the rule
# on this machine is that anything I made gets un-doable.
$stale = @(Get-ChildItem -LiteralPath $env:TEMP -Directory -Filter 'haoai-uishot-*' -ErrorAction SilentlyContinue |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddMinutes(-60) })
if ($stale.Count) {
  Add-Type -AssemblyName Microsoft.VisualBasic
  $gone = 0
  foreach ($d in $stale) {
    try {
      [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory(
        $d.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin')
      $gone++
    } catch { }        # held by a live process: leave it for the next run, never fail the audit
  }
  Write-Output ("recycled state-roots: {0}/{1}" -f $gone, $stale.Count)
}
