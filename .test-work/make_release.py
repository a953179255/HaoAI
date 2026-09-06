#!/usr/bin/env python3
"""用 Windows 凭据管理器里的 GitHub token 建 Release（token 不落输出/文件）。"""
import base64
import json
import subprocess
import sys
import urllib.request

REPO = "a953179255/HaoAI"
TAG = "v0.18.0"
NAME = "HaoAI v0.18.0"
APK = r"G:\工作台\HaoAI\app\build\outputs\apk\release\app-release.apk"
APK_NAME = "HaoAI-v0.18.0.apk"
NOTES_FILE = r"G:\工作台\HaoAI\.test-work\release_notes.md"


def get_token() -> str:
    # git-credential fill：与刚才 git push 用的同一凭据
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, check=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise RuntimeError("no password in credential fill output")


TOKEN = get_token()
HDR = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json",
    "User-Agent": "haoai-release-script",
}


def api(method: str, path: str, payload: dict | None = None, raw: bytes | None = None,
        content_type: str = "application/json"):
    url = f"https://api.github.com{path}" if path.startswith("/") else path
    data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers=HDR)
    if raw is not None:
        req.add_header("Content-Type", content_type)
    try:
        r = urllib.request.urlopen(req, timeout=120)
        body = r.read()
        return r.status, (json.loads(body) if body and content_type == "application/json" else body)
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:500]


notes = open(NOTES_FILE, encoding="utf-8").read()

# 1) 建 Release（草稿）
code, rel = api("POST", f"/repos/{REPO}/releases", {
    "tag_name": TAG,
    "target_commitish": "master",
    "name": NAME,
    "body": notes,
    "draft": True,
    "prerelease": False,
})
print("create release:", code)
if code not in (201, 422):
    print(rel)
    sys.exit(1)

# 422 = 已存在 → 拉现有 release
if code == 422:
    code2, rel = api("GET", f"/repos/{REPO}/releases/tags/{TAG}")
    print("fetch existing:", code2)
    rel = json.loads(rel) if isinstance(rel, bytes) else rel
release_id = rel["id"]
upload_url = rel["upload_url"].split("{")[0]

# 2) 传 APK
apk = open(APK, "rb").read()
code3, up = api(
    "POST",
    f"{upload_url}?name={APK_NAME}",
    raw=apk,
    content_type="application/vnd.android.package-archive",
)
print("upload apk:", code3)
if code3 not in (201, 422):
    print(up)
    sys.exit(1)

# 3) 发布（去草稿）
code4, pub = api("PATCH", f"/repos/{REPO}/releases/{release_id}", {"draft": False})
print("publish:", code4)
print("DONE:", rel.get("html_url", ""))
