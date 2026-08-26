#!/usr/bin/env python3
import hashlib, json, os, pathlib, sys
apk = pathlib.Path(sys.argv[1])
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
repo_root = pathlib.Path(__file__).resolve().parents[1]
cert_sha256 = (repo_root / "SIGNING_CERT_SHA256").read_text().strip().replace(":", "").lower()
release_tag = os.environ.get("RELEASE_TAG", "production-downloads")
data = {
    "versionCode": int(os.environ["FENIX_PRIVACY_VERSION_CODE"]),
    "versionName": os.environ["FENIX_PRIVACY_VERSION_NAME"],
    "apkUrl": os.environ["FENIX_PRIVACY_APK_URL"],
    "sha256": sha,
    "upstreamRef": os.environ["FENIX_PRIVACY_UPSTREAM_REF"],
    "upstreamRevision": os.environ["FENIX_PRIVACY_UPSTREAM_REVISION"],
    "releaseNotesUrl": f"https://github.com/astropuzzo/Fenix-Privacy-Android/releases/tag/{release_tag}",
    "signingCertSha256": cert_sha256,
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, indent=2) + "\n")
print(sha)
