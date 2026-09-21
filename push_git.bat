@echo off
cd /d "%~dp0"

git status

git add .

git commit -m "update"

git push origin main

if errorlevel 1 (
    echo.
    echo Git push failed.
    echo Try:
    echo   git pull --rebase origin main
    echo   git push origin main
    pause
    exit /b 1
)

echo.
echo Upload complete.
pause
