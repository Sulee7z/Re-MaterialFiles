@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   MaterialFiles (Sora-Editor)  UPLOAD script
echo   After upload, GitHub Actions builds the APK
echo ============================================
echo.

REM ---------- 1. check git ----------
where git >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Git not found. Please install Git for Windows first:
  echo         https://git-scm.com/download/win
  echo         Then double-click this script again.
  echo.
  pause
  exit /b 1
)

REM ---------- 2. init local repo ----------
echo [1/4] Preparing local repository...
if not exist ".git" ( git init >nul )
git add -A
git commit -m "MaterialFiles Sora-Editor + APK signing + one-click build" >nul 2>nul
git rev-parse HEAD >nul 2>nul || git commit --allow-empty -m "init" >nul
git branch -M main >nul 2>nul

REM ---------- 3. prefer GitHub CLI (gh) ----------
where gh >nul 2>nul
if not errorlevel 1 goto USE_GH
goto USE_MANUAL

:USE_GH
echo [2/4] GitHub CLI found, checking login...
gh auth status >nul 2>nul
if errorlevel 1 (
  echo      Not logged in. Starting login (approve in your browser)...
  gh auth login
  if errorlevel 1 (
    echo [WARN] Login not finished, switching to manual mode.
    goto USE_MANUAL
  )
)
echo.
set "REPO="
set /p REPO=New repository name (Enter for default MaterialFiles-Sora-Editor):
if "!REPO!"=="" set "REPO=MaterialFiles-Sora-Editor"
echo.
set "VIS=--private"
set "CHOICE="
set /p CHOICE=Make it public? Type y for public, Enter for private:
if /i "!CHOICE!"=="y" set "VIS=--public"
echo.
echo [3/4] Creating repository and pushing...
gh repo create "!REPO!" !VIS! --source=. --remote=origin --push
if errorlevel 1 (
  echo [WARN] gh create/push failed, switching to manual mode.
  goto USE_MANUAL
)
goto DONE

:USE_MANUAL
echo.
echo [2/4] Manual mode. First create an EMPTY repo on github.com
echo       (do NOT add README / .gitignore / license), then copy its URL.
echo.
set "URL="
set /p URL=Paste repo URL (like https://github.com/USER/REPO.git):
if "!URL!"=="" (
  echo [ERROR] No URL entered. Aborted.
  pause
  exit /b 1
)
git remote remove origin >nul 2>nul
git remote add origin "!URL!"
echo.
echo [3/4] Pushing (a browser may open for GitHub login the first time)...
git push -u origin main
if errorlevel 1 (
  echo [ERROR] Push failed. Common causes: wrong URL, repo not empty,
  echo         or login not completed.
  pause
  exit /b 1
)
goto DONE

:DONE
echo.
echo ============================================
echo   [4/4] Done! Your code is uploaded.
echo   Next: open your GitHub repo, Actions tab.
echo   Wait for "build-debug" to finish (green check, ~5-10 min),
echo   open that run, then download the APK from "Artifacts".
echo ============================================
echo.
pause
