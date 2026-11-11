package jo.codeide.preview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LE REGISTRE du moteur (C1 — fichier DÉTACHÉ de l'exécuteur en C3 :
 * chaque genre rend son composable réel de la bibliothèque embarquée,
 * les pastilles d'erreur isolent le nœud fautif (le reste se rend —
 * miroir `createMockView` de la référence : jamais un rendu
 * tout-ou-rien). C2 : [contexte] porte les RÉSOLVEURS du projet (chaque
 * référence `R.string/x`/`R.color/x`/`R.dimen/x`/
 * `MaterialTheme.colorScheme.x` se RÉSOUT ici, au rendu) et le
 * COLLECTEUR de bornes (chaque nœud publie sa géométrie bitmap + sa
 * ligne source — le sésame surlignage de la carte, écho
 * `ComposeViewInfoParser`). C3 : le POINTER est TRANSMIS à la
 * composition vivante ([RenduCompose.transmettre]) — la RIPPLE du
 * bouton M3 réagit au doigt — et les INDICATEURS DE PROGRESSION portent
 * l'ANIMATION INFINIE du registre (l'horloge interactive les fait
 * tourner, le rendu statique les fige à leur pose).
 */
@Composable
internal fun ContenuProgramme(programme: ProgrammeCompose, contexte: ContexteRendu) {
    Column {
        programme.racine.forEach { RendreNoeud(it, contexte) }
        if (programme.coupureBudget) {
            PastilleErreur("Interprétation tronquée (budget)", GenreErreurCompose.BUDGET_INTERPRETATION)
        }
    }
}

/** Rend UN nœud du programme (dispatch du registre C1 + résolution C2 + bornes C2). */
@Composable
internal fun RendreNoeud(noeud: NoeudProgramme, contexte: ContexteRendu) {
    when (noeud.genre) {
        GenreNoeudCompose.TEXTE -> RendreTexte(noeud, contexte)

        GenreNoeudCompose.COLONNE ->
            Column(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.RANGEE ->
            Row(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.BOITE ->
            Box(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.ESPACE -> Spacer(modifier = Modifier.espaceDu(noeud, contexte))

        GenreNoeudCompose.BOUTON ->
            // C1 statique : le ONCLICK de l'utilisateur n'est PAS
            // interprété (frontière honnête de l'interpréteur — la
            // sémantique du code source ne s'exécute jamais ici) ;
            // C3 : le POINTER, lui, est TRANSMIS ([RenduCompose
            // .transmettre]) — la RIPPLE et l'état PRESSED du bouton
            // M3 réagissent au doigt, l'exigence de M3 est honorée par
            // un no-op honnête sur le clic.
            Button(
                onClick = {},
                modifier = Modifier.bornesDe(noeud, contexte),
            ) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.PROGRES_CIRCULAIRE,
        GenreNoeudCompose.PROGRES_LINEAIRE,
        ->
            // C3 — l'ANIMATION INFINIE du registre (extraction miroir
            // de RendreTexte : le when du dispatch reste sous le seuil
            // detekt, chaque genre garde SON composable réel).
            RendreIndicateur(noeud, contexte)

        GenreNoeudCompose.INCONNU,
        GenreNoeudCompose.ERREUR_EXPRESSION,
        GenreNoeudCompose.ERREUR_INLINE,
        ->
            // Extraction miroir de [RendreIndicateur] : les pastilles
            // d'erreur vivent dans LEUR composable, le dispatch du
            // registre reste sous les seuils detekt.
            RendreErreur(noeud, contexte)
    }
}

/**
 * Les PASTILLES d'erreur du registre (extraction C3, miroir
 * [RendreTexte]/[RendreIndicateur]) : le nœud fautif est ISOLÉ en
 * pastille typée, le reste du programme se rend (miroir `createMockView`
 * de la référence — jamais un rendu tout-ou-rien).
 */
@Composable
private fun RendreErreur(noeud: NoeudProgramme, contexte: ContexteRendu) {
    val designation =
        when (noeud.genre) {
            GenreNoeudCompose.INCONNU -> "« ${noeud.nom} » hors registre (C1)" to GenreErreurCompose.COMPOSABLE_INCONNU

            GenreNoeudCompose.ERREUR_EXPRESSION ->
                "Non interprété : " +
                    (
                        if (noeud.nom.length <= LONGUEUR_PASTILLE) {
                            noeud.nom
                        } else {
                            noeud.nom.take(LONGUEUR_PASTILLE - 1) + "…"
                        }
                        ) to
                    GenreErreurCompose.EXPRESSION_NON_INTERPRETEE

            else -> "« ${noeud.nom} » non inliné (projet)" to GenreErreurCompose.COMPOSABLE_PROJET_PARAMETRES
        }
    PastilleErreur(designation.first, designation.second, modifier = Modifier.bornesDe(noeud, contexte))
}

/**
 * Le TEXTE du registre — extrait du dispatch : sa résolution C2
 * (chaîne du projet, couleur du projet ou du THÈME) est la PLUS
 * riche du registre et portait la complexité du when au-delà du
 * seuil detekt (règle 16 : RESTRUCTURE, pas de desserrage).
 */
@Composable
private fun RendreTexte(noeud: NoeudProgramme, contexte: ContexteRendu) {
    Text(
        text = texteDe(noeud, contexte),
        color = couleurTexteDe(noeud, contexte) ?: Color.Unspecified,
        modifier = Modifier.bornesDe(noeud, contexte),
    )
}

/**
 * C3 — Les INDICATEURS DE PROGRESSION M3 du registre (circulaire et
 * linéaire) : indéterminés par défaut, ils portent l'ANIMATION INFINIE
 * du registre — l'horloge interactive (frames réelles du pompier) les
 * fait TOURNER, le rendu statique les FIGE à leur pose (jamais un faux
 * mouvement). Extraction miroir de [RendreTexte] : le dispatch
 * [RendreNoeud] reste sous les seuils detekt.
 */
@Composable
private fun RendreIndicateur(noeud: NoeudProgramme, contexte: ContexteRendu) {
    when (noeud.genre) {
        GenreNoeudCompose.PROGRES_CIRCULAIRE ->
            CircularProgressIndicator(
                modifier = Modifier.bornesDe(noeud, contexte),
            )

        GenreNoeudCompose.PROGRES_LINEAIRE ->
            LinearProgressIndicator(
                modifier = Modifier.bornesDe(noeud, contexte),
            )

        else -> {}
    }
}

/**
 * Ellipse du nom de nœud dans la pastille « Non interprété » : le `nom`
 * d'un nœud expression est le TEXTE BRUT de l'expression (potentiellement
 * longue) — la pastille labelSmall plein cadre reste lisible en le bornant.
 */
private const val LONGUEUR_PASTILLE: Int = 32

/** Les genres d'argument acceptés comme TEXTE d'un nœud. */
private val GENRES_TEXTE: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.LITTERAL_CHAINE, GenreArgumentCompose.REFERENCE_CHAINE)

/**
 * La chaîne d'un Text : `text`/`texte` nommé ou premier texte — C2 : la
 * référence `R.string.NOM` se RÉSOUT sur le résolveur du projet ; une
 * référence introuvable rend le message par DÉFAUT de C1 (le nom du
 * nœud) ET l'erreur typée [GenreErreurCompose.RESSOURCE_INCONNUE]
 * accompagne le rendu (jamais un faux rendu silencieux).
 */
private fun texteDe(noeud: NoeudProgramme, contexte: ContexteRendu): String {
    val arguments = noeud.arguments
    val cible =
        arguments.firstOrNull { it.genre in GENRES_TEXTE && (it.nom == "text" || it.nom == "texte") }
            ?: arguments.firstOrNull { it.genre in GENRES_TEXTE }
    return when (cible?.genre) {
        GenreArgumentCompose.LITTERAL_CHAINE -> cible.valeur ?: noeud.nom

        GenreArgumentCompose.REFERENCE_CHAINE ->
            cible.valeur?.let { nom -> contexte.chaine(nom, noeud.ligne) } ?: noeud.nom

        else -> noeud.nom
    }
}

/** Les genres d'argument acceptés comme COULEUR d'un nœud. */
private val GENRES_COULEUR: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.REFERENCE_COULEUR, GenreArgumentCompose.REFERENCE_THEME)

/**
 * La couleur explicite d'un Text (`color`/`couleur` nommé) : C2 — la
 * référence `R.color.NOM` ou `MaterialTheme.colorScheme.SLOT` se RÉSOUT
 * sur le thème/les ressources du PROJET (`null` = la couleur Material
 * par défaut du contenu, comportement C1).
 */
private fun couleurTexteDe(noeud: NoeudProgramme, contexte: ContexteRendu): Color? {
    val cible =
        noeud.arguments.firstOrNull {
            it.genre in GENRES_COULEUR && (it.nom == "color" || it.nom == "couleur")
        } ?: return null
    return when (cible.genre) {
        GenreArgumentCompose.REFERENCE_COULEUR ->
            cible.valeur?.let { nom -> contexte.couleur(nom, noeud.ligne) }

        GenreArgumentCompose.REFERENCE_THEME ->
            cible.valeur?.let { slot -> contexte.couleurDuSlot(slot, noeud.ligne) }

        else -> null
    }
}

/**
 * Le modificateur d'un ESPACE (EXTENSION de Modifier — règle lint
 * Compose `ModifierFactoryExtensionFunction`) : hauteur explicite,
 * sinon largeur explicite, sinon pleine largeur ; les bornes du nœud
 * se posent en QUEUE de chaîne (l'étendue publiée est l'étendue
 * dessinée). C2 : les références `R.dimen.NOM` se résolvent sur le
 * projet.
 */
private fun Modifier.espaceDu(noeud: NoeudProgramme, contexte: ContexteRendu): Modifier =
    dimensionDe(noeud, contexte, setOf("height", "hauteur", null))
        ?.let { hauteur -> height(hauteur).bornesDe(noeud, contexte) }
        ?: dimensionDe(noeud, contexte, setOf("width", "largeur"))
            ?.let { largeur -> width(largeur).bornesDe(noeud, contexte) }
        ?: fillMaxWidth().bornesDe(noeud, contexte)

/** Les genres d'argument acceptés comme DIMENSION d'un nœud. */
private val GENRES_DIMENSION: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.VALEUR_DP, GenreArgumentCompose.REFERENCE_DIMENSION)

/**
 * La dimension dp d'un ESPACE pour les noms cités (`height`/`hauteur`
 * — `null` accepté en positionnel — puis `width`/`largeur`) : littéral
 * `N.dp` ou référence `R.dimen.NOM` du projet. Les DEUX lecteurs
 * historiques étaient des copies, la C3 les FUSIONNE (detekt
 * TooManyFunctions tué par RESTRUCTURE — une seule machinerie de
 * résolution pour un seul besoin).
 */
private fun dimensionDe(noeud: NoeudProgramme, contexte: ContexteRendu, noms: Set<String?>): Dp? {
    val cible =
        noeud.arguments.firstOrNull {
            it.genre in GENRES_DIMENSION && it.nom in noms
        } ?: return null
    return when (cible.genre) {
        GenreArgumentCompose.VALEUR_DP -> cible.valeur?.toFloatOrNull()?.dp

        GenreArgumentCompose.REFERENCE_DIMENSION ->
            cible.valeur?.let { nom -> contexte.dimensionDp(nom, noeud.ligne) }

        else -> null
    }
}

/** Pastille d'erreur inline (isole le nœud fautif — le reste se rend). */
@Composable
private fun PastilleErreur(message: String, genre: GenreErreurCompose, modifier: Modifier = Modifier) {
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = "$message [${genre.name}]",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
