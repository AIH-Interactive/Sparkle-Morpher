"""Upload full Sparkle Morpher jars to a GitHub Release.

This script only talks to GitHub through the authenticated ``gh`` CLI. It
does not build, edit version files, commit, push, or upload to Modrinth or
CurseForge.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from publishing_utils import workspace, digest, save_receipt


BASE_DIR = workspace(__file__)
RELEASE_DIR = BASE_DIR / "Release"
CONFIG_PATH = BASE_DIR / "publish_config.json"

VARIANTS = (
    ("fa1.21.1", "sparkle-morpher-{version}-fa1.21.1.jar"),
    ("fa26.1.x", "sparkle-morpher-{version}-fa26.1.x.jar"),
    ("fa26.2", "sparkle-morpher-{version}-fa26.2.jar"),
    ("fa26.3", "sparkle-morpher-{version}-fa26.3.jar"),
    ("neo1.21.1", "sparkle-morpher-{version}-neo1.21.1.jar"),
    ("neo26.1.x", "sparkle-morpher-{version}-neo26.1.x.jar"),
    ("neo26.2", "sparkle-morpher-{version}-neo26.2.jar"),
    ("neo26.3", "sparkle-morpher-{version}-neo26.3.jar"),
)


def default_repo() -> str | None:
    if not CONFIG_PATH.is_file():
        return None
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8")).get("github_repo")
    except (OSError, json.JSONDecodeError):
        return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload full jars to GitHub Releases.")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPO") or default_repo())
    parser.add_argument("--version", required=True, help="Release version, for example 1.2.3.")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--all", action="store_true", help="Upload all eight jars from Release.")
    target.add_argument("--variant", choices=[key for key, _ in VARIANTS])
    parser.add_argument("--tag", help="Release tag; defaults to vVERSION.")
    parser.add_argument("--title", help="Release title; defaults to the tag.")
    parser.add_argument("--target", default="main", help="Commit or branch for a new release tag.")
    parser.add_argument("--notes", default="", help="Release notes.")
    parser.add_argument(
        "--notes-file",
        type=Path,
        help="Read the release body from this file (for example the bilingual release notes).",
    )
    parser.add_argument(
        "--asset",
        action="append",
        default=[],
        type=Path,
        metavar="FILE",
        help="Extra file to attach alongside the jars (repeatable), e.g. the release notes.",
    )
    parser.add_argument("--upload", action="store_true", help="Actually modify GitHub.")
    return parser.parse_args()


def extra_assets(args: argparse.Namespace) -> list[Path]:
    """Validate the additional assets that ride along with the jars.

    Release notes live in the repository root without the date in their name
    (``RELEASE_NOTES_<version>_<locale>.md``), and the relative links inside the
    notes point at exactly those names.  Attaching a differently named copy (for
    example the dated archive under ``docs/``) would make the release body's
    links resolve to a file that does not exist at the tag, so reject that here.
    """
    assets: list[Path] = []
    for path in args.asset:
        if not path.is_file():
            raise FileNotFoundError(path)
        if path.suffix.lower() == ".jar":
            raise ValueError(f"use the variant/--all flags for jars, not --asset: {path}")
        if path.name.startswith("RELEASE_NOTES") and re.search(r"_\d{4}-\d{2}-\d{2}", path.name):
            raise ValueError(
                "release note assets must use the repository naming without a date "
                f"(RELEASE_NOTES_<version>_<locale>.md), got: {path.name}"
            )
        assets.append(path)
    return assets


def target_jars(args: argparse.Namespace) -> list[Path]:
    selected = VARIANTS if args.all else tuple(item for item in VARIANTS if item[0] == args.variant)
    return [RELEASE_DIR / template.format(version=args.version) for _, template in selected]


def inspect_full_jar(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    if path.name.lower().endswith("-curseforge.jar"):
        raise ValueError(f"Refusing CurseForge jar: {path}")
    with zipfile.ZipFile(path) as jar:
        if not any(name.lower().startswith("natives/") for name in jar.namelist()):
            raise ValueError(f"Jar does not contain natives/ and is not a full jar: {path}")


def run_gh(args: list[str]) -> str:
    result = subprocess.run(
        ["gh", *args],
        cwd=BASE_DIR,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise RuntimeError(output or f"gh exited with {result.returncode}")
    return output


def main() -> int:
    args = parse_args()
    if not args.repo:
        raise ValueError("Set --repo or GITHUB_REPO, or configure github_repo in publish_config.json")

    tag = args.tag or f"v{args.version}"
    title = args.title or tag
    jars = target_jars(args)
    for jar in jars:
        inspect_full_jar(jar)
        print(f"Jar: {jar}")

    assets = extra_assets(args)
    for asset in assets:
        print(f"Asset: {asset}")

    if args.notes_file is not None:
        if not args.notes_file.is_file():
            raise FileNotFoundError(args.notes_file)
        body = args.notes_file.read_text(encoding="utf-8")
        print(f"Release body: {args.notes_file}")
    else:
        body = args.notes or f"Release {args.version}"

    print(f"Repository: {args.repo}")
    print(f"Tag: {tag}")
    print(f"Title: {title}")
    if not args.upload:
        print("Preview only; add --upload to create/upload the GitHub Release.")
        return 0

    pages = json.loads(run_gh(["api", f"repos/{args.repo}/releases", "--paginate", "--slurp"]))
    exists = any(item["tag_name"] == tag for page in pages for item in page)

    upload_args = [str(path) for path in [*jars, *assets]]
    if exists:
        print(f"Release {tag} exists; uploading assets with --clobber.")
        print(run_gh(["release", "upload", tag, *upload_args, "--clobber", "--repo", args.repo]))
        if args.notes_file is not None or args.notes:
            edit_command = ["release", "edit", tag]
            if args.notes_file is not None:
                edit_command += ["--notes-file", str(args.notes_file)]
            else:
                edit_command += ["--notes", body]
            edit_command += ["--repo", args.repo]
            print(run_gh(edit_command))
    else:
        print(f"Creating GitHub Release {tag} with {len(jars)} jar(s) and {len(assets)} extra asset(s).")
        command = [
            "release",
            "create",
            tag,
            *upload_args,
            "--repo",
            args.repo,
            "--title",
            title,
            "--target",
            args.target,
            "--draft",
        ]
        if args.notes_file is not None:
            command += ["--notes-file", str(args.notes_file)]
        else:
            command += ["--notes", body]
        print(run_gh(command))
    published = json.loads(run_gh(["api", f"repos/{args.repo}/releases/tags/{tag}"]))
    for path in [*jars, *assets]:
        matches = [entry for entry in published["assets"] if entry["name"] == path.name]
        if len(matches) != 1 or matches[0]["size"] != path.stat().st_size:
            raise RuntimeError(f"GitHub asset size verification failed: {path.name}")
        remote_digest = matches[0].get("digest")
        if remote_digest and remote_digest != "sha256:" + digest(path):
            raise RuntimeError(f"GitHub asset hash verification failed: {path.name}")
        save_receipt(BASE_DIR, "github", args.version, path.name, {
            "url": matches[0]["browser_download_url"], "sha256": digest(path), "size": path.stat().st_size,
            "release": published["html_url"], "digest_verified": bool(remote_digest),
        })
    if published["draft"]:
        print(run_gh(["release", "edit", tag, "--repo", args.repo, "--draft=false"]))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
