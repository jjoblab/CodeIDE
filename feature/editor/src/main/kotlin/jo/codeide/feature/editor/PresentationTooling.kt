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
                ressource(R.string.editor_tooling_taches_en_cours)
            }
        }

    /** Statut de l'onglet Sortie (priorité sync, puis build, puis repli). */
    fun libelleStatut(etat: EtatGradle): TexteTooling =
        when {
            etat.synchronisationEnCours -> {
                ressource(R.string.editor_sortie_sync_en_cours)
            }

            etat.synchronisationReussie != null -> {
                reussieSync(etat)
            }

            etat.messageEchecSync != null -> {
                TexteTooling.Brut(etat.messageEchecSync)
            }

            etat.statutBuild == StatutBuild.EN_COURS -> {
                ressource(R.string.editor_sortie_build_en_cours)
            }

            etat.statutBuild == StatutBuild.REUSSI -> {
                reussiBuild(etat)
            }

            etat.statutBuild == StatutBuild.ECHOUE -> {
                echoueBuild(etat)
            }

            etat.statutBuild == StatutBuild.ANNULE -> {
                ressource(R.string.editor_sortie_build_annule)
            }

            etat.connexion == EtatConnexion.ECHOUEE -> {
                ressource(R.string.editor_outil_deconnecte)
            }

            else -> {
                ressource(R.string.editor_sortie_vide)
            }
        }

    // ---- Cascades de l'en-tête (par canal) ------------------------------

    private fun libelleCanalSyncEntete(etat: EtatGradle): TexteTooling =
        when {
            etat.synchronisationEnCours -> {
                ressource(R.string.editor_tooling_sync_en_cours)
            }

            etat.messageEchecSync != null -> {
                TexteTooling.Brut(etat.messageEchecSync)
            }

            etat.synchronisationReussie != null -> {
                reussieSync(etat)
            }

            else -> {
                ressource(R.string.editor_tooling_sync_en_cours)
            }
        }

    private fun libelleCanalBuildEntete(etat: EtatGradle): TexteTooling =
        when {
            etat.statutBuild == StatutBuild.EN_COURS -> {
                if (etat.taches.isEmpty()) {
                    ressource(R.string.editor_tooling_build_en_cours)
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
                ressource(R.string.editor_sortie_build_annule)
            }

            else -> {
                ressource(R.string.editor_sortie_vide)
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
            ?: ressource(R.string.editor_sortie_build_echoue)

    /** Ressource sans arguments. */
    private fun ressource(id: Int): TexteTooling = TexteTooling.Ressource(id)

    /** Ressource avec arguments de format (chaînes). */
    private fun ressource(
        id: Int,
        vararg args: String,
    ): TexteTooling = TexteTooling.Ressource(id, args.toList())
}
