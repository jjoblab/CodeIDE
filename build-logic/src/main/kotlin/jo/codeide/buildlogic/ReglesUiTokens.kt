package jo.codeide.buildlogic

/**
 * Garde-fou des littéraux UI (ADR 0127 §5, lot UI-2) — moteur PUR de
 * scan, sans aucun type Gradle : testable en JUnit standard, câblé
 * ensuite par [UiTokensPlugin] sur la tâche `checkUiTokens`.
 *
 * Portée (ADR §5) : scan TEXTE des `res/layout*`, `res/drawable*`
 * (hors `ic_*` — icônes vectorielles assumées) et des sources
 * Kotlin `src/main` de tous les modules. Le code EXISTANT converge
 * par un CLIQUET (baseline versionnée `config/ui-baseline.json`) :
 * la dette ne peut que diminuer ; le code NOUVEAU est tenu à zéro
 * littéral (« un fichier inconnu de la baseline avec la moindre
 * occurrence échoue »).
 */
object MoteurUiTokens {
    /** Règles R1-R7, chacune avec son conseil de réparation lisible. */
    enum class Regle(
        val numero: String,
        val conseil: String,
    ) {
        R1("R1", "marge/padding littéral·le — utiliser @dimen/spacing_* (échelle 2xs·xs·sm·md_compact·md·lg·xl ; 0dp/1dp admis)"),
        R2("R2", "android:textSize littéral — référencer un textAppearance @style/TextAppearance.CodeIDE.*"),
        R3("R3", "couleur #hex en dur — passer par un rôle de thème ?attr/color… (tools: excepté, ic_ non scanné)"),
        R4("R4", "style Material brut — remplacer par le style Widget.CodeIDE.* correspondant"),
        R5("R5", "widget nu — remplacer par MaterialButton/MaterialCardView ou le composant core:ui dédié"),
        R6("R6", "Toast.makeText — utiliser Retour.snackbar(…) de core:ui (ADR 0127 §3)"),
        R7("R7", "cardCornerRadius/cardElevation littéral — carte par @style/Widget.CodeIDE.Card, rayon/élévation par jeton"),
    }

    /** Une occurrence : règle, ligne (1-indexée), extrait de la ligne fautive. */
    data class Occurrence(
        val regle: Regle,
        val ligne: Int,
        val extrait: String,
    )

    /** Dépassement du cliquet : fichier, règle, ancien plafond, nouveau constat. */
    data class Depassement(
        val chemin: String,
        val regle: String,
        val ancien: Int,
        val constate: Int,
    )

    /** Ligne de marge/padding LITTÉRALE : `android:layout_margin*|padding*="<n>dp"`. */
    private val VALEUR_SPACING = Regex("""android:(?:layout_margin|padding)[A-Za-z]*="\s*(-?\d+)dp"""")

    /** Taille de texte littérale : `android:textSize="<n>sp"`. */
    private val VALEUR_TEXTE = Regex("""android:textSize="\s*\d+(?:\.\d+)?sp"""")

    /** Couleur hexadécimale littérale (6 ou 8 chiffres hexa). */
    private val VALEUR_COULEUR = Regex("""#[0-9A-Fa-f]{6,8}""")

    /** Attribut de carte littéral : `app:cardCornerRadius` / `app:cardElevation`. */
    private val VALEUR_CARTE = Regex("""app:card(?:CornerRadius|Elevation)="\s*-?\d+(?:\.\d+)?dp"""")

    /** Widgets nus : `<Button`, `<ImageButton` (balise ouvrante uniquement). */
    private val WIDGET_NU = Regex("""<(?:Button|ImageButton)[\s>/]""")

    /** Scan d'un fichier XML (layout ou drawable non-icône) : R1-R5, R7. */
    fun xml(contenu: String): List<Occurrence> {
        val occurrences = mutableListOf<Occurrence>()
        contenu.lineSequence().forEachIndexed { index, ligne ->
            val numeroLigne = index + 1

            // R1 : marges et paddings littéraux ; 0 dp et 1 dp admis
            // (filets, contraintes — exceptions assumées ADR §4).
            val margeLiterale =
                VALEUR_SPACING.findAll(ligne).any { correspondance ->
                    (correspondance.groupValues[1].toIntOrNull() ?: 0) >= 2
                }
            if (margeLiterale) {
                occurrences += Occurrence(Regle.R1, numeroLigne, ligne.trim())
            }

            // R2 : tailles de texte hors TextAppearance (point d'ancrage
            // unique : typography.xml de core:ui).
            if (VALEUR_TEXTE.containsMatchIn(ligne)) {
                occurrences += Occurrence(Regle.R2, numeroLigne, ligne.trim())
            }

            // R3 : couleurs en dur, SAUF les valeurs d'aperçu tools:.
            if (VALEUR_COULEUR.containsMatchIn(ligne) && !ligne.contains("tools:")) {
                occurrences += Occurrence(Regle.R3, numeroLigne, ligne.trim())
            }

            // R4 : dialectes de styles tiers (audit C3).
            if (ligne.contains("@style/Widget.Material3.") ||
                ligne.contains("?attr/materialButton") ||
                ligne.contains("Widget.MaterialComponents.")
            ) {
                occurrences += Occurrence(Regle.R4, numeroLigne, ligne.trim())
            }

            // R5 : widgets nus — le Material correspondant est thématisé.
            if (WIDGET_NU.containsMatchIn(ligne) || ligne.contains("androidx.cardview.widget.CardView")) {
                occurrences += Occurrence(Regle.R5, numeroLigne, ligne.trim())
            }

            // R7 : rayon et élévation littéraux sur les cartes — le
            // style Widget.CodeIDE.Card porte ces valeurs par jeton.
            if (VALEUR_CARTE.containsMatchIn(ligne)) {
                occurrences += Occurrence(Regle.R7, numeroLigne, ligne.trim())
            }
        }
        return occurrences
    }

    /** Scan d'un fichier Kotlin : R6 (Toast banni, Snackbar par Retour). */
    fun kotlin(contenu: String): List<Occurrence> =
        contenu.lineSequence().mapIndexedNotNull { index, ligne ->
            if (ligne.contains("Toast.makeText")) {
                Occurrence(Regle.R6, index + 1, ligne.trim())
            } else {
                null
            }
        }.toList()

    /** Décompte par règle d'une liste d'occurrences (format de la baseline). */
    fun decompte(occurrences: List<Occurrence>): Map<String, Int> =
        Regle.entries.associate { regle -> regle.numero to occurrences.count { it.regle == regle } }

    /**
     * Compare un constat au plafond de la baseline : un fichier NOUVEAU
     * n'a pas droit à la moindre occurrence ; un fichier connu ne peut
     * pas dépasser son plafond, règle par règle.
     */
    fun comparer(
        chemin: String,
        constate: Map<String, Int>,
        plafond: Map<String, Int>?,
    ): List<Depassement> =
        Regle.entries.mapNotNull { regle ->
            val ancien = plafond?.get(regle.numero) ?: AUCUN
            val nouveau = constate[regle.numero] ?: AUCUN
            if (nouveau > ancien) {
                Depassement(chemin, regle.numero, ancien, nouveau)
            } else {
                null
            }
        }

    /**
     * Fait descendre la baseline (cliquet) : par fichier, la nouvelle
     * valeur est le MIN de l'ancien plafond et du constat — une
     * remontée manuelle est impossible par construction ; les fichiers
     * disparus du scan SORTENT (la baseline ne peut que diminuer) ; un
     * fichier inconnu du scan n'entre pas, même propre (aucun plafond
     * à poser), et un fichier inconnu SALE ne peut pas entrer : le
     * [cliquet] ne se sert QUE des fichiers déjà plafonnés.
     */
    fun cliquet(
        constat: Map<String, Map<String, Int>>,
        ancienne: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> =
        constat.mapNotNull { (chemin, decompteFichier) ->
            val ancien = ancienne[chemin] ?: return@mapNotNull null
            val fusion =
                Regle.entries.associate { regle ->
                    val plafond = ancien[regle.numero] ?: AUCUN
                    val observe = decompteFichier[regle.numero] ?: AUCUN
                    regle.numero to minOf(plafond, observe)
                }
            if (fusion.values.sum() > AUCUN) chemin to fusion else null
        }.toMap()

    /** Absence de plafond : zéro par définition (constat > 0 = dépassement). */
    const val AUCUN: Int = 0
}
