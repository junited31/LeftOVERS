# LeftOVERS

LeftOVERS is a native Android app with a FastAPI backend.

## Setup

Install Java 17, Android SDK 35 with build-tools 35.0.0, Python, and PowerShell. Then create the backend environment from the repository root:

```powershell
python -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -r backend/requirements.txt
```

## Build

Build and lint the Android app:

```powershell
cd android
./gradlew.bat :app:assembleDebug :app:lintDebug
```

From the repository root, change into the backend directory before running its tests:

```powershell
cd backend
.venv/Scripts/python.exe -m pytest -q tests
```

Scan tracked and untracked files for secret-shaped values:

```powershell
powershell -NoProfile -File scripts/scan_secrets.ps1
```

## Health check

Start the API and call its health endpoint:

```powershell
backend/.venv/Scripts/python.exe -m uvicorn app.main:app --app-dir backend
Invoke-RestMethod http://127.0.0.1:8000/health
```
