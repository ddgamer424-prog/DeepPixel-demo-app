# Copyright (c) 2026 DDgamer. All rights reserved.
# Builds the Java runtimes DeepPixel downloads (Java 17, 21, 25 for Android ARM64) and publishes them
# as assets of the "java-runtimes" release in this repository. Run it with the "Prepare Java" workflow.
import gzip, json, lzma, os, re, shutil, subprocess, sys, tarfile, tempfile, urllib.request, zipfile

TOKEN = os.environ.get("GH_TOKEN", "")
OWN = os.environ.get("GITHUB_REPOSITORY", "")
REPOS = ["QuestCraftPlusPlus/android-openjdk-build-multiarch",
         "PojavLauncherTeam/android-openjdk-build-multiarch",
         "AngelAuraMC/android-openjdk-build-multiarch"]
WANTED = [17, 21, 25]
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


def is_android(p):
    """True only for Java made for Android (uses /system/bin/linker64). Normal Linux Java (glibc) can't run on a phone."""
    with open(p, "rb") as f: return b"/system/bin/linker64" in f.read(8192)


def find_root(dest):
    for dp, _, fn in os.walk(dest):
        j = os.path.join(dp, "java")
        if os.path.basename(dp) == "bin" and "java" in fn and os.path.isfile(j) and is_arm64(j):
            if is_android(j): return os.path.dirname(dp)
            print("  skipped a normal Linux (glibc) Java: it cannot run on Android")
    return None


# ---- Termux: Java made for Android, with its libraries ----
TERMUX = "https://packages.termux.dev/apt/termux-main"
SKIP = {"termux-tools", "termux-exec", "termux-core", "termux-keyring", "termux-licenses", "apt", "dpkg", "bash", "coreutils", "busybox",
        "gawk", "sed", "grep", "findutils", "util-linux", "ncurses", "readline", "command-not-found", "debianutils", "diffutils", "less", "procps"}


def termux_index():
    for name in ("Packages", "Packages.xz", "Packages.gz"):
        try:
            raw = urllib.request.urlopen(urllib.request.Request(f"{TERMUX}/dists/stable/main/binary-aarch64/{name}", headers={"User-Agent": "deeppixel"}), timeout=120).read()
            raw = lzma.decompress(raw) if name.endswith("xz") else gzip.decompress(raw) if name.endswith("gz") else raw
            break
        except Exception as e: print("  index", name, "failed:", e); raw = None
    if raw is None: return {}
    idx = {}
    for block in raw.decode("utf-8", "replace").split("\n\n"):
        f = dict(re.findall(r"^([A-Za-z-]+): (.*)$", block, re.M))
        if "Package" in f and "Filename" in f: idx[f["Package"]] = f
    return idx


def termux_closure(idx, root):
    seen, order, stack = set(), [], [root]
    while stack:
        n = stack.pop()
        if n in seen or n in SKIP or n not in idx: continue
        seen.add(n); order.append(n)
        for d in idx[n].get("Depends", "").split(","):
            alt = re.sub(r"\s*\(.*?\)", "", d.split("|")[0]).strip()
            if alt: stack.append(alt)
    return order


def termux_build(n, work, idx):
    pkgs = termux_closure(idx, f"openjdk-{n}")
    if not pkgs: return None
    print("  Termux packages:", ", ".join(pkgs))
    d = tempfile.mkdtemp(dir=work); x = os.path.join(d, "x"); os.makedirs(x)
    for name in pkgs:
        deb = os.path.join(d, name + ".deb"); fetch(f"{TERMUX}/{idx[name]['Filename']}", deb)
        subprocess.run(["dpkg-deb", "-x", deb, x], check=True)
    usr = os.path.join(x, "data/data/com.termux/files/usr")
    homes = [os.path.dirname(os.path.dirname(os.path.join(dp, "java"))) for dp, _, fn in os.walk(os.path.join(usr, "lib/jvm")) if os.path.basename(dp) == "bin" and "java" in fn]
    if not homes: return None
    tree = os.path.join(d, "tree"); shutil.copytree(homes[0], tree, symlinks=True)
    deps = os.path.join(tree, "libdeps"); os.makedirs(deps)
    for f in os.listdir(os.path.join(usr, "lib")):       # libraries the Java files need
        src = os.path.join(usr, "lib", f)
        if ".so" in f and os.path.isfile(src): shutil.copy2(src, os.path.join(deps, f))
    cac = os.path.join(tree, "lib/security/cacerts")      # certificates for https downloads
    if not os.path.isfile(cac):
        found = [os.path.join(dp, "cacerts") for dp, _, fn in os.walk(x) if "cacerts" in fn and os.path.isfile(os.path.join(dp, "cacerts"))]
        if found:
            if os.path.lexists(cac): os.remove(cac)
            shutil.copy2(found[0], cac)
    for junk in ("jmods", "man", "include", "demo", "sample", "lib/src.zip"):
        shutil.rmtree(os.path.join(tree, junk), ignore_errors=True)
        if os.path.isfile(os.path.join(tree, junk)): os.remove(os.path.join(tree, junk))
    j = os.path.join(tree, "bin/java")
    if not (os.path.isfile(j) and is_arm64(j) and is_android(j)): return None
    pkg = os.path.join(work, f"jre{n}.tar.gz")
    def mode(ti): ti.mode = 0o755; return ti
    with tarfile.open(pkg, "w:gz") as t: t.add(tree, arcname="jre", filter=mode)
    return pkg


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
        if not pkg:
            print("  trying Java for Android from Termux...")
            try:
                if "_tidx" not in globals(): globals()["_tidx"] = termux_index()
                pkg = termux_build(n, work, globals()["_tidx"])
            except Exception as e: print("  Termux failed:", e); pkg = None
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
