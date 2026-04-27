# GhostLink FCM Setup

## 1. Android app config (`google-services.json`)
1. Open Firebase Console and create/select a project.
2. Add Android app with package name `com.rezerv.app`.
3. Download `google-services.json`.
4. Replace file in project:
   - `C:\Projekt\APK\app\google-services.json`
5. Rebuild APK.

## 2. Backend service account
1. In Firebase Console -> Project settings -> Service accounts.
2. Generate new private key (JSON).
3. Put JSON on VPS:
   - `/opt/ghostlink-backend/fcm-service-account.json`
4. Restrict file permissions:
   - `chmod 600 /opt/ghostlink-backend/fcm-service-account.json`
   - `chown ghostlink:ghostlink /opt/ghostlink-backend/fcm-service-account.json`
5. Restart backend:
   - `systemctl restart ghostlink-backend`

## 3. Runtime behavior
- App registers FCM token after login.
- Backend stores token per user and sends push immediately on new message.
- Invalid tokens are removed automatically.
- On logout app unregisters token from backend.

## 4. Quick verification
1. Install latest APK on two devices.
2. Login as two different users.
3. Close app on device B (send to background).
4. Send message from device A to B.
5. Device B should receive instant push and open chat on tap.
