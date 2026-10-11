"""Shared release helpers. Credentials never appear in logs or receipts."""
from __future__ import annotations

import hashlib
import json
import os
import urllib.request
from datetime import date
from pathlib import Path


def workspace(script: str) -> Path:
    if os.environ.get("SPARKLE_WORKSPACE"):
        return Path(os.environ["SPARKLE_WORKSPACE"]).resolve()
    directory = Path(script).resolve().parent
    for candidate in (directory, *directory.parents):
        if (candidate / "Release").is_dir() and (candidate / "Curseforge").is_dir():
            return candidate
    return directory


def credential(name: str, fallback: object = "") -> str:
    value = os.environ.get(name, "")
    if not value and os.name == "nt":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                value = str(winreg.QueryValueEx(key, name)[0])
        except FileNotFoundError:
            pass
    value = value or str(fallback or "")
    if value.upper().startswith(("YOUR_", "REPLACE_", "CHANGEME")):
        return ""
    return value


def digest(path: Path, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def get_json(url: str, headers: dict | None = None):
    request = urllib.request.Request(url, headers={
        "User-Agent": "sdf123098/Sparkle-Morpher-Publisher",
        "Accept": "application/json", **(headers or {}),
    })
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def receipt_path(base: Path, platform: str, version: str) -> Path:
    return base / "docs" / date.today().isoformat() / "release" / f"{platform}-{version}.json"


def receipts(base: Path, platform: str, version: str) -> dict:
    path = receipt_path(base, platform, version)
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}


def save_receipt(base: Path, platform: str, version: str, key: str, value: dict):
    path = receipt_path(base, platform, version)
    path.parent.mkdir(parents=True, exist_ok=True)
    result = receipts(base, platform, version)
    result[key] = value
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
