#!/usr/bin/env bash
# CI only: app/google-services.json is git-ignored (it comes from the Firebase console), but the
# google-services Gradle plugin needs one to build the app. This writes a placeholder with
# obviously fake values: no real project, keys or client ids. Never commit its output.
set -euo pipefail

target="app/google-services.json"
if [[ -f "$target" ]]; then
  echo "$target already exists, leaving it alone."
  exit 0
fi

cat > "$target" <<'JSON'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "ci-placeholder",
    "storage_bucket": "ci-placeholder.invalid"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": { "package_name": "com.jadbarakat.blooddonor" }
      },
      "oauth_client": [
        { "client_id": "000000000000-ciplaceholder.apps.googleusercontent.com", "client_type": 3 }
      ],
      "api_key": [ { "current_key": "ci-placeholder-not-a-real-key" } ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": [
            { "client_id": "000000000000-ciplaceholder.apps.googleusercontent.com", "client_type": 3 }
          ]
        }
      }
    }
  ],
  "configuration_version": "1"
}
JSON
echo "Wrote placeholder $target (fake values, CI only)."
