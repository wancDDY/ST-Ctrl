package com.tavern.app.console.pages

import android.content.Context

/** A deployed avatar waits here until the native character list has highlighted it. */
object CharacterDeploymentNotice {
    private const val PREFS = "character_library_deployments"
    private const val KEY_AVATAR = "pending_avatar"
    private const val KEY_NAME = "pending_name"

    @JvmStatic
    fun mark(context: Context, avatar: String, name: String = "") {
        val clean = avatar.trim()
        if (clean.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AVATAR, clean)
            // Second match key: some rows carry the card name but no avatar file name.
            .putString(KEY_NAME, name.trim())
            .apply()
    }

    @JvmStatic
    fun peek(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AVATAR, "")
            .orEmpty()

    @JvmStatic
    fun peekName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, "")
            .orEmpty()

    @JvmStatic
    fun consume(context: Context, avatar: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_AVATAR, "") != avatar) return
        prefs.edit().remove(KEY_AVATAR).remove(KEY_NAME).apply()
    }
}
