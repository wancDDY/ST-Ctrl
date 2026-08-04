package com.tavern.app.util

import org.json.JSONObject
import java.io.File

/**
 * Lightweight JSON helpers for common org.json.JSONObject patterns
 * used across backup metadata, extension manifests, and package.json parsing.
 */
object JsonUtil {

    /** Safely read a JSON file, returning null on any error. */
    fun readJsonFile(file: File): JSONObject? {
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Extract a version string from a JSON file's top-level "version" key. */
    fun readVersion(file: File, fallback: String = "未知"): String {
        val json = readJsonFile(file) ?: return fallback
        return json.optString("version", fallback).ifBlank { fallback }
    }
}
