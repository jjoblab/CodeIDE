package jo.codeide.core.domain

import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Cas d'usage « lire un rapport complet » (section 5.8).
 */
public class GetCrashReportUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Lit un rapport par identifiant.
         *
         * @param id identifiant du rapport.
         * @return le rapport, ou `null` s'il n'existe plus (ou est corrompu).
         */
        public suspend operator fun invoke(id: String): CrashReport? = repository.get(id)
    }

/**
 * Cas d'usage « rapport non consulté le plus récent » (section 5.8).
 *
 * Sert à la boîte de dialogue du démarrage : elle annonce le dernier
 * incident et propose de le voir ou de l'ignorer — les deux valent
 * consultation.
 */
public class GetLatestUnreviewedCrashReportUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Cherche le résumé non consulté le plus récent.
         *
         * La première émission du flot porte l'état courant des rapports
         * (triés du plus récent au plus ancien) : le premier non consulté
         * de cette liste est le plus récent.
         *
         * @return le résumé à présenter, ou `null` si tout est consulté.
         */
        public suspend operator fun invoke(): CrashReportSummary? =
            repository.observeSummaries().first().firstOrNull { !it.isReviewed }
    }

/**
 * Cas d'usage « marquer un rapport consulté » (section 5.8) — appelé après
 * ouverture du rapport **ou** ignorance de la boîte de dialogue.
 */
public class MarkCrashReportReviewedUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Pose l'état consulté.
         *
         * @param id identifiant du rapport.
         * @return `true` si l'état a été posé.
         */
        public suspend operator fun invoke(id: String): Boolean = repository.markReviewed(id)
    }

/**
 * Cas d'usage « supprimer un rapport » (section 5.8).
 */
public class DeleteCrashReportUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Supprime un rapport du disque.
         *
         * @param id identifiant du rapport.
         * @return `true` si un fichier a été supprimé.
         */
        public suspend operator fun invoke(id: String): Boolean = repository.delete(id)
    }

/**
 * Cas d'usage « supprimer tous les rapports » (section 5.8).
 */
public class DeleteAllCrashReportsUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Supprime tous les rapports.
         *
         * @return le nombre de rapports supprimés.
         */
        public suspend operator fun invoke(): Int = repository.deleteAll()
    }

/**
 * Cas d'usage « existe-t-il un rapport non consulté ? » (section 5.8) —
 * variante booléenne du dernier non consulté, pour les contrôles d'état.
 */
public class HasUnreviewedCrashReportsUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Vérifie la présence d'au moins un rapport jamais consulté.
         *
         * @return `true` si la boîte de dialogue du démarrage doit s'afficher.
         */
        public suspend operator fun invoke(): Boolean = repository.hasUnreviewed()
    }

/**
 * Cas d'usage « enregistrer les sorties non traitées » (section 5.8,
 * détection au démarrage) : ANR et plantages natifs de la session
 * précédente, sans doublon.
 */
public class RecordPendingExitInfosUseCase
    @Inject
    constructor(
        private val recorder: PendingExitInfoRecorder,
    ) {
        /**
         * Déclenche l'enregistrement.
         *
         * @return le nombre de rapports créés.
         */
        public suspend operator fun invoke(): Int = recorder.recordPending()
    }
