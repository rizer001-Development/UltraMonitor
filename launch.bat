@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo [UltraMonitor] Java was not found on PATH.
    echo Install Java 21 or newer from https://adoptium.net and try again.
    pause
    exit /b 1
)

if not exist "%~dp0UltraMonitor.jar" (
    echo [UltraMonitor] UltraMonitor.jar not found next to this launcher.
    echo Run build.bat first to create the portable build.
    pause
    exit /b 1
)

start "" javaw -jar "%~dp0UltraMonitor.jar"
exit /b 0
