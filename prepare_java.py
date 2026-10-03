# Copyright (c) 2026 DDgamer. All rights reserved.
# Builds the Java runtimes DeepPixel downloads (Java 8, 17, 21, 25 for Android ARM64) and publishes them
# as assets of the "java-runtimes" release in this repository. Run it with the "Prepare Java" workflow.
import json, os, re, shutil, subprocess, sys, tarfile, tempfile, urllib.request, zipfile

TOKEN = os.environ.get("GH_TOKEN", "")
OWN = os.environ.get("GITHUB_REPOSITORY", "")
REPOS = ["QuestCraftPlusPlus/android-openjdk-build-multiarch",
         "PojavLauncherTeam/android-openjdk-build-multiarch",
         "AngelAuraMC/android-openjdk-build-multiarch"]
WANTED = [8, 17, 21, 25]
TAG = "java-runtimes"
BAD = re.compile(r"x86|i[3-6]86|amd64|x64|armv7|armeabi|arm32|bin-arm\.|-arm[-_.]", re.I)


def api(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json", "User-Agent": "deeppixel"})
    return json.load(urllib.request.urlopen(req, timeout=60))


def fetch(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "deeppixel"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def gh(*args):
    return subprocess.run(["gh", *args], check=False)


def list_assets():
    out = []
    for r in REPOS:
        page = 1
        while page < 8:
            try:
                rel = api(f"https://api.github.com/repos/{r}/releases?per_page=100&page={page}")
            except Exception as e:
                print("skip", r, e); break
            if not rel: break
            for x in rel:
                for a in x.get("assets", []):
                    out.append((r, x["tag_name"], a["name"], a["browser_download_url"]))
            page += 1
    return out


def sources(assets, n):
    """Possible sources for Java n, best first. Each is a list of urls that are unpacked into one folder."""
    found = []
    for r, t, name, url in assets:       # A: one archive with the version in its name
        if BAD.search(name) or not re.search(r"\.(zip|tar\.xz|tar\.gz|tgz)$", name, re.I): continue
        if re.search(r"(jre|jdk|java)[-_ ]?%d(?!\d)" % n, name, re.I):
            found.append((0 if re.search(r"arm64|aarch64", name, re.I) else 1, [url]))
    found.sort(key=lambda x: x[0])
    res = [u for _, u in found]
    for tag in sorted({t for _, t, _, _ in assets if re.match(r"jre-?%d(?!\d)" % n, t, re.I)}):   # B: universal + bin-arm64
        parts = [u for _, t, name, u in assets if t == tag and re.match(r"(universal|bin-arm64)\.tar\.xz$", name, re.I)]
        if len(parts) == 2: res.append(parts)
    return res


def unpack(path, dest):
    if path.lower().endswith(".zip"):
        zipfile.ZipFile(path).extractall(dest)
    else:
        with tarfile.open(path) as t:
            try: t.extractall(dest, filter="fully_trusted")
            except TypeError: t.extractall(dest)


def is_arm64(p):
    with open(p, "rb") as f: h = f.read(20)
    return h[:4] == b"\x7fELF" and int.from_bytes(h[18:20], "little") == 183


def find_root(dest):
    for dp, _, fn in os.walk(dest):
        if os.path.basename(dp) == "bin" and "java" in fn and is_arm64(os.path.join(dp, "java")):
            return os.path.dirname(dp)
    return None


def build(n, urls, work):
    d = tempfile.mkdtemp(dir=work); x = os.path.join(d, "x"); os.makedirs(x)
    for i, u in enumerate(urls):
        p = os.path.join(d, f"dl{i}" + (".zip" if u.lower().endswith(".zip") else ".tar.xz" if "xz" in u.lower() else ".tar.gz"))
        print("  downloading", u); fetch(u, p); unpack(p, x)
    root = find_root(x)
    if not root: return None
    pkg = os.path.join(work, f"jre{n}.tar.gz")
    def mode(ti): ti.mode = 0o755; return ti
    with tarfile.open(pkg, "w:gz") as t: t.add(root, arcname="jre", filter=mode)
    return pkg


def main():
    assets = list_assets()
    print(len(assets), "assets found in the candidate repositories:")
    for r, t, n, _ in assets[:300]: print("  ", r.split("/")[0], t, n)
    work = tempfile.mkdtemp(); built = []
    for n in WANTED:
        print(f"\n== Java {n} ==")
        pkg = None
        for urls in sources(assets, n):
            try: pkg = build(n, urls, work)
            except Exception as e: print("  failed:", e); pkg = None
            if pkg: break
        if not pkg and n == 21 and OWN:     # earlier release made by this repository
            try:
                p = os.path.join(work, "jre21.tar.gz"); fetch(f"https://github.com/{OWN}/releases/download/java21/jre.tar.gz", p); pkg = p
            except Exception as e: print("  no earlier java21 release:", e)
        print("  =>", "OK" if pkg else "NOT FOUND"); 
        if pkg: built.append((n, pkg))
    if not built: print("\nNo Java runtime could be built."); sys.exit(1)
    gh("release", "create", TAG, "--repo", OWN, "--title", "Java runtimes for DeepPixel", "--notes", "ARM64 Java runtimes (8, 17, 21, 25) for DeepPixel")
    for n, pkg in built: gh("release", "upload", TAG, pkg, "--repo", OWN, "--clobber")
    print("\nPublished:", ", ".join(f"Java {n}" for n, _ in built))
    missing = [n for n in WANTED if n not in [b[0] for b in built]]
    if missing: print("Not found (the app falls back to the next newer Java):", ", ".join(f"Java {n}" for n in missing))


if __name__ == "__main__":
    main()
