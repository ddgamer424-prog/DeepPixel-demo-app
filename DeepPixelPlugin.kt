package com.deeppixel.app
// Copyright (c) 2026 DDgamer. All rights reserved.

import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * Capacitor plugin: installs a Java runtime, downloads PaperMC, and runs servers as child processes.
 * Register in MainActivity:  registerPlugin(DeepPixelPlugin::class.java)
 */
@CapacitorPlugin(name = "DeepPixel")
class DeepPixelPlugin : Plugin() {

    // !! Must be an ARM64 JRE 21 built for Android (bionic libc). A normal Linux/glibc JRE will NOT run. See README.
    private val JRE_URL = "https://github.com/ddgamer424-prog/DeepPixel-demo-app/releases/download/java21/jre.tar.gz"

    private val urlFile get() = File(context.filesDir, "jre_url.txt")
    private fun jreUrl() = (if (urlFile.exists()) urlFile.readText().trim() else "").ifEmpty { JRE_URL }
    @PluginMethod fun getJreUrl(call: PluginCall) = call.resolve(JSObject().put("url", jreUrl()))
    @PluginMethod fun setJreUrl(call: PluginCall) { urlFile.writeText(call.getString("url") ?: ""); call.resolve() }

    private val procs = HashMap<String, Process>()
    private val root get() = File(context.filesDir, "servers").apply { mkdirs() }
    // ---- Java runtimes: 8, 17, 21 and 25 live side by side; the right one is picked per Minecraft version ----
    private val JRE_BASE = "https://github.com/ddgamer424-prog/DeepPixel-demo-app/releases/download/java-runtimes"
    private fun jreDir(v: Int): File { val old = File(context.filesDir, "jre"); return if (v == 21 && File(old, "bin/java").exists()) old else File(context.filesDir, "jre$v") }
    private fun javaBin(v: Int) = File(jreDir(v), "bin/java")
    private fun requiredJava(mc: String): Int {
        val p = mc.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = p.getOrElse(0) { 1 }; val b = p.getOrElse(1) { 0 }; val c = p.getOrElse(2) { 0 }
        return when { a >= 26 -> 25; a != 1 -> 21; b >= 21 -> 21; b == 20 && c >= 5 -> 21; b >= 17 -> 17; else -> 8 }
    }
    private fun javaFor(mc: String, pref: Int) = if (pref > 0) pref else requiredJava(mc)
    private fun javaEnv(pb: ProcessBuilder, v: Int): ProcessBuilder {   // Android doesn't follow the Java folder layout on its own
        val j = jreDir(v).path
        pb.environment()["LD_LIBRARY_PATH"] = listOf("lib/jli", "lib/server", "lib", "lib/aarch64/jli", "lib/aarch64/server", "lib/aarch64").joinToString(":") { "$j/$it" }
        pb.environment()["JAVA_HOME"] = j
        val shim = File(context.applicationInfo.nativeLibraryDir, "libtagfix.so")   // turns off Android heap pointer tagging
        if (shim.exists()) pb.environment()["LD_PRELOAD"] = shim.path
        return pb
    }
    private fun installJava(v: Int, from: Int, to: Int) {
        if (javaBin(v).exists()) return
        val dir = jreDir(v); val tgz = File(context.cacheDir, "jre$v.tgz")
        download("$JRE_BASE/jre$v.tar.gz", tgz, from, from + (to - from) * 85 / 100, "Downloading Java $v")
        progress(to - 3, "Unpacking Java $v…"); dir.deleteRecursively(); dir.mkdirs()
        val t = ProcessBuilder("tar", "xzf", tgz.path, "-C", dir.path, "--strip-components=1").redirectErrorStream(true).start()
        val tout = t.inputStream.bufferedReader().readText(); t.waitFor(); tgz.delete()
        require(javaBin(v).exists()) { "Java $v unpack failed: ${tout.take(300)}" }
        File(dir, "bin").listFiles()?.forEach { it.setExecutable(true) }
        progress(to - 1, "Checking Java $v…")
        val c = javaEnv(ProcessBuilder(javaBin(v).path, "-version"), v).redirectErrorStream(true).start()
        val out = c.inputStream.bufferedReader().readText(); c.waitFor()
        if (!out.contains("version")) { dir.deleteRecursively(); throw IllegalStateException("Java $v does not run on this phone: ${out.take(300)}") }
    }
    /** Installs the wanted Java if needed; if that one can't be downloaded, falls back to the next newer one. */
    private fun ensureJava(want: Int, from: Int, to: Int): Int {
        var last: Throwable? = null
        for (v in listOf(8, 17, 21, 25).filter { it >= want }) {
            try { installJava(v, from, to); return v } catch (e: Throwable) { last = e; if (!javaBin(v).exists()) jreDir(v).deleteRecursively() }
        }
        throw IllegalStateException("Could not get Java $want: ${last?.message}")
    }

    private fun emit(ev: String, o: JSONObject) = notifyListeners(ev, JSObject(o.toString()))
    @Volatile private var lastProg = JSONObject().put("pct", 0).put("text", "")
    private fun progress(p: Int, t: String) { lastProg = JSONObject().put("pct", p).put("text", t); emit("progress", lastProg) }
    @PluginMethod fun getProgress(call: PluginCall) = call.resolve(JSObject(lastProg.toString()))

    private fun download(url: String, out: File, from: Int, to: Int, label: String) {
        progress(from, "$label: connecting…")
        val c = URL(url).openConnection().apply { connectTimeout = 20000; readTimeout = 30000; setRequestProperty("User-Agent", "DeepPixel/1.0") }
        val total = c.contentLengthLong
        c.getInputStream().use { i -> out.outputStream().use { o ->
            val buf = ByteArray(65536); var done = 0L; var n: Int; var last = 0L
            while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); done += n
                if (System.currentTimeMillis() - last > 300) { last = System.currentTimeMillis()
                    progress(if (total > 0) from + ((to - from) * done / total).toInt() else from, "$label: ${done / 1048576} MB") } } } }
    }

    private fun paperDownload(ver: String, out: File, from: Int, to: Int) {
        val url = try {   // PaperMC's current downloads service first, old API only as a fallback
            val b = JSONArray(get("https://fill.papermc.io/v3/projects/paper/versions/$ver/builds"))
            var u: String? = null
            for (i in 0 until b.length()) { val o = b.getJSONObject(i)
                if (o.optString("channel") == "STABLE") { u = o.getJSONObject("downloads").getJSONObject("server:default").getString("url"); break } }
            u ?: b.getJSONObject(0).getJSONObject("downloads").getJSONObject("server:default").getString("url")
        } catch (e: Exception) {
            val builds = JSONObject(get("https://api.papermc.io/v2/projects/paper/versions/$ver/builds")).getJSONArray("builds")
            val b = builds.getJSONObject(builds.length() - 1).getInt("build")
            "https://api.papermc.io/v2/projects/paper/versions/$ver/builds/$b/downloads/paper-$ver-$b.jar"
        }
        download(url, out, from, to, "Downloading Paper $ver")
    }

    private val MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private fun vanillaDownload(ver: String, out: File, from: Int, to: Int) {
        val vs = JSONObject(get(MOJANG)).getJSONArray("versions")
        var pkg: String? = null
        for (i in 0 until vs.length()) { val v = vs.getJSONObject(i); if (v.getString("id") == ver) { pkg = v.getString("url"); break } }
        require(pkg != null) { "Minecraft $ver was not found" }
        val dl = JSONObject(get(pkg)).optJSONObject("downloads")?.optJSONObject("server")
        require(dl != null) { "Minecraft $ver has no server download" }
        download(dl.getString("url"), out, from, to, "Downloading Minecraft $ver")
    }
    private fun serverJar(software: String, ver: String, dir: File, from: Int, to: Int) {
        if (software == "vanilla") vanillaDownload(ver, File(dir, "server.jar"), from, to) else paperDownload(ver, File(dir, "paper.jar"), from, to)
    }
    /** Version list for the chosen server software. Vanilla has every release (and snapshots if asked); Paper has what PaperMC builds. */
    @PluginMethod fun mcVersions(call: PluginCall) = bg(call) {
        if ((call.getString("software") ?: "paper") == "vanilla") {
            val snaps = call.getBoolean("snapshots") == true
            val vs = JSONObject(get(MOJANG)).getJSONArray("versions"); val a = JSONArray()
            for (i in 0 until vs.length()) { val v = vs.getJSONObject(i); val t = v.getString("type"); if (t == "release" || (snaps && t == "snapshot")) a.put(v.getString("id")) }
            call.resolve(JSObject().put("versions", a))
        } else paperVersions(call)
    }

    @PluginMethod fun createServer(call: PluginCall) {
        val name = call.getString("name")!!; val ver = call.getString("version")!!; val ram = call.getInt("ramMb") ?: 1024; val sw = call.getString("software") ?: "paper"
        if (!Regex("\\w+").matches(name)) return call.reject("Invalid name")
        thread {
            try {
                require(!File(File(root, name), "meta.json").exists()) { "A server named $name already exists" }
                ensureJava(requiredJava(ver), 0, 45)
                val loc = call.getString("location") ?: ""
                val link = File(root, name)
                val dir = if (loc.isNotEmpty()) {   // server files live in a normal phone folder; the app links to it
                    val target = File(loc, name).apply { mkdirs() }
                    require(target.isDirectory && target.canWrite()) { "Can't write to $loc. Allow storage access first." }
                    if (java.nio.file.Files.isSymbolicLink(link.toPath())) java.nio.file.Files.delete(link.toPath()) else link.deleteRecursively()
                    java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()); link
                } else link.apply { mkdirs() }
                serverJar(sw, ver, dir, 50, 100)
                File(dir, "eula.txt").writeText("eula=true\n")   // the user accepts Mojang's EULA in the UI before this
                File(dir, "server.properties").writeText("motd=$name\nmax-players=10\nview-distance=6\nserver-port=${25565 + (root.listFiles()?.count { File(it, "meta.json").exists() } ?: 0)}\n")
                File(dir, "meta.json").writeText(JSONObject().put("name", name).put("version", ver).put("software", sw).put("ramMb", ram).toString())
                call.resolve()
            } catch (e: Throwable) { call.reject(e.message ?: e.toString()) }
        }
    }

    @PluginMethod fun listServers(call: PluginCall) {
        val arr = JSONArray()
        root.listFiles()?.forEach { d -> File(d, "meta.json").takeIf { it.exists() }?.let {
            arr.put(JSONObject(it.readText()).put("dir", d.canonicalPath).put("state", if (procs[d.name]?.isAlive == true) "running" else "stopped")) } }
        call.resolve(JSObject().put("servers", arr))
    }

    @PluginMethod fun start(call: PluginCall) = bg(call) {
        val name = call.getString("name")!!; val dir = File(root, name)
        require(procs[name]?.isAlive != true) { "Server is already running" }
        val m = JSONObject(File(dir, "meta.json").readText()); val ram = m.getInt("ramMb")
        val mc = m.optString("version", "1.21.4"); val sw = m.optString("software", "paper"); val want = javaFor(mc, m.optInt("java", 0))
        fun say(t: String) = emit("console", JSONObject().put("name", name).put("line", "[DeepPixel] $t"))
        var jv = want
        try {
            if (!javaBin(want).exists()) { emit("state", JSONObject().put("name", name).put("state", "starting")); say("Installing Java $want (first time only)…") }
            jv = ensureJava(want, 0, 100)
            if (jv != want) say("Java $want was not available, using Java $jv instead.")
        } catch (e: Throwable) { say("Could not start: ${e.message}"); emit("state", JSONObject().put("name", name).put("state", "stopped")); throw e }
        val args = mutableListOf(javaBin(jv).path, "-Xmx${ram}M", "-Xms${ram / 2}M")
        if (m.optInt("cores", 0) > 0) args.add("-XX:ActiveProcessorCount=${m.getInt("cores")}")
        if (m.optBoolean("optimize", true)) args.addAll(listOf("-XX:+UseSerialGC", "-XX:+DisableExplicitGC"))
        File(dir, "tmp").mkdirs()
        args.addAll(listOf("-Djava.io.tmpdir=${File(dir, "tmp").path}", "-Duser.home=${dir.path}", "-Djava.net.preferIPv4Stack=true"))
        if (sw == "paper" && jv >= 17) {   // small launcher that reports a normal Java version to Paper
            val launcher = File(context.filesDir, "deeppixel-launcher.jar")
            context.assets.open("deeppixel-launcher.jar").use { i -> launcher.outputStream().use { o -> i.copyTo(o) } }
            args.addAll(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-DPaper.IgnoreJavaVersion=true", "-cp", launcher.path, "com.deeppixel.Launcher", File(dir, "paper.jar").path, "nogui"))
        } else args.addAll(listOf("-jar", if (sw == "vanilla") "server.jar" else "paper.jar", "nogui"))
        say("Starting Minecraft $mc with Java $jv")
        val p = javaEnv(ProcessBuilder(args), jv).directory(dir).redirectErrorStream(true).start()
        ServerService.start(context)
        procs[name] = p; emit("state", JSONObject().put("name", name).put("state", "running"))
        thread { p.inputStream.bufferedReader().forEachLine { trackPlayers(name, it); emit("console", JSONObject().put("name", name).put("line", it)) }
            val code = try { p.waitFor() } catch (e: Exception) { -1 }
            val why = when (code) { 0 -> "stopped normally"; 137 -> "was killed by Android (memory or battery limit)"; 134, 135, 139 -> "crashed (Java native error)"; else -> "ended unexpectedly" }
            say("Server $why (exit code $code)")
            players.remove(name); emit("state", JSONObject().put("name", name).put("state", "stopped"))
            if (procs.values.none { it.isAlive }) ServerService.stop(context) }
        call.resolve()
    }

    @PluginMethod fun command(call: PluginCall) {
        procs[call.getString("name")]?.outputStream?.apply { write((call.getString("cmd") + "\n").toByteArray()); flush() }; call.resolve()
    }
    @PluginMethod fun stop(call: PluginCall) {
        procs[call.getString("name")]?.outputStream?.apply { write("stop\n".toByteArray()); flush() }; call.resolve() }

    @PluginMethod fun kill(call: PluginCall) { procs[nm(call)]?.destroyForcibly(); call.resolve() }   // force stop a frozen server

    @PluginMethod fun getProps(call: PluginCall) =
        call.resolve(JSObject().put("text", File(root, call.getString("name")!! + "/server.properties").readText()))
    @PluginMethod fun saveProps(call: PluginCall) {
        File(root, call.getString("name")!! + "/server.properties").writeText(call.getString("text")!!); call.resolve() }
    @PluginMethod fun deleteServer(call: PluginCall) {
        val n = call.getString("name")!!; procs.remove(n)?.destroy(); val f = File(root, n)
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.canonicalFile.deleteRecursively(); java.nio.file.Files.deleteIfExists(f.toPath()) } else f.deleteRecursively()
        call.resolve() }

    // ================= phone storage =================
    private fun hasStorage(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    @PluginMethod fun getStorageAccess(call: PluginCall) = call.resolve(JSObject().put("granted", hasStorage())
        .put("defaultDir", android.os.Environment.getExternalStorageDirectory().path + "/DeepPixel"))
    @PluginMethod fun requestStorageAccess(call: PluginCall) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (e: Exception) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        call.resolve() }
    @PluginMethod fun browseDirs(call: PluginCall) {
        try {
            val base = android.os.Environment.getExternalStorageDirectory().canonicalFile
            var f = File(call.getString("path").let { if (it.isNullOrEmpty()) base.path else it }).canonicalFile
            while (!f.isDirectory && f.parentFile != null) f = f.parentFile!!
            require(f.path.startsWith(base.path)) { "Pick a folder inside your phone storage" }
            val a = JSONArray(); f.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }?.forEach { a.put(it.name) }
            call.resolve(JSObject().put("path", f.path).put("parent", if (f.path == base.path) "" else (f.parent ?: "")).put("dirs", a))
        } catch (e: Throwable) { call.reject(e.message ?: e.toString()) } }
    @PluginMethod fun makeDir(call: PluginCall) { File(call.getString("path")!!).mkdirs(); call.resolve() }

    // ================= helpers =================
    private val players = HashMap<String, MutableSet<String>>()
    private fun nm(c: PluginCall) = c.getString("name")!!
    private fun bg(call: PluginCall, body: () -> Unit) { thread { try { body() } catch (e: Throwable) { call.reject(e.message ?: e.toString()) } } }
    private fun get(u: String): String { val c = URL(u).openConnection(); c.connectTimeout = 20000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", "DeepPixel/1.0"); return c.getInputStream().bufferedReader().readText() }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun safe(n: String, rel: String): File {           // blocks ../ escapes
        val base = File(root, n).canonicalFile; val f = File(base, rel).canonicalFile
        require(f.path.startsWith(base.path)) { "Path is outside the server folder" }; return f }
    private fun trackPlayers(n: String, l: String) {
        Regex("(\\w+) (joined|left) the game").find(l)?.let { m ->
            val s = players.getOrPut(n) { mutableSetOf() }
            if (m.groupValues[2] == "joined") s.add(m.groupValues[1]) else s.remove(m.groupValues[1])
            emit("players", JSONObject().put("name", n).put("list", JSONArray(s.toList()))) } }
    private fun unzip(zip: File, dest: File) { java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
        var e = z.nextEntry; while (e != null) { val f = File(dest, e.name).canonicalFile
            require(f.path.startsWith(dest.canonicalPath)) { "Bad zip entry" }
            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }; e = z.nextEntry } } }
    private fun zip(srcs: List<File>, base: File, out: File) { java.util.zip.ZipOutputStream(out.outputStream()).use { z ->
        srcs.forEach { s -> s.walkTopDown().filter { it.isFile }.forEach { f ->
            z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } } } }
    private fun pid(p: Process): Int = try { (Process::class.java.getMethod("pid").invoke(p) as Long).toInt() }
        catch (e: Exception) { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }
    private fun props(n: String) = File(root, "$n/server.properties")
    private fun bdir(n: String): File {
        val real = File(root, n).canonicalFile
        val d = if (real.path.startsWith(context.filesDir.canonicalPath)) File(context.filesDir, "backups/$n") else File(real.parentFile, "_backups/$n")
        return d.apply { mkdirs() }
    }
    @PluginMethod fun getBackupPath(call: PluginCall) = call.resolve(JSObject().put("path", bdir(nm(call)).path))

    // ================= plugin manager (Modrinth) =================
    @PluginMethod fun pluginSearch(call: PluginCall) = bg(call) {
        val cat = call.getString("category") ?: ""; val q = call.getString("query") ?: ""
        val facets = StringBuilder("[[\"categories:paper\"],[\"versions:${call.getString("version")}\"],[\"project_type:mod\"]")
        if (cat.isNotEmpty()) facets.append(",[\"categories:$cat\"]"); facets.append("]")
        val idx = if (q.isEmpty()) "downloads" else "relevance"
        val hits = JSONObject(get("https://api.modrinth.com/v2/search?limit=20&offset=${call.getInt("offset") ?: 0}&index=$idx&query=${enc(q)}&facets=${enc(facets.toString())}")).getJSONArray("hits")
        call.resolve(JSObject().put("hits", hits)) }
    @PluginMethod fun pluginInstall(call: PluginCall) = bg(call) {
        val v = JSONArray(get("https://api.modrinth.com/v2/project/${call.getString("id")}/version?loaders=${enc("[\"paper\"]")}&game_versions=${enc("[\"${call.getString("version")}\"]")}"))
        require(v.length() > 0) { "No release for this Minecraft version" }
        val file = v.getJSONObject(0).getJSONArray("files").getJSONObject(0)
        val dir = File(root, nm(call) + "/plugins").apply { mkdirs() }
        download(file.getString("url"), File(dir, file.getString("filename")), 0, 100, "Installing ${file.getString("filename")}"); call.resolve() }
    @PluginMethod fun pluginsList(call: PluginCall) {
        val a = JSONArray(); File(root, nm(call) + "/plugins").listFiles()?.filter { it.name.contains(".jar") }?.sortedBy { it.name }
            ?.forEach { a.put(JSONObject().put("file", it.name).put("enabled", it.name.endsWith(".jar"))) }
        call.resolve(JSObject().put("plugins", a)) }
    @PluginMethod fun pluginToggle(call: PluginCall) {
        val f = safe(nm(call), "plugins/" + call.getString("file"))
        f.renameTo(File(f.path.let { if (it.endsWith(".disabled")) it.removeSuffix(".disabled") else "$it.disabled" })); call.resolve() }
    @PluginMethod fun pluginDelete(call: PluginCall) { safe(nm(call), "plugins/" + call.getString("file")).delete(); call.resolve() }

    // ================= file manager =================
    @PluginMethod fun fmList(call: PluginCall) {
        val a = JSONArray(); safe(nm(call), call.getString("path") ?: "").listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { a.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length())) }
        call.resolve(JSObject().put("entries", a)) }
    @PluginMethod fun fmRead(call: PluginCall) {
        val f = safe(nm(call), call.getString("path")!!); require(f.length() < 512_000) { "File is too large to edit here" }
        call.resolve(JSObject().put("text", f.readText())) }
    @PluginMethod fun fmTail(call: PluginCall) {
        val f = safe(nm(call), call.getString("path")!!)
        if (!f.exists()) { call.resolve(JSObject().put("text", "")); return }
        val n = (call.getInt("bytes") ?: 60000).toLong()
        java.io.RandomAccessFile(f, "r").use { r ->
            val start = maxOf(0L, r.length() - n); r.seek(start)
            val b = ByteArray((r.length() - start).toInt()); r.readFully(b)
            var t = String(b, Charsets.UTF_8); if (start > 0) t = t.substringAfter('\n', t)
            call.resolve(JSObject().put("text", t)) } }
    @PluginMethod fun fmWrite(call: PluginCall) { safe(nm(call), call.getString("path")!!).writeText(call.getString("text")!!); call.resolve() }
    @PluginMethod fun fmDelete(call: PluginCall) { safe(nm(call), call.getString("path")!!).deleteRecursively(); call.resolve() }
    @PluginMethod fun fmRename(call: PluginCall) { val f = safe(nm(call), call.getString("path")!!); f.renameTo(File(f.parentFile, call.getString("to")!!)); call.resolve() }
    @PluginMethod fun fmUpload(call: PluginCall) {
        val f = safe(nm(call), call.getString("path")!!); f.parentFile?.mkdirs()
        f.writeBytes(android.util.Base64.decode(call.getString("data"), android.util.Base64.DEFAULT)); call.resolve() }
    @PluginMethod fun fmExtract(call: PluginCall) = bg(call) {
        unzip(safe(nm(call), call.getString("path")!!), safe(nm(call), call.getString("dest") ?: "")); call.resolve() }

    // ================= worlds =================
    @PluginMethod fun worldList(call: PluginCall) {
        val f = props(nm(call))
        val active = (if (f.exists()) f.readLines().firstOrNull { it.startsWith("level-name=") }?.substringAfter("=")?.trim() else null) ?: "world"
        val a = JSONArray()
        File(root, nm(call)).listFiles()?.sortedBy { it.name.lowercase() }?.forEach { d ->
            if (!d.isDirectory) return@forEach
            val kind = when {
                File(d, "level.dat").exists() || File(d, "region").exists() -> "world"
                File(d, "DIM-1").exists() -> "nether"
                File(d, "DIM1").exists() -> "end"
                else -> null
            }
            if (kind != null) a.put(JSONObject().put("name", d.name).put("active", d.name == active).put("kind", kind).put("modified", d.lastModified()))
        }
        call.resolve(JSObject().put("worlds", a)) }
    @PluginMethod fun worldSetActive(call: PluginCall) {
        val f = props(nm(call)); val w = call.getString("world")!!
        val lines = f.readLines().filter { !it.startsWith("level-name=") } + "level-name=$w"; f.writeText(lines.joinToString("\n") + "\n"); call.resolve() }
    @PluginMethod fun worldDelete(call: PluginCall) { safe(nm(call), call.getString("world")!!).deleteRecursively(); call.resolve() }
    @PluginMethod fun worldImport(call: PluginCall) = bg(call) {
        val n = nm(call); val d = safe(n, call.getString("world")!!); val z = safe(n, call.getString("zip")!!)
        d.deleteRecursively(); d.mkdirs(); unzip(z, d); z.delete()
        val kids = d.listFiles()!!   // zips often wrap the world in one extra folder
        if (!File(d, "level.dat").exists() && kids.size == 1 && kids[0].isDirectory) { kids[0].listFiles()!!.forEach { it.renameTo(File(d, it.name)) }; kids[0].delete() }
        require(File(d, "level.dat").exists()) { "No level.dat found: this is not a Minecraft world" }; call.resolve() }

    // ================= backups =================
    @PluginMethod fun backupCreate(call: PluginCall) = bg(call) {
        val n = nm(call); val dir = File(root, n); if (procs[n]?.isAlive == true) { procs[n]!!.outputStream.apply { write("save-all\n".toByteArray()); flush() }; Thread.sleep(3000) }
        val src = if (call.getBoolean("full") == true) dir.listFiles()!!.toList() else dir.listFiles()!!.filter { File(it, "level.dat").exists() || it.name.startsWith("world") }
        progress(10, "Backing up…"); zip(src, dir, File(bdir(n), (if (call.getBoolean("full") == true) "full-" else "worlds-") + System.currentTimeMillis() / 1000 + ".zip"))
        progress(100, "Backup complete"); call.resolve() }
    @PluginMethod fun backupsList(call: PluginCall) {
        val a = JSONArray(); bdir(nm(call)).listFiles()?.sortedByDescending { it.name }?.forEach { a.put(JSONObject().put("file", it.name).put("size", it.length())) }
        call.resolve(JSObject().put("backups", a)) }
    @PluginMethod fun backupRestore(call: PluginCall) = bg(call) {
        val n = nm(call); require(procs[n]?.isAlive != true) { "Stop the server before restoring" }
        unzip(File(bdir(n), call.getString("file")!!), File(root, n)); call.resolve() }
    @PluginMethod fun backupDelete(call: PluginCall) { File(bdir(nm(call)), call.getString("file")!!).delete(); call.resolve() }

    // ================= live stats, settings, version, players =================
    private val cpuLast = HashMap<String, Pair<Long, Long>>()
    @PluginMethod fun getStats(call: PluginCall) {
        val n = nm(call); val p = procs[n]; fun kb(f: String, k: String) = Regex("\\d+").find(File(f).readLines().first { it.startsWith(k) })!!.value.toLong() / 1024
        val cores = Runtime.getRuntime().availableProcessors(); var cpu = 0.0
        if (p?.isAlive == true) {
            val f = File("/proc/${pid(p)}/stat").readText().substringAfterLast(')').trim().split(' ')
            val ticks = f[11].toLong() + f[12].toLong(); val now = System.currentTimeMillis(); val prev = cpuLast[n]
            if (prev != null && now > prev.second) cpu = (ticks - prev.first) * 10.0 / (now - prev.second) * 100.0 / cores
            cpuLast[n] = ticks to now
        } else cpuLast.remove(n)
        val maxMb = try { JSONObject(File(root, "$n/meta.json").readText()).optInt("ramMb", 1024) } catch (e: Exception) { 1024 }
        call.resolve(JSObject().put("usedMb", if (p?.isAlive == true) kb("/proc/${pid(p)}/status", "VmRSS") else 0).put("maxMb", maxMb)
            .put("availMb", kb("/proc/meminfo", "MemAvailable")).put("cores", cores).put("cpu", Math.round(cpu * 10) / 10.0)) }
    @PluginMethod fun getJavaInfo(call: PluginCall) {
        val m = JSONObject(File(root, nm(call) + "/meta.json").readText()); val mc = m.optString("version"); val pref = m.optInt("java", 0)
        val inst = JSONArray(); listOf(8, 17, 21, 25).forEach { if (javaBin(it).exists()) inst.put(it) }
        call.resolve(JSObject().put("mc", mc).put("required", requiredJava(mc)).put("pref", pref).put("active", javaFor(mc, pref)).put("installed", inst)) }
    @PluginMethod fun paperVersions(call: PluginCall) = bg(call) {
        val all = mutableListOf<String>()
        try { val o = JSONObject(get("https://fill.papermc.io/v3/projects/paper")).getJSONObject("versions")
            for (k in o.keys()) { val arr = o.getJSONArray(k); for (i in 0 until arr.length()) all.add(arr.getString(i)) }
        } catch (e: Exception) { val arr = JSONObject(get("https://api.papermc.io/v2/projects/paper")).getJSONArray("versions"); for (i in 0 until arr.length()) all.add(arr.getString(i)) }
        fun key(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val sorted = all.filter { Regex("\\d+(\\.\\d+)+").matches(it) }.distinct().sortedWith(Comparator { x, y ->
            val a = key(x); val b = key(y)
            for (i in 0 until maxOf(a.size, b.size)) { val c = b.getOrElse(i) { 0 }.compareTo(a.getOrElse(i) { 0 }); if (c != 0) return@Comparator c }
            0 })
        call.resolve(JSObject().put("versions", JSONArray(sorted))) }
    @PluginMethod fun getConfig(call: PluginCall) = call.resolve(JSObject(File(root, nm(call) + "/meta.json").readText()))
    @PluginMethod fun saveConfig(call: PluginCall) {
        val f = File(root, nm(call) + "/meta.json"); val m = JSONObject(f.readText())
        listOf("ramMb", "cores", "java").forEach { k -> call.getInt(k)?.let { m.put(k, it) } }; call.getBoolean("optimize")?.let { m.put("optimize", it) }
        f.writeText(m.toString()); call.resolve() }
    @PluginMethod fun changeVersion(call: PluginCall) = bg(call) {   // change version, switch Paper/Vanilla, or reinstall the jar
        val n = nm(call); require(procs[n]?.isAlive != true) { "Stop the server first" }; val ver = call.getString("version")!!
        val f = File(root, "$n/meta.json"); val m = JSONObject(f.readText()); val sw = call.getString("software") ?: m.optString("software", "paper")
        serverJar(sw, ver, File(root, n), 0, 100)
        f.writeText(m.put("version", ver).put("software", sw).toString()); call.resolve() }
    @PluginMethod fun playersList(call: PluginCall) = call.resolve(JSObject().put("list", JSONArray((players[nm(call)] ?: setOf<String>()).toList())))

    @PluginMethod fun getNetworkInfo(call: PluginCall) {
        val ips = JSONArray()
        try { java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).forEach { ni ->
            if (ni.isUp && !ni.isLoopback) java.util.Collections.list(ni.inetAddresses).filterIsInstance<java.net.Inet4Address>()
                .forEach { a -> ips.put(JSONObject().put("iface", ni.name).put("ip", a.hostAddress)) } } } catch (e: Exception) { }
        val f = props(nm(call))
        val port = (if (f.exists()) f.readLines().firstOrNull { it.startsWith("server-port=") }?.substringAfter("=")?.trim()?.toIntOrNull() else null) ?: 25565
        call.resolve(JSObject().put("ips", ips).put("port", port)) }

    // ================= public tunnel (playit.gg agent) =================
    private var tunnel: Process? = null
    @PluginMethod fun tunnelStart(call: PluginCall) = bg(call) {
        val bin = File(context.filesDir, "playit")
        if (!bin.exists()) { download("https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64", bin, 0, 100, "Downloading tunnel agent…"); bin.setExecutable(true) }
        tunnel?.destroy(); tunnel = ProcessBuilder(bin.path).directory(context.filesDir).redirectErrorStream(true).start()
        thread { tunnel!!.inputStream.bufferedReader().forEachLine { emit("tunnel", JSONObject().put("line", it)) } }; call.resolve() }
    @PluginMethod fun tunnelStop(call: PluginCall) { tunnel?.destroy(); call.resolve() }

    @PluginMethod fun openUrl(call: PluginCall) {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(call.getString("url")!!)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() }

    // ================= background / battery =================
    @PluginMethod fun requestBatteryOptimization(call: PluginCall) {
        val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i); call.resolve() }
}
