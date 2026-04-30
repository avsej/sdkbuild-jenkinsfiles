#!/usr/bin/env python3
"""Install cbdinocluster at a pinned version with SHA-256 verification.

Cross-platform, stdlib-only replacement for the sh/PowerShell installer that
previously lived inline in the Jenkins scripted pipeline. Detects OS/arch,
reads the pinned version and per-asset SHA-256 map from --config (JSON),
downloads from GitHub Releases, verifies SHA-256, atomically installs to
~/bin (Linux/macOS) or %USERPROFILE%/bin (Windows). Idempotent: if the target
binary already reports the requested version, exits 0 without re-downloading.

Run by ensureCbdinocluster() in cxx-scripted-build-pipeline.groovy. The config
file shape is::

    {
      "version": "v0.0.114",
      "sha256": {
        "cbdinocluster-<os>-<arch>[.exe]": "<64-char hex>",
        ...
      }
    }
"""
import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.request import urlopen


def detect_asset() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if system == "windows":
        os_part, ext = "windows", ".exe"
    elif system == "darwin":
        os_part, ext = "darwin", ""
    else:
        os_part, ext = "linux", ""
    if machine in ("x86_64", "amd64"):
        arch = "amd64"
    elif machine in ("aarch64", "arm64"):
        arch = "arm64"
    else:
        sys.exit(f"install_cbdinocluster: unsupported architecture {machine!r}")
    return f"cbdinocluster-{os_part}-{arch}{ext}"


def install_paths() -> tuple[Path, Path]:
    if platform.system().lower() == "windows":
        bin_dir = Path(os.environ["USERPROFILE"]) / "bin"
        return bin_dir, bin_dir / "cbdinocluster.exe"
    bin_dir = Path.home() / "bin"
    return bin_dir, bin_dir / "cbdinocluster"


def current_version(target: Path) -> str:
    if not target.exists():
        return ""
    try:
        result = subprocess.run(
            [str(target), "version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except Exception:
        return ""
    lines = (result.stdout or "").splitlines()
    return lines[0].strip() if lines else ""


def download_verify_install(url: str, expected_sha: str, dest: Path) -> None:
    # mkstemp in dest.parent so the final rename is intra-device; shutil.move
    # handles the cross-device fallback (copy + remove) if anything exotic is
    # going on with $HOME or %USERPROFILE%.
    fd, tmp_name = tempfile.mkstemp(prefix=".cbdino-", dir=str(dest.parent))
    tmp_path = Path(tmp_name)
    try:
        h = hashlib.sha256()
        with os.fdopen(fd, "wb") as out, urlopen(url, timeout=180) as resp:
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                h.update(chunk)
                out.write(chunk)
        actual = h.hexdigest()
        if actual != expected_sha:
            print(f"install_cbdinocluster: sha256 mismatch for {url}", file=sys.stderr)
            print(f"  expected {expected_sha}", file=sys.stderr)
            print(f"  got      {actual}", file=sys.stderr)
            sys.exit(1)
        if platform.system().lower() != "windows":
            mode = tmp_path.stat().st_mode
            tmp_path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        shutil.move(str(tmp_path), str(dest))
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="Install pinned cbdinocluster.")
    parser.add_argument(
        "--config",
        required=True,
        help="JSON file with 'version' and 'sha256' (per-asset) fields",
    )
    parser.add_argument(
        "--release-base-url",
        default="https://github.com/couchbaselabs/cbdinocluster/releases/download",
    )
    args = parser.parse_args()

    with open(args.config) as f:
        config = json.load(f)
    version = config["version"]
    sha_map = config["sha256"]

    asset = detect_asset()
    expected = sha_map.get(asset)
    if not expected:
        sys.exit(
            f"install_cbdinocluster: no sha256 known for asset {asset!r} in {args.config}"
        )

    bin_dir, target = install_paths()
    bin_dir.mkdir(parents=True, exist_ok=True)

    current = current_version(target)
    if current == version:
        print(f"cbdinocluster {version} already installed at {target}")
    else:
        print(f"Installing cbdinocluster {version} (was: {current!r})")
        url = f"{args.release_base_url}/{version}/{asset}"
        download_verify_install(url, expected, target)

    subprocess.run([str(target), "version"], check=True)


if __name__ == "__main__":
    main()
