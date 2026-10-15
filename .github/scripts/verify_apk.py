"""Verify the packaged fork's identity, modern module metadata and APK signature."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile


def verify(apk: Path, expected_cert: Path | None) -> None:
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        raise ValueError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    tools = Path(sdk_root) / "build-tools" / "36.0.0"
    windows = os.name == "nt"
    aapt = tools / ("aapt.exe" if windows else "aapt")
    # Run the jar directly on Windows; subprocess cannot execute .bat without a shell.
    signer = ["java", "-jar", str(tools / "lib" / "apksigner.jar")] if windows else [str(tools / "apksigner")]

    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    if "package: name='io.github.shibry88_netizen.talktact'" not in badging:
        raise ValueError("Application ID changed")
    if "application-label:'TalkTact'" not in badging:
        raise ValueError("Application name changed")
    abi_line = re.search(r"^native-code:(.*)$", badging, re.MULTILINE)
    if not abi_line or re.findall(r"'([^']+)'", abi_line.group(1)) != ["arm64-v8a"]:
        raise ValueError("Unexpected APK ABI")

    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for name in ("module.prop", "java_init.list", "scope.list"):
            path = f"META-INF/xposed/{name}"
            if path not in names or not archive.read(path).strip():
                raise ValueError(f"Missing or empty modern metadata: {path}")
        if "assets/xposed_init" in names:
            raise ValueError("Legacy Xposed entry is forbidden")
        entry = archive.read("META-INF/xposed/java_init.list").decode().strip()
        if entry != "dev.goutou.wingman.WingmanEntry":
            raise ValueError("Modern module entry changed")
        scope = {
            line.strip() for line in archive.read("META-INF/xposed/scope.list").decode().splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        if scope != {"com.tencent.mm", "io.github.shibry88_netizen.talktact"}:
            raise ValueError("Module scope changed")
        properties = dict(
            line.strip().split("=", 1)
            for line in archive.read("META-INF/xposed/module.prop").decode().splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        )
        if any(properties.get(key) != value for key, value in {
            "minApiVersion": "102", "targetApiVersion": "102",
            "staticScope": "true", "exceptionMode": "protective",
        }.items()):
            raise ValueError("Modern API or host protection settings changed")

    signature = subprocess.check_output(
        [*signer, "verify", "--verbose", "--print-certs", str(apk)], text=True, encoding="utf-8"
    )
    certificates = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", signature, re.IGNORECASE)
    actual = {value.replace(":", "").upper() for value in certificates}
    if not actual:
        raise ValueError("No signer certificate found")
    if expected_cert:
        expected = expected_cert.read_text(encoding="utf-8").strip().replace(":", "").upper()
        if not re.fullmatch(r"[0-9A-F]{64}", expected) or actual != {expected}:
            raise ValueError("APK was not signed with the fork's pinned certificate")
        if "application-debuggable" in badging:
            raise ValueError("Published APK must be a release build")
    print(signature.strip())
    print(f"Verified {apk.name}: TalkTact / original package / arm64 / modern module")
    with apk.open("rb") as stream:
        print(f"SHA-256: {hashlib.file_digest(stream, 'sha256').hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-cert", type=Path)
    args = parser.parse_args()
    verify(args.apk, args.expected_cert)
