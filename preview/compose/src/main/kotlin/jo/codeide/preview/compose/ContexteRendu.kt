package jo.codeide.preview.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Le contexte du REGISTRE (C2) — regroupé pour traverser
 * ([ContenuProgramme]/[RendreNoeud]) SANS CompositionLocal : les
 * dépendances du rendu restent EXPLICITES en paramètre (patron du
 * port étroit de la feature, testable sans machinerie).
 *
 * Il est STABLE et PORTÉ PAR LA SESSION (jamais remplacé) : la
 * PREMIÈRE composition d'une session peut partir à l'ATTACH de la
 * vue — le contexte y est donc déjà VIVANT (résolveur de la requête
 * fondateur), [debuter] ne fait que re-polariser les résolveurs et
 * VIDER les collections du rendu précédent. Un état posé APRÈS
 * l'attach rendrait la première composition VIDE (leçon du correctif
 * C2 : la composition ne rend JAMAIS sur un contexte null).
 *
 * Deux rôles :
 *  1. la RÉSOLUTION des références du programme sur les résolveurs DU
 *     PROJET ([ResolveurCompose] — réutilisation PE1) : chaque
 *     consultation manquée (ressource absente, slot absent, projet
 *     sans `res/`) consigne l'erreur typée
 *     [GenreErreurCompose.RESSOURCE_INCONNUE] et rend `null` —
 *     l'argument retombe sur son DÉFAUT honnête (miroir
 *     `createMockView` : jamais un faux rendu, jamais une exception) ;
 *  2. le COLLECTEUR des bornes ([InfoNoeudCompose] — bounds bitmap px
 *     + ligne source par nœud, écho `ComposeViewInfoParser` de la
 *     référence) : [mextension [bornesDe] compose le modificateur
 *     `onGloballyPositioned` posé en QUEUE de chaîne (OUTERMOST — la
 *     géométrie publiée est celle DESSINÉE), [noeuds] referme le
 *     relevé AU-DELÀ de la passe de mise en page (les callbacks
 *     tirent pendant `measure`/`layout` du rendu, fil de composition
 *     sérialisé — aucun verrou nécessaire).
 *
 * Les collections sont VIDÉES par [debuter] à chaque rendu
 * ([RenduCompose.rendreSurFil]) : les bornes et erreurs collectées
 * n'appartiennent qu'à CE rendu — jamais de pollution entre les
 * rendus d'une même session persistante.
 */
internal class ContexteRendu(
    /** Les résolveurs du projet (`null` = sans projet — défauts du runtime, comportement C1). */
    private var resolveurInterne: ResolveurCompose? = null,
    /** Journal de diagnostic du moteur (pannes, sondes de composition). */
    internal val journal: (String) -> Unit = {},
) {
    /**
     * Polarise SEULEMENT les résolveurs (sans vider) — le PREMIER rendu
     * d'une session : la composition d'ATTACH a déjà collecté erreurs
     * de résolution et bornes, il ne faut JAMAIS les effacer après
     * coup (leçon du correctif C2).
     */
    internal fun polariser(resolveur: ResolveurCompose?) {
        resolveurInterne = resolveur
    }

    /**
     * Ouvre UN rendu SUIVANT : polarise les résolveurs de la requête et
     * VIDE les collections du rendu précédent (bornes, erreurs) — la
     * recomposition qui suit re-collecte tout.
     */
    internal fun debuter(resolveur: ResolveurCompose?) {
        polariser(resolveur)
        geometries.clear()
        erreursInterne.clear()
    }

    /** Géométries publiées par la passe de mise en page du rendu. */
    private val geometries = mutableListOf<InfoNoeudCompose>()

    /** Erreurs de résolution du rendu (accompagnent TOUJOURS un rendu réussi). */
    private val erreursInterne = mutableListOf<ErreurCompose>()

    /** `R.string.NOM` → la chaîne du projet ; manquée → `null` + erreur typée. */
    internal fun chaine(nom: String, ligne: Int): String? {
        val valeur = resolveurInterne?.chaine(nom)
        if (valeur == null) manquee("R.string/$nom", ligne)
        return valeur
    }

    /** `R.color.NOM` → la couleur du projet ; manquée → `null` + erreur typée. */
    internal fun couleur(nom: String, ligne: Int): Color? {
        val valeur = resolveurInterne?.couleur(nom)
        if (valeur == null) manquee("R.color/$nom", ligne)
        return valeur?.let(::Color)
    }

    /** `MaterialTheme.colorScheme.SLOT` → la couleur du thème du projet ; manquée → `null` + erreur typée. */
    internal fun couleurDuSlot(slot: String, ligne: Int): Color? {
        val valeur = resolveurInterne?.couleurDuSlot(slot)
        if (valeur == null) manquee("MaterialTheme.colorScheme/$slot", ligne)
        return valeur?.let(::Color)
    }

    /** `R.dimen.NOM` → la dimension dp du projet ; manquée → `null` + erreur typée. */
    internal fun dimensionDp(nom: String, ligne: Int): Dp? {
        val valeur = resolveurInterne?.dimensionDp(nom)
        if (valeur == null) manquee("R.dimen/$nom", ligne)
        return valeur?.dp
    }

    /** Les erreurs de résolution collectées pendant CE rendu. */
    internal val erreurs: List<ErreurCompose>
        get() = erreursInterne

    /** Le relevé des géométries (ordre de publication de la passe de mise en page). */
    internal val noeuds: Array<InfoNoeudCompose>
        get() = geometries.toTypedArray()

    /**
     * Publie la géométrie d'un nœud positionné (callback du fil de
     * composition) — INTERNE au module : [bornesDe] l'invoque.
     */
    internal fun poser(noeud: NoeudProgramme, coords: LayoutCoordinates) {
        val bornes = coords.boundsInRoot()
        geometries += InfoNoeudCompose(
            ligne = noeud.ligne,
            genre = noeud.genre,
            gauche = bornes.left.roundToInt(),
            haut = bornes.top.roundToInt(),
            droite = bornes.right.roundToInt(),
            bas = bornes.bottom.roundToInt(),
        )
    }

    /** Consigne une référence non résolue (l'erreur accompagne le rendu, jamais d'exception). */
    private fun manquee(designation: String, ligne: Int) {
        erreursInterne += ErreurCompose(GenreErreurCompose.RESSOURCE_INCONNUE, designation, ligne)
    }
}

/**
 * Le modificateur de bornes d'UN nœud — EXTENSION de [Modifier] (règle
 * lint Compose `ModifierFactoryExtensionFunction` : toute fabrique de
 * modificateur vit SUR Modifier, les appelants composent
 * `Modifier.bornesDe(…)`) : `onGloballyPositioned` en tête de chaîne
 * neuve = l'étendue FINALE dessinée, `boundsInRoot` donnant le repère
 * du coin haut-gauche du canevas de composition (l'origine du bitmap —
 * la vue est posée à (0, 0) au rendu off-screen).
 */
internal fun Modifier.bornesDe(noeud: NoeudProgramme, contexte: ContexteRendu): Modifier =
    onGloballyPositioned { coords: LayoutCoordinates -> contexte.poser(noeud, coords) }
