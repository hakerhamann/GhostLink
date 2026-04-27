# Local Backend (Reserv)

## Start (foreground)
```powershell
cd C:\Projekt\APK\backend
.\start_backend.ps1 -Port 8080
```

## Start (background)
```powershell
cd C:\Projekt\APK\backend
.\start_backend_background.ps1 -Port 8080
```

## Health check
```powershell
Invoke-WebRequest http://127.0.0.1:8080/health | Select-Object -ExpandProperty Content
```

## Useful endpoints
- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/push/register`
- `POST /api/push/unregister`
- `GET /api/chats`
- `POST /api/chats/direct`
- `GET /api/chats/{chatId}/messages`
- `POST /api/chats/{chatId}/messages`
- `POST /api/chats/{chatId}/read`

## FCM setup
1. Create Firebase service account key JSON in your Firebase project.
2. Put file at `backend/fcm-service-account.json` (default path), or set env:
   - `FCM_SERVICE_ACCOUNT_PATH=/custom/path/service-account.json`
   - or `FCM_SERVICE_ACCOUNT_JSON=<full-json>`
3. Restart backend.

Without this file backend keeps working, but push delivery is disabled.
