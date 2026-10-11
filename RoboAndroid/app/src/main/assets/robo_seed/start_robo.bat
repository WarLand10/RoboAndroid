@echo off
title Robo AI
cd /d E:\Robo

if "%GEMINI_API_KEY%"=="" (
    echo Gemini API key is not set.
    echo Set a NEW key as the Windows user environment variable:
    echo GEMINI_API_KEY
    echo Then close and reopen this terminal or sign out and sign in again.
    pause
    exit /b 1
)

E:\Python312\python.exe E:\Robo\robo_app.py
if errorlevel 1 (
    echo.
    echo ==============================
    echo ROBO ERROR
    echo ==============================
    pause
)
