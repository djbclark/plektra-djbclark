#!/usr/bin/env python3
"""Publish a "Plektra djb" APK as a Forgejo release, then prune old djb releases.

    djb_release.py APK

Run inside a Forgejo Actions job. Reads FORGEJO_TOKEN (the job's automatic token),
GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_RUN_NUMBER, and optionally
COMMIT_SUBJECT, SIGNER_SHA256 and DJB_KEEP_RELEASES (default 15). The tag is
djb-<UTC yyyymmdd.HHMM>-<sha8>; the APK is uploaded as plektra-djb-<sha8>-stable-debug.apk
with a .sha256 file beside it.
"""
import datetime
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

SERVER = os.environ["GITHUB_SERVER_URL"].rstrip("/")
REPO = os.environ["GITHUB_REPOSITORY"]
SHA = os.environ["GITHUB_SHA"]
TOKEN = os.environ["FORGEJO_TOKEN"]
API = f"{SERVER}/api/v1/repos/{REPO}"


def call(method, path, body=None, data=None, content_type=None):
    headers = {"Authorization": f"token {TOKEN}", "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
        content_type = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"djb_release: {method} {path}: HTTP {e.code}: {e.read().decode()[:500]}")
    return json.loads(payload) if payload.strip() else None


def upload(release_id, name, content):
    boundary = uuid.uuid4().hex
    data = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="attachment"; filename="{name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    query = urllib.parse.urlencode({"name": name})
    call("POST", f"/releases/{release_id}/assets?{query}", data=data,
         content_type=f"multipart/form-data; boundary={boundary}")


def prune(keep):
    releases, page = [], 1
    while True:
        batch = call("GET", f"/releases?limit=50&page={page}") or []
        releases += batch
        if len(batch) < 50:
            break
        page += 1
    djb = sorted((r for r in releases if r["tag_name"].startswith("djb-")),
                 key=lambda r: r["created_at"], reverse=True)
    for r in djb[keep:]:
        print(f"pruning {r['tag_name']}")
        call("DELETE", f"/releases/{r['id']}")
        call("DELETE", f"/tags/{urllib.parse.quote(r['tag_name'], safe='')}")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk_path = sys.argv[1]
    sha8 = SHA[:8]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d.%H%M")
    tag = f"djb-{stamp}-{sha8}"
    apk_name = f"plektra-djb-{sha8}-stable-debug.apk"
    apk = open(apk_path, "rb").read()
    digest = hashlib.sha256(apk).hexdigest()
    run_url = f"{SERVER}/{REPO}/actions/runs/{os.environ['GITHUB_RUN_NUMBER']}"
    body = "\n".join([
        f"Plektra djb (`rocks.pkb.plektra.djb`, stable debug) from plk/main at {SHA}.",
        "",
        f"Commit: {os.environ.get('COMMIT_SUBJECT', '').strip()}",
        f"Build: {run_url}",
        f"APK SHA-256: `{digest}`",
        f"Signer certificate SHA-256: `{os.environ.get('SIGNER_SHA256', 'unchecked')}`",
        "",
        "Install on the phone with `scripts/djb-install-latest`.",
    ])
    release = call("POST", "/releases", body={
        "tag_name": tag, "target_commitish": SHA, "name": f"Plektra djb {sha8}",
        "body": body, "draft": False, "prerelease": False,
    })
    upload(release["id"], apk_name, apk)
    upload(release["id"], apk_name + ".sha256", f"{digest}  {apk_name}\n".encode())
    print(f"released {tag}: {release['html_url']}")
    prune(int(os.environ.get("DJB_KEEP_RELEASES", "15")))


if __name__ == "__main__":
    main()
