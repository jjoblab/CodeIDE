package jo.codeide.feature.preview

import android.content.Context
import android.util.TypedValue
import jo.codeide.preview.xml.JetonsCalques

/**
 * Aides de STYLE partagées des volets de la feuille (PE9a) : la couleur
 * d'un attribut du THÈME, la conversion dp et les JETONS du moteur — UN
 * SEUL endroit pour le chrome des quatre volets. Toute couleur passe par
 * l'ATTRIBUT du thème — aucune couleur en dur (règle 3 du dépôt), hors
 * jetons `preview_*` du module.
 *
 * PE11k : les aides d'habillage d'avant-PE11b (apparences Material,
 * TextView libellé/vide, fond réactif, constantes WRAP) sont retirées —
 * ORPHELINES depuis que chaque contrôle/rangée vit dans SON layout de
 * la référence (ADR 0134), même hygiène que STYLES/TYPES_SAISIE en
 * PE11d.
 */
internal object StyleVolet {
    /** La conversion dp → px (densité de l'écran courant). */
    internal fun dp(contexte: Context, valeur: Int): Int = (valeur * contexte.resources.displayMetrics.density).toInt()

    /** La couleur d'un attribut du THÈME courant (une seule voie). */
    internal fun couleur(contexte: Context, attribut: Int): Int {
        val valeur = TypedValue()
        contexte.theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    /**
     * Les JETONS DU MOTEUR (PE11i — ADR 0134 D7) : les 21 couleurs des
     * calques résolues du THÈME courant (clair ou nuit), DANS L'ORDRE de
     * [JetonsCalques.NOMS] — l'activité les injecte par
     * `VuePreviewXml.appliquerJetons` à la création et à chaque
     * changement de thème ; la parité maquette ↔ ressources est gardée
     * par `JetonsCalquesTest`.
     */
    internal fun jetonsCalques(contexte: Context): JetonsCalques = JetonsCalques(
        surface2 = couleur(contexte, R.attr.apercuSurface2),
        damierCase = couleur(contexte, R.attr.apercuDamierCase),
        accent = couleur(contexte, R.attr.apercuAccent),
        accentDoux = couleur(contexte, R.attr.apercuAccentDoux),
        surface = couleur(contexte, R.attr.apercuSurface),
        texte = couleur(contexte, R.attr.apercuTexte),
        fond = couleur(contexte, R.attr.apercuFond),
        texteDoux = couleur(contexte, R.attr.apercuTexteDoux),
        marge = couleur(contexte, R.attr.apercuMarge),
        margeFond = couleur(contexte, R.attr.apercuMargeFond),
        remplissage = couleur(contexte, R.attr.apercuRemplissage),
        remplissageFond = couleur(contexte, R.attr.apercuRemplissageFond),
        bornes = couleur(contexte, R.attr.apercuBornes),
        nouveau = couleur(contexte, R.attr.apercuNouveau),
        nouveauDoux = couleur(contexte, R.attr.apercuNouveauDoux),
        surNouveau = couleur(contexte, R.attr.apercuSurNouveau),
        epingleAvert = couleur(contexte, R.attr.apercuEpingleAvert),
        epingleErreur = couleur(contexte, R.attr.apercuEpingleErreur),
        surCalque = couleur(contexte, R.attr.apercuSurCalque),
        ecranTrait = couleur(contexte, R.attr.apercuEcranTrait),
        ecranOmbre = couleur(contexte, R.attr.apercuEcranOmbre),
    )
}
