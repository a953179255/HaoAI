@echo off
rem HaoAI PC launcher.  ASCII ONLY on purpose: cmd.exe reads .cmd files with the
rem console code page (cp936 on Chinese Windows), so Chinese comments written as
rem UTF-8 bytes get re-segmented and executed as commands.  Measured 2026-09-26:
rem a UTF-8 comment line in this file produced "'xxx' is not recognized".
rem The Chinese explanation lives in pc/README.md instead.
rem
rem What it does: switch the console to UTF-8 (chcp 65001) and force the JVM to
rem write UTF-8, otherwise every Chinese character in the CLI output is mojibake.
chcp 65001 >nul
setlocal
set HAOAI_HOME=%LOCALAPPDATA%\HaoAI
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8
set SCRIPT_DIR=%~dp0
set DIST=%SCRIPT_DIR%..\build\install\haoai-pc\bin\haoai-pc.bat
if not exist "%DIST%" (
  echo Not built yet.  Run:  cd pc ^&^& gradle installDist 1>&2
  exit /b 1
)
"%DIST%" %*
endlocal
