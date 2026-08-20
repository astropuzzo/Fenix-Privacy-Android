#!/usr/bin/env python3
import hashlib, json, os, pathlib, sys
apk = pathlib.Path(sys.argv[1])
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
data = {
    "versionCode": int(os.environ["FENIX_PRIVACY_VERSION_CODE"]),
    "versionName": os.environ["FENIX_PRIVACY_VERSION_NAME"],
    "apkUrl": os.environ["FENIX_PRIVACY_APK_URL"],
    "sha256": sha,
    "upstreamRef": os.environ["FENIX_PRIVACY_UPSTREAM_REF"],
    "upstreamRevision": os.environ["FENIX_PRIVACY_UPSTREAM_REVISION"],
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, indent=2) + "\n")
print(sha)
