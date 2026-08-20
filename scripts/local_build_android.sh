#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_root=""
output_root=""
offline=false
requested_ref=""

# Firefox 154 can deadlock on Windows when asynchronous mach telemetry
# initialization fails before signalling its completion event.
export DISABLE_TELEMETRY=1
export MACH_MAIN_PID=0

usage() {
  cat <<'EOF'
Usage: scripts/local_build_android.sh [options]

Options:
  --build-root PATH  Dedicated source/cache directory.
  --output-dir PATH   Directory for installable local-development APKs.
  --offline          Rebuild from the prepared source and caches without Gradle network access.
  --ref TAG          Build a specific FIREFOX_*_RELEASE tag instead of latest stable.
EOF
}

while (($#)); do
  case "$1" in
    --build-root)
      build_root="${2:?--build-root requires a path}"
      shift 2
      ;;
    --offline)
      offline=true
      shift
      ;;
    --output-dir)
      output_root="${2:?--output-dir requires a path}"
      shift 2
      ;;
    --ref)
      requested_ref="${2:?--ref requires a tag}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*) host=windows ;;
  Darwin*) host=macos ;;
  Linux*) host=linux ;;
  *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

if [[ -z "$build_root" ]]; then
  if [[ "$host" == windows && -d /f ]]; then
    build_root=/f/Fenix-Privacy-Android-Local
  else
    build_root="$repo_root/work/local-android"
  fi
fi

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/outputs"
fi

mkdir -p "$build_root"
build_root="$(cd "$build_root" && pwd)"
mkdir -p "$output_root"
output_root="$(cd "$output_root" && pwd)"

if [[ "$build_root" == / || "$build_root" == "$HOME" || "$build_root" == "$repo_root" ]]; then
  echo "Refusing unsafe build root: $build_root" >&2
  exit 1
fi

marker="$build_root/.fenix-privacy-local-build"
upstream="$build_root/upstream"
prepared_ref_file="$build_root/PREPARED_REF"
prepared_revision_file="$build_root/PREPARED_REVISION"

if [[ ! -e "$marker" ]]; then
  if [[ -e "$upstream" ]]; then
    echo "Refusing unmarked existing upstream directory: $upstream" >&2
    exit 1
  fi
  printf 'Fenix Privacy dedicated local Android build root\n' > "$marker"
fi

if [[ "$host" == windows ]]; then
  export MOZBUILD_STATE_PATH="$(cygpath -w "$build_root/.mozbuild")"
  export GRADLE_USER_HOME="$(cygpath -w "$build_root/.gradle")"
else
  export MOZBUILD_STATE_PATH="$build_root/.mozbuild"
  export GRADLE_USER_HOME="$build_root/.gradle"
fi

mkdir -p "$build_root/.mozbuild" "$build_root/.gradle"

if [[ "$offline" == true ]]; then
  [[ -f "$prepared_ref_file" && -d "$upstream/.git" ]] || {
    echo "No prepared local build exists. Run once without --offline first." >&2
    exit 1
  }
  selected_ref="$(tr -d '[:space:]' < "$prepared_ref_file")"
  if [[ -n "$requested_ref" && "$requested_ref" != "$selected_ref" ]]; then
    echo "Offline source is $selected_ref, not requested $requested_ref." >&2
    exit 1
  fi
  echo "Offline rebuild of $selected_ref"
else
  if [[ -n "$requested_ref" ]]; then
    selected_ref="$requested_ref"
  else
    read -r selected_ref _ < <(
      git ls-remote --tags https://github.com/mozilla-firefox/firefox.git 'refs/tags/FIREFOX_*_RELEASE' |
        python3 "$repo_root/scripts/find_latest_stable_ref.py" --verify-android-release
    )
  fi

  if [[ ! "$selected_ref" =~ ^FIREFOX_[0-9]+_[0-9]+(_[0-9]+)?_RELEASE$ ]]; then
    echo "Invalid Firefox release tag: $selected_ref" >&2
    exit 1
  fi

  if [[ ! -d "$upstream/.git" ]]; then
    git init "$upstream"
    git -C "$upstream" remote add origin https://github.com/mozilla-firefox/firefox.git
  fi

  remote="$(git -C "$upstream" remote get-url origin)"
  [[ "$remote" == https://github.com/mozilla-firefox/firefox.git ]] || {
    echo "Unexpected upstream remote: $remote" >&2
    exit 1
  }

  echo "Fetching $selected_ref"
  git -C "$upstream" fetch --depth=1 origin \
    "refs/tags/$selected_ref:refs/tags/$selected_ref" --force
  git -C "$upstream" checkout --detach "$selected_ref"
  git -C "$upstream" reset --hard "$selected_ref"
  # Remove files introduced by an older privacy overlay, but retain ignored
  # objdirs and dependency caches so repeat local builds stay incremental.
  git -C "$upstream" clean -fd

  printf '%s\n' "$selected_ref" > "$prepared_ref_file"
  git -C "$upstream" rev-parse HEAD > "$prepared_revision_file"
fi

if [[ "$offline" == false ]]; then
  python3 "$repo_root/scripts/apply_fenix_privacy.py" "$upstream"

  case "$host" in
    windows) bootstrap_file="$upstream/python/mozboot/mozboot/mozillabuild.py" ;;
    macos) bootstrap_file="$upstream/python/mozboot/mozboot/osx.py" ;;
    linux) bootstrap_file="$upstream/python/mozboot/mozboot/linux_common.py" ;;
  esac

  python3 - "$bootstrap_file" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
avd_manifest = "            avd_manifest_path=android.AVD_MANIFEST_X86_64,\n"
avd_toolchain = "        self.install_toolchain_artifact(android.X86_64_ANDROID_AVD)\n"

if text.count(avd_manifest) != 1 or text.count(avd_toolchain) != 1:
    raise SystemExit(f"Mozilla Android bootstrap anchors changed in {path}")

text = text.replace(avd_manifest, "            avd_manifest_path=None,\n", 1)
text = text.replace(
    avd_toolchain,
    "        # Local APK build: AVD/system-image toolchain intentionally omitted.\n",
    1,
)
path.write_text(text, encoding="utf-8")
PY

  python3 - "$upstream/python/mach/mach/main.py" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = """            def _finish_telemetry_init(future):
                from .telemetry import report_invocation_metrics

                context.telemetry = future.result()
                report_invocation_metrics(context.telemetry, handler.name)
                context._telemetry_init_done.set()
"""
new = """            def _finish_telemetry_init(future):
                try:
                    from .telemetry import report_invocation_metrics

                    context.telemetry = future.result()
                    report_invocation_metrics(context.telemetry, handler.name)
                finally:
                    context._telemetry_init_done.set()
"""

if text.count(old) != 1:
    raise SystemExit(f"Mozilla mach telemetry callback anchor changed in {path}")

path.write_text(text.replace(old, new, 1), encoding="utf-8")
PY
fi

cd "$upstream"

if [[ "$offline" == false ]]; then
  unset MOZCONFIG
  ./mach --no-interactive bootstrap \
    --application-choice="GeckoView/Firefox for Android Artifact Mode"
elif [[ ! -f mozconfig ]]; then
  echo "Offline build has no prepared mozconfig." >&2
  exit 1
fi

export MOZCONFIG=mozconfig
./mach build

gradle_flags=(--no-parallel --max-workers=4)
if [[ "$offline" == true ]]; then
  gradle_flags+=(--offline)
fi

# Upstream deliberately uses the standard Android debug key for local release
# builds. This produces an installable test APK without pretending it is a
# production-signed release.
./mach gradle "${gradle_flags[@]}" fenix:assembleRelease

mapfile -t apks < <(
  find . -type f -path '*/fenix/app/outputs/apk/release/*.apk' \
    ! -name '*-unsigned.apk' -print
)
((${#apks[@]})) || {
  echo "No release APK was produced." >&2
  exit 1
}

timestamp="$(date -u +%Y%m%d%H%M%S)"
for apk in "${apks[@]}"; do
  name="$(basename "$apk")"
  destination="$output_root/${selected_ref}-${timestamp}-${name}"
  cp "$apk" "$destination"
  echo "APK: $destination"
done
