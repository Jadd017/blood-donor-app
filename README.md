# BloodDonor

An Android app (Jetpack Compose) for blood donors, with a Ktor backend in `server/`.

## Setup

### Firebase config (required)

`app/google-services.json` is **not** committed to this repository. To build the app:

1. Open the [Firebase console](https://console.firebase.google.com/) and select the project.
2. Go to **Project settings → General → Your apps** and select the Android app
   `com.jadbarakat.blooddonor` (or add it if it doesn't exist).
3. Download `google-services.json` and place it in the `app/` directory.
4. Make sure **Authentication → Sign-in method → Email/Password** is enabled.

Then build with `./gradlew :app:assembleDebug`.
