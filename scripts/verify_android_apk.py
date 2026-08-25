#!/usr/bin/env python3
"""Reject Android APKs whose Gecko native libraries do not match the target ABI."""

from __future__ import annotations

import argparse
import struct
import zipfile
from pathlib import Path


ABI_ELF_MACHINE = {
    "armeabi-v7a": 40,  # EM_ARM
    "arm64-v8a": 183,  # EM_AARCH64
    "x86_64": 62,  # EM_X86_64
}

REQUIRED_GECKO_LIBRARIES = (
    "libxul.so",
    "libmozglue.so",
    "libnss3.so",
    "libmegazord.so",
)


def elf_machine(archive: zipfile.ZipFile, member: str) -> int:
    with archive.open(member) as stream:
        header = stream.read(64)
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise ValueError(f"{member} is not an ELF shared library")
    if header[4] not in (1, 2):
        raise ValueError(f"{member} has an unsupported ELF class")
    if header[5] == 1:
        return struct.unpack_from("<H", header, 18)[0]
    if header[5] == 2:
        return struct.unpack_from(">H", header, 18)[0]
    raise ValueError(f"{member} has an unsupported ELF byte order")


def verify_apk(path: Path, abi: str) -> None:
    expected_machine = ABI_ELF_MACHINE[abi]
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for required in ("AndroidManifest.xml", "classes.dex"):
            if required not in names:
                raise ValueError(f"APK is missing {required}")

        gecko_abis = sorted(
            name.split("/")[1]
            for name in names
            if name.count("/") == 2 and name.endswith("/libxul.so")
        )
        if gecko_abis != [abi]:
            found = ", ".join(gecko_abis) if gecko_abis else "none"
            raise ValueError(
                f"APK Gecko ABI mismatch: expected only {abi}, found {found}"
            )

        for library in REQUIRED_GECKO_LIBRARIES:
            member = f"lib/{abi}/{library}"
            if member not in names:
                raise ValueError(f"APK is missing required {abi} library {library}")
            machine = elf_machine(archive, member)
            if machine != expected_machine:
                raise ValueError(
                    f"{member} ELF machine is {machine}, expected {expected_machine}"
                )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", choices=sorted(ABI_ELF_MACHINE), required=True)
    args = parser.parse_args()
    verify_apk(args.apk, args.abi)
    print(f"APK contains a complete, architecture-correct Gecko runtime for {args.abi}")


if __name__ == "__main__":
    main()
