@echo off
setlocal
cd /d "%~dp0"

echo [UltraMonitor] Building portable distribution...
call gradlew.bat clean fatJar --console=plain
if errorlevel 1 (
    echo.
    echo Build failed.
    pause
    exit /b 1
)

if not exist dist mkdir dist
copy /y build\libs\UltraMonitor.jar dist\ >nul
copy /y launch.bat dist\ >nul

echo.
echo Done. Portable build is in:
echo   dist\UltraMonitor.jar
echo   dist\launch.bat
echo Run dist\launch.bat to start UltraMonitor.
pause
