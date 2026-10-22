package jo.codeide.preview.xml

/**
 * VALIDATION DES ATTRIBUTS (PE11e — couleurs ; PE11f — ressources ;
 * PE11g-contrôles — curseur et ratio ; miroirs `vCol`/`vRes`/`sld`/`ratio`
 * de la maquette § « Fabriques de contrôles ») —
 * FONCTIONS PURES SANS ANDROID, testées cas par cas.
 *
 * Le résultat est un TYPE SCELLÉ (brief PE10a/PE11c § « Contrôles et
 * formats ») : [Resultat.Valide] écrit tel quel, [Resultat.Avertissement]
 * écrit QUAND MÊME (la sévérité ne bloque jamais l'écriture, seul le
 * message change — miroir « un avertissement écrit quand même »),
 * [Resultat.Erreur] n'écrit RIEN. JAMAIS d'exception (règle 6) : la
 * validation retourne un FAIT, l'appelant décide.
 *
 * Les MESSAGES sont des GENRES (clés sémantiques — règle 3 : jamais de
 * chaîne dans le module pur) : l'UI en tire le libellé localisé
 * FR/EN, patron établi par [ProblemesPreview.GenreProbleme].
 *
 * Règles couleur (miroir EXACT de `vCol` — les seuils de la maquette
 * sont des CHOIX de produit, pas des coquilles) :
 * - vide ou résoluble → [Resultat.Valide] (miroir `!x || colorCss(x)` :
 *   le VIDE est valide — il RETIRE l'attribut à l'écriture) ;
 * - `@color/x` hors projet → AVERTISSEMENT (s'écrit quand même) ;
 * - `@drawable/x` hors projet → AVERTISSEMENT — toléré par la maquette
 *   pour les contrôles couleur qui portent aussi des fondants
 *   (`android:background`) ;
 * - `?attr/`, `?android:attr/`, tout `@android:*` → [Resultat.Valide]
 *   (références système/thème : la maquette ne peut pas les vérifier,
 *   le RENDU tranchera) ;
 * - tout le reste (`rouge`, `#GGG`, `#@x`…) → [Resultat.Erreur].
 */
public object ValidationAttribut {
    /** Le GENRE du message — clé du libellé localisé posé par l'UI. */
    public enum class GenreMessage {
        /** « Couleur invalide : #RRGGBB, #AARRGGBB, @color/nom ou ?attr/nom. » (erreur). */
        COULEUR_INVALIDE,

        /** « @color introuvable dans res/values/colors.xml. » (avertissement). */
        COULEUR_INTROUVABLE,

        /** « @drawable introuvable dans res/. » (avertissement). */
        DRAWABLE_INTROUVABLE,

        /** « Référence attendue : @type/nom. » (erreur — [Arguments.TYPE] : premier type attendu). */
        REFERENCE_ATTENDUE,

        /** « Type @x refusé ici, attendu : @color, @drawable. » (erreur — [Arguments.TYPE], [Arguments.TYPES]). */
        TYPE_REFUSE,

        /** « @type/nom introuvable dans res/. » (avertissement — [Arguments.TYPE], [Arguments.NOM]). */
        RESSOURCE_INTROUVABLE,

        /**
         * « Valeur entre %1$s et %2$s. » (erreur — curseur hors bornes,
         * miroir `'Valeur entre ' + c.min + ' et ' + c.max + '.'` ;
         * [Arguments.MIN], [Arguments.MAX]).
         */
        VALEUR_BORNEE,

        /** « Deux nombres positifs, par exemple 16 et 9. » (erreur — ratio). */
        RATIO_INVALIDE,

        /** « Nom attendu : android:…, app:… ou tools:…. » (erreur — attribut libre, PE11h). */
        NOM_ATTRIBUT_LIBRE,
    }

    /**
     * Le résultat d'une validation — jamais une exception (règle 6).
     * [Avertissement.genre]/[Erreur.genre] porte la CLÉ du message ;
     * [Resultat.Avertissement.arguments]/[Resultat.Erreur.arguments]
     * porte les FRAGMENTS du libellé (nom du type, de la ressource… —
     * l'UI compose la ressource chaîne, règle 3).
     */
    public sealed interface Resultat {
        /** La valeur s'écrit telle quelle (vide = RETRAIT à l'écriture). */
        public data object Valide : Resultat

        /** La valeur s'écrit QUAND MÊME, message d'avertissement affiché. */
        public data class Avertissement(
            val genre: GenreMessage,
            val arguments: List<String> = emptyList(),
        ) : Resultat

        /** La valeur NE S'ÉCRIT PAS, message d'erreur affiché. */
        public data class Erreur(
            val genre: GenreMessage,
            val arguments: List<String> = emptyList(),
        ) : Resultat
    }

    /** Les positions des fragments dans `arguments` (les genres les nomment). */
    public object Arguments {
        /** Le type refusé ou attendu (`drawable`, `string`…). */
        public const val TYPE: Int = 0

        /** Le NOM de la ressource introuvable. */
        public const val NOM: Int = 1

        /** La LISTE des types acceptés (`@color, @drawable`). */
        public const val TYPES: Int = 1

        /** La borne BASSE du message de curseur. */
        public const val MIN: Int = 0

        /** La borne HAUTE du message de curseur. */
        public const val MAX: Int = 1
    }

    /** Hexadécimal `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` (miroir `colorCss`). */
    private val HEX: Regex = Regex("""^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")

    /** Référence de thème `?attr/…` ou `?android:attr/…` (miroir `vCol`). */
    private val ATTR_THEME: Regex = Regex("""^\?(android:)?attr/\w+$""")

    /** Un nom de ressource nu (`@color/x`, `@drawable/y` — miroir `\w+`). */
    private val NOM_RESSOURCE: Regex = Regex("""^\w+$""")

    /** Le NOM derrière un préfixe `@type/` (`null` si le nom est invalide). */
    private fun nomRessource(valeur: String): String? {
        val nom = valeur.substringAfter('/', "")
        return if (NOM_RESSOURCE.matches(nom)) nom else null
    }

    /**
     * Valide une valeur de COULEUR (miroir `vCol(x)` de la maquette) :
     * [couleurs] = les noms `@color` du projet (pour l'avertissement
     * d'introuvabilité), [drawables] = les noms `@drawable` du projet
     * (même rôle — la maquette partage `vCol` entre `android:background`
     * et les teintes).
     */
    public fun couleur(
        valeur: String?,
        couleurs: Set<String>,
        drawables: Set<String>,
    ): Resultat {
        if (valeur.isNullOrEmpty()) return Resultat.Valide
        return when {
            HEX.matches(valeur) -> {
                Resultat.Valide
            }

            valeur.startsWith("@color/") -> {
                when (val nom = nomRessource(valeur)) {
                    null -> Resultat.Erreur(GenreMessage.COULEUR_INVALIDE)
                    in couleurs -> Resultat.Valide
                    else -> Resultat.Avertissement(GenreMessage.COULEUR_INTROUVABLE)
                }
            }

            valeur.startsWith("@drawable/") -> {
                when (val nom = nomRessource(valeur)) {
                    null -> Resultat.Erreur(GenreMessage.COULEUR_INVALIDE)
                    in drawables -> Resultat.Valide
                    else -> Resultat.Avertissement(GenreMessage.DRAWABLE_INTROUVABLE)
                }
            }

            ATTR_THEME.matches(valeur) || valeur.startsWith("@android:") -> {
                Resultat.Valide
            }

            else -> {
                Resultat.Erreur(GenreMessage.COULEUR_INVALIDE)
            }
        }
    }

    /** Une référence `@type/nom` (miroir `vRes` : `^@(\w+)/([\w.]+)$`). */
    private val REFERENCE: Regex = Regex("""^@(\w+)/([\w.]+)$""")

    /**
     * Valide une valeur de RESSOURCE (miroir `vRes(x, c.ty)` de la
     * maquette) : [types] = les types acceptés par l'attribut
     * (`drawable`, `string`…), [index] = les noms PAR TYPE du projet
     * (un type absent de l'index n'est PAS vérifiable — miroir `set`
     * nul : pas d'avertissement, le RENDU tranchera).
     */
    public fun ressource(
        valeur: String?,
        types: List<String>,
        index: Map<String, Set<String>>,
    ): Resultat {
        if (valeur.isNullOrEmpty()) return Resultat.Valide
        val correspondance = REFERENCE.find(valeur)
        val type = correspondance?.groupValues?.get(1).orEmpty()
        val nom = correspondance?.groupValues?.get(2).orEmpty()
        val connus = index[type]
        return when {
            ATTR_THEME.matches(valeur) -> {
                Resultat.Valide
            }

            correspondance == null -> {
                // Une forme cassée : l'ERREUR porte le premier type
                // attendu (miroir `'Référence attendue : @' + ty[0] + '/nom.'`).
                erreurReference(types)
            }

            type !in types -> {
                Resultat.Erreur(
                    GenreMessage.TYPE_REFUSE,
                    listOf(type, types.joinToString(", ") { "@$it" }),
                )
            }

            connus != null && nom !in connus -> {
                Resultat.Avertissement(GenreMessage.RESSOURCE_INTROUVABLE, listOf(type, nom))
            }

            else -> {
                Resultat.Valide
            }
        }
    }

    /**
     * L'erreur de FORME : une valeur sans la forme `@type/nom` — le
     * message porte le PREMIER type attendu (miroir
     * `'Référence attendue : @' + ty[0] + '/nom.'`). Les COMPLÉTIONS
     * d'un nom nu (`ic_logo` → `@drawable/ic_logo`) se passent en amont,
     * au liage.
     */
    private fun erreurReference(types: List<String>): Resultat =
        Resultat.Erreur(GenreMessage.REFERENCE_ATTENDUE, listOf(types.firstOrNull().orEmpty()))

    /**
     * Valide une saisie de CURSEUR (PE11g-contrôles — miroir le
     * `commit` du `sld` de la maquette) : non numérique ou hors
     * [min]…[max] → ERREUR qui n'écrit RIEN, le message porte les
     * bornes FORMATÉES (miroir `'Valeur entre ' + c.min + ' et ' +
     * c.max + '.'` — jamais de « 1.0 » pour 1).
     */
    public fun curseur(
        saisie: String,
        min: Float,
        max: Float,
    ): Resultat {
        val nombre = saisie.trim().toFloatOrNull()
        return if (nombre != null && nombre >= min && nombre <= max) {
            Resultat.Valide
        } else {
            Resultat.Erreur(
                GenreMessage.VALEUR_BORNEE,
                listOf(formaterNombre(min), formaterNombre(max)),
            )
        }
    }

    /**
     * Valide une saisie de RATIO (PE11g-contrôles — miroir le `put` du
     * `ratio` de la maquette) : DEUX nombres POSITIFS, sinon ERREUR
     * qui n'écrit RIEN (miroir `!(+x > 0 && +y > 0)`). Les deux champs
     * VIDES ne passent PAS par ici : c'est le RETRAIT, réglé au liage.
     */
    public fun ratio(
        a: String,
        b: String,
    ): Resultat {
        val nombreA = a.trim().toFloatOrNull()
        val nombreB = b.trim().toFloatOrNull()
        return if (positif(nombreA) && positif(nombreB)) {
            Resultat.Valide
        } else {
            Resultat.Erreur(GenreMessage.RATIO_INVALIDE)
        }
    }

    /** Un nombre STRICTEMENT positif (miroir `+x > 0` — le null ou le non numérique ne l'est pas). */
    private fun positif(nombre: Float?): Boolean = nombre != null && nombre > 0f

    /**
     * Un NOM d'attribut LIBRE (PE11h — miroir le toast `n.classList.add('bad')`
     * de la rangée d'ajout de la maquette) : `android:nom`, `app:nom` ou
     * `tools:nom`, le nom en IDENTIFIANT Java (`[A-Za-z_]\w*` — ni chiffre
     * ni tiret en tête, pas de préfixe supplémentaire).
     */
    private val NOM_LIBRE: Regex = Regex("""^(android|app|tools):[A-Za-z_]\w*$""")

    /**
     * Valide le NOM saisi pour un ATTRIBUT LIBRE (miroir
     * `/^(android|app|tools):[A-Za-z_]\w*$/.test(nm)` de la maquette) :
     * le VIDE est une ERREUR aussi (la rangée exige un nom ET une valeur,
     * jamais d'écriture hasardeuse).
     */
    public fun nomAttributLibre(nom: String): Resultat =
        if (NOM_LIBRE.matches(nom.trim())) {
            Resultat.Valide
        } else {
            Resultat.Erreur(GenreMessage.NOM_ATTRIBUT_LIBRE)
        }
}
