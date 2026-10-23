package jo.codeide.preview.xml

/**
 * Les CONTRAINTES DE HIÉRARCHIE DES VUES (v0.170.0, S3 — demande
 * propriétaire du 2026-10-23 : « quelles vues ne peuvent pas contenir de
 * vues enfants, lesquelles n'en admettent qu'une seule ») — les règles
 * de CONTENANCE de la plateforme Android, en DONNÉES PURES sur les noms
 * de balise, miroir des vérifications lint d'Android Studio :
 * - `AdapterViewChildren` (« AdapterViews cannot have children in XML »,
 *   sévérité erreur) — [CapaciteEnfants.ADAPTATEUR] ;
 * - `ScrollViewChildren` (« ScrollView can host only one direct child »,
 *   sévérité avertissement) — [CapaciteEnfants.UN_SEUL] ;
 * - les vues FEUILLES (TextView, Button, Chip…) refusent tout enfant —
 *   l'inflater réel plante ou dégrade — genre
 *   [ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS] ;
 * - les conteneurs à ENFANT ATTENDU n'acceptent qu'un type précis
 *   (TabLayout → TabItem, TextInputLayout → TextInputEditText — la vraie
 *   bibliothèque JET l'enfant fautif : IllegalArgumentException —
 *   ChipGroup → Chip, MaterialButtonToggleGroup → MaterialButton, tolérés).
 *
 * QUATRE consommateurs, une seule table :
 * - [PlacementPopup.positionsAutorisees] retire « Dedans » quand la
 *   référence refuse un enfant de plus (l'offre du popup suit la
 *   capacité, miroir `posList` étendu) ;
 * - [ZonesDepot.cibleSous] remonte au premier ancêtre QUI ACCEPTE un
 *   enfant quand le conteneur le plus profond sous le point est plein
 *   ou piloté par un adaptateur (miroir `targetAt` étendu) ;
 * - [ProblemesFamilles.evaluer] signale les documents qui violent déjà
 *   la contenance (évalués sur le TEXTE, spec § 9 — ils restent listés
 *   quand le rendu échoue) ;
 * - [PreviewViewModel.corrigerProblemeFamille] écrit les correctifs
 *   d'enveloppe et de retrait via [envelopperEnfants]/[retirerEnfants].
 *
 * FONCTIONS PURES, SANS ANDROID — testées en JVM
 * (`ContraintesHierarchieTest`). Une classe INCONNUE (MockView dégradé,
 * vue custom) reste PERMISSIVE : les règles ne s'appliquent qu'aux noms
 * qu'elles connaissent, jamais de faux refus (miroir la philosophie de
 * [FamilleParent.deBalise] : AUTRE par défaut).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — la surface de
 * la contenance (capacités, feuilles, verdict, problème, DEUX éditions
 * et leurs gardes) vit au COMPLET dans cet objet (précédents
 * [PlacementPopup], [InsertionXml]) ; les fonctions portent le contrat
 * pur testé, la TABLE est la règle, pas une accumulation.
 */
@Suppress("TooManyFunctions")
public object ContraintesHierarchie {
    /**
     * La CONTENANCE d'un conteneur par balise. Une FEUILLE n'a pas
     * d'entrée : elle se détecte par [estFeuilleConnue] (le genre
     * ENFANTS_INTERDITS ne s'applique qu'aux noms de la table).
     */
    public enum class CapaciteEnfants {
        /** Autant d'enfants que le document en porte — défaut. */
        PLUSIEURS,

        /** UN SEUL enfant direct (ScrollView et compagnie, SwipeRefreshLayout). */
        UN_SEUL,

        /** Aucun enfant dans le XML : le contenu vient de l'adaptateur. */
        ADAPTATEUR,

        /** Un seul TYPE d'enfant est admis (TabItem, Chip, MaterialButton…). */
        ATTENDU,
    }

    /** Le verdict de contenance d'un conteneur qui porte des enfants. */
    public data class ContenanceProbleme(
        /** Genre — clé du libellé localisé et du correctif. */
        public val genre: ProblemesPreview.GenreProbleme,
        /** Sévérité (miroir lint : erreurs pour l'adaptateur et la feuille). */
        public val severite: ProblemesPreview.Severite,
    )

    /**
     * La capacité d'une balise (simple ou qualifiée — le nom local seul
     * compte, miroir [FamilleParent.deBalise]) : la table EXACTE puis
     * les suffixes (les sous-classes custom « MonScrollView » héritent
     * de la règle de leur ancêtre — jamais de faux refus pour l'inconnu).
     */
    public fun capacite(balise: String): CapaciteEnfants {
        val nom = balise.substringAfterLast('.')
        return when {
            nom in ADAPTATEURS_EXACTS || nom.endsWith(SUFFIXE_LISTE) ||
                nom == BALISE_VIEW_PAGER || nom == BALISE_VIEW_PAGER2
            -> CapaciteEnfants.ADAPTATEUR

            nom in ENFANTS_ATTENDUS
            -> CapaciteEnfants.ATTENDU

            nom.endsWith(SUFFIXE_SCROLL_VIEW) || nom == BALISE_SWIPE_REFRESH
            -> CapaciteEnfants.UN_SEUL

            else -> CapaciteEnfants.PLUSIEURS
        }
    }

    /**
     * Le nom SIMPLE de l'enfant attendu par un conteneur
     * [CapaciteEnfants.ATTENDU], ou null pour tout autre conteneur
     * (TabLayout → « TabItem », TextInputLayout → « TextInputEditText »,
     * ChipGroup → « Chip », MaterialButtonToggleGroup → « MaterialButton »).
     */
    public fun baliseEnfantAttendu(balise: String): String? = ENFANTS_ATTENDUS[balise.substringAfterLast('.')]

    /**
     * Le conteneur REFUSE-T-IL l'enfant inattendu à l'inflation (vrai
     * pour TextInputLayout — la bibliothèque jette
     * IllegalArgumentException) ou le TOLÈRE-T-IL (TabLayout, ChipGroup,
     * MaterialButtonToggleGroup — rendu dégradé sans exception) ? La
     * sévérité du genre ENFANT_INATTENDU en dépend : erreur quand la
     * vraie classe jette, avertissement sinon.
     */
    public fun refuseEnfantInattendu(balise: String): Boolean =
        balise.substringAfterLast('.') == BALISE_TEXT_INPUT_LAYOUT

    /**
     * Le conteneur peut-il recevoir UN enfant de plus, sachant qu'il en
     * porte déjà [enfantsActuels] ? — C'EST LA RÈGLE DU POPUP ET DU
     * DÉPÔT :
     * - [CapaciteEnfants.ADAPTATEUR] et [CapaciteEnfants.ATTENDU]
     *   refusent toujours (le catalogue n'offre pas le type attendu,
     *   l'adaptateur remplace le XML) ;
     * - [CapaciteEnfants.UN_SEUL] accepte seulement à vide ;
     * - [CapaciteEnfants.PLUSIEURS] accepte toujours — sauf FEUILLE
     *   connue (défense en profondeur : le popup teste déjà `conteneur`
     *   du rendu, l'arbre textuel n'a pas ce drapeau).
     */
    public fun accepteEnfant(balise: String, enfantsActuels: Int): Boolean = when (capacite(balise)) {
        CapaciteEnfants.PLUSIEURS -> !estFeuilleConnue(balise)
        CapaciteEnfants.UN_SEUL -> enfantsActuels == 0
        CapaciteEnfants.ADAPTATEUR, CapaciteEnfants.ATTENDU -> false
    }

    /**
     * La FEUILLE CONNUE (TextView, Button, Chip, FloatingActionButton…) :
     * jamais un ViewGroup de la plateforme ne sort de ces tables — les
     * suffixes sont sûrs (`*TextView`, `*EditText`, `*Button`, `*Switch`
     * sont des feuilles ; aucun conteneur connu ne s'y termine). Une
     * classe inconnue n'est JAMAIS une feuille déclarée : pas de faux
     * refus sur les vues custom et les MockView.
     */
    public fun estFeuilleConnue(balise: String): Boolean {
        val nom = balise.substringAfterLast('.')
        return nom in FEUILLES_EXACTES ||
            nom.endsWith(SUFFIXE_TEXT_VIEW) ||
            nom.endsWith(SUFFIXE_EDIT_TEXT) ||
            nom.endsWith(SUFFIXE_BOUTON) ||
            nom.endsWith(SUFFIXE_INTERRUPTEUR)
    }

    /**
     * Le VERDICT de contenance d'un conteneur qui porte [balisesEnfants]
     * enfants (balises lues, simples ou qualifiées) — null quand tout
     * va bien (conteneur sain, feuille vide, classe inconnue). L'ordre
     * des règles suit la sévérité : adaptateur (erreur) → enfant
     * inattendu (erreur ou avertissement selon le refus réel) → enfant
     * unique dépassé (avertissement) → feuille avec enfants (erreur).
     */
    public fun contenance(balise: String, balisesEnfants: List<String>): ContenanceProbleme? {
        if (balisesEnfants.isEmpty()) return null
        val nom = balise.substringAfterLast('.')
        return when (capacite(nom)) {
            CapaciteEnfants.ADAPTATEUR -> {
                ContenanceProbleme(
                    ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
                    ProblemesPreview.Severite.ERREUR,
                )
            }

            CapaciteEnfants.ATTENDU -> {
                val attendu = baliseEnfantAttendu(nom)
                if (balisesEnfants.any { it.substringAfterLast('.') != attendu }) {
                    ContenanceProbleme(
                        ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
                        if (refuseEnfantInattendu(nom)) {
                            ProblemesPreview.Severite.ERREUR
                        } else {
                            ProblemesPreview.Severite.AVERTISSEMENT
                        },
                    )
                } else {
                    null
                }
            }

            CapaciteEnfants.UN_SEUL -> {
                if (balisesEnfants.size <= 1) {
                    null
                } else {
                    ContenanceProbleme(
                        ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE,
                        ProblemesPreview.Severite.AVERTISSEMENT,
                    )
                }
            }

            CapaciteEnfants.PLUSIEURS -> {
                if (!estFeuilleConnue(nom)) {
                    null
                } else {
                    ContenanceProbleme(
                        ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
                        ProblemesPreview.Severite.ERREUR,
                    )
                }
            }
        }
    }

    // ── Correctifs : éditions pures sur le texte vivant ────────────────

    /**
     * Le CORRECTIF « Envelopper dans un LinearLayout » du genre
     * ENFANT_UNIQUE_DEPASSE (miroir la résolution canonique du lint
     * ScrollViewChildren) : les enfants du conteneur fautif passent en
     * UNE SEULE édition sous un LinearLayout vertical — chaque ligne
     * d'enfant gagne UN niveau de retrait, la balise ouvrante prend
     * celui des enfants.
     *
     * Le verdict est RECALCULÉ sur le [texte] VIVANT (miroir le contrat
     * de `corrigerProblemeFamille` : le problème du panneau peut dater
     * d'avant une frappe) : conteneur introuvable au span, capacité
     * changée ou enfants sains → null, le correctif se refuse.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (genre étranger, nœud périmé, capacité changée) est une issue
     * DISTINCTE du refus (philosophie X4, précédent [ZonesDepot]).
     */
    @Suppress("ReturnCount")
    public fun envelopperEnfants(texte: String, probleme: ProblemesFamilles.Probleme): InsertionXml.EditionXml? {
        if (probleme.genre != ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE) return null
        val noeud = noeudVivant(texte, probleme) ?: return null
        val enfants = noeud.enfants
        if (capacite(noeud.balise) != CapaciteEnfants.UN_SEUL || enfants.size < 2) return null
        val premier = enfants.first()
        val dernier = enfants.last()
        val debut = debutLigne(texte, premier.debut)
        val retrait = texte.substring(debut, premier.debut)
        val extraits = texte.substring(premier.debut, dernier.fin)
        return InsertionXml.EditionXml(
            debut = debut,
            fin = dernier.fin,
            remplacement =
            baliseEnveloppe(retrait) + "\n\n" +
                extraits.lignesIndentees(retrait) + "\n\n" +
                retrait + BALISE_ENVELOPPE_FERMANTE,
        )
    }

    /**
     * Le CORRECTIF « Retirer les enfants » des genres ENFANTS_INTERDITS,
     * ENFANTS_ADAPTATEUR et ENFANT_INATTENDU : les enfants fautifs
     * partent en UNE SEULE édition (le retrait de la première ligne
     * fautive part avec — le conteneur reste lisible).
     *
     * Le verdict est RECALCULÉ sur le [texte] VIVANT : conteneur
     * introuvable, capacité changée ou enfants sains → null. Pour un
     * conteneur à enfant ATTENDU, seuls les enfants du MAUVAIS type
     * partent — mais ils doivent former un RUN CONTIGU (des enfants
     * légitimes intercalés rendraient le retrait en une seule édition
     * FAUSSE ; le cas est rare, le correctif se refuse et l'onglet
     * Problèmes garde le message).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (genre étranger, nœud périmé, enfants vides, fautifs absents,
     * contiguïté) est une issue DISTINCTE du refus (philosophie X4,
     * précédent [ProblemesFamilles.boucleDuParent]).
     */
    @Suppress("ReturnCount")
    public fun retirerEnfants(texte: String, probleme: ProblemesFamilles.Probleme): InsertionXml.EditionXml? {
        val genre = probleme.genre
        if (genre !in GENRES_RETRAIT) return null
        val noeud = noeudVivant(texte, probleme) ?: return null
        val enfants = noeud.enfants
        if (enfants.isEmpty()) return null
        val indicesFautifs =
            when (genre) {
                ProblemesPreview.GenreProbleme.ENFANT_INATTENDU -> {
                    val attendu = baliseEnfantAttendu(noeud.balise)
                    enfants
                        .withIndex()
                        .filter { it.value.balise.substringAfterLast('.') != attendu }
                        .map { it.index }
                }

                else -> {
                    enfants.indices.toList()
                }
            }
        if (indicesFautifs.isEmpty()) return null
        if (indicesFautifs != (indicesFautifs.first()..indicesFautifs.last()).toList()) return null
        val premier = enfants[indicesFautifs.first()]
        val dernier = enfants[indicesFautifs.last()]
        return InsertionXml.EditionXml(
            debut = debutLigne(texte, premier.debut),
            fin = dernier.fin,
            remplacement = "",
        )
    }

    // ── Évaluation et correctifs : le problème et ses éditions ──────

    /**
     * Le PROBLÈME de contenance d'un nœud du document (S3 — évalué sur
     * le TEXTE par [ProblemesFamilles.evaluer]) : le verdict de
     * [contenance] sur les balises de ses enfants, RATTACHÉ AU
     * CONTENEUR fautif (balise, id, span de l'élément ENTIER — les
     * correctifs s'y posent). Les attributs en cause restent VIDEs : le
     * problème n'est pas un attribut.
     */
    public fun problemeDeContenance(noeud: ArbreAttributsXml.Noeud): ProblemesFamilles.Probleme? {
        val verdict =
            contenance(
                noeud.balise,
                noeud.enfants.map { enfant -> enfant.balise },
            ) ?: return null
        return ProblemesFamilles.Probleme(
            genre = verdict.genre,
            severite = verdict.severite,
            balise = noeud.balise,
            id = noeud.id,
            debut = noeud.debut,
            fin = noeud.fin,
            attributs = emptyList(),
            cible = null,
            libelleParent = "",
            cycle = emptyList(),
            conteneur = "",
        )
    }

    // ── Nœuds et textes ────────────────────────────────────────────────

    /** Le nœud du texte vivant au span du problème (début + balise), ou null si périmé. */
    private fun noeudVivant(texte: String, probleme: ProblemesFamilles.Probleme): ArbreAttributsXml.Noeud? =
        ArbreAttributsXml
            .lire(texte)
            .firstOrNull { it.debut == probleme.debut && it.balise == probleme.balise }

    /**
     * Le début de la LIGNE de l'offset, QUAND elle n'est que du blanc
     * avant l'élément (le retrait part avec l'édition, le conteneur
     * reste lisible) ; sinon l'offset lui-même (défense).
     */
    private fun debutLigne(texte: String, offset: Int): Int {
        val debut = texte.lastIndexOf('\n', offset - 1) + 1
        val prefixe = texte.substring(debut, offset)
        return if (prefixe.isNotBlank()) offset else debut
    }

    /** La balise ouvrante du LinearLayout d'enveloppe, au retrait des enfants. */
    private fun baliseEnveloppe(retrait: String): String = retrait + "<LinearLayout\n" +
        retrait + PAS + "android:layout_width=\"match_parent\"\n" +
        retrait + PAS + "android:layout_height=\"wrap_content\"\n" +
        retrait + PAS + "android:orientation=\"vertical\">"

    /** Le texte des enfants, décalé d'UN niveau (chaque ligne gagne le retrait d'un enfant). */
    private fun String.lignesIndentees(retrait: String): String = lines().joinToString("\n") { retrait + PAS + it }

    // ── Tables de référence (miroir lint, documentation Android) ──────

    /** AdapterView et assimilés — contenu de l'adaptateur, jamais du XML. */
    private val ADAPTATEURS_EXACTS: Set<String> =
        setOf(
            "ListView",
            "GridView",
            "Spinner",
            "Gallery",
            "AdapterViewFlipper",
            "RecyclerView",
        )

    /** Le pagineur de la première génération — pages de l'adaptateur. */
    private const val BALISE_VIEW_PAGER: String = "ViewPager"

    /** Le pagineur ViewPager2 — pages de l'adaptateur, jamais du XML. */
    private const val BALISE_VIEW_PAGER2: String = "ViewPager2"

    /** Le suffixe des listes (ListView, ExpandableListView…). */
    private const val SUFFIXE_LISTE: String = "ListView"

    /** Le suffixe des conteneurs défilants (ScrollView, NestedScrollView…). */
    private const val SUFFIXE_SCROLL_VIEW: String = "ScrollView"

    /** SwipeRefreshLayout — la documentation : un seul enfant défilant. */
    private const val BALISE_SWIPE_REFRESH: String = "SwipeRefreshLayout"

    /** Les conteneurs à ENFANT ATTENDU, avec le nom simple de l'enfant. */
    private val ENFANTS_ATTENDUS: Map<String, String> =
        mapOf(
            "TabLayout" to "TabItem",
            "TextInputLayout" to "TextInputEditText",
            "ChipGroup" to "Chip",
            "MaterialButtonToggleGroup" to "MaterialButton",
        )

    /** TextInputLayout JET l'enfant fautif (IllegalArgumentException). */
    private const val BALISE_TEXT_INPUT_LAYOUT: String = "TextInputLayout"

    /** Les FEUILLES EXACTES de la plateforme et des bibliothèques embarquées. */
    private val FEUILLES_EXACTES: Set<String> =
        setOf(
            "View",
            "Space",
            "ViewStub",
            "Guideline",
            "ImageView",
            "ImageButton",
            "VideoView",
            "WebView",
            "TextureView",
            "SurfaceView",
            "Chronometer",
            "TextClock",
            "AnalogClock",
            "DigitalClock",
            "CheckBox",
            "ProgressBar",
            "SeekBar",
            "RatingBar",
            "Chip",
            "FloatingActionButton",
            "Slider",
            "SwitchCompat",
            "SwitchMaterial",
        )

    /** Le suffixe des feuilles de texte (TextView, CheckedTextView…). */
    private const val SUFFIXE_TEXT_VIEW: String = "TextView"

    /** Le suffixe des feuilles de saisie (EditText, TextInputEditText…). */
    private const val SUFFIXE_EDIT_TEXT: String = "EditText"

    /** Le suffixe des feuilles boutons (Button, MaterialButton…). */
    private const val SUFFIXE_BOUTON: String = "Button"

    /** Le suffixe des feuilles interrupteurs (Switch, SwitchCompat…). */
    private const val SUFFIXE_INTERRUPTEUR: String = "Switch"

    /** Les genres corrigés par le RETRAIT des enfants. */
    private val GENRES_RETRAIT: Set<ProblemesPreview.GenreProbleme> =
        setOf(
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
        )

    /** La balise fermante du LinearLayout d'enveloppe. */
    private const val BALISE_ENVELOPPE_FERMANTE: String = "</LinearLayout>"

    /** Le pas d'indentation des layouts (miroir [InsertionXml] : 4 espaces). */
    private const val PAS: String = "    "
}
