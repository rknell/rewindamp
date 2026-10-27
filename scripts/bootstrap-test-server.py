#!/usr/bin/env python3
"""Initialize ONLY the disposable integration server. Never print authentication data."""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:18096"
HEADERS = {"Content-Type": "application/json", "Authorization": 'MediaBrowser Client="Jellyamp Test", Device="Local", DeviceId="test", Version="1"'}


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, headers=HEADERS), timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


for attempt in range(60):
    try:
        info = request("/System/Info/Public")
        if not info.get("StartupWizardCompleted"):
            request("/Startup/Configuration")  # Public info becomes available before startup completes.
        break
    except Exception:
        if attempt == 59:
            raise RuntimeError("Disposable Jellyfin did not start") from None
        time.sleep(1)

if info.get("StartupWizardCompleted"):
    raise RuntimeError("Expected a fresh disposable server; refusing to reuse an existing server")
request("/Startup/Configuration", {"UICulture": "en-US", "MetadataCountryCode": "AU", "PreferredMetadataLanguage": "en"})
request("/Startup/User")
request("/Startup/User", {"Name": "jellyamp-test", "Password": "test-only-local-password"})
request("/Startup/RemoteAccess", {"EnableRemoteAccess": True, "EnableAutomaticPortMapping": False})
request("/Startup/Complete", {})
auth = request("/Users/AuthenticateByName", {"Username": "jellyamp-test", "Pw": "test-only-local-password"})
HEADERS["Authorization"] += ', Token="' + auth["AccessToken"] + '"'
request("/Library/VirtualFolders?name=Test%20Music&collectionType=music&refreshLibrary=true",
        {"LibraryOptions": {"PathInfos": [{"Path": "/music"}], "EnableInternetProviders": False}})
for attempt in range(60):
    if request("/Users/" + auth["User"]["Id"] + "/Items?Recursive=true&IncludeItemTypes=Audio")["TotalRecordCount"] == 2:
        break
    if attempt == 59:
        raise RuntimeError("Synthetic music was not indexed")
    time.sleep(1)
request("/Sessions/Logout", {})
print("Disposable Jellyfin ready with two synthetic tracks.")
