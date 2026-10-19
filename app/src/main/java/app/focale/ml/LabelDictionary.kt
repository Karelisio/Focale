package app.focale.ml

import java.text.Normalizer
import java.util.Locale

/** Grandes familles d'étiquettes, utilisées pour les recherches génériques (« animaux »…). */
enum class LabelCategory { ANIMAL, DOG, CAT, FOOD, NATURE, PEOPLE, VEHICLE, BOAT, SPORT, CELEBRATION }

/** Étiquette ML Kit (libellé anglais canonique), sa traduction et ses synonymes. */
data class LabelEntry(
    val en: String,
    val fr: String,
    val synonyms: List<String> = emptyList(),
    val category: LabelCategory? = null,
)

/**
 * Dictionnaire bilingue des 447 étiquettes du modèle ML Kit Image Labeling embarqué.
 *
 * Les étiquettes sont stockées en anglais (forme canonique du modèle) ; la recherche
 * accepte indifféremment le français, l'anglais, des synonymes et des hyperonymes
 * (« chien » couvre aussi les races détectées, « animaux » toutes les espèces).
 */
object LabelDictionary {

    val entries: List<LabelEntry> = buildEntries()

    private val byEnglish: Map<String, LabelEntry> = entries.associateBy { it.en }

    /** Mots déclenchant une catégorie entière (forme normalisée). */
    private val categoryKeywords: Map<String, Set<LabelCategory>> = buildMap {
        fun put(category: LabelCategory, vararg words: String) = words.forEach { word ->
            merge(normalize(word), setOf(category)) { a, b -> a + b }
        }
        put(LabelCategory.ANIMAL, "animal", "animaux", "animals", "bete", "betes", "faune", "wildlife")
        put(LabelCategory.DOG, "chien", "chiens", "chiot", "chiots", "toutou", "dog", "dogs", "puppy", "puppies")
        put(LabelCategory.CAT, "chat", "chats", "chaton", "chatons", "minou", "cat", "cats", "kitten", "kitty")
        put(LabelCategory.FOOD, "nourriture", "repas", "plat", "plats", "manger", "food", "meal", "dish", "restaurant")
        put(LabelCategory.NATURE, "nature", "paysage", "paysages", "landscape", "outdoor", "plein air")
        put(LabelCategory.PEOPLE, "gens", "personnes", "people", "portrait", "portraits", "visage", "visages", "face")
        put(LabelCategory.VEHICLE, "vehicule", "vehicules", "vehicle", "vehicles", "transport", "transports")
        put(LabelCategory.BOAT, "bateau", "bateaux", "boat", "boats", "navire", "navires", "ship")
        put(LabelCategory.SPORT, "sport", "sports", "sportif")
        put(LabelCategory.CELEBRATION, "fete", "fetes", "celebration", "party", "anniversaire", "birthday")
    }

    /** Une catégorie inclut ses sous-catégories (les chiens sont des animaux…). */
    private fun expand(category: LabelCategory): Set<LabelCategory> = when (category) {
        LabelCategory.ANIMAL -> setOf(LabelCategory.ANIMAL, LabelCategory.DOG, LabelCategory.CAT)
        LabelCategory.VEHICLE -> setOf(LabelCategory.VEHICLE, LabelCategory.BOAT)
        else -> setOf(category)
    }

    private val searchIndex: List<Pair<LabelEntry, List<String>>> = entries.map { entry ->
        entry to (listOf(entry.en, entry.fr) + entry.synonyms).map(::normalize).distinct()
    }

    /** Libellé affichable d'une étiquette canonique, dans la langue de [locale]. */
    fun displayName(label: String, locale: Locale): String {
        val entry = byEnglish[label] ?: return label
        return if (locale.language == Locale.FRENCH.language) entry.fr else entry.en
    }

    fun entry(label: String): LabelEntry? = byEnglish[label]

    /**
     * Étiquettes canoniques correspondant à un mot saisi : égalité, préfixe (3 lettres
     * minimum) d'un des mots du libellé, forme singulière, ou mot-clé de catégorie.
     */
    fun match(word: String): Set<String> {
        val term = normalize(word)
        if (term.length < 2) return emptySet()
        val singular = singular(term)
        val result = mutableSetOf<String>()
        (categoryKeywords[term] ?: categoryKeywords[singular])?.let { categories ->
            val all = categories.flatMap(::expand).toSet()
            entries.filter { it.category in all }.mapTo(result) { it.en }
        }
        for ((entry, names) in searchIndex) {
            if (names.any { name -> matches(name, term) || (singular != term && matches(name, singular)) }) {
                result += entry.en
            }
        }
        return result
    }

    private fun matches(name: String, term: String): Boolean {
        if (name == term) return true
        if (term.length < 3) return false
        if (name.startsWith(term)) return true
        return name.split(' ').any { it.startsWith(term) }
    }

    /** Minuscules, sans accents, ponctuation remplacée par des espaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("œ", "oe").replace("æ", "ae")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun singular(term: String): String = when {
        term.length > 3 && term.endsWith("aux") -> term.dropLast(3) + "al"
        term.length > 3 && (term.endsWith("s") || term.endsWith("x")) -> term.dropLast(1)
        else -> term
    }

    private fun buildEntries(): List<LabelEntry> {
        val a = LabelCategory.ANIMAL
        val dog = LabelCategory.DOG
        val cat = LabelCategory.CAT
        val food = LabelCategory.FOOD
        val nat = LabelCategory.NATURE
        val ppl = LabelCategory.PEOPLE
        val veh = LabelCategory.VEHICLE
        val boat = LabelCategory.BOAT
        val sport = LabelCategory.SPORT
        val party = LabelCategory.CELEBRATION
        fun e(en: String, fr: String, vararg synonyms: String, c: LabelCategory? = null) =
            LabelEntry(en, fr, synonyms.toList(), c)
        return listOf(
            e("Team", "Équipe"), e("Bonfire", "Feu de camp", "feu", "campfire"), e("Comics", "Bande dessinée", "bd", "manga"),
            e("Himalayan", "Chat himalayen", c = cat), e("Iceberg", "Iceberg", "glace", c = nat), e("Bento", "Bento", c = food),
            e("Laundry", "Linge", "lessive"), e("Sink", "Évier", "lavabo"), e("Toy", "Jouet", "jouets"),
            e("Statue", "Statue", "sculpture"), e("Cheeseburger", "Cheeseburger", "burger", "hamburger", c = food),
            e("Tractor", "Tracteur", c = veh), e("Sled", "Luge", "traineau"), e("Aquarium", "Aquarium", "poisson"),
            e("Circus", "Cirque"), e("Mascot", "Mascotte"), e("Sitting", "Assis"), e("Beard", "Barbe"),
            e("Bridge", "Pont"), e("Tights", "Collants"), e("Toy", "Jouet"), e("Bird", "Oiseau", "oiseaux", c = a),
            e("Rafting", "Rafting", c = sport), e("Park", "Parc", "jardin public"), e("Doll", "Poupée"),
            e("Factory", "Usine"), e("Graduation", "Remise de diplôme", "diplome"), e("Porcelain", "Porcelaine"),
            e("Twig", "Brindille"), e("Petal", "Pétale", "fleur", c = nat), e("Cushion", "Coussin"),
            e("Sunglasses", "Lunettes de soleil"), e("Infrastructure", "Infrastructure"), e("Ferris wheel", "Grande roue", "fete foraine"),
            e("Pomacentridae", "Poisson-demoiselle", "poisson", c = a), e("Wetsuit", "Combinaison de plongée"),
            e("Shetland sheepdog", "Berger des Shetland", "colley", c = dog), e("Brig", "Brick", "voilier", c = boat),
            e("Watercolor paint", "Aquarelle", "peinture"), e("Competition", "Compétition"), e("Cliff", "Falaise", c = nat),
            e("Badminton", "Badminton", c = sport), e("Safari", "Safari"), e("Bicycle", "Vélo", "bicyclette", "bike", c = veh),
            e("Stadium", "Stade"), e("Diwali", "Diwali", c = party), e("Boat", "Bateau", c = boat), e("Smile", "Sourire", c = ppl),
            e("Surfboard", "Planche de surf", "surf"), e("Fast food", "Restauration rapide", "fast-food", c = food),
            e("Sunset", "Coucher de soleil", "soleil", "crepuscule", c = nat), e("Hot dog", "Hot-dog", c = food),
            e("Shorts", "Short"), e("Bus", "Bus", "autobus", "car", c = veh), e("Bullfighting", "Corrida"),
            e("Sky", "Ciel", "nuage", "nuages", c = nat), e("Gerbil", "Gerbille", "rongeur", c = a), e("Rock", "Rocher", "roche", "pierre", c = nat),
            e("Interaction", "Interaction"), e("Dress", "Robe"), e("Toe", "Orteil"), e("Pest", "Nuisible"),
            e("Bear", "Ours", "nounours", c = a), e("Eating", "Repas", "manger", c = food), e("Tower", "Tour"),
            e("Brick", "Brique"), e("Junk", "Bric-à-brac"), e("Person", "Personne", c = ppl),
            e("Windsurfing", "Planche à voile", c = sport), e("Swimwear", "Maillot de bain"), e("Roller", "Roller", "patins"),
            e("Camping", "Camping", "tente"), e("Playground", "Aire de jeux", "jeux"), e("Bathroom", "Salle de bain"),
            e("Laugh", "Rire", c = ppl), e("Balloon", "Ballon", "baudruche"), e("Concert", "Concert", "spectacle"),
            e("Prom", "Bal de promo"), e("Construction", "Chantier", "construction"), e("Product", "Produit"),
            e("Reef", "Récif", "corail", c = nat), e("Picnic", "Pique-nique", c = food), e("Wreath", "Couronne"),
            e("Wheelbarrow", "Brouette"), e("Boxer", "Boxer", "boxe"), e("Necklace", "Collier"), e("Bracelet", "Bracelet"),
            e("Casino", "Casino"), e("Windshield", "Pare-brise"), e("Stairs", "Escalier", "marches"),
            e("Computer", "Ordinateur", "pc"), e("Cookware and bakeware", "Ustensiles de cuisine", "casserole"),
            e("Monochrome", "Noir et blanc", "monochrome"), e("Chair", "Chaise"), e("Poster", "Affiche", "poster"),
            e("Bar", "Bar"), e("Shipwreck", "Épave", c = boat), e("Pier", "Jetée", "ponton", "quai"), e("Community", "Communauté"),
            e("Caving", "Spéléologie"), e("Cave", "Grotte", "caverne", c = nat), e("Tie", "Cravate"), e("Cabinetry", "Placard", "meuble"),
            e("Underwater", "Sous l'eau", "sous marin"), e("Clown", "Clown"), e("Nightclub", "Boîte de nuit", "discotheque"),
            e("Cycling", "Cyclisme", "velo", c = sport), e("Comet", "Comète"), e("Mortarboard", "Toque universitaire", "diplome"),
            e("Track", "Piste", "athletisme"), e("Christmas", "Noël", "sapin", c = party), e("Church", "Église", "chapelle"),
            e("Clock", "Horloge", "pendule"), e("Dude", "Mec"), e("Cattle", "Bétail", "vache", "vaches", "boeuf", c = a),
            e("Jungle", "Jungle", c = nat), e("Desk", "Bureau"), e("Curling", "Curling", c = sport), e("Cuisine", "Cuisine", "plat", c = food),
            e("Cat", "Chat", c = cat), e("Juice", "Jus", c = food), e("Couscous", "Couscous", c = food),
            e("Screenshot", "Capture d'écran"), e("Crew", "Équipage"), e("Skyline", "Ligne d'horizon", "gratte ciel", "ville"),
            e("Youth", "Jeunesse"), e("Stuffed toy", "Peluche", "doudou"), e("Cookie", "Biscuit", "cookie", c = food),
            e("Tile", "Carrelage"), e("Hanukkah", "Hanoukka", c = party), e("Crochet", "Crochet"),
            e("Skateboarder", "Skateur", "skate", c = sport), e("Clipper", "Clipper", "voilier", c = boat), e("Nail", "Ongle", "clou"),
            e("Cola", "Cola", "soda", c = food), e("Cutlery", "Couverts"), e("Menu", "Menu"), e("Costume", "Déguisement", "costume"),
            e("Sari", "Sari"), e("Plush", "Peluche"), e("Pocket", "Poche"), e("Neon", "Néon"),
            e("Icicle", "Stalactite de glace", "glacon"), e("Pasteles", "Pasteles", c = food), e("Chain", "Chaîne"),
            e("Dance", "Danse"), e("Dune", "Dune", c = nat), e("Santa claus", "Père Noël", c = party),
            e("Thanksgiving", "Thanksgiving", c = party), e("Tuxedo", "Smoking"), e("Mouth", "Bouche"),
            e("Desert", "Désert", c = nat), e("Dinosaur", "Dinosaure"), e("Mufti", "Tenue civile"), e("Fire", "Feu", "flamme"),
            e("Bedroom", "Chambre", "lit"), e("Goggles", "Lunettes de protection", "masque"), e("Dragon", "Dragon"),
            e("Couch", "Canapé", "sofa"), e("Sledding", "Luge", c = sport), e("Cap", "Casquette"), e("Whiteboard", "Tableau blanc"),
            e("Hat", "Chapeau"), e("Gelato", "Glace", "gelato", "creme glacee", c = food), e("Cavalier", "Cavalier King Charles", c = dog),
            e("Beanie", "Bonnet"), e("Jersey", "Maillot"), e("Scarf", "Écharpe"), e("Vacation", "Vacances", "voyage"),
            e("Pitch", "Terrain"), e("Blackboard", "Tableau noir"), e("Deejay", "DJ"), e("Monument", "Monument"),
            e("Bumper", "Pare-chocs"), e("Longboard", "Longboard"), e("Waterfowl", "Oiseau d'eau", "canard", c = a),
            e("Flesh", "Chair"), e("Net", "Filet"), e("Icing", "Glaçage", c = food), e("Dalmatian", "Dalmatien", c = dog),
            e("Speedboat", "Hors-bord", c = boat), e("Trunk", "Tronc", "coffre"), e("Coffee", "Café", c = food),
            e("Soccer", "Football", "foot", c = sport), e("Ragdoll", "Chat ragdoll", c = cat), e("Food", "Nourriture", c = food),
            e("Standing", "Debout"), e("Fiction", "Fiction"), e("Fruit", "Fruit", c = food), e("Pho", "Phở", c = food),
            e("Sparkler", "Cierge magique"), e("Presentation", "Présentation"), e("Swing", "Balançoire"),
            e("Cairn terrier", "Cairn terrier", "terrier", c = dog), e("Forest", "Forêt", "bois", "arbres", c = nat),
            e("Flag", "Drapeau"), e("Frigate", "Frégate", c = boat), e("Foot", "Pied"), e("Jacket", "Veste", "blouson"),
            e("Gun", "Pistolet"), e("Pillow", "Oreiller"), e("Firearm", "Arme à feu"), e("Bathing", "Baignade", "bain"),
            e("Glacier", "Glacier", c = nat), e("Gymnastics", "Gymnastique", c = sport), e("Ear", "Oreille"),
            e("Flora", "Flore", "plantes", c = nat), e("Shell", "Coquillage"), e("Grandparent", "Grand-parent", "grand mere", "grand pere", c = ppl),
            e("Ruins", "Ruines"), e("Eyelash", "Cils"), e("Bunk bed", "Lits superposés"), e("Balance", "Équilibre"),
            e("Backpacking", "Randonnée", "sac a dos", "rando"), e("Horse", "Cheval", "chevaux", c = a), e("Glitter", "Paillettes"),
            e("Saucer", "Soucoupe"), e("Hair", "Cheveux"), e("Miniature", "Miniature"), e("Crowd", "Foule", c = ppl),
            e("Curtain", "Rideau"), e("Icon", "Icône"), e("Pixie-bob", "Chat pixie-bob", c = cat), e("Herd", "Troupeau", c = a),
            e("Insect", "Insecte", c = a), e("Ice", "Glace"), e("Bangle", "Bracelet jonc"), e("Flap", "Rabat"),
            e("Jewellery", "Bijoux", "bijou"), e("Knitting", "Tricot"), e("Centrepiece", "Centre de table"),
            e("Outerwear", "Vêtement d'extérieur", "manteau"), e("Love", "Amour"), e("Muscle", "Muscle"),
            e("Motorcycle", "Moto", "motocyclette", c = veh), e("Money", "Argent", "billets"), e("Mosque", "Mosquée"),
            e("Tableware", "Vaisselle"), e("Ballroom", "Salle de bal"), e("Kayak", "Kayak", c = boat), e("Leisure", "Loisirs"),
            e("Receipt", "Reçu", "ticket", "facture"), e("Lake", "Lac", c = nat), e("Lighthouse", "Phare"), e("Bridle", "Bride"),
            e("Leather", "Cuir"), e("Horn", "Corne", "klaxon"), e("Strap", "Sangle"), e("Lego", "Lego"),
            e("Scuba diving", "Plongée sous-marine", "plongee", c = sport), e("Leggings", "Legging"), e("Pool", "Piscine"),
            e("Musical instrument", "Instrument de musique", "musique"), e("Musical", "Comédie musicale"), e("Metal", "Métal"),
            e("Moon", "Lune", c = nat), e("Blazer", "Blazer"), e("Marriage", "Mariage", c = party),
            e("Mobile phone", "Téléphone portable", "smartphone", "telephone"), e("Militia", "Milice"), e("Tablecloth", "Nappe"),
            e("Party", "Fête", "soiree", c = party), e("Nebula", "Nébuleuse"), e("News", "Actualités"), e("Newspaper", "Journal"),
            e("Primate", "Primate", "singe", c = a), e("Piano", "Piano"), e("Plant", "Plante", c = nat), e("Passport", "Passeport"),
            e("Penguin", "Manchot", "pingouin", c = a), e("Shikoku", "Shikoku", c = dog), e("Palace", "Palais"), e("Doily", "Napperon"),
            e("Polo", "Polo"), e("Paper", "Papier"), e("Pop music", "Musique pop"), e("Skiff", "Esquif", "barque", c = boat),
            e("Pizza", "Pizza", c = food), e("Pet", "Animal de compagnie", c = a), e("Quilting", "Patchwork"), e("Cage", "Cage"),
            e("Skateboard", "Skateboard", "skate", c = sport), e("Surfing", "Surf", c = sport), e("Rugby", "Rugby", c = sport),
            e("Lipstick", "Rouge à lèvres", "maquillage"), e("River", "Rivière", "fleuve", c = nat), e("Race", "Course"),
            e("Rowing", "Aviron", c = sport), e("Road", "Route", "rue"), e("Running", "Course à pied", "running", "jogging", c = sport),
            e("Room", "Pièce", "salle"), e("Roof", "Toit"), e("Star", "Étoile"), e("Sports", "Sport", c = sport),
            e("Shoe", "Chaussure", "chaussures"), e("Tubing", "Bouée tractée"), e("Space", "Espace"), e("Sleep", "Sommeil", "dormir"),
            e("Skin", "Peau"), e("Swimming", "Natation", "nager", c = sport), e("School", "École"), e("Sushi", "Sushi", c = food),
            e("Loveseat", "Causeuse", "canape"), e("Superman", "Superman"), e("Cool", "Cool"), e("Skiing", "Ski", c = sport),
            e("Submarine", "Sous-marin", c = boat), e("Song", "Chanson"), e("Class", "Classe", "cours"), e("Skyscraper", "Gratte-ciel"),
            e("Volcano", "Volcan", c = nat), e("Television", "Télévision", "tele"), e("Rein", "Rêne"), e("Tattoo", "Tatouage"),
            e("Train", "Train", c = veh), e("Handrail", "Rampe"), e("Cup", "Tasse"), e("Vehicle", "Véhicule", c = veh),
            e("Handbag", "Sac à main"), e("Lampshade", "Abat-jour"), e("Event", "Événement"), e("Wine", "Vin", c = food),
            e("Wing", "Aile"), e("Wheel", "Roue"), e("Wakeboarding", "Wakeboard", c = sport), e("Web page", "Page web", "site"),
            e("Zoo", "Zoo"), e("Monkey", "Singe", c = a), e("Ranch", "Ranch"), e("Fishing", "Pêche"), e("Heart", "Cœur"),
            e("Cotton", "Coton"), e("Cappuccino", "Cappuccino", c = food), e("Bread", "Pain", c = food), e("Sand", "Sable", c = nat),
            e("Soldier", "Soldat"), e("Museum", "Musée"), e("Helicopter", "Hélicoptère", c = veh), e("Mountain", "Montagne", "montagnes", c = nat),
            e("Duck", "Canard", c = a), e("Soil", "Terre", "sol"), e("Turtle", "Tortue", c = a), e("Crocodile", "Crocodile", c = a),
            e("Musician", "Musicien"), e("Sneakers", "Baskets"), e("Wool", "Laine"), e("Ring", "Bague"), e("Singer", "Chanteur"),
            e("Carnival", "Carnaval", c = party), e("Snowboarding", "Snowboard", c = sport), e("Waterskiing", "Ski nautique", c = sport),
            e("Wall", "Mur"), e("Rocket", "Fusée"), e("Countertop", "Plan de travail"), e("Beach", "Plage", "mer", "ocean", c = nat),
            e("Rainbow", "Arc-en-ciel", c = nat), e("Branch", "Branche"), e("Moustache", "Moustache"), e("Garden", "Jardin", c = nat),
            e("Gown", "Robe de soirée"), e("Field", "Champ", c = nat), e("Dog", "Chien", c = dog), e("Superhero", "Super-héros"),
            e("Flower", "Fleur", "fleurs", c = nat), e("Placemat", "Set de table"), e("Subwoofer", "Caisson de basses"),
            e("Cathedral", "Cathédrale"), e("Building", "Bâtiment", "immeuble"), e("Airplane", "Avion", c = veh), e("Fur", "Fourrure"),
            e("Bull", "Taureau", c = a), e("Bench", "Banc"), e("Temple", "Temple"), e("Butterfly", "Papillon", c = a),
            e("Model", "Mannequin", "modele"), e("Marathon", "Marathon", c = sport), e("Needlework", "Broderie", "couture"),
            e("Kitchen", "Cuisine (pièce)"), e("Castle", "Château"), e("Aurora", "Aurore boréale", c = nat), e("Larva", "Larve"),
            e("Racing", "Course automobile"), e("Human", "Humain", c = ppl), e("Airliner", "Avion de ligne", c = veh),
            e("Dam", "Barrage"), e("Textile", "Textile"), e("Groom", "Marié", c = party), e("Fun", "Amusement"),
            e("Steaming", "Vapeur"), e("Vegetable", "Légume", "legumes", c = food), e("Unicycle", "Monocycle"), e("Jeans", "Jean"),
            e("Flowerpot", "Pot de fleurs"), e("Drawer", "Tiroir"), e("Cake", "Gâteau", c = food), e("Armrest", "Accoudoir"),
            e("Aviation", "Aviation"), e("Aggression", "Agression"), e("Fog", "Brouillard", "brume", c = nat),
            e("Fireworks", "Feux d'artifice", c = party), e("Farm", "Ferme"), e("Seal", "Phoque", c = a), e("Shelf", "Étagère"),
            e("Bangs", "Frange"), e("Lightning", "Éclair", "orage", c = nat), e("Van", "Camionnette", c = veh),
            e("Sphynx", "Chat sphynx", c = cat), e("Tire", "Pneu"), e("Denim", "Denim"), e("Prairie", "Prairie", c = nat),
            e("Snorkeling", "Snorkeling", "masque et tuba", c = sport), e("Umbrella", "Parapluie", "parasol"), e("Asphalt", "Asphalte", "bitume"),
            e("Sailboat", "Voilier", "voile", c = boat), e("Basset hound", "Basset hound", c = dog), e("Pattern", "Motif"),
            e("Supper", "Dîner", c = food), e("Veil", "Voile"), e("Waterfall", "Cascade", "chute d'eau", c = nat),
            e("Animal", "Animal", c = a), e("Lunch", "Déjeuner", c = food), e("Odometer", "Compteur kilométrique"),
            e("Baby", "Bébé", c = ppl), e("Glasses", "Lunettes"), e("Car", "Voiture", "auto", c = veh), e("Aircraft", "Aéronef", c = veh),
            e("Hand", "Main"), e("Rodeo", "Rodéo"), e("Canyon", "Canyon", "gorge", c = nat), e("Meal", "Repas", c = food),
            e("Softball", "Softball", c = sport), e("Alcohol", "Alcool", "biere", "cocktail", c = food), e("Bride", "Mariée", c = party),
            e("Swamp", "Marais", c = nat), e("Pie", "Tarte", c = food), e("Bag", "Sac"), e("Joker", "Joker"),
            e("Supervillain", "Super-méchant"), e("Army", "Armée"), e("Canoe", "Canoë", c = boat), e("Selfie", "Selfie", c = ppl),
            e("Rickshaw", "Pousse-pousse", c = veh), e("Barn", "Grange"), e("Archery", "Tir à l'arc", c = sport),
            e("Aerospace engineering", "Aérospatiale"), e("Child", "Enfant", "enfants", c = ppl), e("Storm", "Orage", "tempete", c = nat),
            e("Helmet", "Casque"),
        ).distinctBy { it.en }
    }
}
