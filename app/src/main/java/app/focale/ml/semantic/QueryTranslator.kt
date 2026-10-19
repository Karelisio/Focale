package app.focale.ml.semantic

import app.focale.ml.LabelDictionary

/**
 * Traduction hors ligne d'une recherche française en anglais, pour l'encodeur de texte
 * CLIP (entraîné sur des légendes anglaises), et gabarit « a photo of … ».
 *
 * Traduction mot à mot : dictionnaire des mots courants (animaux, lieux, objets, couleurs,
 * météo, activités, prépositions…) complété par les étiquettes de [LabelDictionary], et
 * quelques règles — expressions figées (« coucher de soleil »), adjectifs replacés avant le
 * nom (« voiture rouge » → « red car »), pluriels, articles, verbes à la forme en -ing
 * (« enfants qui jouent » → « children playing »). Une requête déjà en anglais est gardée
 * telle quelle. Les mots inconnus (noms propres…) passent inchangés.
 *
 * Les mots reçus sont ceux de [LabelDictionary.normalize] : minuscules, sans accents.
 */
object QueryTranslator {

    /** Nature d'un mot : décide de l'ordre, des articles et du choix entre plusieurs sens. */
    internal enum class Kind { NOUN, ADJ, ADV, VERB, PREP, PHRASE, DET, NUM, CONJ, DROP, CONTEXT, UNKNOWN }

    /**
     * Sens d'un mot ou d'une expression : [en] est la traduction, [plural] le pluriel
     * anglais des noms ; [uncountable] marque les noms sans article (« snow »).
     */
    internal class Sense(
        val kind: Kind,
        val en: String,
        val plural: String = en,
        val uncountable: Boolean = false,
        /** Nom toujours au pluriel (« gens » → « people »). */
        val alwaysPlural: Boolean = false,
    )

    private class Token(
        val en: String,
        val kind: Kind,
        val plural: Boolean = false,
        val uncountable: Boolean = false,
        /** Mot français d'origine, pour les mots dont la traduction dépend du contexte. */
        val source: String = en,
    )

    // Collections lues pendant la construction du dictionnaire : déclarées avant lui.

    private val NUMBERS = listOf(
        "deux" to "two", "trois" to "three", "quatre" to "four", "cinq" to "five", "six" to "six", "sept" to "seven",
        "huit" to "eight", "dix" to "ten", "douze" to "twelve", "vingt" to "twenty", "cent" to "hundred",
        "plusieurs" to "several", "quelques" to "a few", "beaucoup" to "many", "nombreux" to "many", "tous" to "all",
        "toutes" to "all",
    )

    /** Mots dont la traduction dépend du voisinage (résolus par [arrange]) ou figés. */
    private val SPECIAL: Map<String, Sense> = buildMap {
        for (word in listOf("de", "d", "du", "des", "a", "au", "aux", "en")) put(word, Sense(Kind.CONTEXT, word))
        for (word in listOf("le", "la", "l", "les")) put(word, Sense(Kind.DET, "the"))
        for (word in listOf("un", "une")) put(word, Sense(Kind.DET, "a"))
        put("et", Sense(Kind.CONJ, "and"))
        put("ou", Sense(Kind.CONJ, "or"))
        put("mais", Sense(Kind.CONJ, "but"))
        for (word in listOf("qui", "que", "qu", "dont", "y")) put(word, Sense(Kind.DROP, ""))
        for ((word, en) in NUMBERS) put(word, Sense(Kind.NUM, en))
    }

    private val RELATIVE_WORDS = setOf("qui", "il", "elle", "ils", "elles", "on")
    private val EDGE_KINDS = setOf(Kind.PREP, Kind.CONJ, Kind.CONTEXT)

    private val CONSONANT_SOUND_PREFIXES = listOf("uni", "use", "usu", "euro", "one", "uk")
    private val VOWEL_SOUND_PREFIXES = listOf("hour", "honest", "honor", "heir")

    private val ENGLISH_FUNCTION_WORDS = setOf(
        "a", "an", "the", "of", "in", "on", "at", "with", "by", "for", "from", "to", "and", "or", "under", "over",
        "above", "below", "behind", "near", "next", "into", "onto", "inside", "outside", "around", "between",
        "among", "during", "after", "before", "without", "my", "your", "our", "their", "his", "her", "its",
        "this", "that", "these", "those", "some", "two", "three", "four", "five", "many", "several", "is", "are",
        "very", "front", "top", "bottom", "while", "who", "being",
    )

    private val FRENCH_FUNCTION_WORDS = setOf(
        "de", "d", "du", "des", "le", "la", "l", "les", "un", "une", "au", "aux", "en", "et", "ou", "qui", "que",
        "a", "sur", "sous", "dans", "avec", "sans", "pour", "par", "chez", "mon", "ma", "mes", "ton", "ta", "tes",
        "son", "sa", "ses", "notre", "nos", "votre", "vos", "leur", "leurs", "ce", "cet", "cette", "ces", "est",
        "sont", "il", "elle", "ils", "elles",
    )

    /** Dictionnaire français → sens possibles (le premier est le sens par défaut). */
    internal val dictionary: Map<String, List<Sense>> = buildDictionary()

    /** Longueur maximale (en mots) des expressions du dictionnaire. */
    private val longestExpression = dictionary.keys.maxOf { key -> key.count { it == ' ' } + 1 }

    /** Mots anglais connus, pour reconnaître une requête déjà en anglais. */
    private val englishWords: Set<String> = buildSet {
        addAll(ENGLISH_FUNCTION_WORDS)
        for (senses in dictionary.values) for (sense in senses) {
            if (' ' !in sense.en) add(sense.en)
            if (' ' !in sense.plural) add(sense.plural)
        }
        for (entry in LabelDictionary.entries) {
            val en = LabelDictionary.normalize(entry.en)
            if (' ' !in en) add(en)
        }
    }

    /** Noms anglais (singulier → indénombrable ?) et pluriels, pour l'article d'une requête anglaise. */
    private val englishNouns: Map<String, Boolean> = buildMap {
        for (senses in dictionary.values) for (sense in senses) if (sense.kind == Kind.NOUN) put(sense.en, sense.uncountable)
    }
    private val englishPlurals: Set<String> = buildSet {
        for (senses in dictionary.values) for (sense in senses) if (sense.kind == Kind.NOUN && sense.plural != sense.en) add(sense.plural)
    }
    private val englishAdjectives: Set<String> = buildSet {
        for (senses in dictionary.values) for (sense in senses) if (sense.kind == Kind.ADJ) add(sense.en)
    }

    /**
     * Texte à soumettre à l'encodeur, « a photo of … » (« a photo by the sea » pour un
     * complément seul), ou null si [words] ne décrit rien (mots vides, chiffres, sigles).
     */
    fun prompt(words: List<String>): String? {
        val tokens = translateTokens(words.filter { it.isNotBlank() })
        if (tokens.none(::isMeaningful)) return null
        val prefix = if (tokens.first().kind == Kind.PHRASE) "a photo " else "a photo of "
        return prefix + render(tokens)
    }

    /** Vrai si [word] est un mot du dictionnaire (français ou anglais), et non un nom propre. */
    fun isKnownWord(word: String): Boolean =
        lookup(word) != null || word in englishWords || word in FRENCH_FUNCTION_WORDS

    // ── Traduction ────────────────────────────────────────────────────────────────

    private fun translateTokens(words: List<String>): List<Token> {
        if (words.isEmpty()) return emptyList()
        if (looksEnglish(words)) return words.map(::englishToken)
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < words.size) {
            val expression = matchExpression(words, i)
            if (expression != null) {
                val (length, senses, plural) = expression
                tokens += choose(senses, tokens.lastOrNull(), words.getOrNull(i - 1), plural, words.subList(i, i + length).joinToString(" "))
                i += length
                continue
            }
            val word = words[i]
            val special = SPECIAL[word]
            if (special != null) {
                tokens += Token(special.en, special.kind, source = word)
            } else {
                val found = lookup(word)
                tokens += if (found == null) {
                    Token(word, Kind.UNKNOWN)
                } else {
                    choose(found.first, tokens.lastOrNull(), words.getOrNull(i - 1), found.second, word)
                }
            }
            i++
        }
        return arrange(tokens)
    }

    /** Plus longue expression du dictionnaire commençant à [start] : (longueur, sens, pluriel). */
    private fun matchExpression(words: List<String>, start: Int): Triple<Int, List<Sense>, Boolean>? {
        for (length in minOf(longestExpression, words.size - start) downTo 2) {
            val slice = words.subList(start, start + length)
            dictionary[slice.joinToString(" ")]?.let { return Triple(length, it, false) }
            // Expression au pluriel : « couchers de soleil », « feux d'artifice ».
            val singular = slice.mapIndexed { index, w -> if (index == 0) singularCandidates(w).firstOrNull() ?: w else w }
            if (singular != slice) dictionary[singular.joinToString(" ")]?.let { return Triple(length, it, true) }
        }
        return null
    }

    /** Sens d'un mot isolé et indicateur de pluriel français (forme réduite au singulier). */
    private fun lookup(word: String): Pair<List<Sense>, Boolean>? {
        dictionary[word]?.let { return it to false }
        for (candidate in singularCandidates(word)) dictionary[candidate]?.let { return it to true }
        return null
    }

    private fun singularCandidates(word: String): List<String> = buildList {
        if (word.length > 3 && (word.endsWith('s') || word.endsWith('x'))) add(word.dropLast(1))
        if (word.length > 4 && word.endsWith("aux")) add(word.dropLast(3) + "al")
    }

    /**
     * Sens retenu selon le mot précédent : après un article ou une préposition, un nom ;
     * après un nom, un adjectif ou un verbe ; après « qui », un verbe.
     */
    private fun choose(senses: List<Sense>, previous: Token?, previousWord: String?, plural: Boolean, source: String): Token {
        val preferred = when {
            previousWord in RELATIVE_WORDS -> listOf(Kind.VERB, Kind.ADJ, Kind.NOUN)
            previous == null -> listOf(senses.first().kind)
            previous.kind == Kind.DET || previous.kind == Kind.PREP || previous.kind == Kind.NUM ||
                previous.kind == Kind.CONTEXT -> listOf(Kind.NOUN, Kind.ADJ, Kind.VERB)
            previous.kind == Kind.NOUN || previous.kind == Kind.UNKNOWN -> listOf(Kind.ADJ, Kind.VERB, Kind.NOUN)
            else -> listOf(senses.first().kind)
        }
        val sense = preferred.firstNotNullOfOrNull { kind -> senses.firstOrNull { it.kind == kind } } ?: senses.first()
        val isPlural = plural && sense.kind == Kind.NOUN
        return Token(
            en = if (isPlural) sense.plural else sense.en,
            kind = sense.kind,
            plural = isPlural || sense.alwaysPlural,
            uncountable = sense.uncountable,
            source = source,
        )
    }

    /** Ordre anglais et mots dépendant du contexte. */
    private fun arrange(input: List<Token>): List<Token> {
        val tokens = input.filter { it.kind != Kind.DROP }.toMutableList()

        // Adjectifs placés après le nom : « voiture rouge » → « red car ».
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.kind == Kind.NOUN || token.kind == Kind.UNKNOWN) {
                val end = adjectiveGroupEnd(tokens, i + 1)
                if (end > i + 1) {
                    val group = tokens.subList(i + 1, end).toList()
                    repeat(group.size) { tokens.removeAt(i + 1) }
                    tokens.addAll(i, group)
                    i += group.size
                }
            }
            i++
        }

        // Articles contractés et « de » : « verre de vin » → « glass of wine », « du pain » → « bread ».
        val result = ArrayList<Token>(tokens.size)
        for ((index, token) in tokens.withIndex()) {
            if (token.kind != Kind.CONTEXT) {
                result += token
                continue
            }
            val previous = result.lastOrNull()
            val next = tokens.getOrNull(index + 1)
            val afterNoun = previous != null && (previous.kind == Kind.NOUN || previous.kind == Kind.UNKNOWN)
            val afterPlaying = previous?.kind == Kind.VERB && previous.en == "playing"
            val en = when (token.source) {
                "de", "d" -> if (afterNoun && next != null && next.kind != Kind.PREP) "of" else null
                "du" -> if (afterNoun) "of the" else null
                "des" -> if (afterNoun) "of" else null
                "a" -> when {
                    afterPlaying && next?.kind != Kind.DET -> null
                    next?.kind == Kind.UNKNOWN -> "in"
                    else -> "at"
                }
                "au" -> if (afterPlaying) null else "at the"
                "aux" -> when {
                    afterPlaying -> null
                    afterNoun -> "with"
                    else -> "at the"
                }
                else -> "in"
            }
            if (en != null) result += Token(en, Kind.PREP, source = token.source)
        }

        // Ni préposition ni conjonction aux extrémités : « chien en » → « dog ».
        while (result.isNotEmpty() && result.first().kind in EDGE_KINDS) result.removeAt(0)
        while (result.isNotEmpty() && (result.last().kind in EDGE_KINDS || result.last().kind == Kind.DET)) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    /** Fin (exclue) d'un groupe d'adjectifs commençant à [start] : « noir et blanc », « très beau ». */
    private fun adjectiveGroupEnd(tokens: List<Token>, start: Int): Int {
        var end = start
        var i = start
        while (i < tokens.size) {
            var j = i
            while (j < tokens.size && tokens[j].kind == Kind.ADV) j++
            if (j < tokens.size && tokens[j].kind == Kind.ADJ) {
                end = j + 1
                i = j + 1
                if (i < tokens.size && tokens[i].kind == Kind.CONJ && i + 1 < tokens.size &&
                    (tokens[i + 1].kind == Kind.ADJ || tokens[i + 1].kind == Kind.ADV)
                ) {
                    i++
                    continue
                }
            }
            break
        }
        return end
    }

    private fun render(tokens: List<Token>): String {
        val words = tokens.map { it.en }.toMutableList()
        if (needsArticle(tokens)) words.add(0, "a")
        // « a » devient « an » devant une voyelle.
        for (i in 0 until words.size - 1) {
            if (words[i] == "a" && startsWithVowelSound(words[i + 1])) words[i] = "an"
        }
        return words.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /** Un nom dénombrable au singulier en tête (éventuellement précédé d'adjectifs) prend « a ». */
    private fun needsArticle(tokens: List<Token>): Boolean {
        for (token in tokens) {
            when (token.kind) {
                Kind.ADV, Kind.ADJ, Kind.CONJ -> continue
                Kind.NOUN -> return !token.plural && !token.uncountable
                else -> return false
            }
        }
        return false
    }

    private fun startsWithVowelSound(word: String): Boolean {
        if (word.isEmpty()) return false
        if (CONSONANT_SOUND_PREFIXES.any { word.startsWith(it) }) return false
        if (VOWEL_SOUND_PREFIXES.any { word.startsWith(it) }) return true
        return word[0] in "aeiou"
    }

    private fun isMeaningful(token: Token): Boolean = when (token.kind) {
        Kind.NOUN, Kind.ADJ, Kind.VERB, Kind.PHRASE -> true
        Kind.UNKNOWN -> token.en.length >= 4 && token.en.all { it in 'a'..'z' }
        else -> false
    }

    // ── Requête anglaise ──────────────────────────────────────────────────────────

    /** Plus de mots propres à l'anglais qu'au français : la requête est gardée telle quelle. */
    private fun looksEnglish(words: List<String>): Boolean {
        var score = 0
        for (word in words) {
            val english = word in englishWords
            val french = lookup(word) != null || word in FRENCH_FUNCTION_WORDS
            if (english && !french) score++
            if (french && !english) score--
        }
        return score > 0
    }

    private fun englishToken(word: String): Token {
        englishNouns[word]?.let { uncountable -> return Token(word, Kind.NOUN, uncountable = uncountable) }
        return when {
            word in englishPlurals -> Token(word, Kind.NOUN, plural = true)
            word in englishAdjectives -> Token(word, Kind.ADJ)
            word in ENGLISH_FUNCTION_WORDS -> Token(word, Kind.DET)
            word in englishWords -> Token(word, Kind.VERB)
            else -> Token(word, Kind.UNKNOWN)
        }
    }

    // ── Dictionnaire ──────────────────────────────────────────────────────────────

    private fun buildDictionary(): Map<String, List<Sense>> {
        val map = LinkedHashMap<String, MutableList<Sense>>()
        fun add(key: String, sense: Sense) {
            val list = map.getOrPut(key) { mutableListOf() }
            if (list.none { it.kind == sense.kind && it.en == sense.en }) list += sense
        }
        fun entries(data: String) = data.split(Regex("\\s+")).filter { it.isNotBlank() }.map { entry ->
            val (keys, value) = entry.split('=', limit = 2)
            keys.split('|').map { it.replace('_', ' ') } to value.replace('_', ' ')
        }

        for ((keys, value) in entries(NOUNS)) {
            val parts = value.split('/')
            val en = parts[0]
            val sense = when (val plural = parts.getOrNull(1)) {
                null -> Sense(Kind.NOUN, en, englishPlural(en))
                "-" -> Sense(Kind.NOUN, en, en, uncountable = true)
                "+" -> Sense(Kind.NOUN, en, en, alwaysPlural = true)
                else -> Sense(Kind.NOUN, en, plural)
            }
            keys.forEach { add(it, sense) }
        }
        for ((keys, value) in entries(ADJECTIVES)) {
            val forms = if (keys.size == 1) adjectiveForms(keys.single()) else keys
            forms.forEach { add(it, Sense(Kind.ADJ, value)) }
        }
        for ((keys, value) in entries(VERBS)) keys.forEach { add(it, Sense(Kind.VERB, value)) }
        for ((keys, value) in entries(PREPOSITIONS)) keys.forEach { add(it, Sense(Kind.PREP, value)) }
        for ((keys, value) in entries(PHRASES)) keys.forEach { add(it, Sense(Kind.PHRASE, value)) }
        for ((keys, value) in entries(ADVERBS)) keys.forEach { add(it, Sense(Kind.ADV, value)) }
        for ((keys, value) in entries(FIXED)) keys.forEach { add(it, Sense(Kind.DET, value)) }
        for ((keys, _) in entries(DROPPED)) keys.forEach { add(it, Sense(Kind.DROP, "")) }

        // Étiquettes ML Kit : leur nom français, traduit par leur libellé anglais.
        for (entry in LabelDictionary.entries) {
            if ('(' in entry.fr) continue
            val key = LabelDictionary.normalize(entry.fr)
            val en = LabelDictionary.normalize(entry.en)
            if (key.isNotEmpty() && key !in map && key !in SPECIAL) add(key, Sense(Kind.NOUN, en, englishPlural(en)))
        }
        return map
    }

    /**
     * Formes d'un adjectif régulier : masculin, féminin, pluriels. Sans accents, « étoilé »
     * finit par « e » comme « rouge » : les formes en trop sont sans conséquence.
     */
    private fun adjectiveForms(base: String): List<String> =
        if (base.endsWith('s') || base.endsWith('x')) listOf(base, base + "e", base + "es")
        else listOf(base, base + "e", base + "s", base + "es")

    /** Pluriel anglais régulier ; les irréguliers sont donnés par le dictionnaire. */
    internal fun englishPlural(word: String): String {
        val head = word.substringBeforeLast(' ', "")
        val last = word.substringAfterLast(' ')
        val plural = when {
            last.endsWith("s") || last.endsWith("x") || last.endsWith("z") || last.endsWith("ch") || last.endsWith("sh") -> last + "es"
            last.endsWith("y") && last.length > 1 && last[last.length - 2] !in "aeiou" -> last.dropLast(1) + "ies"
            else -> last + "s"
        }
        return if (head.isEmpty()) plural else "$head $plural"
    }

    // Données : « fr=en », variantes françaises séparées par « | », espaces notés « _ ».
    // Noms : « en/pluriel » pour un pluriel anglais irrégulier, « en/- » pour un nom
    // indénombrable (sans article), « en/+ » pour un nom toujours au pluriel.

    private const val NOUNS = """
chien|toutou=dog chiot=puppy/puppies chat|minou=cat chaton=kitten oiseau=bird cheval=horse poney=pony ane=donkey
mule=mule vache=cow veau=calf/calves taureau=bull boeuf=ox/oxen mouton|brebis=sheep/sheep agneau=lamb
chevre|bouc=goat cochon|porc=pig poule=hen poussin=chick coq=rooster canard=duck caneton=duckling oie=goose/geese
cygne=swan pigeon=pigeon colombe=dove mouette|goeland=seagull aigle=eagle faucon=falcon hibou|chouette=owl
perroquet=parrot moineau=sparrow corbeau=crow paon=peacock flamant|flamant_rose=flamingo pelican=pelican
autruche=ostrich poisson=fish/fish requin=shark dauphin=dolphin baleine=whale phoque=seal otarie=sea_lion
tortue=turtle grenouille=frog crapaud=toad serpent=snake lezard=lizard crocodile=crocodile lion=lion lionne=lioness
tigre=tiger elephant=elephant girafe=giraffe zebre=zebra singe=monkey gorille=gorilla ours=bear panda=panda
loup=wolf/wolves renard=fox cerf|biche|chevreuil=deer/deer sanglier=wild_boar ecureuil=squirrel lapin=rabbit
lievre=hare souris=mouse/mice rat=rat hamster=hamster herisson=hedgehog papillon=butterfly/butterflies abeille=bee
guepe=wasp fourmi=ant araignee=spider insecte=insect coccinelle=ladybug escargot=snail meduse=jellyfish/jellyfish
crabe=crab homard=lobster crevette=shrimp/shrimp chameau|dromadaire=camel kangourou=kangaroo koala=koala
pingouin|manchot=penguin hippopotame=hippo rhinoceros=rhino leopard=leopard guepard=cheetah lama=llama alpaga=alpaca
dinosaure=dinosaur animal|bete=animal faune=wildlife/- animal_de_compagnie=pet

plage=beach mer=sea ocean=ocean vague=wave sable=sand/- dune=dune falaise=cliff rocher|roche=rock pierre=stone
galet=pebble ile=island lac=lake etang|mare=pond riviere|fleuve=river ruisseau=stream cascade|chute_d_eau=waterfall
montagne|mont=mountain colline=hill vallee=valley sommet=summit pic=peak glacier=glacier volcan=volcano foret=forest
bois=woods/+ arbre=tree palmier=palm_tree sapin=fir_tree chene=oak_tree jungle=jungle desert=desert
prairie|pre=meadow champ=field jardin=garden parc=park fleur=flower rose=rose tulipe=tulip tournesol=sunflower
marguerite=daisy/daisies coquelicot=poppy/poppies lavande=lavender/- orchidee=orchid cactus=cactus/cacti
herbe=grass/- pelouse=lawn feuille=leaf/leaves branche=branch mousse=moss/- champignon=mushroom ciel=sky/skies
nuage=cloud soleil=sun lune=moon etoile=star horizon=horizon paysage=landscape panorama=panorama vue=view grotte=cave
canyon=canyon gorge=gorge marais=marsh oasis=oasis/oases recif=reef corail=coral/- algue=seaweed/- coquillage=seashell
eau=water/- neige=snow/- pluie=rain/- orage=thunderstorm tempete=storm brouillard=fog/- brume=mist/- vent=wind/-
eclair=lightning/- grele=hail/- givre=frost/- arc_en_ciel=rainbow nuit=night jour|journee=day matin=morning
soir=evening midi=noon aube=dawn crepuscule=dusk ete=summer/- hiver=winter/- printemps=spring/- automne=autumn/-
coucher_de_soleil|coucher_du_soleil=sunset lever_de_soleil|lever_du_soleil=sunrise clair_de_lune=moonlight/-
ciel_etoile=starry_sky voie_lactee=milky_way/- aurore_boreale=northern_lights/+
bord_de_mer=seaside/-


ville=city/cities village=village campagne=countryside/- rue=street route=road chemin=path sentier=trail
autoroute=highway place=square marche=market pont=bridge port=harbor quai=quay jetee|ponton=pier phare=lighthouse
chateau=castle eglise=church chapelle=chapel cathedrale=cathedral mosquee=mosque temple=temple musee=museum
monument=monument tour=tower statue=statue fontaine=fountain gare=train_station aeroport=airport stade=stadium
ecole=school universite=university/universities hopital=hospital maison=house immeuble|batiment=building
appartement=apartment cabane=hut chalet=chalet tente=tent hotel=hotel restaurant=restaurant bar=bar
boulangerie=bakery/bakeries magasin|boutique=shop supermarche=supermarket cuisine=kitchen salon=living_room
chambre=bedroom salle_de_bain|salle_de_bains=bathroom salle_a_manger=dining_room bureau=office piscine=swimming_pool
terrasse=terrace balcon=balcony/balconies fenetre=window porte=door mur=wall toit=roof escalier=stairs/+
cour=courtyard ruine=ruin zoo=zoo aquarium=aquarium cirque=circus cinema=cinema theatre=theater scene=stage
bibliotheque=library/libraries ferme=farm grange=barn usine=factory/factories chantier=construction_site
parking=parking_lot gratte_ciel=skyscraper tunnel=tunnel vignoble=vineyard vigne=vine verger=orchard
potager=vegetable_garden fete_foraine=funfair grande_roue=ferris_wheel aire_de_jeux=playground marche_de_noel=christmas_market
tour_eiffel=the_eiffel_tower/- arc_de_triomphe=the_arc_de_triomphe/-

personne=person/people gens=people/+ homme=man/men femme=woman/women enfant=child/children bebe=baby/babies
garcon=boy fille=girl fillette=little_girl ado|adolescent=teenager ami|amie|copain|copine|pote=friend
famille=family/families parent=parent pere=father mere=mother papa=dad maman=mom fils=son/sons frere=brother
soeur=sister grand_pere|papi|papy=grandfather grand_mere|mamie|mamy=grandmother grands_parents=grandparents/+
oncle=uncle tante=aunt cousin|cousine=cousin couple=couple mariee=bride marie=groom foule=crowd equipe=team
groupe=group touriste=tourist portrait=portrait selfie=selfie visage=face sourire=smile oeil=eye yeux=eyes/+
main=hand pied=foot/feet jambe=leg bras=arm/arms tete=head cheveux=hair/- barbe=beard moustache=mustache
lunettes=glasses/+ policier=police_officer pompier=firefighter soldat=soldier musicien=musician
chanteur|chanteuse=singer danseur|danseuse=dancer joueur|joueuse=player cycliste=cyclist coureur|coureuse=runner
surfeur|surfeuse=surfer skieur|skieuse=skier nageur|nageuse=swimmer pecheur=fisherman/fishermen cuisinier=cook
chef=chef medecin=doctor eleve|etudiant|etudiante=student professeur|maitresse=teacher bonhomme_de_neige=snowman/snowmen
pere_noel=santa_claus/- princesse=princess prince=prince

nourriture=food/- repas=meal plat=dish assiette=plate pizza=pizza pates=pasta/- spaghetti=spaghetti/- riz=rice/-
pain=bread/- baguette=baguette croissant=croissant fromage=cheese gateau=cake gateau_au_chocolat=chocolate_cake tarte=pie tarte_aux_pommes=apple_pie dessert=dessert
chocolat=chocolate bonbon=candy/candies biscuit=cookie crepe=crepe gaufre=waffle fruit=fruit pomme=apple poire=pear
banane=banana orange=orange citron=lemon fraise=strawberry/strawberries framboise=raspberry/raspberries
cerise=cherry/cherries raisin=grapes/+ peche=fishing/- ananas=pineapple pasteque=watermelon melon=melon
legume=vegetable salade=salad tomate=tomato/tomatoes carotte=carrot pomme_de_terre=potato/potatoes frites=fries/+
burger=burger hamburger=hamburger sandwich=sandwich soupe=soup viande=meat/- steak=steak poulet=chicken oeuf=egg
fruits_de_mer=seafood/- huitre=oyster moule=mussel vin=wine/- biere=beer cafe=coffee/- the=tea/- jus=juice/-
lait=milk/- cocktail=cocktail boisson=drink verre=glass tasse=cup bouteille=bottle barbecue=barbecue
pique_nique=picnic petit_dejeuner=breakfast dejeuner=lunch diner=dinner gouter=snack glace=ice_cream/- sucette=lollipop
miel=honey/- omelette=omelette gateau_d_anniversaire=birthday_cake

voiture|auto=car camion=truck camionnette=van bus|autobus=bus/buses moto=motorcycle scooter|trottinette=scooter
velo|bicyclette=bicycle train=train tram|tramway=tram metro=subway avion=airplane helicoptere=helicopter bateau=boat
voilier=sailboat yacht=yacht navire=ship paquebot=cruise_ship barque=rowboat montgolfiere=hot_air_balloon
fusee=rocket tracteur=tractor taxi=taxi ambulance=ambulance roue=wheel
telephone=phone smartphone=smartphone ordinateur=computer ecran=screen television|tele=television
appareil_photo=camera livre=book journal=newspaper carte=map lettre=letter stylo=pen crayon=pencil cahier=notebook
sac=bag valise=suitcase parapluie=umbrella chapeau=hat casquette=cap bonnet=beanie echarpe=scarf/scarves
gants=gloves/+ manteau=coat veste=jacket robe=dress jupe=skirt pantalon=trousers/+ jean=jeans/+
chemise=shirt tee_shirt|t_shirt=t-shirt pull=sweater short=shorts/+ maillot_de_bain=swimsuit maillot=jersey
chaussure=shoe botte=boot baskets=sneakers/+ chaussette=sock bijou=jewel collier=necklace bague=ring
bracelet=bracelet montre=watch lunettes_de_soleil=sunglasses/+ table=table chaise=chair fauteuil=armchair
canape=sofa lit=bed coussin=cushion oreiller=pillow tapis=rug lampe=lamp bougie=candle miroir=mirror
tableau=painting peinture=painting dessin=drawing photo=photo affiche=poster horloge|pendule=clock vase=vase
bouquet=bouquet plante=plant pot=pot cle=key jouet=toy peluche|doudou=teddy_bear poupee=doll ballon|balle=ball
ballon_de_baudruche=balloon cerf_volant=kite lego=lego/- puzzle=puzzle jeu=game guitare=guitar piano=piano
violon=violin batterie=drums/+ micro=microphone drapeau=flag guirlande=garland sapin_de_noel=christmas_tree
cadeau=gift feu=fire feu_de_camp=campfire feu_d_artifice|feux_d_artifice=fireworks/+ boule_de_neige=snowball
luge=sled planche=board planche_de_surf=surfboard hamac=hammock parasol=beach_umbrella transat=deckchair
serviette=towel seau=bucket pelle=shovel chateau_de_sable=sandcastle filet=net canne_a_peche=fishing_rod
tente_de_camping=tent carte_postale=postcard jeu_de_cartes=playing_cards/+

anniversaire=birthday fete|soiree=party/parties fete_d_anniversaire=birthday_party noel=christmas/- paques=easter/- halloween=halloween/-
nouvel_an=new_year/- carnaval=carnival concert=concert festival=festival spectacle=show match=match mariage=wedding
bapteme=baptism vacances=holidays/+ voyage=trip week_end=weekend sortie=outing promenade|balade=walk
randonnee=hike course=race sport=sport football|foot=football/- rugby=rugby/- tennis=tennis/- basket=basketball/-
volley=volleyball/- natation=swimming/- danse=dance musique=music/- chasse=hunting/- jardinage=gardening/-
equitation=horse_riding/- escalade=climbing/- plongee=diving/- ski=skiing/- snowboard=snowboarding/- surf=surfing/-
voile=sailing/- kayak=kayak camping=camping/- yoga=yoga/- golf=golf/- boxe=boxing/- judo=judo/- course_a_pied=running/-

"""

    private const val ADJECTIVES = """
rouge=red bleu=blue vert=green jaune=yellow orange=orange violet|violette|violets|violettes=purple rose=pink
noir=black blanc|blanche|blancs|blanches=white
noir_et_blanc|noire_et_blanche|noirs_et_blancs|noires_et_blanches=black_and_white gris|grise|grises=grey marron=brown brun=brown beige=beige dore=golden
argente=silver turquoise=turquoise multicolore|colore=colorful clair=light fonce=dark
grand=big petit=small gros|grosse|grosses=big enorme=huge minuscule=tiny long|longue|longs|longues=long court=short
haut=tall bas|basse|basses=low large=wide etroit=narrow
beau|bel|belle|beaux|belles=beautiful joli=pretty mignon|mignonne|mignons|mignonnes=cute drole=funny
heureux|heureuse|heureuses=happy triste=sad content=happy calme=calm sauvage=wild
vieux|vieil|vieille|vieilles=old ancien|ancienne|anciens|anciennes=old jeune=young
nouveau|nouvel|nouvelle|nouveaux|nouvelles=new neuf|neuve|neufs|neuves=new moderne=modern
chaud=hot froid=cold mouille=wet sec|seche|secs|seches=dry sale=dirty propre=clean plein=full vide=empty ouvert=open
ferme=closed allume=lit eteint=dark sombre=dark lumineux|lumineuse|lumineuses=bright flou=blurry rond=round carre=square
enneige=snowy ensoleille=sunny nuageux|nuageuse|nuageuses=cloudy pluvieux|pluvieuse|pluvieuses=rainy
orageux|orageuse|orageuses=stormy venteux|venteuse|venteuses=windy brumeux|brumeuse|brumeuses=misty gele=frozen
fleuri=blooming etoile=starry illumine=illuminated decore=decorated deguise=dressed_up celebre=famous
typique=typical traditionnel|traditionnelle|traditionnels|traditionnelles=traditional urbain=urban rural|rurale=rural
tropical|tropicale|tropicaux|tropicales=tropical desert=deserted sablonneux|sablonneuse=sandy rocheux|rocheuse=rocky
romantique=romantic magnifique|superbe|splendide=beautiful premier|premiere|premiers|premieres=first
dernier|derniere|derniers|dernieres=last seul=alone fatigue=tired endormi=asleep assis=sitting allonge|couche=lying
debout=standing souriant=smiling
"""

    private const val VERBS = """
nager|nage|nagent=swimming baigner|baigne|baignent=bathing courir|court|courent=running marcher|marche|marchent=walking
sauter|saute|sautent=jumping danser|danse|dansent=dancing chanter|chante|chantent=singing jouer|joue|jouent=playing
manger|mange|mangent=eating boire|boit|boivent=drinking dormir|dort|dorment=sleeping lire|lit|lisent=reading
ecrire|ecrit|ecrivent=writing cuisiner|cuisine|cuisinent=cooking peindre|peint|peignent=painting
dessiner|dessine|dessinent=drawing rire|rit|rient=laughing sourire|sourit|sourient=smiling
pleurer|pleure|pleurent=crying voler|vole|volent=flying rouler|roule|roulent=riding conduire|conduit|conduisent=driving
grimper|grimpe|grimpent|escalader|escalade|escaladent=climbing skier|skie|skient=skiing surfer|surfe|surfent=surfing
pecher=fishing camper|campe|campent=camping randonner=hiking patiner|patine|patinent=skating
plonger|plonge|plongent=diving souffler|souffle|soufflent=blowing poser|pose|posent=posing
regarder|regarde|regardent=looking attendre|attend|attendent=waiting parler|parle|parlent=talking
embrasser|embrasse|embrassent=kissing tenir|tient|tiennent=holding porter|porte|portent=carrying
promener|promene|promenent=walking travailler|travaille|travaillent=working jardiner=gardening
bronzer|bronze|bronzent=sunbathing feter|fete|fetent=celebrating construire|construit|construisent=building
amuser|amuse|amusent=having_fun glisser|glisse|glissent=sliding tomber|tombe|tombent=falling
lancer|lance|lancent=throwing attraper|attrape|attrapent=catching nourrir|nourrit|nourrissent=feeding
caresser|caresse|caressent=petting fait_du_velo|faire_du_velo|font_du_velo=riding_a_bike
fait_du_ski|faire_du_ski|font_du_ski=skiing fait_du_surf|faire_du_surf|font_du_surf=surfing
fait_du_cheval|faire_du_cheval|font_du_cheval=horse_riding fait_du_bateau|faire_du_bateau|font_du_bateau=boating
fait_de_la_voile|faire_de_la_voile|font_de_la_voile=sailing fait_la_fete|faire_la_fete|font_la_fete=partying
"""

    private const val PREPOSITIONS = """
sur=on sous=under dans=in avec=with sans=without pour=for par=by chez=at vers=towards entre=between contre=against
pendant=during apres=after avant=before devant=in_front_of derriere=behind pres|pres_de|pres_d=near
pres_du|pres_des=near_the loin_de|loin_d=far_from parmi=among autour|autour_de|autour_d=around autour_du=around_the
le_long_de=along le_long_du=along_the a_cote_de|a_cote_d=next_to a_cote_du|a_cote_des=next_to_the
au_dessus_de|au_dessus_d=above au_dessus_du|au_dessus_des=above_the au_dessous_de|en_dessous_de=below
au_milieu_de|au_milieu_d=in_the_middle_of au_milieu_du=in_the_middle_of_the au_milieu_des=among_the
en_face_de|en_face_d=in_front_of au_bord_de|au_bord_d=by au_bord_du|au_bord_des=by_the en_haut_de=at_the_top_of
au_sommet_de|au_sommet_du=at_the_top_of au_pied_de|au_pied_du=at_the_foot_of dehors|a_l_exterieur=outside
dedans|a_l_interieur=inside a_travers=through depuis=from
"""

    /** Compléments figés, gardés en bloc : « au bord de la mer » → « by the sea ». */
    private const val PHRASES = """
au_soleil=in_the_sun sous_la_pluie=in_the_rain sous_la_neige|dans_la_neige|a_la_neige=in_the_snow
au_bord_de_la_mer|en_bord_de_mer=by_the_sea au_bord_du_lac=by_the_lake au_bord_de_l_eau=by_the_water
en_montagne|a_la_montagne=in_the_mountains a_la_campagne=in_the_countryside en_ville=in_the_city en_foret=in_the_forest
en_vacances=on_holiday a_la_maison=at_home a_la_plage=at_the_beach a_la_mer=at_the_seaside la_nuit|de_nuit=at_night
le_soir=in_the_evening le_matin=in_the_morning en_hiver=in_winter en_ete=in_summer en_automne=in_autumn
au_printemps=in_spring a_noel=at_christmas au_coucher_du_soleil|au_coucher_de_soleil=at_sunset
au_lever_du_soleil|au_lever_de_soleil=at_sunrise sous_l_eau=underwater dans_l_eau=in_the_water
"""

    private const val ADVERBS = """
tres=very trop=too assez=quite
"""

    private const val FIXED = """
mon|ma|mes=my ton|ta|tes|votre|vos=your notre|nos=our leur|leurs=their ce|cet|cette=this ces=these
"""

    private const val DROPPED = """
je|j|tu|il|elle|ils|elles|on|nous|vous|me|m|te|t|se|s|lui|ca|c|est|sont|etait|etaient|ai|as|avons|avez|ont=x
en_train_de|en_train_d|il_y_a|voici|voila|son|sa|ses=x
"""
}
