#!/usr/bin/env python3
"""Publish a Plektra djb APK as a GitHub release, then prune old djb releases.

    djb_release.py APK

Runs inside GitHub Actions. Uses GH_TOKEN (or GITHUB_TOKEN), GITHUB_REPOSITORY,
TARGET_SHA (real app commit), GITHUB_RUN_ID, and optionally COMMIT_SUBJECT,
SIGNER_SHA256, DJB_KEEP_RELEASES (default 15). Tag: djb-<UTC yyyymmdd.HHMM>-<sha8>;
APK asset: plektra-djb-<sha8>-stable-debug.apk + .sha256.
"""
import datetime
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ.get("GH_TOKEN") or os.environ["GITHUB_TOKEN"]
TARGET_SHA = os.environ.get("TARGET_SHA") or os.environ["GITHUB_SHA"]
API = f"https://api.github.com/repos/{REPO}"


def call(method, url, body=None, data=None, content_type=None):
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "plektra-djb-release",
    }
    if body is not None:
        data = json.dumps(body).encode()
        content_type = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"djb_release: {method} {url}: HTTP {e.code}: {e.read().decode()[:500]}")
    return json.loads(payload) if payload.strip() else None


def upload(upload_url_template, name, content):
    # upload_url looks like https://uploads.github.com/.../assets{?name,label}
    base = upload_url_template.split("{", 1)[0]
    url = base + "?" + urllib.parse.urlencode({"name": name})
    call("POST", url, data=content, content_type="application/octet-stream")


def prune(keep):
    releases, page = [], 1
    while True:
        batch = call("GET", f"{API}/releases?per_page=50&page={page}") or []
        releases += batch
        if len(batch) < 50:
            break
        page += 1
    djb = sorted(
        (r for r in releases if r["tag_name"].startswith("djb-")),
        key=lambda r: r["created_at"],
        reverse=True,
    )
    for r in djb[keep:]:
        print(f"pruning {r['tag_name']}")
        call("DELETE", f"{API}/releases/{r['id']}")
        # Best-effort tag delete (annotated/lightweight).
        call("DELETE", f"{API}/git/refs/tags/{urllib.parse.quote(r['tag_name'], safe='')}")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk_path = sys.argv[1]
    sha8 = TARGET_SHA[:8]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d.%H%M")
    tag = f"djb-{stamp}-{sha8}"
    apk_name = f"plektra-djb-{sha8}-stable-debug.apk"
    with open(apk_path, "rb") as f:
        apk = f.read()
    digest = hashlib.sha256(apk).hexdigest()
    run_url = f"https://github.com/{REPO}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    body = "\n".join(
        [
            f"Plektra djb (`rocks.pkb.plektra.djb`, stable debug) from {os.environ.get('TARGET_REF', 'ref')} at {TARGET_SHA}.",
            "",
            f"Commit: {os.environ.get('COMMIT_SUBJECT', '').strip()}",
            f"Build: {run_url}",
            f"APK SHA-256: `{digest}`",
            f"Signer certificate SHA-256: `{os.environ.get('SIGNER_SHA256', 'unchecked')}`",
            "",
            "Install on the phone with `scripts/github-install-latest` (Forgejo path: `scripts/djb-install-latest`).",
        ]
    )
    release = call(
        "POST",
        f"{API}/releases",
        body={
            "tag_name": tag,
            "target_commitish": TARGET_SHA,
            "name": f"Plektra djb {sha8}",
            "body": body,
            "draft": False,
            "prerelease": False,
        },
    )
    upload(release["upload_url"], apk_name, apk)
    upload(release["upload_url"], apk_name + ".sha256", f"{digest}  {apk_name}\n".encode())
    print(f"released {tag}: {release['html_url']}")
    prune(int(os.environ.get("DJB_KEEP_RELEASES", "15")))


if __name__ == "__main__":
    main()
