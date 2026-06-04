# JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# NodeRunner JNI
-keep class com.tavern.app.node.NodeRunner {
    native <methods>;
}

# WebView JS Bridge
-keepclassmembers class com.tavern.app.webview.WebViewBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Compose
-keep class androidx.compose.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
