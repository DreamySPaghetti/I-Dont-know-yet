package com.arenascanner.app

/**
 * What the move coach tells Gemma. Kept as plain text (no Android types) so it is unit-tested.
 * A 2B on-device model reads small card text poorly, so the prompt hands it each visible card's
 * real rules text and asks for a short, fixed answer format.
 */
object CoachPrompt {
    const val SYSTEM = "You are an expert Magic: The Gathering Arena coach. Give the single best play for the " +
        "player's current turn, based on the screenshot and the card text provided. " +
        "Trust the provided card text over what you see in the image. Never invent cards that are not visible or listed. " +
        "Be concrete: which land to play, which spells to cast and in what order, which creatures attack, what to hold back.\n" +
        "Strategy checklist:\n" +
        "1. Lethal: if your attackers' total power beats the opponent's life after their possible blocks, attack with everything.\n" +
        "2. Play a land every turn if you have one in hand.\n" +
        "3. Use your mana efficiently: prefer the play that spends the most mana this turn (curve out).\n" +
        "4. Attack before casting non-haste creatures, unless the spell helps the attack (removal, pump, haste).\n" +
        "5. Attack only where blocks are bad for the opponent; avoid losing a creature for nothing unless you are racing.\n" +
        "6. Save removal for the opponent's best threat; use it now if that creature will hurt you next turn.\n" +
        "7. When ahead, keep pressure; when behind, block well and stabilize.\n" +
        "Answer in exactly two short lines:\n" +
        "PLAY: one or two sentences.\n" +
        "WHY: one or two sentences.\n" +
        "If the screen shows the opening-hand choice (Mulligan / Keep buttons), answer instead:\n" +
        "PLAY: KEEP or MULLIGAN.\n" +
        "WHY: count lands and spells. Keep 7 cards with 2-5 lands and plays on turns 1-3; mulligan 0-1 or 6-7 land hands.\n" +
        "If the opponent is attacking, answer which blocks to make. Use the current step given in the question.\n" +
        "If the screen is unclear, say so in one line instead."

    /** Detailed style adds one alternative play, so the player sees the trade-off. */
    fun system(detailed: Boolean) = if (!detailed) SYSTEM else SYSTEM +
        "\nDetailed mode: after WHY, add a third line:\nALSO: the second-best play and when it would be better (one sentence)."

    /** Short rules reminders, added only for keywords that appear on the visible cards. */
    val KEYWORDS = linkedMapOf(
        "flying" to "can only be blocked by creatures with flying or reach",
        "reach" to "can block flying creatures",
        "deathtouch" to "any damage it deals to a creature destroys it",
        "trample" to "excess combat damage goes to the player",
        "lifelink" to "damage it deals also gains you that much life",
        "first strike" to "deals combat damage before creatures without first strike",
        "double strike" to "deals first-strike and regular combat damage",
        "menace" to "can't be blocked except by two or more creatures",
        "vigilance" to "attacking doesn't tap it",
        "haste" to "can attack and tap the turn it comes in",
        "defender" to "can't attack",
        "flash" to "can be cast any time you could cast an instant",
        "hexproof" to "can't be targeted by opponents' spells or abilities",
        "ward" to "opponents' spells/abilities targeting it are countered unless they pay the ward cost",
        "indestructible" to "isn't destroyed by damage or destroy effects",
        "prowess" to "gets +1/+1 until end of turn whenever you cast a noncreature spell",
        "toxic" to "combat damage to a player also gives that many poison counters",
        "surveil" to "look at the top cards, put any in your graveyard, rest back on top",
        "scry" to "look at the top cards, put any on the bottom, rest back on top",
    )

    /** Where a card was seen: decides whether it's in your hand, on your side, or the opponent's. */
    enum class Zone(val label: String) { HAND("Your hand"), MINE("Your battlefield"), THEIRS("Opponent's battlefield"), OPENING("Opening hand") }

    /** One line of text read from the screen; positions and height are fractions of the screen size. */
    data class Seen(val text: String, val cx: Float, val cy: Float, val h: Float, val glow: Float = -1f, val angle: Float = 0f) {
        /** Arena turns tapped permanents sideways, so their text reads at about ±90°. */
        val tapped get() = kotlin.math.abs(angle) in 45f..135f
    }

    /** Arena outlines cards you can play right now in bright blue; measured on real screenshots (≥17 % vs ≤4 %). */
    const val GLOW_MIN = 0.10f

    /**
     * Share of bright-blue pixels in a band above a card name (where Arena draws its "playable" outline).
     * [rgb] returns a packed 0xRRGGBB pixel; the name box is in pixels.
     */
    fun glowFraction(rgb: (Int, Int) -> Int, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): Float {
        val lh = maxOf(12, bottom - top)
        val y0 = maxOf(0, top - 3 * lh); val y1 = maxOf(0, top)
        val x0 = maxOf(0, left - 2 * lh); val x1 = minOf(w, right + 3 * lh)
        var n = 0; var hit = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = rgb(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (b > 200 && g > 150 && r < 140 && b - r > 80) hit++
                n++; x += 2
            }
            y += 2
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    /** What the coach knows about the board, worked out from the text on screen. */
    data class Board(
        val cards: List<Pair<Card, Zone>>,
        val lands: Map<Zone, Map<String, Int>>,
        val creatures: Map<Zone, List<String>>,
        val lifeYou: Int?,
        val lifeOpp: Int?,
        val phase: Phase,
        val other: List<String>,
        /** Names of hand cards Arena highlights as playable now; null when it couldn't be measured. */
        val playable: Set<String>? = null,
        val notPlayable: Set<String> = emptySet(),
        /** Battlefield creatures with names and abilities, when P/T boxes could be linked to names. */
        val fighters: Map<Zone, List<Fighter>> = emptyMap(),
        /** Untapped lands I control (mana available now), when tapped/untapped could be read. */
        val untappedLands: Int? = null,
        val totalLands: Int? = null,
    )

    enum class Phase(val note: String, val question: String) {
        MULLIGAN("This is my opening hand.", "Should I keep it or mulligan? Answer in exactly this format:\nPLAY: KEEP or MULLIGAN\nWHY: ..."),
        BLOCK("The opponent is attacking me right now and I must choose blockers.",
            "Which of my creatures should block which attacker, or should I not block? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
        ATTACK("It is my turn and I am choosing attackers.",
            "Which of my creatures should attack? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
        MAIN1("It is my first main phase, before combat.", "What is my best play this turn? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
        MAIN2("It is my second main phase, after combat.", "What should I do before ending my turn? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
        OPPONENT("It is the opponent's turn.", "What should I watch for or do on the opponent's turn? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
        UNKNOWN("", "What is my best play this turn? Answer in exactly this format:\nPLAY: ...\nWHY: ..."),
    }

    private val BASIC = setOf("plains", "island", "swamp", "mountain", "forest", "wastes")
    private val PT = Regex("^\\d{1,2}/\\d{1,2}$")
    private val LIFE = Regex("^\\d{1,3}$")

    fun isMulligan(text: List<String>) =
        text.any { it.contains("Mulligan", ignoreCase = true) } && text.any { it.trim().startsWith("Keep", ignoreCase = true) }

    /** Arena's own button and prompt text tells us the step of the turn. */
    fun phaseOf(text: List<String>): Phase {
        val t = text.joinToString(" | ").lowercase()
        return when {
            isMulligan(text) -> Phase.MULLIGAN
            "choose blockers" in t || "no blocks" in t -> Phase.BLOCK
            "no attacks" in t || "all attack" in t || "choose attackers" in t || Regex("\\battack\\b").containsMatchIn(t) && "to damage" in t -> Phase.ATTACK
            "to combat" in t -> Phase.MAIN1
            "end turn" in t || "to end" in t -> Phase.MAIN2
            "opponent's turn" in t || "opponent\u2019s turn" in t -> Phase.OPPONENT
            else -> Phase.UNKNOWN
        }
    }

    /**
     * Zone from screen position (landscape Arena): the opponent's half is above 44 % of the height.
     * On my half, hand cards are drawn large, so their names are taller than names on the battlefield.
     */
    fun zoneOf(s: Seen, mulligan: Boolean, handH: Float = 0f) = when {
        mulligan -> Zone.OPENING
        s.cy < 0.44f -> Zone.THEIRS
        // Hand names are the biggest card names on my side; battlefield names are about half their size.
        s.cy > 0.6f && s.h >= maxOf(0.010f, 0.75f * handH) -> Zone.HAND
        else -> Zone.MINE
    }

    /** Can be cast during combat or the opponent's turn. */
    fun isInstantSpeed(c: Card) = c.rules.split(" — ").take(2).any { "Instant" in it } ||
        Regex("\\bflash\\b", RegexOption.IGNORE_CASE).containsMatchIn(c.rules)

    /** Instants and sorceries never stay on the battlefield. */
    fun isSpell(c: Card) = c.rules.split(" — ").take(2).any { "Instant" in it || "Sorcery" in it }

    private val STACK = Regex("^[xX×]\\s?(\\d{1,2})$")

    fun analyze(lines: List<Seen>, matchRaw: (String) -> Card?): Board {
        // Each line is looked up several times below; fuzzy matching isn't free, so remember results.
        val memo = HashMap<String, Card?>()
        val match = { t: String -> memo.getOrPut(t) { matchRaw(t) } }
        val texts = lines.map { it.text }
        val phase = phaseOf(texts)
        val mull = phase == Phase.MULLIGAN
        val cards = mutableListOf<Pair<Card, Zone>>()
        val lands = linkedMapOf<Zone, MutableMap<String, Int>>()
        val creatures = linkedMapOf<Zone, MutableList<String>>()
        var lifeYou: Int? = null
        var lifeOpp: Int? = null
        val other = mutableListOf<String>()
        val playable = mutableSetOf<String>()
        val notPlayable = mutableSetOf<String>()
        var measured = false
        val ptSeen = mutableListOf<Pair<Seen, Zone>>()
        var myLands = 0
        var myUntapped = 0
        val cardSeen = mutableListOf<Triple<Card, Seen, Zone>>()
        val handH = lines.filter { it.cy > 0.6f && (Matcher.normalize(it.text) in BASIC || match(it.text.trim()) != null) }
            .maxOfOrNull { it.h } ?: 0f
        // Arena shows "x4" on a stack of identical lands.
        val stacks = lines.mapNotNull { s -> STACK.find(s.text.trim())?.let { s to it.groupValues[1].toInt() } }
        for (s in lines) {
            val t = s.text.trim()
            val norm = Matcher.normalize(t)
            var zone = zoneOf(s, mull, handH)
            if (zone == Zone.MINE && match(t)?.let { isSpell(it) } == true) zone = Zone.HAND
            if (zone == Zone.HAND && s.glow >= 0f && (norm in BASIC || match(t) != null)) {
                measured = true
                val name = if (norm in BASIC) t.replaceFirstChar { it.uppercase() } else match(t)!!.name
                if (s.glow >= GLOW_MIN) playable += name else notPlayable += name
            }
            when {
                norm in BASIC -> {
                    val n = if (zone == Zone.HAND || zone == Zone.OPENING) 1
                        else stacks.filter { (b, _) -> kotlin.math.abs(b.cx - s.cx) < 0.07f && kotlin.math.abs(b.cy - s.cy) < 0.07f }
                            .minByOrNull { (b, _) -> kotlin.math.abs(b.cx - s.cx) + kotlin.math.abs(b.cy - s.cy) }?.second ?: 1
                    lands.getOrPut(zone) { linkedMapOf() }.merge(t.replaceFirstChar { it.uppercase() }, n, Int::plus)
                    if (zone == Zone.MINE) { myLands += n; if (!s.tapped) myUntapped += n }
                }
                STACK.matches(t) -> {}
                PT.matches(t) && s.cy < 0.8f -> {
                    val z = if (s.cy < 0.44f) Zone.THEIRS else Zone.MINE
                    creatures.getOrPut(z) { mutableListOf() }.add(t)
                    ptSeen += s to z
                }
                LIFE.matches(t) && kotlin.math.abs(s.cx - 0.5f) < 0.07f && (s.cy < 0.15f || s.cy > 0.82f) ->
                    if (s.cy < 0.15f) lifeOpp = t.toInt() else lifeYou = t.toInt()
                else -> {
                    val c = if ("basic land" in norm) null else match(t)
                    if (c != null) {
                        cards += c to zone
                        if (zone == Zone.MINE || zone == Zone.THEIRS) cardSeen += Triple(c, s, zone)
                        if (zone == Zone.MINE && isLand(c)) { myLands++; if (!s.tapped) myUntapped++ }
                    } else other += t
                }
            }
        }
        // Link each P/T box to the card name drawn above it on the same small battlefield card.
        val used = mutableSetOf<Int>()
        val fighters = linkedMapOf<Zone, MutableList<Fighter>>()
        for ((p, z) in ptSeen) {
            val (pw, tg) = pt(p.text.trim()) ?: continue
            val best = cardSeen.withIndex()
                .filter { (i, c) -> i !in used && c.third == z && p.cy - c.second.cy in 0.03f..0.2f && kotlin.math.abs(p.cx - c.second.cx) < 0.07f }
                .minByOrNull { (_, c) -> kotlin.math.abs(p.cx - c.second.cx) + (p.cy - c.second.cy) }
            val f = if (best == null) Fighter(p.text.trim(), pw, tg, tapped = p.tapped)
                else {
                    used += best.index
                    Fighter("${best.value.first.name} ${p.text.trim()}", pw, tg, combatKeywords(best.value.first), p.tapped || best.value.second.tapped)
                }
            fighters.getOrPut(z) { mutableListOf() }.add(f)
        }
        return Board(cards, lands, creatures, lifeYou, lifeOpp, phase, other,
            if (measured) playable else null, notPlayable - playable, fighters,
            if (myLands > 0 && lines.any { it.angle != 0f }) myUntapped else null, if (myLands > 0) myLands else null)
    }

    /**
     * Rule checks on an answer, shown under it as warnings: suggesting a card that's already in play,
     * one that Arena says can't be played now, or more than one land.
     */
    fun check(answer: String, b: Board): List<String> {
        val play = Regex("PLAY:(.*)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.lowercase() ?: answer.lowercase()
        val out = mutableListOf<String>()
        if (b.phase in setOf(Phase.BLOCK, Phase.ATTACK, Phase.OPPONENT)) {
            // Outside my main phase only instants and flash cards can be cast.
            if (Regex("\\b(cast|play)\\b").containsMatchIn(play))
                for ((c, z) in b.cards) if (z == Zone.HAND && !isInstantSpeed(c) && c.name.lowercase() in play)
                    out += "${c.name} can't be cast now: it isn't an instant and doesn't have flash."
            return out.distinct()
        }
        if (b.phase !in setOf(Phase.MAIN1, Phase.MAIN2, Phase.UNKNOWN)) return emptyList()
        val hand = b.cards.filter { it.second == Zone.HAND }.map { it.first.name }.toSet()
        val mine = b.cards.filter { it.second == Zone.MINE }.map { it.first.name }.toSet()
        val castWords = Regex("\\b(cast|play|playing|casting)\\b").containsMatchIn(play)
        if (castWords) for (n in mine - hand) if (n.lowercase() in play && !Regex("(attack|tap|use|activate|block)[^.]*" + Regex.escape(n.lowercase())).containsMatchIn(play))
            out += "$n is already on your battlefield."
        if (castWords) for (n in b.notPlayable) if (n.lowercase() in play) out += "Arena says $n can't be played right now."
        val landWords = Regex("\\b(plains|island|swamp|mountain|forest|land)s?\\b").findAll(play).count()
        if (landWords >= 2 && Regex("\\b(and|then)\\b").containsMatchIn(play) && play.contains("play")) out += "You can only play one land per turn."
        return out.distinct()
    }

    /** Mana value from the rules line's cost, e.g. "{2}{G}{G}" = 4; null if the card has no cost. */
    fun manaValue(c: Card): Int? {
        val cost = c.rules.split(" — ").firstOrNull()?.takeIf { it.startsWith("{") } ?: return null
        return Regex("\\{([^}]+)}").findAll(cost).sumOf { m -> m.groupValues[1].toIntOrNull() ?: if (m.groupValues[1] == "X") 0 else 1 }
    }

    fun isLand(c: Card) = c.rules.split(" — ").take(2).any { Regex("\\bLand\\b").containsMatchIn(it) }

    /** Counted facts about the opening hand, so the model doesn't have to count. */
    fun openingFacts(b: Board): String? {
        if (b.phase != Phase.MULLIGAN) return null
        val cards = b.cards.filter { it.second == Zone.OPENING }.map { it.first }.distinctBy { it.name }
        val basics = b.lands[Zone.OPENING].orEmpty().values.sum()
        if (cards.isEmpty() && basics == 0) return null
        val lands = basics + cards.count { isLand(it) }
        val spells = cards.filterNot { isLand(it) }
        val cheap = spells.count { (manaValue(it) ?: 9) <= 2 }
        return "Opening hand facts: $lands lands, ${spells.size} spells, $cheap of them cost 2 or less" +
            " (counts may miss stacked duplicates). A keepable 7 usually has 2-5 lands and early plays."
    }

    private fun pt(s: String): Pair<Int, Int>? = Regex("^(\\d+)/(\\d+)$").find(s)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** A creature on the battlefield for combat math: its power/toughness box, linked to its name when found. */
    data class Fighter(val label: String, val p: Int, val t: Int, val kw: Set<String> = emptySet(), val tapped: Boolean = false)

    private val COMBAT_KW = listOf("flying", "reach", "deathtouch", "first strike", "double strike", "menace", "indestructible", "trample", "defender")

    fun combatKeywords(c: Card): Set<String> {
        val r = c.rules.lowercase()
        return COMBAT_KW.filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(r) }.toSet()
    }

    private fun canBlock(blk: Fighter, att: Fighter) =
        "flying" !in att.kw || "flying" in blk.kw || "reach" in blk.kw

    private fun deals(x: Fighter, y: Fighter) = x.p > 0 && "indestructible" !in y.kw && (x.p >= y.t || "deathtouch" in x.kw)

    /** (attacker dies, blocker dies) for a one-on-one block, with first strike handled. */
    fun fight(a: Fighter, b: Fighter): Pair<Boolean, Boolean> {
        val aFirst = "first strike" in a.kw || "double strike" in a.kw
        val bFirst = "first strike" in b.kw || "double strike" in b.kw
        return when {
            aFirst && !bFirst -> { val bDies = deals(a, b); (if (bDies) false else deals(b, a)) to bDies }
            bFirst && !aFirst -> { val aDies = deals(b, a); aDies to (if (aDies) false else deals(a, b)) }
            else -> deals(b, a) to deals(a, b)
        }
    }

    private fun fighters(b: Board, z: Zone): List<Fighter> =
        b.fighters[z]?.takeIf { it.isNotEmpty() }
            ?: b.creatures[z].orEmpty().mapNotNull { s -> pt(s)?.let { Fighter(s, it.first, it.second) } }

    /** Combat math from power/toughness plus key abilities (flying, deathtouch, first strike, ...). */
    fun combatFacts(b: Board): String? {
        val allMine = fighters(b, Zone.MINE).distinctBy { it.label }
        val allTheirs = fighters(b, Zone.THEIRS).distinctBy { it.label }
        if (allMine.isEmpty() || allTheirs.isEmpty()) return null
        // Tapped creatures can't block or attack. While I'm blocking, their tapped creatures are the attackers.
        val mine = allMine.filter { !it.tapped }
        val theirs = when (b.phase) {
            Phase.BLOCK -> allTheirs.filter { it.tapped }.ifEmpty { allTheirs }
            else -> allTheirs.filter { !it.tapped }
        }
        if (mine.isEmpty() && b.phase == Phase.BLOCK) return "Combat: all your creatures are tapped, so you can't block.\n"
        if (mine.isEmpty()) return null
        fun name(f: Fighter) = f.label + if (f.kw.isEmpty()) "" else " (" + f.kw.joinToString(", ") + ")"
        val sb = StringBuilder("Combat math (power/toughness and key abilities; other abilities ignored):\n")
        if (b.phase == Phase.BLOCK && allTheirs.any { it.tapped }) sb.append("- Attacking you (tapped): ").append(theirs.joinToString(", ") { name(it) }).append('\n')
        if (b.phase != Phase.BLOCK && theirs.isEmpty()) sb.append("- All their creatures are tapped: nothing can block.\n")
        when (b.phase) {
            Phase.BLOCK -> for (att in theirs) {
                if (att.p == 0 && "defender" in att.kw) continue
                val outs = mine.mapNotNull { blk ->
                    if (!canBlock(blk, att)) return@mapNotNull null
                    val (attDies, blkDies) = fight(att, blk)
                    when {
                        attDies && !blkDies -> "your ${blk.label} kills it and survives"
                        attDies && blkDies -> "your ${blk.label} trades with it"
                        !blkDies -> "your ${blk.label} blocks it safely"
                        else -> null
                    }
                }
                sb.append("- If their ${name(att)} attacks: ").append(when {
                    "menace" in att.kw -> "it needs two blockers. " + (if (outs.isEmpty()) "" else "One-on-one: " + outs.joinToString("; "))
                    mine.none { canBlock(it, att) } -> "none of your creatures can block it"
                    outs.isEmpty() -> "every block loses your creature"
                    else -> outs.joinToString("; ") + if ("trample" in att.kw) " (it has trample: extra damage still hits you)" else ""
                }).append('\n')
            }
            Phase.ATTACK, Phase.MAIN1 -> for (att in mine) {
                if (att.p == 0 || "defender" in att.kw) continue
                val blockers = theirs.filter { canBlock(it, att) }
                if (theirs.isEmpty()) continue
                sb.append("- Your ${name(att)} attacking: ")
                if (blockers.isEmpty()) { sb.append("nothing they have can block it\n"); continue }
                val killers = blockers.filter { fight(att, it).first }.map { it.label }.distinct()
                val victims = blockers.filter { fight(att, it).second }.map { it.label }.distinct()
                sb.append(if (killers.isEmpty()) "no blocker of theirs kills it" else "dies if blocked by their ${killers.joinToString("/")}")
                    .append(if (victims.isEmpty()) "" else "; kills a blocking ${victims.joinToString("/")}").append('\n')
            }
            else -> return null
        }
        lethalNote(b, mine, theirs)?.let { sb.append(it).append('\n') }
        return sb.toString()
    }

    /**
     * Race math. My turn: damage that gets through if everything attacks and they block my biggest
     * blockable attackers one each. Blocking: their total power against my life.
     */
    fun lethalNote(b: Board, mine: List<Fighter>, theirs: List<Fighter>): String? = when (b.phase) {
        Phase.ATTACK, Phase.MAIN1 -> {
            val attackers = mine.filter { it.p > 0 && "defender" !in it.kw }
            val evasive = attackers.filter { a -> theirs.none { canBlock(it, a) } }
            val blockable = (attackers - evasive.toSet()).sortedByDescending { it.p }
            val through = evasive.sumOf { it.p } + blockable.drop(theirs.size).sumOf { it.p }
            val life = b.lifeOpp
            when {
                attackers.isEmpty() -> null
                life != null && through >= life ->
                    "- LETHAL: even if they block your biggest attackers, at least $through damage gets through and they're at $life. Attack with everything."
                life != null -> "- If everything attacks, at least $through damage gets through (they're at $life)."
                else -> null
            }
        }
        Phase.BLOCK -> {
            val total = theirs.sumOf { it.p }
            val life = b.lifeYou
            when {
                life == null || total == 0 -> null
                total >= life -> "- DANGER: their creatures have $total power and you're at $life. Not blocking could lose the game; block to survive."
                else -> "- Their creatures have $total power in total; you're at $life."
            }
        }
        else -> null
    }

    /** Second try after [check] found problems: same board, plus what was wrong with the first answer. */
    fun retry(prompt: String, answer: String, problems: List<String>): String {
        val q = prompt.lastIndexOf("Answer in exactly this format:")
        val head = if (q > 0) prompt.substring(0, q) else prompt + "\n"
        val tail = if (q > 0) prompt.substring(q) else "Answer in exactly this format:\nPLAY: ...\nWHY: ..."
        return head + "My first answer was: \"" + answer.replace('\n', ' ').take(300) + "\"\n" +
            "That answer is wrong because:\n" + problems.joinToString("\n") { "- $it" } +
            "\nGive a corrected answer that avoids these mistakes. " + tail
    }

    /** One-line board description, saved with a lesson so it can be shown back to the model. */
    fun summary(b: Board): String {
        val parts = mutableListOf<String>()
        for (z in Zone.values()) {
            val names = b.cards.filter { it.second == z }.map { it.first.name }.distinct()
            val lands = b.lands[z].orEmpty().entries.joinToString(", ") { "${it.key} x${it.value}" }
            val pts = b.creatures[z].orEmpty().joinToString(", ")
            val bits = listOf(names.joinToString(", "), lands, if (pts.isEmpty()) "" else "P/T $pts").filter { it.isNotEmpty() }
            if (bits.isNotEmpty()) parts += z.label + ": " + bits.joinToString("; ")
        }
        if (b.lifeYou != null || b.lifeOpp != null) parts += "life me ${b.lifeYou ?: "?"} / opp ${b.lifeOpp ?: "?"}"
        return parts.joinToString(" | ")
    }

    fun build(board: Board, maxCards: Int = 25, lessons: List<Lesson> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("This is a screenshot of my MTG Arena game on my phone. My side is the bottom half of the screen ")
            .append("and my hand is along the bottom edge; my opponent is the top half.\n")
        if (board.phase.note.isNotEmpty()) sb.append("Current step: ").append(board.phase.note).append('\n')
        if (board.lifeYou != null || board.lifeOpp != null)
            sb.append("Life: me ${board.lifeYou ?: "?"}, opponent ${board.lifeOpp ?: "?"}.\n")
        sb.append('\n')
        val unique = board.cards.distinctBy { it.first.name to it.second }.take(maxCards)
        for (zone in Zone.values()) {
            val inZone = unique.filter { it.second == zone }
            val lands = board.lands[zone].orEmpty()
            val pts = board.creatures[zone].orEmpty()
            if (inZone.isEmpty() && lands.isEmpty() && pts.isEmpty()) continue
            sb.append(zone.label).append(":\n")
            for ((c, _) in inZone) {
                sb.append("- ").append(c.name)
                if (zone == Zone.HAND && board.playable != null)
                    sb.append(if (c.name in board.playable) " [PLAYABLE NOW]" else " [can't play now]")
                if (c.rules.isNotEmpty()) sb.append(": ").append(c.rules.take(320))
                sb.append('\n')
            }
            if (lands.isNotEmpty()) {
                val what = lands.entries.joinToString(", ") { "${it.key} x${it.value}" }
                if (zone == Zone.HAND && board.playable != null) {
                    val can = lands.keys.any { it in board.playable }
                    sb.append("- Basic lands: ").append(what)
                        .append(if (can) " [PLAYABLE NOW: I haven't played a land this turn]" else " [can't play now: I already played a land this turn]").append('\n')
                } else if (zone == Zone.HAND || zone == Zone.OPENING) sb.append("- Basic lands: ").append(what).append('\n')
                else sb.append("- Lands already in play (at least): ").append(what).append(" (stacked lands may hide more)\n")
            }
            if (pts.isNotEmpty()) sb.append("- Creatures' power/toughness shown: ").append(pts.joinToString(", ")).append('\n')
            sb.append('\n')
        }
        if (board.playable != null && board.phase != Phase.BLOCK && board.phase != Phase.MULLIGAN) {
            sb.append(if (board.playable.isEmpty()) "Arena shows nothing in my hand is playable right now. Don't suggest casting or playing cards from my hand.\n\n"
                else "Arena highlights the cards I can play right now. Only suggest cards marked [PLAYABLE NOW].\n\n")
        }
        if (board.lands[Zone.MINE].isNullOrEmpty().not())
            sb.append("Lands already on my battlefield are in play: don't tell me to play them again. Only one land can be played per turn.\n\n")
        val text = unique.joinToString(" ") { it.first.rules.lowercase() }
        val kw = KEYWORDS.filterKeys { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
        if (kw.isNotEmpty()) {
            sb.append("Keyword reminders:\n")
            for ((k, v) in kw) sb.append("- ").append(k).append(": ").append(v).append('\n')
            sb.append('\n')
        }
        // Only short UI labels (buttons, prompts); longer fragments are rules text the cards already cover.
        val other = board.other.map { it.trim() }.filter { it.length in 2..24 && it.any(Char::isLetter) }.distinct().joinToString(" | ").take(220)
        if (other.isNotEmpty()) sb.append("Other text on screen (buttons, prompts): ").append(other).append("\n\n")
        if (lessons.isNotEmpty()) {
            sb.append("Lessons from my past games in similar spots (I confirmed these plays; follow them when they fit):\n")
            for (l in lessons) sb.append("- Step ").append(l.phase).append("; board: ").append(l.board.take(220))
                .append(" -> right play: ").append(l.correct.take(200).replace('\n', ' ')).append('\n')
            sb.append('\n')
        }
        openingFacts(board)?.let { sb.append(it).append("\n\n") }
        if (board.untappedLands != null && board.phase in setOf(Phase.MAIN1, Phase.MAIN2, Phase.UNKNOWN))
            sb.append("Mana: ${board.untappedLands} of my ${board.totalLands} lands are untapped, so I have about ${board.untappedLands} mana available now.\n\n")
        combatFacts(board)?.let { sb.append(it).append('\n') }
        if (board.phase in setOf(Phase.BLOCK, Phase.ATTACK, Phase.OPPONENT)) {
            val hand = unique.filter { it.second == Zone.HAND }.map { it.first }
            val fast = hand.filter { isInstantSpeed(it) && (board.playable == null || it.name in board.playable) }
            sb.append(if (fast.isEmpty()) "I have no instant-speed cards to use right now; only attacks or blocks matter.\n\n"
                else "Instant-speed cards I could use right now: ${fast.joinToString(", ") { it.name }}. Nothing else in my hand can be cast at this moment.\n\n")
        }
        // Small models follow what's closest to the question, so repeat the hard limits here.
        if (board.phase in setOf(Phase.MAIN1, Phase.MAIN2, Phase.UNKNOWN, Phase.ATTACK)) {
            val inPlay = unique.filter { it.second == Zone.MINE }.map { it.first.name }
            sb.append("Summary before you answer:\n")
            board.playable?.let { sb.append("- Cards I can play right now: ").append(if (it.isEmpty()) "none" else it.joinToString(", ")).append('\n') }
            if (inPlay.isNotEmpty()) sb.append("- Already on my battlefield (never cast or play these again): ").append(inPlay.joinToString(", ")).append('\n')
            sb.append("- At most one land per turn.\n\n")
        }
        sb.append(board.phase.question)
        return sb.toString()
    }
}
