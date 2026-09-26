"""Fetch `by` and `buff` for one platform out of the basedpython wheel on PyPI.

    python fetch-bundled-binaries.py <basedpython version> <platform slug> <output dir>

The CI half of the plugin's own download (`ByBinaryDownloadPlan`): the same distribution, the same
wheel-tag rules per platform slug, the same `.data/scripts/` entries, and the same refusal of a file
that is yanked or carries no SHA-256. Keep `WHEEL_TAGS` in step with `ByBinaryDownloadPlan.Platform`
so that a bundle holds exactly the binaries the IDE would download on that machine.

Fails, rather than picking something close, when the release has no wheel for the platform or more
than one: a release that silently lost a target is what this exists to catch.
"""

import hashlib
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

DISTRIBUTION = "basedpython"

# slug -> (whether a single platform tag runs there, executable suffix).
# Mirrors ByBinaryDownloadPlan.Platform, glibc-only on Linux for the reason given there.
WHEEL_TAGS = {
    "mac-arm64": (lambda t: t.startswith("macosx_") and t.endswith("_arm64"), ""),
    "mac-x64": (lambda t: t.startswith("macosx_") and t.endswith("_x86_64"), ""),
    "linux-x64": (lambda t: t.startswith("manylinux") and t.endswith("_x86_64"), ""),
    "linux-arm64": (lambda t: t.startswith("manylinux") and t.endswith("_aarch64"), ""),
    "windows-x64": (lambda t: t == "win_amd64", ".exe"),
    "windows-arm64": (lambda t: t == "win_arm64", ".exe"),
}


def runs_wheel(filename: str, tag_runs) -> bool:
    if not filename.endswith(".whl"):
        return False
    tags = filename.removesuffix(".whl").rsplit("-", 1)[-1]
    return any(tag_runs(tag) for tag in tags.split("."))


def main(version: str, slug: str, out: Path) -> None:
    tag_runs, exe = WHEEL_TAGS[slug]
    url = f"https://pypi.org/pypi/{DISTRIBUTION}/{version}/json"
    with urllib.request.urlopen(url) as response:
        files = json.load(response)["urls"]

    wheels = [
        f
        for f in files
        if not f.get("yanked") and f.get("digests", {}).get("sha256") and runs_wheel(f["filename"], tag_runs)
    ]
    if len(wheels) != 1:
        available = "\n  ".join(sorted(f["filename"] for f in files))
        sys.exit(
            f"{DISTRIBUTION} {version} has {len(wheels)} wheels for {slug}, expected exactly one. "
            f"Files in the release:\n  {available}"
        )
    wheel = wheels[0]
    print(f"{slug}: {wheel['filename']}")

    out.mkdir(parents=True, exist_ok=True)
    archive = out / wheel["filename"]
    with urllib.request.urlopen(wheel["url"]) as response:
        archive.write_bytes(response.read())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != wheel["digests"]["sha256"].lower():
        sys.exit(f"{wheel['filename']}: SHA-256 {digest}, PyPI says {wheel['digests']['sha256']}")

    with zipfile.ZipFile(archive) as zf:
        for binary in ("by", "buff"):
            name = binary + exe
            entries = [e for e in zf.namelist() if e.endswith(f".data/scripts/{name}")]
            if len(entries) != 1:
                sys.exit(f"{wheel['filename']}: expected one .data/scripts/{name}, found {entries}")
            target = out / name
            target.write_bytes(zf.read(entries[0]))
            target.chmod(0o755)
    archive.unlink()


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[2] not in WHEEL_TAGS:
        sys.exit(f"usage: {sys.argv[0]} <version> <{'|'.join(WHEEL_TAGS)}> <output dir>")
    main(sys.argv[1], sys.argv[2], Path(sys.argv[3]))
