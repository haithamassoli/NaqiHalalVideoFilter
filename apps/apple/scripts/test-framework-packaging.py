#!/usr/bin/env python3
"""Pass DerivedData/SourcePackages/artifacts/ffmpeg-kit-spm and optionally an archived .app."""
import hashlib
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


artifacts = Path(sys.argv[1]).resolve()
frameworks = sorted(artifacts.glob("*/*.xcframework/ios-arm64_arm64e/*.framework"))
assert len(frameworks) == 8, "Expected all eight pinned FFmpeg device frameworks"
originals = {f: digest(f / f.stem) for f in frameworks}
script = Path(__file__).with_name("prepare-frameworks.sh").resolve()
with tempfile.TemporaryDirectory(prefix="naqi-framework-check-") as directory:
    root = Path(directory)
    embedded = root / "Frameworks"
    embedded.mkdir()
    for framework in frameworks:
        shutil.copytree(framework, embedded / framework.name)
        run("codesign", "--force", "--sign", "-", str(embedded / framework.name), stderr=subprocess.DEVNULL)
    before = {f.name: digest(embedded / f.name / f.stem) for f in frameworks}
    env = dict(os.environ, TARGET_BUILD_DIR=str(root), FRAMEWORKS_FOLDER_PATH="Frameworks",
               PLATFORM_NAME="iphonesimulator", CODE_SIGNING_ALLOWED="YES", EXPANDED_CODE_SIGN_IDENTITY="-")
    run("sh", str(script), env=env)
    assert all(digest(embedded / f.name / f.stem) == before[f.name] for f in frameworks)
    env["PLATFORM_NAME"] = "iphoneos"
    run("sh", str(script), env=env, stderr=subprocess.DEVNULL)
    for framework in frameworks:
        binary = embedded / framework.name / framework.stem
        assert run("xcrun", "lipo", "-archs", str(binary)) == "arm64"
        original_uuid = run("xcrun", "dwarfdump", "--uuid", str(framework / framework.stem)).splitlines()[0].split()[1]
        assert run("xcrun", "dwarfdump", "--uuid", str(binary)).split()[1] == original_uuid
        run("codesign", "--verify", "--strict", str(binary.parent), stderr=subprocess.DEVNULL)
    after = {f.name: digest(embedded / f.name / f.stem) for f in frameworks}
    run("sh", str(script), env=env)
    assert all(digest(embedded / f.name / f.stem) == after[f.name] for f in frameworks)
assert all(digest(f / f.stem) == originals[f] for f in frameworks), "Dependency cache changed"
print("All 8 frameworks: arm64 preserved, arm64e removed, signatures valid, simulator untouched, repeat run unchanged.")

if len(sys.argv) > 2:
    app = Path(sys.argv[2]).resolve()
    content = app / "Contents" if (app / "Contents").is_dir() else app
    info = plistlib.loads((content / "Info.plist").read_bytes())
    executable = content / "MacOS" / info["CFBundleExecutable"] if content != app else app / info["CFBundleExecutable"]
    embedded = content / "Frameworks"
    assert (embedded / "FFmpeg-Kit.framework/FFmpeg-Kit").is_file(), "Missing dynamic Swift package wrapper"
    binaries = [executable] + [f / f.stem for f in embedded.glob("*.framework")]
    for binary in binaries:
        for line in run("xcrun", "otool", "-L", str(binary)).splitlines()[1:]:
            dependency = line.strip().split(" (", 1)[0]
            if dependency.startswith("@rpath/"):
                assert (embedded / dependency.removeprefix("@rpath/")).is_file(), (binary, dependency)
    print("App packaging: dynamic FFmpeg-Kit wrapper present; all embedded runtime dependencies resolve.")
