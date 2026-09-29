package jo.codeide.feature.editor

import android.content.Context
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.StatutBuild

/**
 * Libellé localisable produit par [PresentationTooling] : un identifiant de
 * ressource (avec ses arguments de format) ou un texte brut (message
 * d'échec du serveur). Le présentateur reste PUR — il ne touche ni `Context`
 * ni vue ; la résolution a lieu au dernier moment par l'appelant.
 */
internal sealed interface TexteTooling {
    /** Libellé d'une ressource de chaîne (identifiant + arguments). */
    data class Ressource(
        val id: Int,
        val args: List<String> = emptyList(),
    ) : TexteTooling

    /** Libellé brut (message technique déjà formulé). */
    data class Brut(
        val texte: String,
    ) : TexteTooling
}

/**
 * Résout un [TexteTooling] en chaîne localisée.
 *
 * Exemption detekt ciblée (règle 16, même précédent que
 * `BuildHandler.forTasks`) : `Context.getString` n'expose ses arguments de
 * format qu'en vararg (aucune surcharge `Iterable<String>`) — l'éclatement
 * de la liste est l'unique option.
 */
@Suppress("SpreadOperator")
internal fun TexteTooling.resoudre(contexte: Context): String =
    when (this) {
        is TexteTooling.Ressource -> {
            if (args.isEmpty()) {
                contexte.getString(id)
            } else {
                contexte.getString(id, *args.toTypedArray())
            }
        }

        is TexteTooling.Brut -> {
            texte
        }
    }

/**
 * État de rendu de l'EN-TÊTE du panneau tooling (v4, §3.2) — sorti du
 * présentateur pur : un seul code décide du titre, du sous-titre (étape
 * courante + détail), de la progression déterminée (0..1) ou indéterminée,
 * de la couleur de canal, du chrono (en vol ou figé) et du bouton Arrêt.
 * L'activité n'est plus que de la colle.
 *
 * @property titre titre principal (l'activité courante ou le résultat).
 * @property sousTitre détail de l'étape courante (v4) — texte libre déjà
 *           formulé, `null` si sans objet.
 * @property progression 0..1 quand une progression DÉTERMINÉE existe
 *           (octets reçus/total), `null` pour l'indéterminé.
 * @property couleur couleur de canal (ressource core:ui).
 * @property chronoMs départ du chrono en vol (ms horloge) — `null` sinon.
 * @property dureeFigeeMs durée du dernier résultat quand le chrono est figé.
 * @property arret le bouton Arrêter se montre-t-il (build en vol) ?
 */
internal data class EtatEnteteTooling(
    val titre: TexteTooling,
    val sousTitre: String? = null,
    val progression: Float? = null,
    val couleur: Int,
    val chronoMs: Long? = null,
    val dureeFigeeMs: Long? = null,
    val arret: Boolean = false,
)

/**
 * Détails de progression d'une étape de sync (v4, §3.2 — alimentation du
 * SOUS-TITRE de l'en-tête et de la progression déterminée) : un objet à
 * part, pur, la même discipline de test que le présentateur.
 */
internal object DetailsEtapesSync {
    /** Sous-titre : détail de l'étape courante (« 42 Mo · 3 · artefact »). */
    fun sousTitre(etat: EtatGradle): String? =
        etat.etapeCourante
            ?.takeIf { etat.synchronisationEnCours && !it.terminee }
            ?.let(::detail)

    /** Progression déterminée : octets reçus / total quand les deux sont connus. */
    fun progression(etat: EtatGradle): Float? =
        etat.etapeCourante
            ?.takeIf { etat.synchronisationEnCours }
            ?.takeIf { courante -> courante.octetsRecus > 0 && (courante.octetsTotal ?: 0L) > 0 }
            ?.let { courante -> courante.octetsRecus.toFloat() / courante.octetsTotal!! }
            ?.coerceIn(0f, 1f)

    /** Détail lisible d'une étape : octets reçus (Mo), compteur, élément. */
    private fun detail(etape: EtapeSyncAffichee): String? {
        val parties = mutableListOf<String>()
        if (etape.octetsRecus > 0) {
            parties += "${etape.octetsRecus / OCTETS_PAR_MO} Mo"
        }
        if (etape.compteur != null && etape.compteur > 0) {
            parties += "${etape.compteur} élément(s)"
        }
        etape.element?.take(TAILLE_ELEMENT_MAX)?.let { element -> parties += element }
        return if (parties.isEmpty()) null else parties.joinToString(" · ")
    }

    private const val OCTETS_PAR_MO = 1_024L * 1_024L

    private const val TAILLE_ELEMENT_MAX = 28
}

/**
 * Présentateur UNIQUE du tooling (correctif n°11 du prompt « tooling Gradle
 * professionnel ») : deux cascades dupliquées traduisaient [EtatGradle] en
 * libellés — [EditorActivity.libelleActiviteTooling] pour l'en-tête du
 * panneau et [PanneauConsoleFragment.libelleStatutTooling] pour le statut
 * de l'onglet Sortie. Elles vivent désormais ici, pures et testées
 * (FR/EN, échec, annulé, sync, build, listage).
 *
 * Les deux cascades gardent leurs différences VOLONTAIRES (aucun
 * changement visuel au correctif n°11) :
 *
 * - l'en-tête suit SON canal ([CanalTooling]) : échec de sync avant
 *   réussite (une sync partielle reste un échec affiché), noms des tâches
 *   du build, canal Taches en vol ;
 * - le statut de la console est une priorité sync-puis-build avec repli
 *   réussite-avant-échec (une sync partielle reste « synchronisée »),
 *   sans noms de tâches, avec repli « orchestrateur déconnecté ».
 *
 * Les durées passent par [DureesLisibles] (correctif n°11 : la copie
 * privée de `EditorActivity` est supprimée — un seul formateur).
 */

internal object PresentationTooling {
    /**
     * État d'en-tête complet (v4, §3.2) : le présentateur décide, l'activité
     * rend — titre par canal, sous-titre = étape courante avec son détail
     * (octets, compteur), progression déterminée quand les octets totaux
     * sont connus, chrono en vol ou durée figée du dernier résultat.
     */
    fun etatEntete(etat: EtatGradle): EtatEnteteTooling {
        val canal = etat.canalActif ?: etat.canalDernierResultat
        val titre = canal?.let { libelleActivite(etat, it) } ?: TexteTooling.Ressource(R.string.editor_sortie_vide)
        return EtatEnteteTooling(
            titre = titre,
            sousTitre = DetailsEtapesSync.sousTitre(etat),
            progression = DetailsEtapesSync.progression(etat),
            couleur = canal?.couleur ?: jo.codeide.core.ui.R.color.codeide_canal_sync,
            chronoMs =
                when (etat.canalActif) {
                    CanalTooling.SYNC -> etat.debutSyncMs
                    CanalTooling.BUILD -> etat.debutBuildMs
                    CanalTooling.TACHES -> etat.debutTachesMs
                    null -> null
                },
            dureeFigeeMs = dureeFigee(etat, canal),
            arret = etat.canalActif == CanalTooling.BUILD,
        )
    }

    /** Durée figée du dernier résultat (chrono arrêté). */
    private fun dureeFigee(
        etat: EtatGradle,
        canal: CanalTooling?,
    ): Long? =
        when (canal) {
            CanalTooling.SYNC -> etat.synchronisationReussie?.dureeMs
            CanalTooling.BUILD -> etat.dureeBuildMs
            else -> null
        }

    /** Activité courante de l'en-tête, sur SON canal. */
    fun libelleActivite(
        etat: EtatGradle,
        canal: CanalTooling,
    ): TexteTooling =
        when (canal) {
            CanalTooling.SYNC -> {
                libelleCanalSyncEntete(etat)
            }

            CanalTooling.BUILD -> {
                libelleCanalBuildEntete(etat)
            }

            CanalTooling.TACHES -> {
                // Indicateur de vol : le sélecteur est le résultat du canal.
                TexteTooling.Ressource(R.string.editor_tooling_taches_en_cours)
            }
        }

    /** Statut de l'onglet Sortie (priorité sync, puis build, puis repli). */
    fun libelleStatut(etat: EtatGradle): TexteTooling =
        when {
            etat.synchronisationEnCours -> {
                TexteTooling.Ressource(R.string.editor_sortie_sync_en_cours)
            }

            etat.synchronisationReussie != null -> {
                reussieSync(etat)
            }

            etat.messageEchecSync != null -> {
                TexteTooling.Brut(etat.messageEchecSync)
            }

            etat.statutBuild == StatutBuild.EN_COURS -> {
                TexteTooling.Ressource(R.string.editor_sortie_build_en_cours)
            }

            etat.statutBuild == StatutBuild.REUSSI -> {
                reussiBuild(etat)
            }

            etat.statutBuild == StatutBuild.ECHOUE -> {
                echoueBuild(etat)
            }

            etat.statutBuild == StatutBuild.ANNULE -> {
                TexteTooling.Ressource(R.string.editor_sortie_build_annule)
            }

            etat.connexion == EtatConnexion.ECHOUEE -> {
                TexteTooling.Ressource(R.string.editor_outil_deconnecte)
            }

            else -> {
                TexteTooling.Ressource(R.string.editor_sortie_vide)
            }
        }

    // ---- Cascades de l'en-tête (par canal) ------------------------------

    private fun libelleCanalSyncEntete(etat: EtatGradle): TexteTooling =
        when {
            etat.synchronisationEnCours -> {
                TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours)
            }

            etat.messageEchecSync != null -> {
                TexteTooling.Brut(etat.messageEchecSync)
            }

            etat.synchronisationReussie != null -> {
                reussieSync(etat)
            }

            else -> {
                TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours)
            }
        }

    private fun libelleCanalBuildEntete(etat: EtatGradle): TexteTooling =
        when {
            etat.statutBuild == StatutBuild.EN_COURS -> {
                if (etat.taches.isEmpty()) {
                    TexteTooling.Ressource(R.string.editor_tooling_build_en_cours)
                } else {
                    ressource(
                        R.string.editor_tooling_build_taches,
                        etat.taches.joinToString(", "),
                    )
                }
            }

            etat.statutBuild == StatutBuild.REUSSI -> {
                reussiBuild(etat)
            }

            etat.statutBuild == StatutBuild.ECHOUE -> {
                echoueBuild(etat)
            }

            etat.statutBuild == StatutBuild.ANNULE -> {
                TexteTooling.Ressource(R.string.editor_sortie_build_annule)
            }

            else -> {
                TexteTooling.Ressource(R.string.editor_sortie_vide)
            }
        }

    // ---- Libellés partagés par les deux cascades ------------------------

    /** « Synchronisé en X » (durée formatée par [DureesLisibles]). */
    private fun reussieSync(etat: EtatGradle): TexteTooling =
        ressource(
            R.string.editor_sortie_sync_reussie,
            DureesLisibles.formater(etat.synchronisationReussie?.dureeMs ?: 0L),
        )

    /** « Build réussi en X ». */
    private fun reussiBuild(etat: EtatGradle): TexteTooling =
        ressource(
            R.string.editor_sortie_build_reussi,
            DureesLisibles.formater(etat.dureeBuildMs ?: 0L),
        )

    /** Échec de build : le message du serveur, sinon le libellé générique. */
    private fun echoueBuild(etat: EtatGradle): TexteTooling =
        etat.messageEchecBuild?.let { TexteTooling.Brut(it) }
            ?: TexteTooling.Ressource(R.string.editor_sortie_build_echoue)

    /** Ressource avec arguments de format (chaînes). */
    private fun ressource(
        id: Int,
        vararg args: String,
    ): TexteTooling = TexteTooling.Ressource(id, args.toList())
}
