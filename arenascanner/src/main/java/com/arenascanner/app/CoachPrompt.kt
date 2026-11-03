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
    data class Seen(val text: String, val cx: Float, val cy: Float, val h: Float, val glow: Float = -1f)

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

    /** Instants and sorceries never stay on the battlefield. */
    fun isSpell(c: Card) = c.rules.split(" — ").take(2).any { "Instant" in it || "Sorcery" in it }

    private val STACK = Regex("^[xX×]\\s?(\\d{1,2})$")

    fun analyze(lines: List<Seen>, match: (String) -> Card?): Board {
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
                }
                STACK.matches(t) -> {}
                PT.matches(t) && s.cy < 0.8f -> creatures.getOrPut(if (s.cy < 0.44f) Zone.THEIRS else Zone.MINE) { mutableListOf() }.add(t)
                LIFE.matches(t) && kotlin.math.abs(s.cx - 0.5f) < 0.07f && (s.cy < 0.15f || s.cy > 0.82f) ->
                    if (s.cy < 0.15f) lifeOpp = t.toInt() else lifeYou = t.toInt()
                else -> {
                    val c = if ("basic land" in norm) null else match(t)
                    if (c != null) cards += c to zone else other += t
                }
            }
        }
        return Board(cards, lands, creatures, lifeYou, lifeOpp, phase, other,
            if (measured) playable else null, notPlayable - playable)
    }

    /**
     * Rule checks on an answer, shown under it as warnings: suggesting a card that's already in play,
     * one that Arena says can't be played now, or more than one land.
     */
    fun check(answer: String, b: Board): List<String> {
        if (b.phase !in setOf(Phase.MAIN1, Phase.MAIN2, Phase.UNKNOWN)) return emptyList()
        val play = Regex("PLAY:(.*)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.lowercase() ?: answer.lowercase()
        val out = mutableListOf<String>()
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
        val other = board.other.map { it.trim() }.filter { it.length in 1..40 }.distinct().joinToString(" | ").take(400)
        if (other.isNotEmpty()) sb.append("Other text on screen (buttons, prompts): ").append(other).append("\n\n")
        if (lessons.isNotEmpty()) {
            sb.append("Lessons from my past games in similar spots (I confirmed these plays; follow them when they fit):\n")
            for (l in lessons) sb.append("- Step ").append(l.phase).append("; board: ").append(l.board.take(220))
                .append(" -> right play: ").append(l.correct.take(200).replace('\n', ' ')).append('\n')
            sb.append('\n')
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
