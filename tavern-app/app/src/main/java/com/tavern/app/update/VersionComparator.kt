package com.tavern.app.update

object VersionComparator {
    fun compare(left: String, right: String): Int {
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av > bv) return 1
            if (av < bv) return -1
        }
        return 0
    }

    fun isNewer(available: String, current: String): Boolean =
        compare(available, current) > 0

    private fun parts(value: String): List<Int> {
        val normalized = value.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return normalized.split('.').map { it.toIntOrNull() ?: 0 }
    }
}
