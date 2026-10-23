package jo.codeide.preview.compose

import android.graphics.Bitmap

/**
 * Profil d'appareil du rendu Compose (C1 — miroir allégé de
 * ProfilPeripherique du frère XML : la carte « goutte d'eau » ne
 * propose PAS encore de sélecteur, le profil est un PARAMÈTRE du
 * moteur — la carte le fixe, un futur chantier l'exposera).
 *
 * @param largeurDp largeur du canevas de composition en dp.
 * @param hauteurDp hauteur du canevas de composition en dp.
 * @param densiteDpi densité de l'écran en dpi (160 = ×1).
 */
public data class ProfilApercuCompose(
    public val largeurDp: Int,
    public val hauteurDp: Int,
    public val densiteDpi: Int,
) {
    public companion object {
        /** Pixel 4 — 393×851dp, 440dpi (miroir du profil par défaut du frère XML). */
        public val PAR_DEFAUT: ProfilApercuCompose = ProfilApercuCompose(393, 851, 440)
    }
}

/**
 * Le rendu d'UNE preview `@Preview` du document (C1) — transporté dans
 * [EtatPreviewCompose.RenduValide], une entrée par preview DANS
 * l'ordre du document (l'indice = l'onglet de la carte « goutte
 * d'eau »).
 *
 * L'interprétation étant PARTIELLE par construction, les erreurs
 * typées ([erreurs]) accompagnent TOUJOURS le rendu — elles sont déjà
 * dessinées en PASTILLES par le processus (le nœud fautif est isolé,
 * le reste se rend) ; [erreurs] les rapporte pour l'affichage inline
 * côté éditeur (la C3 les surlignera dans le code).
 */
@Suppress("LongParameterList") // Porte TOUS les champs du rendu d'un onglet — état de la carte
public class RenduPreviewCompose(
    /** Nom de la fonction `@Preview` (libellé de l'onglet de la carte). */
    public val nom: String,
    /** Indice de la preview dans le document (= onglet de la carte). */
    public val index: Int,
    /** Le rendu — null si CE rendu a échoué (voir [messageErreur]). */
    public val bitmap: Bitmap?,
    /** Métriques du rendu (durée, fidélité, dimensions — NULLES si échec). */
    public val metriques: MetriquesRenduCompose,
    /** Erreurs typées du pipeline (présentes même si [bitmap] existe). */
    public val erreurs: List<ErreurCompose>,
    /** Message d'échec de CE rendu (null si [bitmap] présent). */
    public val messageErreur: String?,
    /**
     * C2 — géométries extraites de la composition (bounds px bitmap +
     * ligne source par nœud) : le SÉSAME surlignage de la carte — le
     * caret de l'éditeur sélectionne le nœud de SA ligne ; vide sur un
     * échec local (aucun bitmap, aucun overlay).
     */
    public val noeuds: List<InfoNoeudCompose> = emptyList(),
)

/**
 * État du moteur de preview Compose (C1 — flux d'état du
 * [MoteurPreviewCompose], miroir assumé de EtatPreviewXml).
 *
 * Contrat « jamais de carte vide » : pendant les frappes, le DERNIER
 * rendu valide de chaque preview RESTE affiché — [EnEdition] est un
 * signal discret, pas un remplacement des rendus ; le diff
 * d'identité (digeste, PC1) ne re-rend que ce qui a CHANGÉ, l'état de
 * la session Compose du processus survit aux éditions.
 */
public sealed interface EtatPreviewCompose {
    /** État initial : aucun document branché, aucun rendu produit. */
    public object Inactif : EtatPreviewCompose

    /**
     * Rendu du document : UNE entrée par `@Preview` découverte, dans
     * l'ordre du document (l'indice = l'onglet de la carte « goutte
     * d'eau »). Une entrée peut porter un ÉCHEC local ([RenduPreviewCompose.bitmap]
     * null) sans invalider les autres.
     */
    public class RenduValide(public val rendus: List<RenduPreviewCompose>) : EtatPreviewCompose

    /** Échec du document entier (défensif — le fournisseur a levé). */
    public class ErreurRendu(public val message: String) : EtatPreviewCompose

    /**
     * Signal discret : plus aucune preview découverte (frappe en
     * cours, fichier sans `@Preview`, session LSP absente) — les
     * DERNIERS rendus restent affichés.
     */
    public object EnEdition : EtatPreviewCompose
}
