"""Fetch the toolchain a bundle carries, for one platform, out of the wheels on PyPI.

    python fetch-bundled-binaries.py <platform slug> <output dir> \\
        --basedpython <version> --basedpython-debugger <version>

`by` and `buff` come from the `basedpython` wheel; `bpd` and the agents it loads into a debuggee from
the `basedpython-debugger` wheel. The output directory becomes the plugin's `bin/`: the executables
at its top, and `bpd`'s `agents/<tag>/` beside `bpd`, which is where `bpd` looks for them.

The CI half of the plugin's own download (`ByBinaryDownloadPlan`): the same wheel-tag rules per
platform slug, the same `.data/scripts/` entries, and the same refusal of a file that is yanked or
carries no SHA-256. Keep `WHEEL_TAGS` in step with `ByBinaryDownloadPlan.Platform` so that a bundle
holds exactly the binaries the IDE would download on that machine.

Fails, rather than picking something close, when a release has no wheel for the platform or more
than one: a release that silently lost a target is what this exists to catch. The one exception is
declared in `NOT_PUBLISHED`, with the reason.
"""

import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

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

# distribution -> (executables under `.data/scripts/`, directories under `.data/data/` to copy)
DISTRIBUTIONS = {
    "basedpython": (["by", "buff"], []),
    "basedpython-debugger": (["bpd"], ["agents"]),
}

# (distribution, slug) -> why that distribution publishes no wheel for that platform. A bundle for
# the platform goes without it, and the IDE looks for it where it looks for an installed one.
NOT_PUBLISHED = {
    ("basedpython-debugger", "windows-arm64"): (
        "basedpython-debugger has never built or tested windows-arm64, and publishes no wheel for "
        "it on purpose (its docs/development/releasing.md)"
    ),
}


def runs_wheel(filename: str, tag_runs) -> bool:
    if not filename.endswith(".whl"):
        return False
    tags = filename.removesuffix(".whl").rsplit("-", 1)[-1]
    return any(tag_runs(tag) for tag in tags.split("."))


def fetch(distribution: str, version: str, slug: str, out: Path) -> None:
    if (distribution, slug) in NOT_PUBLISHED:
        print(f"{slug}: no {distribution}: {NOT_PUBLISHED[distribution, slug]}")
        return

    tag_runs, exe = WHEEL_TAGS[slug]
    scripts, data_dirs = DISTRIBUTIONS[distribution]
    with urllib.request.urlopen(f"https://pypi.org/pypi/{distribution}/{version}/json") as response:
        files = json.load(response)["urls"]

    wheels = [
        f
        for f in files
        if not f.get("yanked") and f.get("digests", {}).get("sha256") and runs_wheel(f["filename"], tag_runs)
    ]
    if len(wheels) != 1:
        available = "\n  ".join(sorted(f["filename"] for f in files))
        sys.exit(
            f"{distribution} {version} has {len(wheels)} wheels for {slug}, expected exactly one. "
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
        names = zf.namelist()
        for script in scripts:
            name = script + exe
            entries = [e for e in names if e.endswith(f".data/scripts/{name}")]
            if len(entries) != 1:
                sys.exit(f"{wheel['filename']}: expected one .data/scripts/{name}, found {entries}")
            target = out / name
            target.write_bytes(zf.read(entries[0]))
            target.chmod(0o755)
        for directory in data_dirs:
            marker = f".data/data/{directory}/"
            entries = [e for e in names if marker in e and not e.endswith("/")]
            if not entries:
                sys.exit(f"{wheel['filename']}: nothing under .data/data/{directory}/")
            for entry in entries:
                target = out / directory / entry.split(marker, 1)[1]
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(entry) as source, open(target, "wb") as sink:
                    shutil.copyfileobj(source, sink)
    archive.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("slug", choices=sorted(WHEEL_TAGS))
    parser.add_argument("out", type=Path)
    for distribution in DISTRIBUTIONS:
        parser.add_argument(f"--{distribution}", required=True, metavar="VERSION")
    args = parser.parse_args()
    for distribution in DISTRIBUTIONS:
        fetch(distribution, getattr(args, distribution.replace("-", "_")), args.slug, args.out)


if __name__ == "__main__":
    main()
