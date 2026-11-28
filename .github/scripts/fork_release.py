"""Follow upstream release tags; promote only the candidate that passed CI."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

UPSTREAM = "joestar817/legado_NG"
FORK = "Guany725/legado_NG"
STATE = Path(".fork/upstream-release.json")


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    request = urllib.request.Request("https://api.github.com/" + path, headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def release_is_current(state, upstream, sha, published):
    return (state.get("release_id") == upstream["id"] and
            state.get("upstream_sha") == sha and published is not None and
            not published.get("draft", True) and not published.get("prerelease", True))


def valid_tag(tag):
    return isinstance(tag, str) and bool(tag) and not tag.startswith("-") and not any(c in tag for c in "\r\n")


def output(**values):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def merge_release(sha, base):
    # GITHUB_TOKEN cannot write workflow files. Keep this fork's workflow definitions.
    try:
        command("git", "merge", "--no-commit", "--no-ff", sha)
    except subprocess.CalledProcessError:
        if not Path(command("git", "rev-parse", "--git-path", "MERGE_HEAD")).is_file():
            raise
    command("git", "restore", "--source=" + base, "--staged", "--worktree", "--", ".github/workflows")
    conflicts = command("git", "diff", "--name-only", "--diff-filter=U")
    if conflicts:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write("## Upstream merge needs attention\n\nNo branch or release was updated.\n\n```\n" + conflicts + "\n```\n")
        command("git", "merge", "--abort")
        raise RuntimeError("Upstream merge conflicts require manual resolution")
    if Path(command("git", "rev-parse", "--git-path", "MERGE_HEAD")).is_file():
        command("git", "commit", "-m", "Merge upstream release; retain fork workflows")


def prepare(force=False):
    if os.environ.get("GITHUB_REPOSITORY") != FORK:
        raise RuntimeError("This workflow belongs to " + FORK)
    upstream = api(f"repos/{UPSTREAM}/releases/latest")
    if not upstream or upstream["draft"] or upstream["prerelease"]:
        raise RuntimeError("No upstream stable release is available")
    tag = upstream["tag_name"]
    if not valid_tag(tag):
        raise ValueError("Invalid upstream tag")
    command("git", "check-ref-format", "refs/tags/" + tag)
    # Fetch just the published tag, never upstream main.
    command("git", "fetch", "--no-tags", "https://github.com/" + UPSTREAM + ".git", "refs/tags/" + tag)
    sha = command("git", "rev-parse", "FETCH_HEAD^{commit}")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid commit SHA")
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    published = api(f"repos/{FORK}/releases/tags/{state['fork_tag']}") if re.fullmatch(r"3\.\d{2}\.\d{6}", state.get("fork_tag", "")) else None
    base = command("git", "rev-parse", "HEAD")
    if not force and release_is_current(state, upstream, sha, published) and published.get("target_commitish") == base:
        output(changed="false")
        return
    command("git", "config", "user.name", "github-actions[bot]")
    command("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    merge_release(sha, base)
    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=8)))
    version = now.strftime("3.%y.%m%d%H")
    # Version names follow NG's hourly update parser. Never replace an existing release.
    if api(f"repos/{FORK}/releases/tags/{version}") is not None:
        raise RuntimeError("A release already exists for this hour. Retry next hour.")
    state = {"release_id": upstream["id"], "upstream_tag": tag, "upstream_sha": sha, "fork_tag": version}
    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    command("git", "add", str(STATE))
    command("git", "commit", "-m", "Track upstream release " + tag)
    output(changed="true", base=base, candidate=command("git", "rev-parse", "HEAD"),
           version=version, version_long=now.strftime("3.%y.%m%d%H%M"),
           version_code=1000000 + int(os.environ["GITHUB_RUN_NUMBER"]))


def publish():
    state = json.loads(STATE.read_text())
    candidate = os.environ["CANDIDATE_SHA"]
    base = os.environ["BASE_SHA"]
    if command("git", "rev-parse", "HEAD") != candidate:
        raise RuntimeError("Candidate changed after build")
    remote = command("git", "ls-remote", "origin", "refs/heads/main").split()[0]
    if remote != base:
        raise RuntimeError("Main changed during build; rerun against the new head")
    apks = sorted(str(p) for p in Path("apk").glob("*.apk"))
    if len(apks) != 1:
        raise RuntimeError("Expected exactly one signed APK")
    # An ordinary fast-forward push rejects races; never force-push the user's branch.
    command("git", "push", "origin", "HEAD:refs/heads/main")
    tag = state["fork_tag"]
    body = Path(os.environ["RUNNER_TEMP"]) / "fork-release-notes.md"
    body.write_text(
        "阅读 NG 图集分支：独立图集管理、本地多选图片、日夜同步及独立图集 ZIP。\n\n"
        f"上游：[{state['upstream_tag']}](https://github.com/{UPSTREAM}/releases/tag/{state['upstream_tag']})\n\n"
        f"上游提交：`{state['upstream_sha']}`\n\n"
        "包名独立，可与官方版并存。首次迁移请使用备份恢复。\n"
        "已通过自动测试和构建；本次上游合并未经过安卓真机验收。\n", encoding="utf-8")
    command("gh", "release", "create", tag, "--target", candidate, "--title", "阅读 NG 图集 " + tag,
            "--notes-file", str(body), "--draft", *apks)
    command("gh", "release", "edit", tag, "--draft=false", "--latest")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "publish"])
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.force)
    else:
        publish()
