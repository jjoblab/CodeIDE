package jo.codeide.feature.editor

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.model.AppResult
import jo.codeide.core.session.ContexteFermeture
import jo.codeide.core.session.Fermable
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adaptateurs `Fermable` des ressources de l'espace (mission « Cycle de
 * vie » étape C, ADR 0114 l.79-81) : chaque ressource qui démarre
 * s'enregistre au registre de la session de projet — refermée en ordre
 * inverse, bornée par délai, tolérante aux pannes.
 *
 * Les délais vident ici (l'intégrateur sait, le contrat ignore —
 * ADR 0114 §2) : ils bornent des libérations réputées RAPIDES en
 * pratique ; le dépassement est journalisé WARN et ABANDONNÉ, jamais
 * bloquant.
 */
internal object FermablesEspace {
    /** Borne de fermeture de la session LSP Java (annulation, moteurs, modèle). */
    internal val DELAI_FERMETURE_LSP: Duration = 5.seconds

    /**
     * Borne du flush des sauvegardes (I7) : l'élément est NON annulable —
     * l'écriture part même si l'appelant annule ; généreuse car SAF peut
     * être lent, elle borne un DÉRAISONNEMENT, pas une écriture normale.
     */
    internal val DELAI_FLUSH_SAUVEGARDES: Duration = 10.seconds

    /**
     * Borne de la fermeture du projet côté TOOLING (étape D, v9) : la
     * fermeture d'une connexion Tooling API peut prendre quelques
     * secondes — l'accusé du protocole est attendu dedans.
     */
    internal val DELAI_FERMETURE_TOOLING: Duration = 10.seconds

    /**
     * Session LSP Java → `Fermable` : la fermeture détache d'abord la
     * référence du ViewModel (les observateurs voient `null` avant la
     * mort, pas d'usage post-close de `verifierOuverte()`), puis referme
     * — `SessionLsp.close()` est idempotent et attrape ses propres pannes
     * pas à pas (contrat h1).
     *
     * PAS de saut `Dispatchers.IO` ici : le registre sérialise et BORNE
     * déjà chaque élément (l'appelant de production est une portée de
     * fermeture dédiée — un close lent y consomme son délai, pas un fil
     * de travail) ; et un Fermable doit rester déterministe sous le
     * planificateur de TEST de la fermeture (un saut réel IO échappe au
     * temps virtuel et laisse la séquence en plan au milieu).
     *
     * @param session la session vivante (jamais refermée par ailleurs).
     * @param detacher remet la référence observable du ViewModel à `null`.
     */
    internal fun sessionLsp(
        session: SessionLsp,
        detacher: () -> Unit,
    ): Fermable =
        object : Fermable {
            override val nom: String = "session-lsp-java"

            override suspend fun fermer(contexte: ContexteFermeture) {
                detacher()
                session.close()
            }
        }

    /**
     * Session LSP Kotlin → `Fermable` (mission « LSP Kotlin » K2) : même
     * contrat que [sessionLsp] — détache la référence observable d'abord
     * (jamais d'usage post-close), referme ensuite (idempotent, les
     * pannes pas à pas sont attrapées dans close).
     *
     * @param session la session Kotlin vivante.
     * @param detacher remet la référence observable du ViewModel à `null`.
     */
    internal fun sessionLspKotlin(
        session: SessionLspKotlin,
        detacher: () -> Unit,
    ): Fermable =
        object : Fermable {
            override val nom: String = "session-lsp-kotlin"

            override suspend fun fermer(contexte: ContexteFermeture) {
                detacher()
                session.close()
            }
        }

    /**
     * Flush des sauvegardes d'onglets → `Fermable` NON annulable (I7) :
     * [flusher] écrit chaque onglet sale depuis SA session d'édition —
     * l'intégrateur garantit que les sessions vivent encore à ce moment
     * (libérées APRÈS le flush, jamais avant).
     *
     * @param flusher écriture de tous les onglets sales (idempotente :
     * plus rien à écrire au second appel, les onglets sont propres).
     */
    internal fun sauvegardesOnglets(flusher: suspend () -> Unit): Fermable =
        object : Fermable {
            override val nom: String = "sauvegardes-onglets"

            override suspend fun fermer(contexte: ContexteFermeture) = flusher()
        }

    /**
     * Libération du projet côté ORCHESTRATEUR → `Fermable` (mission «
     * Cycle de vie » étape D, v9, E9) : demande à l'orchestrateur de
     * fermer la connexion Tooling API du projet et de purger son cache
     * de sync — le démon applicatif continue de vivre (politique
     * reportée aux mesures S6).
     *
     * Politique de renoncement (§2.1c) : un build ou une sync EN VOL au
     * moment de la fermeture CONTINUE — sa connexion vit avec lui (la
     * libération est renoncée, journalisée ; la prochaine ouverture ou
     * la mort du process refermera). Un chemin de projet INCONNU au
     * moment de la fermeture (projet non résolu) : rien à libérer —
     * l'orchestrateur n'a jamais ouvert ce projet.
     *
     * @param tooling port du dépôt tooling (voie de l'accusé protocole v9).
     * @param cheminDuProjet fournisseur du chemin FUSE du projet (lu à la
     *        FERMETURE — résolu après l'ouverture dans le flux réel).
     * @param travailEnVol un build OU une sync vit-il encore (états du
     *        GradleService process-wide — I1 : un seul projet à la fois).
     * @param journal la voie unique de traçabilité (règle 14).
     */
    internal fun fermetureProjetTooling(
        tooling: GradleToolingRepository,
        cheminDuProjet: () -> String?,
        travailEnVol: () -> Boolean,
        journal: AppLogger,
    ): Fermable =
        object : Fermable {
            override val nom: String = "tooling-projet"

            override suspend fun fermer(contexte: ContexteFermeture) {
                val chemin = cheminDuProjet()
                if (chemin == null) {
                    journal.i("FermablesEspace") { "fermeture tooling renoncée : chemin de projet inconnu" }
                    return
                }
                if (travailEnVol()) {
                    journal.w("FermablesEspace") {
                        "fermeture tooling renoncée : un travail Gradle vit encore " +
                            "(§2.1c — il continue avec sa connexion)"
                    }
                    return
                }
                when (val resultat = tooling.fermerProjet(File(chemin))) {
                    is AppResult.Success -> {
                        journal.i("FermablesEspace") {
                            "projet fermé côté orchestrateur (connexion libérée, cache purgé)"
                        }
                    }

                    is AppResult.Failure -> {
                        journal.w("FermablesEspace") { "fermeture tooling en échec : ${resultat.error}" }
                    }
                }
            }
        }
}
