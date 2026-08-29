package id.autoair.app.tweak

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import id.autoair.app.monitor.Logger
import id.autoair.app.shizuku.ShizukuBridge

/**
 * Pemindai & pembersih bloatware (aplikasi bawaan) tanpa root.
 *
 * Metode: `pm uninstall -k --user 0` - metode debloat klasik via shell.
 * APK tetap di partisi sistem (read-only), jadi SELALU bisa dipulihkan dengan
 * `cmd package install-existing`. Ini bukan uninstall sungguhan dan tidak
 * mengubah partisi /system.
 *
 * Prinsip keselamatan: lebih baik terlalu protektif daripada membiarkan
 * pengguna menghapus sesuatu yang membuat HP bootloop. Paket yang masuk
 * daftar proteksi TIDAK BISA dipilih sama sekali di UI.
 */
object Debloat {

    data class Entry(
        val pkg: String,
        val label: String,
        val isUserApp: Boolean,
        val isDisabled: Boolean,
        val isRemovedForUser: Boolean,
        val isProtected: Boolean,
        val isSuspect: Boolean,
    ) {
        /** Bisa dipilih untuk aksi? Paket terproteksi tidak pernah bisa. */
        val selectable: Boolean get() = !isProtected

        fun statusText(): String = buildList {
            if (isRemovedForUser) add("terhapus")
            if (isDisabled) add("nonaktif")
            if (isUserApp) add("user")
            if (isProtected) add("sistem")
            if (isSuspect && !isProtected) add("bloat?")
        }.joinToString(" · ")
    }

    // ------------------------------------------------------------ proteksi
    //
    // Dua lapis:
    // 1) Substring: paket MANA PUN yang mengandung kata ini otomatis terkunci.
    //    Ini menangkap komponen kritis di semua merek tanpa perlu tahu nama
    //    lengkapnya (mis. com.miui.securitycenter, com.samsung.android.lool).
    // 2) Dinamis: launcher (HOME), keyboard aktif (IME), aplikasi ini sendiri,
    //    dan Shizuku - karena menghapus Shizuku berarti kehilangan kemampuan
    //    untuk memulihkan apa pun.

    private val PROTECT_SUBSTRINGS = listOf(
        "systemui", "settings", "installer", "permission",
        "telecom", "telephony", "phone", "ims", "radio", "modem",
        "framework", "webview", "gms", "gsf", "vending", "tts",
        "shell", "inputmethod", "keyboard", "latinime",
        "nfc", "bluetooth", "wifi", "camera", "camera2",
        "securitycenter", "securitycore", "securityadd", "guardprovider",
        "powerkeeper", "joyose", "therm", "knox",
        "launcher", "home", "recents", "lockscreen", "keyguard",
        "packageinstaller", "documentsui", "downloadprovider",
        "location", "providers", "server.telecom", "incallui",
        "overlay", "resoverlay", "overlaystub",
        "moe.shizuku", // Shizuku sendiri
    )

    /** Prefix yang selalu inti, apa pun mereknya. */
    private val PROTECT_PREFIXES = listOf(
        "android", "com.android.", "com.google.android.",
        "com.qualcomm.", "com.qti.", "com.mediatek.", "com.mtk.",
        "com.samsung.android.", "com.sec.", "com.samsung.accessory",
    )

    /**
     * Pengecualian dari prefix com.android./com.google.android. di atas:
     * paket-paket ini memang umum dan aman di-debloat.
     */
    private val REMOVABLE_EXCEPTIONS = setOf(
        "com.android.chrome",
        "com.android.email",
        "com.android.musicfx",
        "com.android.bookmarkprovider",
        "com.android.deskclock",
        "com.android.calculator2",
        "com.google.android.apps.photos",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.videos",
        "com.google.android.music",
        "com.google.android.apps.docs",
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.apps.tachyon", // Google Duo/Meet
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.subscriptions.red",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.marvin.talkback",
        "com.google.android.apps.wellbeing",
        "com.google.android.projection.gearhead", // Android Auto
    )

    /** Kata kunci bloat umum - hanya untuk penanda/urutan, bukan izin hapus. */
    private val SUSPECT_KEYWORDS = listOf(
        "analytics", "msa", "adengine", "ads", "abtest",
        "facebook", "tiktok", "musically", "trill",
        "lazada", "shopee", "tokopedia", "blibli", "bukalapak", "alibaba",
        "netflix", "spotify", "microsoft", "linkedin", "wps", "opera",
        "cleanmaster", "duapps", "cmcm", "baidu", "ucmobile",
        "game", "music", "video", "gallery", "weather", "browser",
        "news", "magazine", "theme", "email", "notes", "compass",
        "recorder", "calculator", "scanner", "community", "partner",
        "booking", "agoda", "trip", "traveloka", "gojek", "grab",
    )

    /** Prefix vendor - aplikasi di sini kandidat bloat bila tidak terproteksi. */
    private val VENDOR_PREFIXES = listOf(
        "com.miui.", "com.xiaomi.", "com.mi.global",
        "com.samsung.", "com.oppo.", "com.coloros.", "com.realme.",
        "com.vivo.", "com.iqoo.", "com.oneplus.", "com.huawei.",
        "com.hihonor.", "com.transsion.", "com.infinix.", "com.tecno.",
        "com.itel.", "com.lenovo.", "com.motorola.", "com.lge.",
        "com.htc.", "com.asus.", "com.zte.", "com.tcl.", "com.sonymobile.",
        "com.nothing.", "com.google.",
    )

    // ----------------------------------------------------------------- scan

    /**
     * Pindai paket bawaan (sistem). [includeUser] juga menyertakan aplikasi
     * pihak ketiga - banyak bloatware pabrikan justru dipasang sebagai aplikasi
     * user, tapi uninstall aplikasi user bersifat PERMANEN (tidak bisa
     * install-existing), jadi opsi ini harus disertai peringatan di UI.
     */
    fun scan(context: Context, includeUser: Boolean): List<Entry> {
        if (!ShizukuBridge.isReady()) {
            Logger.error("pindai batal: Shizuku belum siap")
            return emptyList()
        }

        val flag = if (includeUser) "" else "-s"
        val all = listPackages("pm list packages $flag -u")
        val active = listPackages("pm list packages $flag")
        val disabled = listPackages("pm list packages $flag -d")
        val removed = all - active

        if (all.isEmpty()) {
            Logger.error("hasil pindai kosong: perintah pm gagal?")
            return emptyList()
        }

        val dynamicProtected = buildDynamicProtected(context)
        val selfPkg = context.packageName
        val pm = context.packageManager
        val userSet = if (includeUser) listPackages("pm list packages -3 -u") else emptySet()

        val entries = all.map { pkg ->
            val isUserApp = pkg in userSet
            Entry(
                pkg = pkg,
                label = loadLabel(pm, pkg),
                isUserApp = isUserApp,
                isDisabled = pkg in disabled,
                isRemovedForUser = pkg in removed,
                isProtected = isProtected(pkg, selfPkg, dynamicProtected),
                isSuspect = isSuspect(pkg),
            )
        }

        // Urutan: kandidat bloat -> bisa dihapus lainnya -> terproteksi,
        // masing-masing menurut label.
        val sorted = entries.sortedWith(
            compareBy(
                { it.isProtected },
                { !(it.isSuspect && !it.isProtected) },
                { it.label.lowercase() }
            )
        )
        Logger.info(
            "pindai selesai: ${sorted.size} paket, " +
                "${sorted.count { it.isSuspect && !it.isProtected }} kandidat bloat, " +
                "${sorted.count { it.isRemovedForUser }} terhapus, " +
                "${sorted.count { it.isDisabled }} nonaktif"
        )
        return sorted
    }

    // ----------------------------------------------------------------- aksi

    /**
     * Nonaktifkan: paling aman, efeknya mirip uninstall (ikon hilang, tidak
     * berjalan) tapi aplikasi tetap terpasang dan bisa `pm enable` kapan saja.
     */
    fun disable(pkg: String): Boolean {
        val r = ShizukuBridge.exec("pm disable-user --user 0 $pkg", timeoutSec = 15)
        if (r.ok) Logger.info("dinonaktifkan: $pkg")
        else Logger.error("gagal menonaktifkan $pkg: ${r.output.take(200)}")
        return r.ok
    }

    /**
     * Hapus untuk user 0. APK tetap di /system sehingga dapat dipulihkan,
     * KECUALI untuk aplikasi user (isUserApp) - itu benar-benar hilang.
     */
    fun uninstall(pkg: String): Boolean {
        val r = ShizukuBridge.exec("pm uninstall -k --user 0 $pkg", timeoutSec = 20)
        if (r.ok) Logger.warn("dihapus (user 0): $pkg")
        else Logger.error("gagal menghapus $pkg: ${r.output.take(200)}")
        return r.ok
    }

    /**
     * Pulihkan paket yang dihapus-untuk-user-0 atau dinonaktifkan.
     * Keduanya dicoba dan keduanya aman bila tidak berlaku.
     */
    fun restore(pkg: String): Boolean {
        val a = ShizukuBridge.exec("cmd package install-existing $pkg", timeoutSec = 20)
        val b = ShizukuBridge.exec("pm enable $pkg", timeoutSec = 15)
        val ok = a.ok || b.ok
        if (ok) Logger.info("dipulihkan: $pkg")
        else Logger.error("gagal memulihkan $pkg: ${a.output.take(150)} / ${b.output.take(150)}")
        return ok
    }

    /** Tombol darurat: pulihkan SEMUA paket sistem yang terhapus untuk user 0. */
    fun restoreAllRemoved(): Pair<Int, Int> {
        val all = listPackages("pm list packages -s -u")
        val active = listPackages("pm list packages -s")
        val removed = all - active
        if (removed.isEmpty()) {
            Logger.info("tidak ada paket terhapus untuk dipulihkan")
            return 0 to 0
        }
        var ok = 0
        var fail = 0
        removed.forEach { pkg ->
            val r = ShizukuBridge.exec("cmd package install-existing $pkg", timeoutSec = 20)
            if (r.ok) ok++ else {
                fail++
                Logger.error("gagal memulihkan $pkg: ${r.output.take(150)}")
            }
        }
        Logger.warn("pulihkan massal: $ok berhasil, $fail gagal")
        return ok to fail
    }

    // -------------------------------------------------------------- internal

    private fun listPackages(command: String): Set<String> {
        val r = ShizukuBridge.exec(command, timeoutSec = 30)
        if (!r.ok) {
            Logger.error("perintah gagal [$command]: ${r.output.take(200)}")
            return emptySet()
        }
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun isProtected(pkg: String, selfPkg: String, dynamic: Set<String>): Boolean {
        if (pkg == selfPkg) return true
        if (pkg in dynamic) return true
        if (pkg in REMOVABLE_EXCEPTIONS) return false
        val lower = pkg.lowercase()
        if (PROTECT_PREFIXES.any { lower.startsWith(it) }) return true
        if (PROTECT_SUBSTRINGS.any { lower.contains(it) }) return true
        return false
    }

    private fun isSuspect(pkg: String): Boolean {
        val lower = pkg.lowercase()
        if (SUSPECT_KEYWORDS.any { lower.contains(it) }) return true
        if (VENDOR_PREFIXES.any { lower.startsWith(it) }) return true
        return false
    }

    /**
     * Paket yang tidak boleh hilang apa pun yang terjadi: launcher (HOME),
     * keyboard aktif. Tanpa launcher/keyboard pengguna bisa terkunci di luar
     * sistemnya sendiri.
     */
    private fun buildDynamicProtected(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .forEach { out += it.activityInfo.packageName }
        }
        runCatching {
            Settings.Secure.getString(context.contentResolver, "default_input_method")
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { out += it }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun loadLabel(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .loadLabel(pm).toString()
            .takeIf { it.isNotBlank() }
    }.getOrNull() ?: pkg
}
