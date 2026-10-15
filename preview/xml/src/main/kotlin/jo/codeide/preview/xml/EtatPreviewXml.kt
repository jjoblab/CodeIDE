package jo.codeide.preview.xml

import android.graphics.Bitmap

/**
 * État du panneau de preview XML (flux d'état du [MoteurPreviewXml]).
 *
 * Contrat « jamais de panneau vide » (PREVIEW.md §6) : pendant les
 * frappes sur du XML malformé, le DERNIER rendu valide RESTE affiché —
 * [EnEdition] est un signal discret, pas un remplacement du rendu.
 */
public sealed interface EtatPreviewXml {
    /** État initial : aucun rendu encore produit. */
    public object Inactif : EtatPreviewXml

    /**
     * Rendu valide : le bitmap off-screen dessiné, avec ses métriques
     * (durée du rendu, nombre de vues, dimensions réellement mesurées) et
     * l'arbre APLATI des vues rendues (PE3, P6 : bounds absolues dans le
     * BITMAP — chrome compris, v0.134.2 — + span source de chaque vue :
     * le hit-test de l'UI sélectionne dans l'éditeur, la sélection de
     * l'éditeur surligne la vue).
     */
    @Suppress("LongParameterList")
    public class RenduValide(
        /** Bitmap ARGB_8888 du rendu — propriété du panneau, recyclé au rendu suivant. */
        public val bitmap: Bitmap,
        /** Durée du rendu complet (validation → dessin), en millisecondes. */
        public val dureeMs: Long,
        /** Nombre de vues de l'arbre inflaté (ou entrées de values — PE3). */
        public val nombreVues: Int,
        /** Largeur mesurée de la racine (pixels du profil device cible). */
        public val largeur: Int,
        /** Hauteur mesurée de la racine (pixels du profil device cible). */
        public val hauteur: Int,
        /** Vues rendues en pré-ordre (vide hors LAYOUT — pas de sélection). */
        public val vues: List<InfoVueRendu> = emptyList(),
    ) : EtatPreviewXml

    /**
     * XML valide mais rendu échoué (tag inconnu, classe introuvable —
     * erreur SÉMANTIQUE) : message affiché en bandeau, le dernier rendu
     * valide reste affiché dessous s'il existe.
     */
    public class ErreurRendu(
        /** Message d'erreur lisible (jamais brut : cause simplifiée). */
        public val message: String,
    ) : EtatPreviewXml

    /** XML malformé (frappe en cours) : signal discret, le rendu précédent reste. */
    public object EnEdition : EtatPreviewXml
}
