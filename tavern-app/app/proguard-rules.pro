# Tink / EncryptedSharedPreferences — compile-time annotations not present at runtime
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

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

# ONNX Runtime resolves its own classes by name from native code (TensorInfo, OnnxTensor and
# friends), so nothing in the Java sources references them and R8 would strip them. Without this
# the session loads and then aborts inside run() with ClassNotFoundException, which reads as a
# model problem rather than a shrinking one.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
