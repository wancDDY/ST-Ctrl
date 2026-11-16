package com.tavern.app.console.pages

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder + versions model exercised at the file layer: the layout migration,
 * the renameable folder name and the deploy switch all run on plain files, so
 * they are tested here without an Android context.
 */
class LibraryFolderModelTest {
    private fun tempDir(): File =
        File.createTempFile("library", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private fun legacyCardDir(root: File, id: String, name: String, coreName: String): File {
        val dir = File(File(root, "cards"), id)
        dir.mkdirs()
        File(dir, "card.png").writeText("body-$name")
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("format", "png")
                .put("source", "核心迁移")
                .put("coreFileName", coreName)
                .put("coreStatus", "installed")
                .put("importedAt", 123L)
                .toString(2)
        )
        return dir
    }

    @Test
    fun `legacy layout migrates body and meta into a version folder`() {
        val root = tempDir()
        legacyCardDir(root, "a", "阿甲", "阿甲.png")

        assertEquals(1, migrateLegacyLayoutAt(root))

        val entry = File(File(root, "cards"), "a")
        val entryMeta = JSONObject(File(entry, "meta.json").readText())
        assertEquals("阿甲", entryMeta.optString("name"))
        val version = File(File(entry, "versions"), "a")
        assertTrue(File(version, "card.png").isFile)
        val vMeta = JSONObject(File(version, "meta.json").readText())
        assertEquals("核心迁移", vMeta.optString("source"))
        assertEquals("installed", vMeta.optString("coreStatus"))
        assertEquals("阿甲.png", vMeta.optString("coreFileName"))
        assertFalse(File(entry, "card.png").exists())
    }

    @Test
    fun `legacy migration is idempotent`() {
        val root = tempDir()
        legacyCardDir(root, "a", "阿甲", "阿甲.png")
        migrateLegacyLayoutAt(root)
        assertEquals(0, migrateLegacyLayoutAt(root))
        assertTrue(File(File(File(root, "cards"), "a"), "versions").isDirectory)
    }

    @Test
    fun `renaming changes the folder display name`() {
        val root = tempDir()
        legacyCardDir(root, "a", "阿甲", "阿甲.png")
        migrateLegacyLayoutAt(root)

        assertTrue(renameCardAt(File(root, "cards"), "a", "阿乙"))
        val meta = JSONObject(File(File(File(root, "cards"), "a"), "meta.json").readText())
        assertEquals("阿乙", meta.optString("name"))
    }

    @Test
    fun `renaming refuses a blank name`() {
        val root = tempDir()
        legacyCardDir(root, "a", "阿甲", "阿甲.png")
        migrateLegacyLayoutAt(root)
        assertFalse(renameCardAt(File(root, "cards"), "a", "   "))
        val meta = JSONObject(File(File(File(root, "cards"), "a"), "meta.json").readText())
        assertEquals("阿甲", meta.optString("name"))
    }

    private fun versionCard(
        cardsDir: File,
        cardId: String,
        versionId: String,
        name: String,
        format: String = "png",
        coreStatus: String = "uninstalled",
        coreFileName: String? = null,
    ): LibraryVersion {
        val vDir = File(File(cardsDir, cardId), "versions/$versionId")
        vDir.mkdirs()
        File(vDir, cardBodyName(format)).writeText("body-$name")
        val meta = JSONObject()
            .put("id", versionId)
            .put("name", name)
            .put("format", format)
            .put("source", "导入")
            .put("coreStatus", coreStatus)
        if (coreFileName != null) meta.put("coreFileName", coreFileName)
        File(vDir, "meta.json").writeText(meta.toString(2))
        return LibraryVersion(
            id = versionId,
            name = name,
            format = format,
            file = File(vDir, cardBodyName(format)),
            meta = meta,
            imported = coreStatus == "installed",
            coreFileName = coreFileName
        )
    }

    @Test
    fun `deploying a second version adds a character and leaves the first alone`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val v1 = versionCard(cardsDir, "a", "v1", "阿甲-v1", coreStatus = "installed", coreFileName = "阿甲.png")
        val v2 = versionCard(cardsDir, "a", "v2", "阿甲-v2")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        File(charsDir, "阿甲.png").writeText("body-阿甲-v1")
        val card = LibraryCard("a", "阿甲", JSONObject(), listOf(v1, v2))

        assertTrue(deployVersionAt(cardsDir, card, v2, coreDir))

        // The core files a chat under the png name, so overwriting the first version's file would
        // hand the second one every chat the first already had. Each version gets its own name.
        assertEquals("已部署到 阿甲.png", "已部署到 ${JSONObject(File(File(entryDir, "versions"), "v1/meta.json").readText()).optString("coreFileName")}")
        assertEquals("installed", JSONObject(File(File(entryDir, "versions"), "v1/meta.json").readText()).optString("coreStatus"))
        assertEquals("body-阿甲-v1", File(charsDir, "阿甲.png").readText())
        assertEquals("installed", JSONObject(File(File(entryDir, "versions"), "v2/meta.json").readText()).optString("coreStatus"))
        assertEquals("阿甲 (2).png", JSONObject(File(File(entryDir, "versions"), "v2/meta.json").readText()).optString("coreFileName"))
        assertEquals("body-阿甲-v2", File(charsDir, "阿甲 (2).png").readText())
    }

    @Test
    fun `the version label goes into the file name so two deployments stay apart`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val v1 = versionCard(cardsDir, "a", "v1", "阿甲-v1")
        v1.meta.put("version", "1.3")
        val v2 = versionCard(cardsDir, "a", "v2", "阿甲-v2")
        v2.meta.put("version", "1.6")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        val card = LibraryCard("a", "阿甲", JSONObject(), listOf(v1, v2))

        assertTrue(deployVersionAt(cardsDir, card, v1, coreDir))
        assertTrue(deployVersionAt(cardsDir, card, v2, coreDir))

        // Both answers, both files, and the second one is not sitting on the first one's chats.
        assertTrue(File(charsDir, "阿甲 1.3.png").isFile)
        assertTrue(File(charsDir, "阿甲 1.6.png").isFile)
        assertEquals("body-阿甲-v1", File(charsDir, "阿甲 1.3.png").readText())
        assertEquals("body-阿甲-v2", File(charsDir, "阿甲 1.6.png").readText())
    }

    @Test
    fun `re-deploying a version rewrites its own file, not the other version's`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val v1 = versionCard(cardsDir, "a", "v1", "阿甲-v1")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        val card = LibraryCard("a", "阿甲", JSONObject(), listOf(v1))

        assertTrue(deployVersionAt(cardsDir, card, v1, coreDir))
        val first = JSONObject(File(File(entryDir, "versions"), "v1/meta.json").readText()).optString("coreFileName")
        // Read the version back the way the page does after a reload, then edit its body.
        val reread = versionCard(cardsDir, "a", "v1", "阿甲-v1", coreStatus = "installed", coreFileName = first)
        File(File(cardsDir, "a/versions/v1"), cardBodyName("png")).writeText("body-edited")

        assertTrue(deployVersionAt(cardsDir, LibraryCard("a", "阿甲", JSONObject(), listOf(reread)), reread, coreDir))

        assertEquals("body-edited", File(charsDir, first).readText())
        assertEquals(1, charsDir.listFiles()!!.count { it.name.endsWith(".png") })
    }

    @Test
    fun `a version filed under the folder's old name deploys as its own`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(
            JSONObject().put("id", "a").put("name", "阿甲1.3").toString(2)
        )
        val first = versionCard(cardsDir, "a", "v13", "阿甲1.3")
        first.meta.put("version", "1.3")
        // The older build filed 1.6 under the folder's name, so its record still points at the
        // file 1.3's chats are filed under.
        val second = versionCard(cardsDir, "a", "v16", "阿甲1.6", coreStatus = "installed", coreFileName = "阿甲1.3.png")
        second.meta.put("version", "1.6")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        File(charsDir, "阿甲1.3.png").writeText("body-1.6")
        val card = LibraryCard("a", "阿甲1.3", JSONObject(), listOf(first, second))

        assertTrue(deployVersionAt(cardsDir, card, second, coreDir))

        assertEquals("body-阿甲1.6", File(charsDir, "阿甲1.6.png").readText())
        assertEquals("阿甲1.6.png",
            JSONObject(File(File(entryDir, "versions"), "v16/meta.json").readText()).optString("coreFileName"))
    }

    @Test
    fun `a rename keeps every deployed version on its own file name`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val v1 = versionCard(cardsDir, "a", "v1", "阿甲-v1", coreStatus = "installed", coreFileName = "阿甲.png")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        File(charsDir, "阿甲.png").writeText("old")

        // The core files a chat under the png name, so that name is the identity: a renamed folder
        // deploying under a new name would start a second character and leave every chat behind.
        assertTrue(renameCardAt(cardsDir, "a", "阿乙"))
        val renamed = LibraryCard("a", "阿乙", JSONObject(), listOf(v1))
        assertTrue(deployVersionAt(cardsDir, renamed, v1, coreDir))

        assertEquals("body-阿甲-v1", File(charsDir, "阿甲.png").readText())
        assertFalse(File(charsDir, "阿乙.png").exists())
        // The label moved, the identity did not.
        val meta = JSONObject(File(entryDir, "meta.json").readText())
        assertEquals("阿乙", meta.optString("name"))
        assertEquals("阿甲.png", meta.optString("coreFile"))
    }

    @Test
    fun `the first deploy fixes the name a later rename cannot change`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val v1 = versionCard(cardsDir, "a", "v1", "阿甲-v1")
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()

        assertTrue(deployVersionAt(cardsDir, LibraryCard("a", "阿甲", JSONObject(), listOf(v1)), v1, coreDir))
        assertTrue(File(charsDir, "阿甲.png").isFile)

        // Rename, then deploy the version that already owns that file: it stays where the chat looks.
        renameCardAt(cardsDir, "a", "阿丙")
        val reread = versionCard(cardsDir, "a", "v1", "阿甲-v1", coreStatus = "installed", coreFileName = "阿甲.png")
        val renamed = LibraryCard("a", "阿丙", JSONObject(), listOf(reread), coreFileName = "阿甲.png")
        assertTrue(deployVersionAt(cardsDir, renamed, reread, coreDir))

        assertEquals("body-阿甲-v1", File(charsDir, "阿甲.png").readText())
        assertFalse(File(charsDir, "阿丙.png").exists())
    }

    @Test
    fun `a card imported under a retired name gets its own core file`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val retired = File(cardsDir, "a")
        retired.mkdirs()
        File(retired, "meta.json").writeText(
            JSONObject().put("id", "a").put("name", "阿乙").put("coreFile", "阿甲.png").toString(2)
        )
        File(File(retired, "versions"), "v1").mkdirs()
        val coreDir = tempDir()
        val charsDir = File(File(coreDir, "data/default-user"), "characters")
        charsDir.mkdirs()
        File(charsDir, "阿甲.png").writeText("holds the old chats")

        // The renamed folder still answers on that file name, so a new card arriving
        // under that display name must not deploy on top of it and inherit its chats.
        assertEquals("阿甲 (2).png", freeCoreName(charsDir, cardsDir, "b", "阿甲.png"))
        assertEquals("阿丁.png", freeCoreName(charsDir, cardsDir, "b", "阿丁.png"))
    }

    @Test
    fun `name folding drops brackets and trailing version markers`() {
        assertEquals("阿甲", normalizeCardName("《阿甲》v5.4.2"))
        assertEquals("阿甲", normalizeCardName("「阿甲」 V2"))
        assertEquals("seraphina", normalizeCardName("Seraphina"))
        assertEquals("某张卡", normalizeCardName("某张卡 V1.41"))
    }

    @Test
    fun `routing matches the folded name`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "《阿甲》v5.4.2").toString(2))
        val card = JSONObject()
            .put("name", "阿甲")
            .put("data", JSONObject().put("creator", "x").put("description", "y"))
        val hit = findMatchingEntryAt(cardsDir, card)
        assertEquals("a", hit?.first)
        assertEquals("name", hit?.second)
    }

    @Test
    fun `routing falls back to content features`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        val vDir = File(File(entryDir, "versions"), "v1")
        entryDir.mkdirs()
        vDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "原版名称").toString(2))
        // A json card body so the fingerprint can be read back on the JVM.
        File(vDir, "card.json").writeText(
            JSONObject()
                .put("name", "原版名称")
                .put("data", JSONObject().put("creator", "Same Author").put("description", "shared story"))
                .toString(2)
        )
        File(vDir, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "json").toString(2)
        )
        // Same creator + description, different title: a rework of the same card.
        val card = JSONObject()
            .put("name", "二创新名")
            .put("data", JSONObject().put("creator", "Same Author").put("description", "shared story"))
        val hit = findMatchingEntryAt(cardsDir, card)
        assertEquals("a", hit?.first)
        assertEquals("feature", hit?.second)
    }

    @Test
    fun `routing creates a new folder when nothing matches`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val card = JSONObject()
            .put("name", "阿乙")
            .put("data", JSONObject().put("creator", "other").put("description", "other story"))
        assertNull(findMatchingEntryAt(cardsDir, card))
    }

    @Test
    fun `the folder avatar is the newest version`() {
        val cardsDir = File(tempDir(), "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val old = versionCard(cardsDir, "a", "v1", "old")
        old.meta.put("importedAt", 100L)
        File(File(File(cardsDir, "a"), "versions"), "v1/meta.json").writeText(old.meta.toString(2))
        val fresh = versionCard(cardsDir, "a", "v2", "fresh")
        fresh.meta.put("importedAt", 200L)
        File(File(File(cardsDir, "a"), "versions"), "v2/meta.json").writeText(fresh.meta.toString(2))
        val card = LibraryCard("a", "阿甲", JSONObject(), listOf(old, fresh))
        assertEquals("v2", card.latestVersion?.id)
    }

    @Test
    fun `default card names come from the Seraphina folder`() {
        val charsDir = tempDir()
        val serDir = File(charsDir, "Seraphina")
        serDir.mkdirs()
        File(serDir, "anger.png").writeText("a")
        File(serDir, "joy.png").writeText("j")
        File(charsDir, "default_Seraphina.png").writeText("s")
        val names = stDefaultCardNames(charsDir)
        assertTrue("seraphina" in names)
        assertTrue("default_seraphina" in names)
        assertTrue("anger" in names)
        assertTrue("joy" in names)
        assertFalse("阿甲" in names)
    }

    @Test
    fun `purge removes only migrated default-set folders`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val charsDir = tempDir()
        val serDir = File(charsDir, "Seraphina")
        serDir.mkdirs()
        File(serDir, "anger.png").writeText("a")
        // A default-set card mirrored from the core: removed.
        val emotion = File(cardsDir, "e")
        emotion.mkdirs()
        File(emotion, "meta.json").writeText(
            JSONObject().put("id", "e").put("name", "anger").toString(2)
        )
        val eVersions = File(File(emotion, "versions"), "v1")
        eVersions.mkdirs()
        File(eVersions, "card.png").writeText("body")
        File(eVersions, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "png")
                .put("source", "核心迁移").put("coreFileName", "anger.png").toString(2)
        )
        // A user-imported card: kept even though the name matches the default set.
        val user = File(cardsDir, "u")
        user.mkdirs()
        File(user, "meta.json").writeText(
            JSONObject().put("id", "u").put("name", "joy").toString(2)
        )
        val uVersions = File(File(user, "versions"), "v1")
        uVersions.mkdirs()
        File(uVersions, "card.json").writeText("{}")
        File(uVersions, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "json")
                .put("source", "导入").toString(2)
        )
        // A migrated user card: kept.
        val real = File(cardsDir, "r")
        real.mkdirs()
        File(real, "meta.json").writeText(
            JSONObject().put("id", "r").put("name", "阿甲").toString(2)
        )
        val rVersions = File(File(real, "versions"), "v1")
        rVersions.mkdirs()
        File(rVersions, "card.png").writeText("body")
        File(rVersions, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "png")
                .put("source", "核心迁移").put("coreFileName", "阿甲.png").toString(2)
        )

        assertEquals(1, purgeStDefaultCardsAt(root, charsDir))
        assertEquals(listOf("r", "u"), cardsDir.listFiles()?.map { it.name }?.sorted())
    }

    @Test
    fun `a version body with the same payload is a duplicate`() {
        val cardsDir = File(tempDir(), "cards")
        val entryDir = File(cardsDir, "a")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val versions = File(File(entryDir, "versions"), "v1")
        versions.mkdirs()
        File(versions, "card.json").writeText(
            JSONObject().put("data", JSONObject().put("name", "阿甲").put("description", "正文")).toString(2)
        )
        File(versions, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "json").toString(2)
        )
        // Same payload, different file bytes: the card was exported twice, not edited.
        val same = JSONObject()
            .put("spec", "chara_card_v3")
            .put("avatar", "data:image/png;base64,AAAA")
            .put("data", JSONObject().put("description", "正文").put("name", "阿甲"))
        assertTrue(hasSameCardAt(cardsDir, "a", same))

        val edited = JSONObject().put("data", JSONObject().put("name", "阿甲").put("description", "改过的正文"))
        assertFalse(hasSameCardAt(cardsDir, "a", edited))
    }

    @Test
    fun `a version can be renamed in place`() {
        val cardsDir = File(tempDir(), "cards")
        val vDir = File(File(File(cardsDir, "a"), "versions"), "v1")
        vDir.mkdirs()
        File(vDir, "card.json").writeText("{}")
        File(vDir, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "json").toString(2)
        )

        assertTrue(renameVersionAt(cardsDir, "a", "v1", " 新名字 "))
        val meta = JSONObject(File(vDir, "meta.json").readText())
        assertEquals("新名字", meta.optString("name"))
        assertFalse(renameVersionAt(cardsDir, "a", "v1", "   "))
    }

    @Test
    fun `a version moves to another folder and the empty source disappears`() {
        val cardsDir = File(tempDir(), "cards")
        val srcEntry = File(cardsDir, "a")
        val srcV = File(File(srcEntry, "versions"), "v1")
        srcV.mkdirs()
        File(srcV, "card.png").writeText("body")
        File(srcV, "meta.json").writeText(
            JSONObject().put("id", "v1").put("name", "v1").put("format", "png").toString(2)
        )
        File(srcEntry, "meta.json").writeText(JSONObject().put("id", "a").put("name", "A").toString(2))
        val dstEntry = File(cardsDir, "b")
        dstEntry.mkdirs()
        File(dstEntry, "meta.json").writeText(JSONObject().put("id", "b").put("name", "B").toString(2))

        assertTrue(moveVersionAt(cardsDir, "a", "v1", "b"))
        assertTrue(File(File(File(cardsDir, "b"), "versions"), "v1").isDirectory)
        assertFalse(File(File(File(cardsDir, "a"), "versions"), "v1").exists())
        assertFalse(srcEntry.exists())
        assertFalse(moveVersionAt(cardsDir, "b", "v1", "b"))
    }

    @Test
    fun `a pinned cover wins and a stale pin falls back to the newest version`() {
        val root = tempDir()
        val cardsDir = File(root, "cards")
        val dir = File(cardsDir, "a")
        dir.mkdirs()
        File(dir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val older = versionCard(cardsDir, "a", "v1", "阿甲-v1")
        val newer = versionCard(cardsDir, "a", "v2", "阿甲-v2")
        val card = LibraryCard("a", "阿甲", JSONObject(), listOf(older, newer))

        // Unpinned, the folder shows the newest import.
        assertEquals("v2", card.coverVersion?.id)

        assertTrue(setCoverVersionAt(cardsDir, "a", "v1"))
        assertEquals("v1", card.copy(coverVersionId = "v1").coverVersion?.id)

        assertTrue(setCoverVersionAt(cardsDir, "a", null))
        assertFalse(JSONObject(File(dir, "meta.json").readText()).has("coverVersionId"))

        // A pin pointing at a deleted version must not leave the folder faceless.
        assertEquals("v2", card.copy(coverVersionId = "gone").coverVersion?.id)
    }

    @Test
    fun `a folder without a version number takes the next free one`() {
        assertEquals("v1", nextVersionLabel(emptyList()))
        assertEquals("v3", nextVersionLabel(listOf("v1", "v2")))
        // Only the leading number counts, so "v1.2" advances the count by one and
        // a hand-written label neither increments it nor gets overwritten.
        assertEquals("v2", nextVersionLabel(listOf("v1.2")))
        assertEquals("v4", nextVersionLabel(listOf("v3", "终稿")))
    }

    @Test
    fun `the import dialog starts from what the card says about itself`() {
        val v2 = JSONObject().put(
            "data",
            JSONObject().put("name", "阿甲").put("character_version", "v1.2"),
        )
        assertEquals(ImportDefaults("阿甲", "v1.2"), importDefaultsOf(v2, "阿甲 - 雨夜.png"))

        val legacy = JSONObject().put("char_name", "阿乙").put("char_version", "3")
        assertEquals(ImportDefaults("阿乙", "3"), importDefaultsOf(legacy, "随便.png"))

        val bare = JSONObject().put("data", JSONObject())
        assertEquals(ImportDefaults("文件名", ""), importDefaultsOf(bare, "文件名.png"))
    }

    @Test
    fun `a folder is found by the folded name, and by content when the title changed`() {
        val root = tempDir()
        val cards = File(root, "cards")
        val dir = File(cards, "a")
        val version = File(File(dir, "versions"), "v1")
        version.mkdirs()
        File(dir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val stored = JSONObject().put(
            "data",
            JSONObject().put("name", "阿甲").put("creator", "某人").put("description", "同一段介绍"),
        )
        File(version, "card.json").writeText(stored.toString())
        File(version, "meta.json").writeText(JSONObject().put("id", "v1").put("format", "json").toString(2))

        val sameName = JSONObject().put("data", JSONObject().put("name", "阿甲"))
        val retitled = JSONObject().put(
            "data",
            JSONObject().put("name", "阿丙").put("creator", "某人").put("description", "同一段介绍"),
        )

        assertEquals("a", resolveImportTarget(cards, sameName, null)?.first)
        assertEquals("name", resolveImportTarget(cards, sameName, null)?.second)
        // A re-titled copy keeps the folder it already belongs to while the name is untouched.
        assertEquals("a", resolveImportTarget(cards, retitled, null)?.first)
        assertEquals("feature", resolveImportTarget(cards, retitled, null)?.second)
        assertEquals("a", resolveImportTarget(cards, retitled, "阿丙")?.first)
    }

    @Test
    fun `an edited name that matches no folder starts a new one`() {
        val root = tempDir()
        legacyCardDir(root, "a", "阿甲", "阿甲.png")
        val card = JSONObject().put("data", JSONObject().put("name", "阿甲"))
        assertNull(resolveImportTarget(File(root, "cards"), card, "阿丙"))
    }

    @Test
    fun `an edited name is not overruled by a content match`() {
        val root = tempDir()
        val cards = File(root, "cards")
        val dir = File(cards, "a")
        val version = File(File(dir, "versions"), "v1")
        version.mkdirs()
        File(dir, "meta.json").writeText(JSONObject().put("id", "a").put("name", "阿甲").toString(2))
        val stored = JSONObject().put(
            "data",
            JSONObject().put("name", "阿甲").put("creator", "某人").put("description", "同一段介绍"),
        )
        File(version, "card.json").writeText(stored.toString())
        File(version, "meta.json").writeText(JSONObject().put("id", "v1").put("format", "json").toString(2))

        // Same body, but the reader typed a name that belongs to no folder: they said this is
        // a different character, and the fingerprint must not drag it into another folder.
        val incoming = JSONObject().put(
            "data",
            JSONObject().put("name", "阿丁").put("creator", "某人").put("description", "同一段介绍"),
        )
        assertNull(resolveImportTarget(cards, incoming, "阿戊"))
    }
}
