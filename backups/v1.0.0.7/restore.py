import shutil, os
base = r"C:\Users\aplve\Desktop\cc"
mapping = {
    # Build files
    "app-root-build.gradle.kts": "tavern-app/build.gradle.kts",
    "app-build.gradle.kts": "tavern-app/app/build.gradle.kts",
    "proguard-rules.pro": "tavern-app/app/proguard-rules.pro",

    # Manifest
    "AndroidManifest.xml": "tavern-app/app/src/main/AndroidManifest.xml",

    # Main source
    "MainActivity.kt": "tavern-app/app/src/main/java/com/tavern/app/MainActivity.kt",
    "TavernApplication.kt": "tavern-app/app/src/main/java/com/tavern/app/TavernApplication.kt",

    # Node
    "NodeRunner.kt": "tavern-app/app/src/main/java/com/tavern/app/node/NodeRunner.kt",
    "NodeState.kt": "tavern-app/app/src/main/java/com/tavern/app/node/NodeState.kt",

    # C++
    "node-bridge.cpp": "tavern-app/app/src/main/cpp/node-bridge.cpp",
    "CMakeLists.txt": "tavern-app/app/src/main/cpp/CMakeLists.txt",

    # WebView
    "TavernWebView.kt": "tavern-app/app/src/main/java/com/tavern/app/webview/TavernWebView.kt",
    "WebViewBridge.kt": "tavern-app/app/src/main/java/com/tavern/app/webview/WebViewBridge.kt",

    # Console
    "ConsoleNavHost.kt": "tavern-app/app/src/main/java/com/tavern/app/console/ConsoleNavHost.kt",
    "ConsoleScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/ConsoleScreen.kt",
    "ConsoleViewModel.kt": "tavern-app/app/src/main/java/com/tavern/app/console/ConsoleViewModel.kt",
    "FileManager.kt": "tavern-app/app/src/main/java/com/tavern/app/console/FileManager.kt",
    "SettingsState.kt": "tavern-app/app/src/main/java/com/tavern/app/console/SettingsState.kt",
    "ThemeState.kt": "tavern-app/app/src/main/java/com/tavern/app/console/ThemeState.kt",

    # Console pages
    "FileManagerScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/FileManagerScreen.kt",
    "SettingsScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/SettingsScreen.kt",
    "BackupScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/BackupScreen.kt",
    "RestoreScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/RestoreScreen.kt",
    "AutoBackupScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/AutoBackupScreen.kt",
    "ServerStatusScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/ServerStatusScreen.kt",
    "StorageScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/StorageScreen.kt",
    "CoreUpdateScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/CoreUpdateScreen.kt",
    "ExtensionsHubScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/ExtensionsHubScreen.kt",
    "ExtensionsScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/ExtensionsScreen.kt",
    "ClearCacheScreen.kt": "tavern-app/app/src/main/java/com/tavern/app/console/pages/ClearCacheScreen.kt",

    # Console components
    "ConsoleCard.kt": "tavern-app/app/src/main/java/com/tavern/app/console/components/ConsoleCard.kt",
    "ConsoleTopBar.kt": "tavern-app/app/src/main/java/com/tavern/app/console/components/ConsoleTopBar.kt",
    "ConfirmDialog.kt": "tavern-app/app/src/main/java/com/tavern/app/console/components/ConfirmDialog.kt",

    # Util
    "AssetExtractor.kt": "tavern-app/app/src/main/java/com/tavern/app/util/AssetExtractor.kt",
    "BatteryHelper.kt": "tavern-app/app/src/main/java/com/tavern/app/util/BatteryHelper.kt",
    "DeviceDetector.kt": "tavern-app/app/src/main/java/com/tavern/app/util/DeviceDetector.kt",
    "DownloadTask.kt": "tavern-app/app/src/main/java/com/tavern/app/util/DownloadTask.kt",

    # Backup
    "BackupManager.kt": "tavern-app/app/src/main/java/com/tavern/app/backup/BackupManager.kt",
    "BackupMetadata.kt": "tavern-app/app/src/main/java/com/tavern/app/backup/BackupMetadata.kt",
    "AutoBackupWorker.kt": "tavern-app/app/src/main/java/com/tavern/app/backup/AutoBackupWorker.kt",

    # Service
    "KeepAliveMonitor.kt": "tavern-app/app/src/main/java/com/tavern/app/service/KeepAliveMonitor.kt",
    "TavernForegroundService.kt": "tavern-app/app/src/main/java/com/tavern/app/service/TavernForegroundService.kt",

    # Update
    "CoreUpdater.kt": "tavern-app/app/src/main/java/com/tavern/app/update/CoreUpdater.kt",
    "UpdateChecker.kt": "tavern-app/app/src/main/java/com/tavern/app/update/UpdateChecker.kt",
    "AppUpdateChecker.kt": "tavern-app/app/src/main/java/com/tavern/app/update/AppUpdateChecker.kt",

    # APK
    "app-arm64-v8a-debug.apk": "tavern-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk",
}

for name, path in mapping.items():
    src = os.path.join(os.path.dirname(__file__), name)
    dst = os.path.join(base, path)
    if os.path.exists(src):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        print(f"Restored: {path}")
    else:
        print(f"MISSING: {name}")
print("Restore complete.")
