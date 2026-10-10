package jo.codeide.core.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Poseur d'étiquettes de l'historique local (mission H4, ADR 0106 § d) :
 * le **point d'extension des actions risquées** — la compilation, la
 * future bascule de branche Git, le remplacement multi-fichiers —
 * posent « Avant … » SANS dépendre du module historique : elles
 * appellent ce cas d'usage du domaine, jamais l'inverse.
 *
 * Une étiquette est une ENTREE sans contenu (pas de blob) : elle ne
 * fait que MARQUER un instant du projet ou d'un fichier — la liste des
 * révisions la montre et les révisions voisines se lisent « avant /
 * après l'étiquette », exactement comme le Local History d'IntelliJ.
 *
 * Le libellé d'une étiquette SYSTÈME est une DONNÉE (français, pas une
 * chaîne d'interface) : il vit dans l'index, visible dans toute langue
 * d'interface — les libellés utilisateur sont libres.
 */
@Singleton
public class EtiqueteurHistorique
    @Inject
    constructor(
        private val historique: HistoriqueLocal,
    ) {
        /**
         * Pose une étiquette UTILISATEUR — [nom] nettoyé (trim, vide
         * refusé) sur le [cheminRelatif] donné (`null` : tout le
         * projet).
         *
         * @return l'entrée créée, ou `null` (nom vide / hors projet).
         */
        public suspend fun etiqueter(
            nom: String,
            cheminRelatif: String? = null,
        ): EntreeHistorique? {
            val nomPropre = nom.trim()
            if (nomPropre.isEmpty()) return null
            return historique.etiqueter(nomPropre, cheminRelatif)
        }

        /**
         * Pose une étiquette SYSTÈME « Avant compilation » au niveau du
         * PROJET (mission « Exécuter » : le Run compile, installe et
         * lance — le filet marque l'état d'avant, l'utilisateur peut
         * revenir en arrière d'un coup d'œil).
         */
        public suspend fun avantCompilation(): EntreeHistorique? = historique.etiqueter(ETIQUETTE_AVANT_COMPILATION)

        public companion object {
            /** Libellé de l'étiquette système « Avant compilation » (DONNÉE). */
            public const val ETIQUETTE_AVANT_COMPILATION: String = "Avant compilation"
        }
    }
