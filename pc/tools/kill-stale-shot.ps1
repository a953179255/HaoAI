# Reap leftover harness processes from a previous ui-shot run.
# A headless Edge whose profile starts with haoai-shot- keeps port 9339 alive; the next
# run's probe then succeeds against THAT browser and screenshots come from its old tabs.
# A haoai-pc server still listening in the harness port range (8737-8790) is a zombie too.
# ASCII only: this file must survive any code page.
Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" |
  Where-Object { $_.CommandLine -like '*haoai-shot*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object {
    ($_.CommandLine -like '*haoai-pc*' -or $_.CommandLine -like '*haoai-pc.bat*') -and
    $_.CommandLine -match '--port 8[78][0-9][0-9]'
  } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# The preview panel drives a browser under haoai-browser-profile-<port>.
# Edge detaches from the launcher, so a killed run can leave that window behind for good.
Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" |
  Where-Object { $_.CommandLine -like '*haoai-browser-profile*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
