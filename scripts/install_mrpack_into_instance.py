#!/usr/bin/env python3
"""Merge a Modrinth .mrpack into an existing Minecraft instance directory."""

from __future__ import annotations

import hashlib
import json
import ssl
import subprocess
import sys
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


def sha1_file(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _ssl_context() -> ssl.SSLContext:
    try:
        import certifi  # type: ignore
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        # macOS python.org builds often lack system CA bundle; curl still works.
        return ssl._create_unverified_context()


def download(url: str, dest: Path, expected_sha1: str | None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and expected_sha1 and sha1_file(dest) == expected_sha1:
        return
    tmp = dest.with_suffix(dest.suffix + ".part")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "PMCL-mrpack-installer/1.0"})
        with urllib.request.urlopen(req, timeout=120, context=_ssl_context()) as resp, tmp.open("wb") as out:
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
    except Exception:
        # Fallback: system curl (uses macOS keychain CAs)
        tmp.unlink(missing_ok=True)
        r = subprocess.run(
            ["curl", "-fsSL", "--retry", "3", "-A", "PMCL-mrpack-installer/1.0",
             "-o", str(tmp), url],
            capture_output=True, text=True
        )
        if r.returncode != 0 or not tmp.exists():
            raise IOError(r.stderr.strip() or f"curl failed ({r.returncode})")
    if expected_sha1:
        got = sha1_file(tmp)
        if got != expected_sha1:
            tmp.unlink(missing_ok=True)
            raise IOError(f"SHA-1 mismatch for {dest.name}: expected {expected_sha1}, got {got}")
    tmp.replace(dest)


def safe_extract_member(zf: zipfile.ZipFile, member: str, dest_root: Path) -> Path | None:
    # overrides/foo -> foo ; client-overrides/foo -> foo
    for prefix in ("overrides/", "client-overrides/"):
        if member.startswith(prefix) and not member.endswith("/"):
            rel = member[len(prefix) :]
            if not rel or rel.endswith("/"):
                return None
            target = (dest_root / rel).resolve()
            if not str(target).startswith(str(dest_root.resolve())):
                raise IOError(f"Illegal override path: {member}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(member) as src, target.open("wb") as out:
                out.write(src.read())
            return target
    return None


def main() -> int:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <pack.mrpack> <instance_dir>")
        return 2

    mrpack = Path(sys.argv[1]).expanduser().resolve()
    instance = Path(sys.argv[2]).expanduser().resolve()
    if not mrpack.is_file():
        print(f"mrpack not found: {mrpack}")
        return 1
    if not instance.is_dir():
        print(f"instance dir not found: {instance}")
        return 1

    with zipfile.ZipFile(mrpack) as zf:
        with zf.open("modrinth.index.json") as f:
            index = json.load(f)

    name = index.get("name", mrpack.stem)
    deps = index.get("dependencies") or {}
    files = index.get("files") or []
    print(f"Pack: {name}")
    print(f"Dependencies: {json.dumps(deps)}")
    print(f"Files: {len(files)}")
    print(f"Target: {instance}")
    print()

    game = str(deps.get("minecraft", ""))
    if game and game != "1.21.1":
        print(f"WARNING: pack Minecraft {game} vs BMC5 inheritsFrom 1.21.1 — may be incompatible")
    loader = None
    for k in ("neoforge", "forge", "fabric-loader", "quilt-loader"):
        if k in deps:
            loader = f"{k} {deps[k]}"
            break
    if loader:
        print(f"Loader required by pack: {loader}")
    print()

    ok = 0
    skipped = 0
    failed: list[str] = []

    def one(entry: dict) -> tuple[str, str]:
        path = entry.get("path") or ""
        if not path:
            return ("fail", "missing path")
        # optional/env: only install client/required
        env = entry.get("env") or {}
        client = env.get("client", "required")
        if client == "unsupported":
            return ("skip", path)
        hashes = entry.get("hashes") or {}
        sha1 = hashes.get("sha1")
        urls = entry.get("downloads") or []
        if not urls:
            return ("fail", f"{path}: no download URL")
        dest = (instance / path).resolve()
        if not str(dest).startswith(str(instance)):
            return ("fail", f"{path}: illegal path")
        try:
            download(urls[0], dest, sha1)
            return ("ok", path)
        except Exception as e:
            return ("fail", f"{path}: {e}")

    print("Downloading mods/files...")
    with ThreadPoolExecutor(max_workers=12) as pool:
        futs = [pool.submit(one, e) for e in files]
        done = 0
        for fut in as_completed(futs):
            status, msg = fut.result()
            done += 1
            if status == "ok":
                ok += 1
            elif status == "skip":
                skipped += 1
            else:
                failed.append(msg)
            if done % 10 == 0 or done == len(files):
                print(f"  progress {done}/{len(files)} (ok={ok}, fail={len(failed)}, skip={skipped})")

    print("\nExtracting overrides...")
    override_count = 0
    with zipfile.ZipFile(mrpack) as zf:
        for name in zf.namelist():
            try:
                out = safe_extract_member(zf, name, instance)
                if out is not None:
                    override_count += 1
            except Exception as e:
                failed.append(f"override {name}: {e}")
    print(f"  overrides written: {override_count}")

    print()
    print(f"Done. downloaded/kept={ok}, skipped={skipped}, overrides={override_count}, failed={len(failed)}")
    if failed:
        print("Failures (up to 20):")
        for line in failed[:20]:
            print(" -", line)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
