package com.tavern.app.update

data class VersionSection(
    val title: String,
    val summary: String,
    val items: List<String>,
)

data class AppReleaseSummary(
    val intro: String,
    val sections: List<VersionSection>,
    val migrationSteps: List<String> = emptyList(),
    val migrationTitle: String? = null,
    val footer: String = "",
    val fromVersion: String? = null,
)

/**
 * Reads a release body written as `### section`, one summary line, then `- item` bullets.
 *
 * The release body is the only copy of the notes that exists after a release, so the
 * reader must survive small deviations: a missing summary line, a bullet that wraps, the
 * document title left in, or the older-release section that the changelog file keeps at
 * its end. Anything that is not a section still renders as plain text, so a body written
 * in another shape stays readable instead of disappearing.
 */
object ReleaseNotes {
    private const val SECTION = "### "
    private val olderRelease = Regex("^##\\s+v?\\d")
    private val rule = Regex("^(-{3,}|\\*{3,})$")
    private val migrationTitles = setOf("升级步骤", "升级说明", "升级前请先备份")

    fun parse(raw: String): AppReleaseSummary? {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return null

        val intro = mutableListOf<String>()
        val footer = mutableListOf<String>()
        val sections = mutableListOf<VersionSection>()
        val steps = mutableListOf<String>()
        var stepTitle: String? = null
        var title: String? = null
        var summary: String? = null
        var items = mutableListOf<String>()
        // The document's own version heading comes first and must not end the notes;
        // only a version heading after a section has started does.
        var sectionSeen = false
        // A rule closes the notes; the license and modification notice that follows is
        // part of the release body but belongs to no section and must not be glued onto
        // the last bullet.
        var belowRule = false

        fun flush() {
            val name = title
            if (name != null) {
                if (migrationTitles.contains(name)) {
                    steps += items
                    if (stepTitle == null) stepTitle = name
                } else if (items.isNotEmpty() || !summary.isNullOrBlank()) {
                    sections += VersionSection(name, summary.orEmpty(), items.toList())
                }
            }
            title = null
            summary = null
            items = mutableListOf()
        }

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (belowRule) {
                when {
                    olderRelease.containsMatchIn(trimmed) -> break
                    trimmed.isEmpty() -> continue
                    trimmed.startsWith(SECTION) -> belowRule = false
                    else -> {
                        footer += trimmed
                        continue
                    }
                }
            }
            when {
                // A version heading opens the older-release part of the file, but the
                // document's own heading comes before any section and must not end it.
                olderRelease.containsMatchIn(trimmed) -> if (sectionSeen) break
                trimmed.isEmpty() -> Unit
                rule.matches(trimmed) -> {
                    flush()
                    belowRule = true
                }
                trimmed.startsWith("## ") || trimmed.startsWith("# ") -> Unit
                trimmed.startsWith(SECTION) -> {
                    flush()
                    title = trimmed.removePrefix(SECTION).trim()
                    sectionSeen = true
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val item = trimmed.drop(2).trim()
                    if (item.isEmpty()) Unit
                    else if (title == null) intro += item
                    else items += item
                }
                title == null -> intro += trimmed
                items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + trimmed
                summary.isNullOrBlank() -> summary = trimmed
                else -> summary = summary + " " + trimmed
            }
        }
        flush()

        if (sections.isEmpty() && steps.isEmpty()) return null
        return AppReleaseSummary(
            intro = intro.joinToString("\n"),
            sections = sections,
            migrationSteps = steps,
            migrationTitle = stepTitle,
            footer = footer.joinToString("\n"),
        )
    }
}
