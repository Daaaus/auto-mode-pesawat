# ------------------------------------------------------------------
# Auto Mode Pesawat - aturan R8
# ------------------------------------------------------------------

# Shizuku memanggil balik lewat AIDL/binder; jangan diacak.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# ShizukuBridge memanggil Shizuku.newProcess dan waitForTimeout lewat REFLEKSI.
# Tanpa aturan ini R8 akan merename/menghapusnya dan toggle mode pesawat
# akan gagal diam-diam saat runtime.
-keepclassmembers class rikka.shizuku.Shizuku {
    *** newProcess(...);
}
-keepclassmembers class rikka.shizuku.ShizukuRemoteProcess {
    public boolean waitForTimeout(long, java.util.concurrent.TimeUnit);
    public int exitValue();
    public void destroy();
    public java.io.InputStream getInputStream();
    public java.io.InputStream getErrorStream();
}

# ViewBinding dipakai lewat refleksi inflate().
-keep class id.autoair.app.databinding.** { *; }

# Komponen yang dirujuk manifest.
-keep class id.autoair.app.service.NetMonitorService
-keep class id.autoair.app.service.BootReceiver
-keep class id.autoair.app.ui.MainActivity

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Buang semua Log.d/v di rilis (mengecilkan ukuran + tidak bocor info).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Optimasi lebih agresif
-repackageclasses
-allowaccessmodification
