package com.tavern.app.util

import android.os.Build

object DeviceDetector {

    data class VendorInfo(val name: String, val guideHint: String)

    private val VENDORS = listOf(
        VendorInfo("Xiaomi", "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许酒馆"),
        VendorInfo("OPPO", "设置 → 应用 → 自启动 → 允许酒馆"),
        VendorInfo("vivo", "i管家 → 应用管理 → 权限管理 → 自启动 → 允许酒馆"),
        VendorInfo("HUAWEI", "手机管家 → 应用启动管理 → 酒馆 → 手动管理 → 全部开启"),
        VendorInfo("samsung", "设置 → 设备维护 → 电池 → 未监视的应用 → 添加酒馆"),
        VendorInfo("unknown", "请在系统设置中允许酒馆后台运行")
    )

    fun detect(): VendorInfo {
        val brand = Build.BRAND.lowercase()
        return VENDORS.firstOrNull { brand.contains(it.name.lowercase()) }
            ?: VENDORS.last()
    }
}
