@echo off
chcp 65001 >nul
title EhViewer OCR Server

echo ============================================
echo   EhViewer PaddleOCR LAN Server
echo ============================================
echo.

:: Check if Python is available
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python not found.
    echo Please install Python 3.9+ from https://www.python.org/
    echo Make sure "Add Python to PATH" is checked during installation.
    echo.
    pause
    exit /b 1
)

:: Show Python version
for /f "tokens=*" %%i in ('python --version 2^>^&1') do echo Python: %%i
echo.

:: Check dependencies
echo Checking dependencies...
python -c "import flask" >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo [WARN] Flask not found. Installing dependencies...
    pip install -r "%~dp0requirements_ocr.txt"
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to install dependencies.
        pause
        exit /b 1
    )
)

:: Get local IP
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    set LAN_IP=%%a
    set LAN_IP=!LAN_IP: =!
    goto :found_ip
)
:found_ip

echo.
echo ============================================
echo   Server Configuration
echo ============================================
echo.
echo   URL:  http://%LAN_IP%:5001
echo   Health: http://%LAN_IP%:5001/health
echo.
echo   In EhViewer App settings, set:
echo   LAN OCR Server URL: http://%LAN_IP%:5001
echo.
echo ============================================
echo.

:: Start server
echo Starting OCR server...
echo (Press Ctrl+C to stop)
echo.
python "%~dp0ocr_server.py" --host 0.0.0.0 --port 5001

pause
