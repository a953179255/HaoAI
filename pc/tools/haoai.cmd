@echo off
rem HaoAI PC 启动器。
rem 存在的唯一理由：先把控制台代码页切到 UTF-8。Windows 中文系统默认 cp936，
rem JVM 就算用 -Dstdout.encoding=UTF-8 输出 UTF-8 字节，控制台也会把它当 936 解，
rem 中文全是乱码（实测 2026-09-26）。
chcp 65001 >nul
setlocal
set HAOAI_HOME=%LOCALAPPDATA%\HaoAI
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8
set SCRIPT_DIR=%~dp0
set DIST=%SCRIPT_DIR%..\build\install\haoai-pc\bin\haoai-pc.bat
if not exist "%DIST%" (
  echo 还没构建。先执行： cd pc ^&^& gradle installDist 1>&2
  exit /b 1
)
"%DIST%" %*
endlocal
