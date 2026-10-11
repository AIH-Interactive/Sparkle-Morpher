"""Upload the full/native Sparkle Morpher jars to Modrinth.

This script is intentionally separate from publish.py. It never changes
gradle.properties, commits, pushes, or uploads to CurseForge/GitHub.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import uuid
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from publishing_utils import workspace, credential, digest, get_json, save_receipt


BASE_DIR = workspace(__file__)
RELEASE_DIR = BASE_DIR / "Release"
API_URL = "https://api.modrinth.com/v2/version"
USER_AGENT = "sdf123098/Sparkle-Morpher-Modrinth-Uploader"

VARIANTS = (
    {
        "key": "fa1.21.1",
        "jar": "sparkle-morpher-{version}-fa1.21.1.jar",
        "loader": "fabric",
        "game_versions": ["1.21.1"],
        "name": "Fabric 1.21.1",
    },
    {
        "key": "fa26.1.x",
        "jar": "sparkle-morpher-{version}-fa26.1.x.jar",
        "loader": "fabric",
        "game_versions": ["26.1.2"],
        "name": "Fabric 26.1.x",
    },
    {
        "key": "fa26.2",
        "jar": "sparkle-morpher-{version}-fa26.2.jar",
        "loader": "fabric",
        "game_versions": ["26.2"],
        "name": "Fabric 26.2",
    },
    {
        "key": "fa26.3",
        "jar": "sparkle-morpher-{version}-fa26.3.jar",
        "loader": "fabric",
        "game_versions": ["26.3"],
        "name": "Fabric 26.3",
    },
    {
        "key": "neo1.21.1",
        "jar": "sparkle-morpher-{version}-neo1.21.1.jar",
        "loader": "neoforge",
        "game_versions": ["1.21.1"],
        "name": "NeoForge 1.21.1",
    },
    {
        "key": "neo26.1.x",
        "jar": "sparkle-morpher-{version}-neo26.1.x.jar",
        "loader": "neoforge",
        "game_versions": ["26.1.2"],
        "name": "NeoForge 26.1.x",
    },
    {
        "key": "neo26.2",
        "jar": "sparkle-morpher-{version}-neo26.2.jar",
        "loader": "neoforge",
        "game_versions": ["26.2"],
        "name": "NeoForge 26.2",
    },
    {
        "key": "neo26.3",
        "jar": "sparkle-morpher-{version}-neo26.3.jar",
        "loader": "neoforge",
        "game_versions": ["26.3"],
        "name": "NeoForge 26.3",
    },
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upload full Sparkle Morpher release jars to Modrinth."
    )
    parser.add_argument(
        "--project-id",
        default=credential("MODRINTH_PROJECT_ID"),
        help="Modrinth project ID (or MODRINTH_PROJECT_ID).",
    )
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--jar", type=Path, help="One full jar to upload.")
    target.add_argument("--variant", choices=[v["key"] for v in VARIANTS])
    target.add_argument(
        "--all",
        action="store_true",
        help="Upload all eight full jars from Release.",
    )
    parser.add_argument(
        "--version",
        required=True,
        help="Mod version, for example 1.2.3.",
    )
    parser.add_argument(
        "--changelog",
        default="",
        help="Changelog shown on Modrinth.",
    )
    parser.add_argument(
        "--changelog-file",
        type=Path,
        help="Read the changelog from this file (avoids long shell arguments). "
        "The same release README can be used on every platform.",
    )
    parser.add_argument(
        "--version-type",
        choices=("release", "beta", "alpha"),
        default="release",
    )
    parser.add_argument(
        "--environment",
        choices=(
            "client_and_server",
            "client_only",
            "server_only",
            "client_or_server",
        ),
        default="client_and_server",
    )
    parser.add_argument(
        "--loader",
        choices=("fabric", "neoforge"),
        help="Loader for --jar; ignored by --all/--variant.",
    )
    parser.add_argument(
        "--game-version",
        action="append",
        dest="game_versions",
        help="Minecraft version for --jar; may be specified more than once.",
    )
    parser.add_argument(
        "--name",
        help="Modrinth version name for --jar; otherwise generated.",
    )
    parser.add_argument(
        "--upload",
        action="store_true",
        help="Actually create the Modrinth version. Without this flag, preview only.",
    )
    parser.add_argument(
        "--update-changelog",
        action="store_true",
        help="Update the changelog of already-published versions instead of uploading. "
        "Select with --all/--variant and pass --version plus --changelog-file.",
    )
    return parser.parse_args()


def resolve_changelog(args: argparse.Namespace) -> str:
    """--changelog-file wins over --changelog so long markdown can be piped in."""
    if args.changelog_file is not None:
        if not args.changelog_file.is_file():
            raise FileNotFoundError(args.changelog_file)
        return args.changelog_file.read_text(encoding="utf-8")
    return args.changelog


def multipart_form(file_path: Path, data: dict[str, object]) -> tuple[bytes, str]:
    boundary = f"----SparkleMorpherModrinth{uuid.uuid4().hex}"
    boundary_bytes = boundary.encode("ascii")
    chunks: list[bytes] = []

    chunks.extend(
        (
            b"--" + boundary_bytes,
            b'Content-Disposition: form-data; name="data"',
            b"Content-Type: application/json; charset=utf-8",
            b"",
            json.dumps(data, ensure_ascii=False).encode("utf-8"),
        )
    )
    chunks.extend(
        (
            b"--" + boundary_bytes,
            f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"'.encode(
                "utf-8"
            ),
            b"Content-Type: application/java-archive",
            b"",
            file_path.read_bytes(),
        )
    )
    chunks.extend((b"--" + boundary_bytes + b"--", b""))
    return b"\r\n".join(chunks), f"multipart/form-data; boundary={boundary}"


def inspect_full_jar(jar_path: Path) -> None:
    if not jar_path.is_file():
        raise FileNotFoundError(jar_path)
    if jar_path.name.lower().endswith("-curseforge.jar"):
        raise ValueError(f"Refusing CurseForge jar; use the full jar from Release: {jar_path}")

    with zipfile.ZipFile(jar_path, "r") as jar:
        entries = [entry.lower() for entry in jar.namelist()]
    native_entries = [entry for entry in entries if entry.startswith("natives/")]
    if not native_entries:
        raise ValueError(f"Jar does not contain natives/, so it is not the full jar: {jar_path}")


def build_metadata(
    project_id: str,
    version: str,
    variant: dict[str, object],
    changelog: str,
    version_type: str,
    environment: str,
    name: str | None = None,
) -> dict[str, object]:
    loader = str(variant["loader"])
    game_versions = list(variant["game_versions"])
    display_name = name or f"Sparkle Morpher {version} - {variant['name']}"
    return {
        "project_id": project_id,
        "name": display_name,
        "version_number": version,
        "version_type": version_type,
        "loaders": [loader],
        "game_versions": game_versions,
        "environment": environment,
        "status": "listed",
        "file_parts": ["file"],
        "primary_file": "file",
        "dependencies": [],
        "featured": False,
        "changelog": changelog or f"Release {version}",
    }


def upload(jar_path: Path, metadata: dict[str, object], token: str) -> dict[str, object]:
    body, content_type = multipart_form(jar_path, metadata)
    request = urllib.request.Request(API_URL, data=body, method="POST")
    request.add_header("Authorization", token)
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json")
    request.add_header("Content-Type", content_type)

    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Modrinth HTTP {error.code}: {detail}") from error


def variant_for_key(key: str) -> dict[str, object]:
    for variant in VARIANTS:
        if variant["key"] == key:
            return variant
    raise ValueError(f"Unknown variant: {key}")


def listed_versions(project_id: str) -> list[dict[str, object]]:
    """Read the project's published versions (public endpoint, no auth needed)."""
    request = urllib.request.Request(
        f"https://api.modrinth.com/v2/project/{project_id}/version",
        method="GET",
    )
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json")
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def find_version_id(project_id: str, version: str, variant: dict[str, object]) -> str:
    """Locate the published version for one variant by version number and name."""
    expected_name = f"Sparkle Morpher {version} - {variant['name']}"
    for entry in listed_versions(project_id):
        if str(entry.get("version_number")) != version:
            continue
        if str(entry.get("name")) == expected_name:
            return str(entry["id"])
    raise RuntimeError(
        f"could not find a published Modrinth version for {version} / {variant['name']}"
    )


def update_changelog(version_id: str, changelog: str, token: str) -> None:
    """PATCH an existing version's changelog (does not touch the uploaded file)."""
    body = json.dumps({"changelog": changelog}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{API_URL}/{version_id}",
        data=body,
        method="PATCH",
    )
    request.add_header("Authorization", token)
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json")
    request.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            # A successful modify returns 204 with an empty body.
            response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Modrinth HTTP {error.code}: {detail}") from error


def targets(args: argparse.Namespace) -> list[tuple[Path, dict[str, object]]]:
    if args.all:
        return [
            (RELEASE_DIR / str(variant["jar"]).format(version=args.version), variant)
            for variant in VARIANTS
        ]

    if args.variant:
        variant = variant_for_key(args.variant)
        return [
            (RELEASE_DIR / str(variant["jar"]).format(version=args.version), variant)
        ]

    if args.jar is None:
        raise ValueError("One of --jar, --variant, or --all is required")
    variant = {
        "key": "custom",
        "loader": args.loader or "fabric",
        "game_versions": args.game_versions or [],
        "name": "custom build",
    }
    if not variant["game_versions"]:
        raise ValueError("--game-version is required when using --jar")
    return [(args.jar.resolve(), variant)]


def main() -> int:
    args = parse_args()
    if not args.project_id:
        raise ValueError("Set --project-id or MODRINTH_PROJECT_ID")

    upload_token = credential("MODRINTH_TOKEN")
    if args.upload and not upload_token:
        raise ValueError("Set MODRINTH_TOKEN before using --upload")

    if args.update_changelog:
        return update_changelogs(args, upload_token or "")

    selected = targets(args)
    for path, _ in selected:
        inspect_full_jar(path)
    existing = []
    if args.upload:
        available = {item["version"] for item in get_json("https://api.modrinth.com/v2/tag/game_version")}
        for _, variant in selected:
            missing = set(variant["game_versions"]) - available
            if missing:
                raise ValueError(f"Modrinth does not accept these Minecraft versions: {sorted(missing)}")
        existing = listed_versions(str(args.project_id))
        # A changed binary under an existing version needs an explicit version decision.
        for path, variant in selected:
            matches = [item for item in existing if item.get("version_number") == args.version
                       and item.get("loaders") == [variant["loader"]]
                       and item.get("game_versions") == variant["game_versions"]]
            if len(matches) > 1:
                raise RuntimeError(f"Ambiguous existing versions for {variant['key']}")
            if matches and not any(f.get("hashes", {}).get("sha512") == digest(path, "sha512")
                                   for f in matches[0].get("files", [])):
                raise RuntimeError(f"Different binary already exists for {variant['key']} / {args.version}")
    for jar_path, variant in selected:
        inspect_full_jar(jar_path)
        metadata = build_metadata(
            args.project_id,
            args.version,
            variant,
            resolve_changelog(args),
            args.version_type,
            args.environment,
            args.name if len(selected) == 1 else None,
        )
        print(f"Jar: {jar_path}")
        print(f"Metadata: {json.dumps({k:v for k,v in metadata.items() if k != 'changelog'}, ensure_ascii=False)}")
        if not args.upload:
            print("Preview only; add --upload to create this Modrinth version.")
            continue

        matching = [item for item in existing if item.get("version_number") == args.version
                    and item.get("loaders") == metadata["loaders"]
                    and item.get("game_versions") == metadata["game_versions"]]
        if matching:
            result = matching[0]
            update_changelog(str(result["id"]), str(metadata["changelog"]), upload_token or "")
        else:
            result = upload(jar_path, metadata, upload_token or "")
        verified = get_json(f"{API_URL}/{result['id']}")
        if (verified.get("game_versions") != metadata["game_versions"]
                or verified.get("loaders") != metadata["loaders"]
                or verified.get("changelog") != metadata["changelog"]
                or not any(f.get("hashes", {}).get("sha512") == digest(jar_path, "sha512")
                           for f in verified.get("files", []))):
            raise RuntimeError(f"Modrinth verification failed for {result['id']}")
        save_receipt(BASE_DIR, "modrinth", args.version, variant["key"], {
            "id": result["id"], "project_id": args.project_id, "file": jar_path.name,
            "sha256": digest(jar_path), "game_versions": metadata["game_versions"],
            "loaders": metadata["loaders"], "url": f"https://modrinth.com/mod/{args.project_id}/version/{result['id']}",
        })
        print(f"Uploaded: {result.get('id', '<no-version-id>')}")

    return 0


def update_changelogs(args: argparse.Namespace, token: str) -> int:
    """Fix the changelog of already-published versions (no file re-upload)."""
    if args.jar is not None:
        raise ValueError("--update-changelog works with --all or --variant, not --jar")
    changelog = resolve_changelog(args)
    if not changelog.strip():
        raise ValueError("--changelog-file (or --changelog) is required with --update-changelog")

    if args.all:
        variants = list(VARIANTS)
    elif args.variant:
        variants = [variant_for_key(args.variant)]
    else:
        raise ValueError("--all or --variant is required with --update-changelog")

    for variant in variants:
        version_id = find_version_id(str(args.project_id), args.version, variant)
        print(f"{variant['name']}: version id {version_id}")
        if not args.upload:
            print("  preview only; add --upload to patch the changelog")
            continue
        update_changelog(version_id, changelog, token)
        print(f"  changelog updated ({len(changelog)} chars)")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
