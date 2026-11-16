package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The memory as a map: one node per thing the chat remembers, one edge per relation it can
 * prove. Everything here is assembled from the sidecars the page already reads — no model call, no
 * network, so the map costs nothing and works with the memory switches off.
 *
 * The map deliberately has no relation node: a relation between two people is the edge itself.
 * A node for it would put a third body between the two it connects and read as a separate fact.
 */
enum class MemoryNodeKind { SCENE, PERSON, PLACE, ITEM, PROMISE, EVENT, ENTRY }

/** What an edge means. The view colours by this, so the same word never has to be re-parsed. */
enum class MemoryEdgeKind { PRESENCE, HOLD, RELATION, PROMISE, CAUSE, AT, OTHER }

data class MemoryNode(
    val id: String,
    val label: String,
    val kind: MemoryNodeKind,
    val note: String = "",
    /** Message floors the row came from, one-based on screen; -1 when the source is a rule. */
    val from: Int = -1,
    val to: Int = -1,
    val seen: Int = -1,
    val conflict: Boolean = false,
    /** Degree after the edges are built, used for the radius. */
    val weight: Float = 1f,
    /**
     * The ring the node sits on: how many strong steps it is from the scene. The reader picks a
     * depth (one ring, two, three, all) and the map shows the disc up to that ring.
     */
    val layer: Int = 0,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
)

data class MemoryEdge(
    val from: String,
    val to: String,
    val label: String = "",
    val weight: Float = 1f,
    /** A cause that could not be tied to another row stays a guess, and is drawn as one. */
    val dashed: Boolean = false,
    val kind: MemoryEdgeKind = MemoryEdgeKind.OTHER,
    /**
     * Steps this line costs when counting layers, overriding the kind's default. A structural
     * line (an event hung on the scene so it is not an orphan) is cheaper than a dust line.
     */
    val steps: Float = 0f,
    /** Which stored record proves this line: "state:items", "scene:present", "event:who"… */
    val source: String = "",
    /** The floors of the record that proves it; -1 when the source carries none. */
    val fromFloor: Int = -1,
    val toFloor: Int = -1,
    /** The stored text the line was read out of, quoted in the edge sheet. */
    val evidence: String = "",
)

data class MemoryGraph(
    val nodes: List<MemoryNode>,
    val edges: List<MemoryEdge>,
) {
    // Both maps look nodes up inside their per-frame draw loops, once per node and once per
    // edge end. A linear scan there made a pass quadratic — 400 nodes meant 160k comparisons
    // every frame. The index is built on first use and never rebuilt, since the graph itself
    // is immutable.
    private val index: Map<String, MemoryNode> by lazy(LazyThreadSafetyMode.NONE) {
        nodes.associateBy { it.id }
    }

    fun node(id: String): MemoryNode? = index[id]

    /** Nodes nothing connects to: on the map these are the memory's loose fragments. */
    fun isolated(): List<MemoryNode> {
        val linked = HashSet<String>()
        edges.forEach {
            linked += it.from
            linked += it.to
        }
        return nodes.filter { it.id !in linked }
    }
}

object MemoryGraphs {
    const val MAX_ENTRIES = 60
    const val MAX_EVENTS = 24
    // The cap counts every node, and the dust layer alone can hold hundreds: with 140 the map
    // filled up before a single entry was added, so the live network (mostly entries) collapsed
    // to the scene hub. Entries have their own cap, so the total one only guards the extreme.
    private const val MAX_NODES = 900
    private const val MAX_PLACES = 12

    // The same category words the prompt filters out of entry labels: a tag that names a kind of
    // row is not a person, and turning one into a body would add a node the chat never had.
    private val CATEGORY_TAGS = setOf(
        "person", "people", "event", "events", "plan", "detail", "item", "items", "scene",
        "relation", "relations", "promise", "possession", "location", "state",
        "人物", "关系", "场景", "事件", "约定", "计划", "细节", "物品", "地点", "状态", "剧情",
        "事实", "待定", "目击", "能力", "活动", "校规", "规则", "时间", "背景", "性格",
        "爱好", "身份", "称呼", "秘密", "目标", "经过", "情节", "外貌", "心理",
        "rule", "rules", "place", "places", "time", "ability", "skill", "activity",
        "background", "setting", "mood", "goal", "secret", "appearance",
    )

    // Words the consolidator writes as an attribute subject when it did not name a body: a
    // group, a pronoun-like collective or a stage direction. None of them is a person.
    private val COLLECTIVE_WORDS = setOf(
        "三人", "两人", "众人", "大家", "所有人", "他们", "她们", "他俩", "她俩",
        "彼此", "双方", "几个", "自己", "彼此二人", "二人", "四人", "一群人",
    )

    // A node has to carry meaning. A flower on the windowsill, a cup on the table: the model lists
    // them as props, and turning every one into a body is what made the map unreadable. A name
    // that ends in one of these is scenery unless something else in the memory refers to it.
    private val SCENERY_SUFFIX = Regex(
        "(花|草|叶|枝|树|木|石|尘|沙|土|水|酒|茶|饭|菜|汤|光|影|风|雨|雪|烟|雾|云|天|日|月|星|" +
            "灯|烛|窗|帘|床|桌|椅|镜|门|墙|地|顶|沿|边|角|台阶|栏杆|被|枕|毯|碗|盘|杯|壶|瓶)$",
    )

    private val kindLabel = mapOf(
        MemoryNodeKind.SCENE to "场景",
        MemoryNodeKind.PERSON to "人物",
        MemoryNodeKind.PLACE to "地点",
        MemoryNodeKind.ITEM to "物品",
        MemoryNodeKind.PROMISE to "约定",
        MemoryNodeKind.EVENT to "事件",
        MemoryNodeKind.ENTRY to "条目",
    )

    fun labelOf(kind: MemoryNodeKind): String = kindLabel[kind] ?: ""

    /** A subject the consolidator wrote that names a group or a condition, not one body. */
    fun isCollective(name: String): Boolean =
        name.trim() in COLLECTIVE_WORDS || SCENERY_SUFFIX.containsMatchIn(name.trim())

    // The consolidator sometimes answers "who holds it" with "where it lies" ("on the desk").
    // A position word is furniture, never a body: giving it a person node put seven such
    // fragments on the first ring beside the cast.
    private val POSITION_SUFFIX = setOf("上", "下", "里", "内", "外", "中", "旁", "前", "后", "边", "底", "顶", "侧", "口", "头", "间", "家")
    private val POSITION_PREFIX = setOf("已", "将", "被", "把", "在", "从", "向", "到")

    // The consolidator answers "who holds it" with a place often enough that the map grew a
    // person named after the county ("the county" held a newspaper). A place word never
    // becomes a body, whatever slot it arrived in.
    private val PLACE_SUFFIX = Regex(
        "(城|县|市|区|镇|村|路|街|巷|校|园|楼|室|店|馆|场|站|院|房|屋|口|层|梯|道)$",
    )

    // Kin words, grouped so that two ways of saying the same relation compare equal. The map
    // needs this because the state names a body once and the scenes name it again as a
    // relation: one archive held both a short name and a phrase for the same person.
    private val KIN_GROUPS = listOf(
        listOf("妈妈", "母亲"),
        listOf("爸爸", "父亲"),
        listOf("姐姐", "妹妹"),
        listOf("哥哥", "弟弟"),
        listOf("女儿", "闺女"),
        listOf("妻子", "老婆"),
        listOf("丈夫", "老公"),
    )

    /** A relationship phrase -> a key that ignores which kin word was used. */
    private fun kinKey(phrase: String): String? {
        val match = Regex("^(.{2,8})的(.{2,3})$").find(phrase.trim()) ?: return null
        val who = match.groupValues[1]
        val kin = match.groupValues[2]
        val group = KIN_GROUPS.indexOfFirst { kin in it }
        return if (group < 0) null else who + "#" + group
    }

    private val PLACE_WORDS = listOf(
        "房间", "教室", "客厅", "浴室", "厨房", "宿舍", "小区", "学校", "楼道", "走廊",
        "操场", "课桌", "床头", "柜上", "地上", "桌上", "包里", "门口",
    )

    fun looksLikePosition(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        return clean.last().toString() in POSITION_SUFFIX || clean.first().toString() in POSITION_PREFIX
    }

    /**
     * Steps a line costs when counting layers, by what the line means. Owning, relating and
     * promising are one step; witnessing and causing are a bit more; a mention or a dust link is
     * two and never chains deeper on its own.
     */
    private fun defaultSteps(kind: MemoryEdgeKind): Float = when (kind) {
        MemoryEdgeKind.PRESENCE, MemoryEdgeKind.HOLD, MemoryEdgeKind.RELATION, MemoryEdgeKind.PROMISE -> 1f
        MemoryEdgeKind.CAUSE, MemoryEdgeKind.AT -> 1.25f
        MemoryEdgeKind.OTHER -> 2f
    }

    /**
     * Builds the map. `showEntries` is the dust layer: entries carry most of the volume and the
     * least structure, so they are off until the reader asks for them. The scenes and the entity
     * list back the place nodes and the alias folding: a name the chat shortened must land on
     * the body it already has, or the map grows a second person for one and the same cast
     * member.
     */
    fun build(
        state: AgentClient.MemoryState?,
        events: List<AgentClient.MemoryEvent>,
        entries: List<AgentClient.MemoryEntry>,
        showEntries: Boolean,
        scenes: List<AgentClient.MemoryScene> = emptyList(),
        entities: List<AgentClient.MemoryEntity> = emptyList(),
        /**
         * When set, the dust layer keeps exactly these entries instead of the newest slice: the
         * live network names its rows by id, and those rows are scattered across the archive.
         */
        onlyIds: Set<String>? = null,
        /**
         * Circles written by the last consolidation pass: display name to group index. Empty on an
         * archive that has not been consolidated since circles existed, and the first ring then
         * keeps its old order instead of inventing a grouping.
         */
        circles: Map<String, Int> = emptyMap(),
    ): MemoryGraph {
        val nodes = LinkedHashMap<String, MemoryNode>()
        val edges = ArrayList<MemoryEdge>()

        fun add(node: MemoryNode) {
            if (nodes.size < MAX_NODES && !nodes.containsKey(node.id)) nodes[node.id] = node
        }

        // Alias folding: every name the memory uses for a person resolves to the canonical one,
        // so a scene that uses the short form and a slot that uses the full name share one body.
        val aliasToName = HashMap<String, String>()
        entities.filter { it.kind == "person" }.forEach { entity ->
            entity.aliases.forEach { alias ->
                val clean = alias.trim()
                if (clean.length >= 2 && clean != entity.name) aliasToName[clean] = entity.name
            }
        }
        // A body named by relation folds onto the person whose own relation says the same
        // thing. That is the only place the archive states the link, so it is the only place
        // to read it from.
        val kinToName = HashMap<String, String>()
        state?.people.orEmpty().forEach { person ->
            val name = person.title.trim()
            if (name.isEmpty()) return@forEach
            Regex("(\\S{2,8}的[^\\s；，,。]{2,3})").findAll(person.note).forEach { found ->
                kinKey(found.value)?.let { key ->
                    if (name !in kinToName.values) kinToName[key] = name
                }
            }
        }

        fun canonical(name: String): String {
            val clean = name.trim()
            aliasToName[clean]?.let { return it }
            kinKey(clean)?.let { key -> kinToName[key]?.let { return it } }
            return clean
        }

        // The state's own cast. These are bodies even when the word would fail the shape test
        // below; anything else has to look like a name rather than a location.
        val knownPeople = LinkedHashSet<String>()
        state?.people.orEmpty().forEach { knownPeople += canonical(it.title) }
        state?.present.orEmpty().forEach { knownPeople += canonical(it) }
        entities.filter { it.kind == "person" }.forEach { knownPeople += canonical(it.name) }

        fun looksLikePlace(name: String): Boolean =
            PLACE_SUFFIX.containsMatchIn(name) || PLACE_WORDS.any { name.contains(it) }

        fun addPerson(name: String, note: String = "") {
            val clean = canonical(name)
            if (clean.isEmpty()) return
            if (clean !in knownPeople &&
                (clean in COLLECTIVE_WORDS || looksLikePosition(clean) || looksLikePlace(clean))
            ) {
                return
            }
            val id = "person:$clean"
            val existing = nodes[id]
            if (existing == null) {
                add(MemoryNode(id = id, label = clean, kind = MemoryNodeKind.PERSON, note = note))
            } else if (existing.note.isEmpty() && note.isNotEmpty()) {
                nodes[id] = existing.copy(note = note)
            }
        }

        fun connect(
            from: String,
            to: String,
            label: String = "",
            weight: Float = 1f,
            dashed: Boolean = false,
            kind: MemoryEdgeKind = MemoryEdgeKind.OTHER,
            steps: Float = 0f,
            source: String = "",
            fromFloor: Int = -1,
            toFloor: Int = -1,
            evidence: String = "",
        ) {
            if (from == to) return
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) return
            val duplicate = edges.any {
                (it.from == from && it.to == to) || (it.from == to && it.to == from)
            }
            if (duplicate) return
            edges += MemoryEdge(from, to, label, weight, dashed, kind, steps, source, fromFloor, toFloor, evidence)
        }

        val people = state?.people.orEmpty()
        val present = state?.present.orEmpty()
        val place = state?.location.orEmpty()
        val time = state?.time.orEmpty()

        if (people.isNotEmpty() || present.isNotEmpty() || place.isNotEmpty() || time.isNotEmpty()) {
            val label = place.ifEmpty { "当前场景" }
            val note = buildList {
                if (time.isNotEmpty()) add("时间 $time")
                if (place.isNotEmpty() && time.isNotEmpty()) add(place)
            }.joinToString("，")
            add(MemoryNode(id = "scene", label = label, kind = MemoryNodeKind.SCENE, note = note))
        }

        // Candidate owners for promises, gathered before any node is built: the text of a promise
        // may shorten a name, and the shortest name wins only after the longer one had its turn.
        val itemNames = state?.items.orEmpty().map { it.title.trim() }.filter { it.isNotEmpty() } +
            entities.filter { it.kind == "item" }.map { it.name }
        fun plausiblePerson(name: String): Boolean {
            val clean = name.trim()
            if (clean.length < 2 || clean in COLLECTIVE_WORDS) return false
            if (SCENERY_SUFFIX.containsMatchIn(clean) || looksLikePosition(clean)) return false
            if (clean in itemNames) return false
            return true
        }
        val ownerCandidates = (people.map { it.title } + present +
            events.flatMap { it.who } + scenes.flatMap { it.present } +
            entities.filter { it.kind == "person" }.map { it.name })
            .map(::canonical)
            .filter(::plausiblePerson)
            .distinct()
        // Text-to-body pairs: a canonical name stands for itself, and every recorded alias
        // stands for the body it folds onto. Longest text wins, so the full name is matched
        // before the short form it contains.
        val selfNamed: List<Pair<String, String>> = ownerCandidates.map { name -> name to name }
        val aliased: List<Pair<String, String>> = aliasToName.entries
            .map { entry -> entry.key to entry.value }
            .filter { entry -> plausiblePerson(entry.second) }
        val ownerByText = (selfNamed + aliased)
            .sortedWith(compareByDescending<Pair<String, String>> { it.first.length })

        (present + people.map { it.title }).distinct().forEach { name ->
            addPerson(name, people.firstOrNull { canonical(it.title) == canonical(name) }?.note.orEmpty())
        }
        present.forEach { name ->
            connect(
                "scene", "person:${canonical(name)}", "在场", 3f,
                kind = MemoryEdgeKind.PRESENCE, source = "state:present",
                evidence = "当前状态记录的在场名单",
            )
        }

        val eventCorpus = events.joinToString("\n") { it.text + " " + it.cause + " " + it.effect }
        state?.items.orEmpty().forEach { item ->
            val name = item.title.trim()
            if (name.isEmpty()) return@forEach
            // An item needs evidence that it mattered: someone holds it, a turning point names
            // it, or the memory returns to it in more than one entry.
            val holders = holderOf(item.note).isNotEmpty()
            val inEvents = eventCorpus.contains(name)
            val mentions = entries.count { it.text.contains(name) }
            if (!holders && !inEvents && mentions < 2) return@forEach
            val id = "item:$name"
            add(
                MemoryNode(
                    id = id,
                    label = name,
                    kind = MemoryNodeKind.ITEM,
                    note = item.note,
                    from = item.from,
                    to = item.to,
                    seen = item.seen,
                    conflict = item.conflict,
                ),
            )
            // A possessive holder ("the bag of so-and-so") names the bag, not a body: it folds
            // onto the person it points at when that body exists, and counts as unheld when it
            // does not.
            val holderRaw = canonical(holderOf(item.note))
            val holder = if (holderRaw.contains('的')) {
                holderRaw.substringBefore('的')
                    .takeIf { base -> base.length >= 2 && nodes.containsKey("person:$base") }
                    .orEmpty()
            } else {
                holderRaw
            }
            if (holder.isNotEmpty() && !looksLikePosition(holder)) {
                addPerson(holder)
                connect(
                    "person:$holder", id, "持有", 5f,
                    kind = MemoryEdgeKind.HOLD, source = "state:items",
                    fromFloor = item.from, toFloor = item.to, evidence = item.note,
                )
            } else {
                // A prop nobody holds rests where the scene is: one step out, with the place
                // it lies at kept in the note for the sheet.
                connect(
                    "scene", id, "物品", 1f, steps = 1.5f,
                    source = "state:items", fromFloor = item.from, toFloor = item.to,
                    evidence = item.note.ifEmpty { "状态里的物品记录" },
                )
            }
        }

        state?.promises.orEmpty().forEachIndexed { index, promise ->
            val text = promise.title.trim()
            if (text.isEmpty()) return@forEachIndexed
            val id = "promise:$index"
            add(
                MemoryNode(
                    id = id,
                    label = text,
                    kind = MemoryNodeKind.PROMISE,
                    note = promise.note,
                    from = promise.from,
                    to = promise.to,
                    seen = promise.seen,
                    conflict = promise.conflict,
                ),
            )
            // The detail sheet reads the maker off a PROMISE line from a person; an unowned
            // promise therefore hangs on the scene as a plain line, so the sheet never names the
            // room as the one who promised.
            val owner = ownerByText.firstOrNull { text.contains(it.first) }?.second
            if (owner != null) {
                addPerson(owner)
                connect(
                    "person:$owner", id, "约定", 2.5f,
                    kind = MemoryEdgeKind.PROMISE, source = "state:promises",
                    fromFloor = promise.from, toFloor = promise.to, evidence = promise.note.ifEmpty { text },
                )
            } else {
                connect(
                    "scene", id, "约定", 1.5f, steps = 1.5f,
                    source = "state:promises", fromFloor = promise.from, toFloor = promise.to,
                    evidence = promise.note.ifEmpty { text },
                )
            }
        }

        state?.relations.orEmpty().forEachIndexed { index, relation ->
            val parts = relation.title.split("与", limit = 2)
            if (parts.size != 2) return@forEachIndexed
            val a = canonical(parts[0])
            val b = canonical(parts[1])
            addPerson(a)
            addPerson(b)
            connect(
                "person:$a", "person:$b", relation.note.ifEmpty { "关系" }, 4f,
                kind = MemoryEdgeKind.RELATION, source = "state:relations",
                fromFloor = relation.from, evidence = relation.note,
            )
        }

        // Places: the stretches record where the chat happens, and the same spot returns with
        // different detail in brackets. Cutting at the first comma as well produces a node
        // naming the county instead of the room, because the location often reads
        // "county, building, room". Only the brackets are detail; the rest of the line is the
        // place.
        fun normalizePlace(raw: String): String {
            val detail = Regex("[（(【\\[].*?[）)】\\]]")
            return raw.replace(detail, "").trim().trimEnd('，', ',', '、', '。', '；', ';', ' ')
                .trim()
        }
        // Variants that differ only by a filler word are the same room: folding on a key with
        // the particles and spaces removed stops one room from drawing as two places side by
        // side.
        fun placeKey(name: String): String =
            name.replace(Regex("[的\\s，,、。]"), "")
        // Two passes: collect every spelling per key first, then label the key. Labelling while
        // iterating would split one room across two nodes as soon as a more common spelling
        // turned up later in the list.
        val scenesByKey = LinkedHashMap<String, MutableList<AgentClient.MemoryScene>>()
        val variantsByKey = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        scenes.forEach { scene ->
            val name = normalizePlace(scene.location)
            if (name.length < 2) return@forEach
            val key = placeKey(name)
            val variants = variantsByKey.getOrPut(key) { LinkedHashMap() }
            variants[name] = (variants[name] ?: 0) + 1
            scenesByKey.getOrPut(key) { ArrayList() }.add(scene)
        }
        // The most frequent spelling labels the place; ties keep the longer, more specific one.
        fun labelFor(key: String): String = variantsByKey[key]?.entries
            ?.maxWithOrNull(compareBy({ it.value }, { it.key.length }))
            ?.key.orEmpty()
        val stretchesByPlace = LinkedHashMap<String, MutableList<AgentClient.MemoryScene>>()
        scenesByKey.forEach { (key, stretches) ->
            val label = labelFor(key)
            if (label.isNotEmpty()) stretchesByPlace[label] = stretches
        }
        val currentPlaceName = normalizePlace(place)
        val currentPlace = if (currentPlaceName.length >= 2) {
            labelFor(placeKey(currentPlaceName)).ifEmpty { currentPlaceName }
        } else {
            ""
        }
        if (currentPlace.length >= 2 && currentPlace !in stretchesByPlace) {
            stretchesByPlace[currentPlace] = ArrayList()
        }
        // A long chat revisits a handful of spots; the rest of the list would crowd the map
        // without adding a question the reader could ask.
        val keptPlaces = stretchesByPlace.entries
            .sortedWith(compareBy({ -it.value.size }, { it.key }))
            .take(MAX_PLACES)
            .associate { it.key to it.value }
        keptPlaces.forEach { (name, stretches) ->
            val id = "place:$name"
            add(
                MemoryNode(
                    id = id,
                    label = name,
                    kind = MemoryNodeKind.PLACE,
                    note = if (stretches.isEmpty()) "此刻" else "出现 ${stretches.size} 段",
                ),
            )
            if (name == currentPlace) {
                connect(
                    "scene", id, "此刻", 2f, kind = MemoryEdgeKind.PRESENCE, steps = 1f,
                    source = "state:location", evidence = place,
                )
            }
            stretches.forEach { stretch ->
                stretch.present.forEach { person ->
                    addPerson(person)
                    connect(
                        "person:${canonical(person)}", id, "出场", 1.5f,
                        kind = MemoryEdgeKind.AT, source = "scene:present",
                        fromFloor = stretch.from, toFloor = stretch.to,
                        evidence = listOf(stretch.summary, stretch.event, stretch.quote)
                            .firstOrNull { it.isNotEmpty() }.orEmpty(),
                    )
                }
            }
        }

        val eventList = events.takeLast(MAX_EVENTS)
        eventList.forEachIndexed { index, event ->
            val id = "event:$index"
            add(
                MemoryNode(
                    id = id,
                    label = event.text,
                    kind = MemoryNodeKind.EVENT,
                    note = listOfNotNull(
                        event.whenText.takeIf { it.isNotEmpty() }?.let { "时间 $it" },
                        event.cause.takeIf { it.isNotEmpty() }?.let { "起因 $it" },
                        event.effect.takeIf { it.isNotEmpty() }?.let { "结果 $it" },
                    ).joinToString("，"),
                    from = event.from,
                    to = event.to,
                ),
            )
            event.who.forEach { name ->
                addPerson(name)
                connect(
                    "person:${canonical(name)}", id, "参与", weight = 3f, steps = 1.25f,
                    source = "event:who", fromFloor = event.from, toFloor = event.to,
                    evidence = event.text,
                )
            }
            // The structural line is a fallback for an event nobody participates in, priced
            // above the through-a-person path so it never short-circuits the depth count.
            connect("scene", id, weight = 1f, steps = 2.5f, source = "structural:event")
            // What happened where: the stretch the turning point falls in names the place, so
            // the line comes from the floors rather than from guessing at words.
            val spots: Map<String, AgentClient.MemoryScene?> = if (event.from >= 0) {
                keptPlaces.mapValues { (_, stretches) ->
                    stretches.firstOrNull { it.from <= event.to && event.from <= it.to }
                }.filterValues { it != null }
            } else {
                keptPlaces.filterKeys { event.text.contains(it) }.mapValues { null }
            }
            spots.forEach { (name, stretch) ->
                connect(
                    "place:$name", id, "发生地", 1f,
                    kind = MemoryEdgeKind.AT, source = "scene:floors",
                    fromFloor = event.from, toFloor = event.to,
                    evidence = stretch?.let { listOf(it.summary, it.event).firstOrNull { t -> t.isNotEmpty() } }
                        ?: event.text,
                )
            }
            // What it happened to: a turning point that names a prop ties the two together.
            state?.items.orEmpty().forEach { item ->
                val name = item.title.trim()
                if (name.isNotEmpty() && event.text.contains(name) && nodes.containsKey("item:$name")) {
                    connect("item:$name", id, "涉及", 1f, steps = 1.25f)
                }
            }
        }
        // A cause can only reach another row when the chat wrote it in the same terms. The map
        // shows a dashed line when it can prove the link, and a person link when it names someone.
        eventList.forEachIndexed { index, event ->
            val cause = event.cause.trim()
            if (cause.length < 4) return@forEachIndexed
            eventList.forEachIndexed { otherIndex, other ->
                if (otherIndex == index) return@forEachIndexed
                if (cause.contains(other.text.take(8)) || other.text.contains(cause.take(8))) {
                    connect(
                        "event:$otherIndex", "event:$index", "起因", 1.5f, dashed = true,
                        kind = MemoryEdgeKind.CAUSE, source = "event:cause",
                        fromFloor = other.from, toFloor = event.to, evidence = cause,
                    )
                }
            }
            ownerCandidates.filter { cause.contains(it) }.forEach { name ->
                connect(
                    "person:$name", "event:$index", "起因", 1.5f, dashed = true,
                    kind = MemoryEdgeKind.CAUSE, source = "event:cause",
                    fromFloor = event.from, toFloor = event.to, evidence = cause,
                )
            }
        }

        if (showEntries) {
            // Newest first: the list arrives oldest-last, and the dust worth drawing is the part
            // the reader just lived through — taking the tail put the oldest rows on the map and
            // left the live network's entries out entirely. A caller that names its rows (the
            // live network) gets exactly those, because they span every age of the archive.
            val visible = if (onlyIds != null) {
                entries.filter { "entry:${it.id}" in onlyIds }
            } else {
                entries.take(MAX_ENTRIES)
            }
            // A tag that repeats is a person the chat actually keeps naming; a one-off tag is
            // usually a category or a prop, and making it a body would crowd the map.
            val tagCounts = HashMap<String, Int>()
            visible.forEach { entry ->
                entry.tags.forEach { tag ->
                    val name = tag.trim()
                    if (name.isNotEmpty()) tagCounts[name] = (tagCounts[name] ?: 0) + 1
                }
            }
            visible.forEach { entry ->
                val id = "entry:${entry.id}"
                add(
                    MemoryNode(
                        id = id,
                        label = entry.text,
                        kind = MemoryNodeKind.ENTRY,
                        from = entry.from,
                        to = entry.to,
                    ),
                )
                entry.tags.forEach { tag ->
                    val name = canonical(tag.trim())
                    if (name.isEmpty() || name in CATEGORY_TAGS) return@forEach
                    // An ASCII tag is usually a category the model wrote ("rule", "place"); a
                    // name the chat actually uses is already a person or is written in the
                    // conversation's own script.
                    val looksLikeName = name.any { it.code in 0x4E00..0x9FFF }
                    // Scenery is never a person, however many entries mention it.
                    if (isCollective(name)) return@forEach
                    if (nodes.containsKey("person:$name") || ((tagCounts[tag.trim()] ?: 0) >= 2 && looksLikeName)) {
                        addPerson(name)
                        connect(
                            id, "person:$name", weight = 1f, steps = 2.5f,
                            source = "entry:tags", fromFloor = entry.from, toFloor = entry.to,
                            evidence = entry.text,
                        )
                    }
                }
                state?.items.orEmpty().forEach { item ->
                    if (entry.text.contains(item.title) && nodes.containsKey("item:${item.title}")) {
                        connect(
                            id, "item:${item.title}", weight = 1f, steps = 1.5f,
                            source = "entry:text", fromFloor = entry.from, toFloor = entry.to,
                            evidence = entry.text,
                        )
                    }
                }
            }
        }

        // The cast is the first ring: a person the chat remembers but who is off stage right now
        // still hangs on the scene, or the depth count would push a main character to the rim.
        nodes.values.filter { it.kind == MemoryNodeKind.PERSON }.forEach { person ->
            val attached = edges.any {
                it.kind == MemoryEdgeKind.PRESENCE && (it.from == person.id || it.to == person.id)
            }
            if (!attached) {
                connect(
                    "scene", person.id, "出场过", 1f,
                    kind = MemoryEdgeKind.PRESENCE, source = "structural:cast",
                )
            }
        }

        val degrees = HashMap<String, Int>()
        edges.forEach {
            degrees[it.from] = (degrees[it.from] ?: 0) + 1
            degrees[it.to] = (degrees[it.to] ?: 0) + 1
        }
        val maxDegree = (degrees.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val withLayers = layersOf(MemoryGraph(nodes.values.toList(), edges))
        val weighted = nodes.values.map { node ->
            node.copy(
                weight = (degrees[node.id] ?: 1).toFloat() / maxDegree,
                layer = withLayers[node.id] ?: 1,
            )
        }
        return layout(MemoryGraph(weighted, edges), circles)
    }

    private fun holderOf(note: String): String {
        val text = note.trim()
        if (text.startsWith("在") && text.endsWith("身上") && text.length > 3) {
            return text.substring(1, text.length - 2)
        }
        return ""
    }

    /**
     * The ring of every node: fewest steps from the scene over the lines, each line priced by
     * what it means. Dijkstra without a heap — the map tops out at a hundred-odd nodes, and
     * scanning the frontier keeps the order deterministic. A node no line reaches sits one ring
     * past the deepest reached one, so loose dust still gets a ring of its own.
     */
    private fun layersOf(graph: MemoryGraph): Map<String, Int> {
        val adjacency = HashMap<String, MutableList<Pair<String, Float>>>()
        graph.edges.forEach { edge ->
            val step = if (edge.steps > 0f) edge.steps else defaultSteps(edge.kind)
            adjacency.getOrPut(edge.from) { ArrayList() }.add(edge.to to step)
            adjacency.getOrPut(edge.to) { ArrayList() }.add(edge.from to step)
        }
        val distance = HashMap<String, Float>()
        val settled = HashSet<String>()
        distance["scene"] = 0f
        while (true) {
            val current = graph.nodes
                .filter { it.id in distance && it.id !in settled }
                .minByOrNull { distance[it.id] ?: Float.MAX_VALUE }
                ?: break
            settled += current.id
            val base = distance[current.id] ?: continue
            (adjacency[current.id] ?: emptyList()).forEach { (neighbour, step) ->
                val candidate = base + step
                if (candidate < (distance[neighbour] ?: Float.MAX_VALUE)) distance[neighbour] = candidate
            }
        }
        // Ceil, not round: a line that costs more than a whole step (a turning point reached
        // through a person, a place reached through a visit) belongs to the deeper ring. With
        // rounding the deepest rings stayed empty and two depth buttons listed the same map.
        val deepest = graph.nodes
            .filter { it.id in settled && it.id != "scene" }
            .maxOfOrNull { distance[it.id] ?: 0f } ?: 0f
        val fallback = ceil(deepest).toInt() + 1
        return graph.nodes.associate { node ->
            val reached = distance[node.id]
            val layer = when {
                node.id == "scene" -> 0
                reached == null -> fallback
                else -> ceil(reached).toInt().coerceAtLeast(1)
            }
            node.id to layer
        }
    }

    /**
     * Half the footprint of one node, in frame units, used by the overlap pass. A label is read
     * from outside the dot, so the pad is wider than the marker it belongs to.
     */
    private fun footprintOf(kind: MemoryNodeKind): Float = when (kind) {
        MemoryNodeKind.SCENE -> 0.050f
        MemoryNodeKind.PERSON -> 0.040f
        MemoryNodeKind.PLACE -> 0.044f
        MemoryNodeKind.ITEM -> 0.033f
        MemoryNodeKind.PROMISE, MemoryNodeKind.EVENT -> 0.030f
        MemoryNodeKind.ENTRY -> 0.020f
    }

    /**
     * One ring per depth: the scene in the middle, the cast on the first ring, what they carry,
     * promise and live through on the second, dust and deep chains outside that.
     *
     * A ring sorts its nodes by the angle of the ring closer to the centre they hang from — so
     * what belongs to somebody still sits next to them — and then spreads them evenly, which no
     * length of chat can collapse onto a single spoke. A ring also claims the radius its headcount
     * needs: sixty dust rows on a radius sized for four would read as a bead string.
     *
     * The overlap pass afterwards only removes what is left. It is deterministic: no randomness,
     * so the same graph always lands on the same points and panning never re-solves.
     */
    fun layout(graph: MemoryGraph, circles: Map<String, Int> = emptyMap()): MemoryGraph {
        if (graph.nodes.isEmpty()) return graph
        val byId = graph.nodes.associateBy { it.id }
        val layers = graph.nodes.associate { it.id to it.layer }
        val angles = HashMap<String, Double>()

        /**
         * The circle a node reads as part of: its own, or the one its holder belongs to.
         *
         * Keys are display names because that is what the core stores. A node id carries a kind
         * prefix, and a prop has no circle of its own — it sits with whoever holds it.
         */
        fun circleOf(id: String): Int {
            val label = byId[id]?.label
            if (label != null) circles[label]?.let { return it }
            if (id.startsWith("person:")) circles[id.removePrefix("person:")]?.let { return it }
            for (edge in graph.edges) {
                val other = when (id) {
                    edge.from -> edge.to
                    edge.to -> edge.from
                    else -> null
                } ?: continue
                val otherLabel = byId[other]?.label ?: other.removePrefix("person:")
                circles[otherLabel]?.let { return it }
            }
            return Int.MAX_VALUE
        }

        fun neighborsOf(id: String): List<String> {
            val out = ArrayList<String>()
            graph.edges.forEach {
                if (it.from == id) out += it.to
                if (it.to == id) out += it.from
            }
            return out
        }

        /** The angle a node would take if it stayed beside the ring it hangs from. */
        fun anchorAngleOf(id: String, layer: Int): Double? {
            val rank = { other: String ->
                when (byId[other]?.kind) {
                    MemoryNodeKind.PERSON -> 0
                    MemoryNodeKind.SCENE -> 1
                    MemoryNodeKind.PLACE -> 2
                    else -> 3
                }
            }
            val anchor = neighborsOf(id)
                .filter { (layers[it] ?: 1) < layer }
                .minByOrNull { rank(it) }
                ?: return null
            return angles[anchor]
        }

        val ringLayers = layers.values.filter { it > 0 }.distinct().sorted()
        val radiusByNode = HashMap<String, Float>()
        ringLayers.forEachIndexed { index, layer ->
            val group = graph.nodes.filter { layers[it.id] == layer }
            if (group.isEmpty()) return@forEachIndexed
            val ordered = group.sortedWith(
                compareBy(
                    // The first ring is the cast, and grouping it by circle is what makes the map
                    // read as circles instead of an alphabetical line. Deeper rings keep following
                    // their anchors — which now sit inside their circle — so they need no key.
                    { if (layer == 1) circleOf(it.id) else 0 },
                    { anchorAngleOf(it.id, layer) ?: Double.NEGATIVE_INFINITY },
                    { it.id },
                ),
            )
            // A ring that grew past its legible spacing splits into staggered sub-rings: the
            // angular order keeps cluster neighbours adjacent, and every sub-ring carries at
            // most enough nodes for its circumference to sit a footprint apart. Without the
            // split, the busiest depth read as one bead string no tap could land inside.
            val subCount = ceil(ordered.size / 44f).toInt().coerceAtLeast(1)
            val base = maxOf(
                0.24f + 0.21f * index,
                (ordered.size / subCount) * 0.062f / (2f * Math.PI.toFloat()),
            )
            val step = 2 * Math.PI / ordered.size
            // A gap between circles. Grouping that only reorders the ring is invisible: a reader
            // looking for "who belongs with whom" reads spacing before order, and a continuous
            // arc of dots says "one group" no matter how the names are sequenced. Two circles are
            // the minimum that can show a gap; one circle keeps the even ring.
            val circleCount = if (layer == 1) {
                ordered.map { circleOf(it.id) }.filter { it != Int.MAX_VALUE }.distinct().size
            } else {
                0
            }
            val gap = if (circleCount > 1) step * 0.45 else 0.0
            val spread = (2 * Math.PI - gap * (circleCount - 1).coerceAtLeast(0)) / ordered.size
            val phase = 0.9 + 1.3 * index
            var cursor = -Math.PI / 2 + phase
            var current = -1
            ordered.forEachIndexed { nodeIndex, node ->
                val circle = if (layer == 1) circleOf(node.id) else Int.MAX_VALUE
                if (circle != Int.MAX_VALUE && current != -1 && circle != current) cursor += gap
                if (circle != Int.MAX_VALUE) current = circle
                angles[node.id] = cursor
                cursor += spread
                radiusByNode[node.id] = base + 0.07f * (nodeIndex % subCount)
            }
        }

        val positions = HashMap<String, FloatArray>()
        graph.nodes.forEach { node ->
            val angle = angles[node.id] ?: -Math.PI / 2
            val radius = radiusByNode[node.id] ?: 0.20f
            positions[node.id] = floatArrayOf(
                0.5f + (radius * cos(angle)).toFloat(),
                0.5f + (radius * sin(angle)).toFloat(),
            )
        }
        positions["scene"] = floatArrayOf(0.5f, 0.5f)

        val list = graph.nodes
        repeat(80) {
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val pa = positions.getValue(a.id)
                    val pb = positions.getValue(b.id)
                    val dx = pb[0] - pa[0]
                    val dy = pb[1] - pa[1]
                    val wanted = footprintOf(a.kind) + footprintOf(b.kind) + 0.014f
                    val measured = sqrt(dx * dx + dy * dy)
                    if (measured >= wanted) continue
                    // A zero-length direction has no unit vector; two nodes on the same point are
                    // split along a fixed axis instead of dividing by the distance they do not
                    // have.
                    val ux: Float
                    val uy: Float
                    if (measured < 0.0005f) {
                        val split = 2 * Math.PI * ((i * 7 + j * 3) % 12) / 12.0
                        ux = cos(split).toFloat()
                        uy = sin(split).toFloat()
                    } else {
                        ux = dx / measured
                        uy = dy / measured
                    }
                    val push = (wanted - measured.coerceAtLeast(0.0001f)) / 2f
                    pa[0] -= ux * push
                    pa[1] -= uy * push
                    pb[0] += ux * push
                    pb[1] += uy * push
                }
            }
            // The scene is the origin every ring is read from; letting the pass move it would
            // shift the whole map under the reader.
            positions["scene"]?.let {
                it[0] = 0.5f
                it[1] = 0.5f
            }
        }

        var extent = 0.01f
        list.forEach { node ->
            val point = positions.getValue(node.id)
            val reach = hypot(point[0] - 0.5f, point[1] - 0.5f) + footprintOf(node.kind)
            if (reach > extent) extent = reach
        }
        val scale = 0.455f / extent
        return graph.copy(
            nodes = list.map { node ->
                val point = positions.getValue(node.id)
                node.copy(
                    x = (0.5f + (point[0] - 0.5f) * scale).coerceIn(0.03f, 0.97f),
                    y = (0.5f + (point[1] - 0.5f) * scale).coerceIn(0.03f, 0.97f),
                )
            },
        )
    }
}
