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
    private val JRE_URL = "https://YOUR-HOST/jre21-android-aarch64.tar.gz"

    private val urlFile get() = File(context.filesDir, "jre_url.txt")
    private fun jreUrl() = (if (urlFile.exists()) urlFile.readText().trim() else "").ifEmpty { JRE_URL }
    @PluginMethod fun getJreUrl(call: PluginCall) = call.resolve(JSObject().put("url", jreUrl()))
    @PluginMethod fun setJreUrl(call: PluginCall) { urlFile.writeText(call.getString("url") ?: ""); call.resolve() }

    private val procs = HashMap<String, Process>()
    private val root get() = File(context.filesDir, "servers").apply { mkdirs() }
    private val jre get() = File(context.filesDir, "jre")
    private fun java() = File(jre, "bin/java")

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

    @PluginMethod fun createServer(call: PluginCall) {
        val name = call.getString("name")!!; val ver = call.getString("version")!!; val ram = call.getInt("ramMb") ?: 1024
        if (!Regex("\\w+").matches(name)) return call.reject("Invalid name")
        thread {
            try {
                if (!java().exists()) {
                    val tgz = File(context.cacheDir, "jre.tgz"); val url = jreUrl(); require(url.startsWith("http") && !url.contains("YOUR-HOST")) { "Set the Java runtime link first (Create tab)" }
                    download(url, tgz, 0, 45, "Downloading Java")
                    progress(46, "Unpacking Java…"); jre.deleteRecursively(); jre.mkdirs()
                    val t = ProcessBuilder("tar", "xzf", tgz.path, "-C", jre.path, "--strip-components=1").redirectErrorStream(true).start()
                    val tout = t.inputStream.bufferedReader().readText(); t.waitFor(); tgz.delete()
                    require(java().exists()) { "Java unpack failed: ${tout.take(300)}" }
                    File(jre, "bin").listFiles()?.forEach { it.setExecutable(true) }
                    progress(48, "Checking Java…")
                    val v = ProcessBuilder(java().path, "-version").redirectErrorStream(true).start()
                    val vout = v.inputStream.bufferedReader().readText(); v.waitFor()
                    if (!vout.contains("version")) { jre.deleteRecursively(); throw IllegalStateException("Java does not run on this phone: ${vout.take(300)}") }
                }
                val dir = File(root, name).apply { mkdirs() }
                paperDownload(ver, File(dir, "paper.jar"), 50, 100)
                File(dir, "eula.txt").writeText("eula=true\n")   // the user accepts Mojang's EULA in the UI before this
                File(dir, "server.properties").writeText("motd=$name\nmax-players=10\nview-distance=6\n")
                File(dir, "meta.json").writeText(JSONObject().put("name", name).put("version", ver).put("ramMb", ram).toString())
                call.resolve()
            } catch (e: Throwable) { call.reject(e.message ?: e.toString()) }
        }
    }

    @PluginMethod fun listServers(call: PluginCall) {
        val arr = JSONArray()
        root.listFiles()?.forEach { d -> File(d, "meta.json").takeIf { it.exists() }?.let {
            arr.put(JSONObject(it.readText()).put("state", if (procs[d.name]?.isAlive == true) "running" else "stopped")) } }
        call.resolve(JSObject().put("servers", arr))
    }

    @PluginMethod fun start(call: PluginCall) {
        val name = call.getString("name")!!; val dir = File(root, name)
        val m = JSONObject(File(dir, "meta.json").readText()); val ram = m.getInt("ramMb")
        val args = mutableListOf(java().path, "-Xmx${ram}M", "-Xms${ram / 2}M")
        if (m.optInt("cores", 0) > 0) args.add("-XX:ActiveProcessorCount=${m.getInt("cores")}")
        if (m.optBoolean("optimize", true)) args.addAll(listOf("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-XX:+DisableExplicitGC", "-XX:+ParallelRefProcEnabled"))
        args.addAll(listOf("-jar", "paper.jar", "nogui"))
        val p = ProcessBuilder(args).directory(dir).redirectErrorStream(true).start()
        ServerService.start(context)
        procs[name] = p; emit("state", JSONObject().put("name", name).put("state", "running"))
        thread { p.inputStream.bufferedReader().forEachLine { trackPlayers(name, it); emit("console", JSONObject().put("name", name).put("line", it)) }
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
        val n = call.getString("name")!!; procs.remove(n)?.destroy(); File(root, n).deleteRecursively(); call.resolve() }

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
    private fun bdir(n: String) = File(context.filesDir, "backups/$n").apply { mkdirs() }

    // ================= plugin manager (Modrinth) =================
    @PluginMethod fun pluginSearch(call: PluginCall) = bg(call) {
        val f = enc("[[\"categories:paper\"],[\"versions:${call.getString("version")}\"],[\"project_type:mod\"]]")
        val hits = JSONObject(get("https://api.modrinth.com/v2/search?limit=20&query=${enc(call.getString("query") ?: "")}&facets=$f")).getJSONArray("hits")
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
        val active = props(nm(call)).readLines().firstOrNull { it.startsWith("level-name=") }?.substringAfter("=") ?: "world"
        val a = JSONArray(); File(root, nm(call)).listFiles()?.filter { File(it, "level.dat").exists() }
            ?.forEach { a.put(JSONObject().put("name", it.name).put("active", it.name == active)) }
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
    @PluginMethod fun getStats(call: PluginCall) {
        val p = procs[nm(call)]; fun kb(f: String, k: String) = Regex("\\d+").find(File(f).readLines().first { it.startsWith(k) })!!.value.toLong() / 1024
        call.resolve(JSObject().put("usedMb", if (p?.isAlive == true) kb("/proc/${pid(p)}/status", "VmRSS") else 0)
            .put("availMb", kb("/proc/meminfo", "MemAvailable")).put("cores", Runtime.getRuntime().availableProcessors())) }
    @PluginMethod fun getConfig(call: PluginCall) = call.resolve(JSObject(File(root, nm(call) + "/meta.json").readText()))
    @PluginMethod fun saveConfig(call: PluginCall) {
        val f = File(root, nm(call) + "/meta.json"); val m = JSONObject(f.readText())
        listOf("ramMb", "cores").forEach { k -> call.getInt(k)?.let { m.put(k, it) } }; call.getBoolean("optimize")?.let { m.put("optimize", it) }
        f.writeText(m.toString()); call.resolve() }
    @PluginMethod fun changeVersion(call: PluginCall) = bg(call) {   // also used for "reinstall jar"
        val n = nm(call); require(procs[n]?.isAlive != true) { "Stop the server first" }; val ver = call.getString("version")!!
        paperDownload(ver, File(root, "$n/paper.jar"), 0, 100)
        val f = File(root, "$n/meta.json"); f.writeText(JSONObject(f.readText()).put("version", ver).toString()); call.resolve() }
    @PluginMethod fun playersList(call: PluginCall) = call.resolve(JSObject().put("list", JSONArray((players[nm(call)] ?: setOf<String>()).toList())))

    // ================= public tunnel (playit.gg agent) =================
    private var tunnel: Process? = null
    @PluginMethod fun tunnelStart(call: PluginCall) = bg(call) {
        val bin = File(context.filesDir, "playit")
        if (!bin.exists()) { download("https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64", bin, 0, 100, "Downloading tunnel agent…"); bin.setExecutable(true) }
        tunnel?.destroy(); tunnel = ProcessBuilder(bin.path).directory(context.filesDir).redirectErrorStream(true).start()
        thread { tunnel!!.inputStream.bufferedReader().forEachLine { emit("tunnel", JSONObject().put("line", it)) } }; call.resolve() }
    @PluginMethod fun tunnelStop(call: PluginCall) { tunnel?.destroy(); call.resolve() }

    // ================= background / battery =================
    @PluginMethod fun requestBatteryOptimization(call: PluginCall) {
        val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i); call.resolve() }
}
