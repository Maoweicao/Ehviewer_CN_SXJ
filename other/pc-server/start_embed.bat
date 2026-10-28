@echo off
chcp 65001 >nul
title EhViewer Embedding Server

echo ============================================
echo   EhViewer Embedding Server (remote GPU)
echo ============================================
echo.

python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python not found.
    echo Install Python 3.9+ from https://www.python.org/
    pause
    exit /b 1
)

echo Checking dependencies...
python -c "import flask" >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Flask not found. Installing...
    pip install -r "%~dp0requirements_embed.txt"
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
echo   Configuration
echo ============================================
echo   URL:   http://!LAN_IP!:5002/v1
echo.
echo   In EhViewer: Settings ^> Lab ^> Semantic search
echo     Remote API URL   : http://!LAN_IP!:5002/v1
echo     Protocol         : OpenAI compatible
echo.
echo   GPU offload:
echo     CUDA (RTX/T4/P4) : --gpu-layers 99
echo     CPU only         : --no-gpu
echo ============================================
echo.

set /p MODEL_PATH=GGUF model path (blank = proxy mode): 
if "%MODEL_PATH%"=="" (
    set /p PROXY_URL=Upstream OpenAI-compatible URL (e.g. http://127.0.0.1:8000/v1): 
    python "%~dp0embed_server.py" --host 0.0.0.0 --port 5002 --proxy "!PROXY_URL!"
) else (
    python "%~dp0embed_server.py" --host 0.0.0.0 --port 5002 --model "%MODEL_PATH%" --gpu-layers 99
)

pause