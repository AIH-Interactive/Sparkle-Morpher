"""Upload CurseForge-safe Sparkle Morpher jars to CurseForge.

This script only uploads files from ``Curseforge``. It does not build, edit
version files, commit, push, or upload to GitHub or Modrinth.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path
from publishing_utils import workspace, credential, digest, get_json, receipts, save_receipt


BASE_DIR = workspace(__file__)
CURSEFORGE_DIR = BASE_DIR / "Curseforge"
CONFIG_PATH = BASE_DIR / "publish_config.json"
API_BASE = "https://minecraft.curseforge.com/api"

VARIANTS = (
    {
        "key": "fa1.21.1",
        "jar": "sparkle-morpher-{version}-fa1.21.1-curseforge.jar",
        "loader": "Fabric",
        "minecraft": "1.21.1",
        "java": "Java 21",
        "name": "Fabric 1.21.1",
    },
    {
        "key": "fa26.1.x",
        "jar": "sparkle-morpher-{version}-fa26.1.x-curseforge.jar",
        "loader": "Fabric",
        "minecraft": "26.1.2",
        "java": "Java 25",
        "name": "Fabric 26.1.x",
    },
    {
        "key": "fa26.2",
        "jar": "sparkle-morpher-{version}-fa26.2-curseforge.jar",
        "loader": "Fabric",
        "minecraft": "26.2",
        "java": "Java 25",
        "name": "Fabric 26.2",
    },
    {
        "key": "fa26.3",
        "jar": "sparkle-morpher-{version}-fa26.3-curseforge.jar",
        "loader": "Fabric",
        "minecraft": "26.3",
        "java": "Java 25",
        "name": "Fabric 26.3",
    },
    {
        "key": "neo1.21.1",
        "jar": "sparkle-morpher-{version}-neo1.21.1-curseforge.jar",
        "loader": "NeoForge",
        "minecraft": "1.21.1",
        "java": "Java 21",
        "name": "NeoForge 1.21.1",
    },
    {
        "key": "neo26.1.x",
        "jar": "sparkle-morpher-{version}-neo26.1.x-curseforge.jar",
        "loader": "NeoForge",
        "minecraft": "26.1.2",
        "java": "Java 25",
        "name": "NeoForge 26.1.x",
    },
    {
        "key": "neo26.2",
        "jar": "sparkle-morpher-{version}-neo26.2-curseforge.jar",
        "loader": "NeoForge",
        "minecraft": "26.2",
        "java": "Java 25",
        "name": "NeoForge 26.2",
    },
    {
        "key": "neo26.3",
        "jar": "sparkle-morpher-{version}-neo26.3-curseforge.jar",
        "loader": "NeoForge",
        "minecraft": "26.3",
        "java": "Java 25",
        "name": "NeoForge 26.3",
    },
)


def load_config() -> dict[str, object]:
    if not CONFIG_PATH.is_file():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid publish_config.json: {error}") from error


def parse_args(config: dict[str, object]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload CurseForge-safe jars.")
    parser.add_argument(
        "--project-id",
        default=credential("CURSEFORGE_PROJECT_ID", config.get("curseforge_project_id")),
    )
    parser.add_argument("--version", required=True, help="Release version, for example 1.2.3.")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--all", action="store_true", help="Upload all eight CurseForge jars.")
    target.add_argument("--variant", choices=[v["key"] for v in VARIANTS])
    parser.add_argument("--changelog", default="", help="Changelog shown on CurseForge.")
    parser.add_argument(
        "--changelog-file",
        type=Path,
        help="Read the changelog from this file (avoids long shell arguments). "
        "The same release README can be used on every platform.",
    )
    parser.add_argument(
        "--release-type",
        choices=("release", "beta", "alpha"),
        default=str(config.get("release_type", "release")),
    )
    parser.add_argument(
        "--manual-release",
        action="store_true",
        help="Keep the file unpublished until manually released after approval.",
    )
    parser.add_argument("--upload", action="store_true", help="Actually modify CurseForge.")
    parser.add_argument(
        "--update-changelog",
        action="store_true",
        help="Update the changelog of already-uploaded files instead of uploading new ones. "
        "Requires --file-id for each selected variant.",
    )
    parser.add_argument(
        "--file-id",
        action="append",
        default=[],
        metavar="ID",
        help="CurseForge file ID to patch (repeatable; consumed in variant order with --all).",
    )
    return parser.parse_args()


def resolve_changelog(args: argparse.Namespace) -> str:
    """--changelog-file wins over --changelog so long markdown can be piped in."""
    if args.changelog_file is not None:
        if not args.changelog_file.is_file():
            raise FileNotFoundError(args.changelog_file)
        return args.changelog_file.read_text(encoding="utf-8")
    return args.changelog


def selected_variants(args: argparse.Namespace) -> list[dict[str, object]]:
    if args.all:
        return list(VARIANTS)
    return [variant for variant in VARIANTS if variant["key"] == args.variant]


def inspect_curseforge_jar(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    if not path.name.lower().endswith("-curseforge.jar"):
        raise ValueError(f"Refusing non-CurseForge jar: {path}")
    with zipfile.ZipFile(path) as jar:
        bad_entries = [
            name
            for name in jar.namelist()
            if name.lower().startswith("natives/")
            or name.lower().endswith((".dll", ".so", ".dylib", ".jnilib", ".exe"))
        ]
    if bad_entries:
        preview = "\n".join(bad_entries[:20])
        raise ValueError(f"CurseForge jar contains native files:\n{preview}")


def metadata_for(variant: dict[str, object], version: str, changelog: str, release_type: str, manual: bool) -> dict[str, object]:
    game_version_names = [
        "Client",
        "Server",
        str(variant["minecraft"]),
        str(variant["loader"]),
        str(variant["java"]),
    ]
    metadata: dict[str, object] = {
        "changelog": changelog or f"Release {version}",
        "changelogType": "markdown",
        "displayName": f"Sparkle Morpher {version} - {variant['name']}",
        "gameVersionNames": game_version_names,
        "releaseType": release_type,
    }
    if manual:
        metadata["isMarkedForManualRelease"] = True
    return metadata


def multipart_form(file_path: Path, metadata: dict[str, object]) -> tuple[bytes, str]:
    boundary = f"----SparkleMorpherCurseForge{uuid.uuid4().hex}"
    boundary_bytes = boundary.encode("ascii")
    chunks = [
        b"--" + boundary_bytes,
        b'Content-Disposition: form-data; name="metadata"',
        b"Content-Type: application/json; charset=utf-8",
        b"",
        json.dumps(metadata, ensure_ascii=False).encode("utf-8"),
        b"--" + boundary_bytes,
        f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"'.encode("utf-8"),
        b"Content-Type: application/java-archive",
        b"",
        file_path.read_bytes(),
        b"--" + boundary_bytes + b"--",
        b"",
    ]
    return b"\r\n".join(chunks), f"multipart/form-data; boundary={boundary}"


def metadata_multipart(metadata: dict[str, object]) -> tuple[bytes, str]:
    """Multipart body carrying only metadata (used by update-file)."""
    boundary = f"----SparkleMorpherCurseForge{uuid.uuid4().hex}"
    boundary_bytes = boundary.encode("ascii")
    chunks = [
        b"--" + boundary_bytes,
        b'Content-Disposition: form-data; name="metadata"',
        b"Content-Type: application/json; charset=utf-8",
        b"",
        json.dumps(metadata, ensure_ascii=False).encode("utf-8"),
        b"--" + boundary_bytes + b"--",
        b"",
    ]
    return b"\r\n".join(chunks), f"multipart/form-data; boundary={boundary}"


def update_file(project_id: str, token: str, metadata: dict[str, object]) -> dict[str, object]:
    """POST /update-file — change metadata (e.g. changelog) of an uploaded file.

    The endpoint intermittently answers HTTP 500 for an otherwise valid request,
    so a bounded retry is applied here rather than failing the whole run.
    """
    import time

    last_error: RuntimeError | None = None
    for attempt in range(1, 4):
        body, content_type = metadata_multipart(metadata)
        request = urllib.request.Request(
            f"{API_BASE}/projects/{project_id}/update-file",
            data=body,
            method="POST",
        )
        request.add_header("X-Api-Token", token)
        request.add_header("User-Agent", "sdf123098/Sparkle-Morpher-CurseForge-Uploader")
        request.add_header("Accept", "application/json")
        request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            last_error = RuntimeError(f"CurseForge HTTP {error.code}: {detail}")
            # Retry only transient server-side failures.
            if error.code < 500:
                raise last_error from error
            if attempt < 3:
                time.sleep(2 * attempt)
        except urllib.error.URLError as error:
            last_error = RuntimeError(f"CurseForge connection error: {error.reason}")
            if attempt < 3:
                time.sleep(2 * attempt)
    raise last_error if last_error else RuntimeError("CurseForge update-file failed")


def upload(project_id: str, token: str, jar_path: Path, metadata: dict[str, object]) -> dict[str, object]:
    body, content_type = multipart_form(jar_path, metadata)
    request = urllib.request.Request(
        f"{API_BASE}/projects/{project_id}/upload-file",
        data=body,
        method="POST",
    )
    request.add_header("X-Api-Token", token)
    request.add_header("User-Agent", "sdf123098/Sparkle-Morpher-CurseForge-Uploader")
    request.add_header("Accept", "application/json")
    request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"CurseForge HTTP {error.code}: {detail}") from error


def main() -> int:
    config = load_config()
    args = parse_args(config)
    if not args.project_id:
        raise ValueError("Set --project-id, CURSEFORGE_PROJECT_ID, or publish_config.json")
    token = credential("CURSEFORGE_API_TOKEN", config.get("curseforge_api_token"))
    if args.upload and not token:
        raise ValueError("Set CURSEFORGE_API_TOKEN before using --upload")

    if args.update_changelog:
        return update_changelogs(args, token)

    selected = selected_variants(args)
    # Validate every artifact and label before the first upload.
    plans = []
    for variant in selected:
        jar_path = CURSEFORGE_DIR / str(variant["jar"]).format(version=args.version)
        inspect_curseforge_jar(jar_path)
        metadata = metadata_for(variant, args.version, resolve_changelog(args),
                                args.release_type, args.manual_release)
        plans.append((variant, jar_path, metadata))
    if args.upload:
        available = get_json(f"{API_BASE}/game/versions", {"X-Api-Token": token})
        names = {item["name"] for item in available}
        for _, _, metadata in plans:
            missing = set(metadata["gameVersionNames"]) - names
            if missing:
                raise ValueError(f"CurseForge does not accept these version labels: {sorted(missing)}")
            # Duplicate names exist across dependency taxonomies. Let the project
            # upload endpoint resolve names in its own allowed dependency scope.
    previous = receipts(BASE_DIR, "curseforge", args.version)
    for variant, jar_path, metadata in plans:
        print(f"Jar: {jar_path}")
        print(f"Metadata: {json.dumps({k:v for k,v in metadata.items() if k != 'changelog'}, ensure_ascii=False)}")
        if not args.upload:
            print("Preview only; add --upload to create this CurseForge file.")
            continue
        checksum = digest(jar_path)
        old = previous.get(variant["key"], {})
        if old.get("sha256") == checksum and str(old.get("project_id")) == str(args.project_id):
            result = update_file(str(args.project_id), token, {"fileID": int(old["id"]), **metadata})
            print(f"Already uploaded; metadata refreshed: {old['id']}")
        else:
            result = upload(str(args.project_id), token, jar_path, metadata)
        if not result.get("id"):
            raise RuntimeError("CurseForge upload did not return a file ID")
        save_receipt(BASE_DIR, "curseforge", args.version, variant["key"], {
            "id": result["id"], "project_id": str(args.project_id), "file": jar_path.name,
            "sha256": checksum, "metadata": metadata,
        })
        print(f"Uploaded: {result.get('id', '<no-file-id>')}")
    return 0


def update_changelogs(args: argparse.Namespace, token: str) -> int:
    """Patch the changelog of already-uploaded files (no jar re-upload)."""
    changelog = resolve_changelog(args)
    if not changelog.strip():
        raise ValueError("--changelog-file (or --changelog) is required with --update-changelog")

    variants = selected_variants(args)
    file_ids = [str(value) for value in args.file_id]
    if len(file_ids) != len(variants):
        raise ValueError(
            f"--file-id count ({len(file_ids)}) must match the selected variant count "
            f"({len(variants)}); pass one --file-id per variant in order"
        )

    for variant, file_id in zip(variants, file_ids):
        if not file_id.isdigit():
            raise ValueError(f"--file-id must be numeric: {file_id}")
        print(f"{variant['name']}: file id {file_id}")
        if not args.upload:
            print("  preview only; add --upload to patch the changelog")
            continue
        metadata = {
            "fileID": int(file_id),
            "changelog": changelog,
            "changelogType": "markdown",
        }
        result = update_file(str(args.project_id), token, metadata)
        print(f"  changelog updated (server returned file id {result.get('id', '<none>')})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
