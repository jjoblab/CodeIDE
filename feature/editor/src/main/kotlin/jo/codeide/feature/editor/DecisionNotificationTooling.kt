package jo.codeide.feature.editor

import jo.codeide.core.domain.StatutBuild

/**
 * Décision de notification du service tooling (étape 32, ADR 0057) :
 * traduction PURE de [EtatGradle] en ce que le service Android doit
 * montrer — même précédent que `DecisionServiceTerminal` (prompt
 * Terminal-1 §4.2 : le service reste mince, la décision se teste sans
 * cadre Android).
 *
 * Priorité : l'activité en vol d'abord (notification en cours), le
 * dernier résultat ensuite (notification finale), rien sinon — le service
 * s'arrête de lui-même sur [Rien].
 */
internal sealed interface DecisionNotificationTooling {
    /** Rien à montrer : le service n'a aucune raison de vivre. */
    object Rien : DecisionNotificationTooling

    /**
     * Activité en vol : notification EN COURS (honnête — elle disparaît
     * avec l'activité).
     *
     * @property canal canal de l'activité (Sync, Build ou Taches).
     * @property taches tâches du build (libellé), vide sinon.
     */
    data class EnCours(
        val canal: CanalTooling,
        val taches: List<String> = emptyList(),
    ) : DecisionNotificationTooling

    /**
     * Dernier résultat : notification FINALE (non persistante, reste
     * dans le tiroir jusqu'à effacement), puis le service s'arrête.
     *
     * @property canal canal du résultat (Sync ou Build).
     * @property reussi l'activité a abouti.
     * @property annule l'activité a été arrêtée par l'utilisateur.
     * @property dureeMs durée effective du résultat.
     * @property messageEchec message d'échec (Sync ou Build), si échoué.
     */
    data class Resultat(
        val canal: CanalTooling,
        val reussi: Boolean,
        val annule: Boolean = false,
        val dureeMs: Long = 0,
        val messageEchec: String? = null,
    ) : DecisionNotificationTooling
}

/**
 * Décide de la notification à montrer pour un état tooling (pure,
 * étape 32) : activité en vol → [DecisionNotificationTooling.EnCours] ;
 * dernier résultat → [DecisionNotificationTooling.Resultat] ; rien
 * (état initial, ou listage des tâches conclu — son résultat est le
 * sélecteur, pas une notification) → [DecisionNotificationTooling.Rien].
 */
internal fun decisionNotificationTooling(etat: EtatGradle): DecisionNotificationTooling =
    etat.canalActif?.let { canal -> decisionEnCours(canal, etat) } ?: decisionResultat(etat)

/** Activité en vol : notification en cours, tâches du build en libellé. */
private fun decisionEnCours(
    canal: CanalTooling,
    etat: EtatGradle,
): DecisionNotificationTooling =
    if (canal == CanalTooling.BUILD) {
        DecisionNotificationTooling.EnCours(canal = canal, taches = etat.taches)
    } else {
        DecisionNotificationTooling.EnCours(canal = canal)
    }

/**
 * Aucune activité en vol : le dernier résultat, ou rien — le canal
 * Taches ne produit pas de notification de résultat (le sélecteur EST
 * le résultat).
 */
private fun decisionResultat(etat: EtatGradle): DecisionNotificationTooling =
    when (etat.canalDernierResultat) {
        CanalTooling.SYNC -> resultatSync(etat)
        CanalTooling.BUILD -> resultatBuild(etat)
        null, CanalTooling.TACHES -> DecisionNotificationTooling.Rien
    }

/** Résultat de sync : l'échec prime (un échec récent ne montre JAMAIS
 *  le succès périmé qui le précède), puis le résultat complet. */
private fun resultatSync(etat: EtatGradle): DecisionNotificationTooling =
    when {
        etat.messageEchecSync != null -> {
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.SYNC,
                reussi = false,
                messageEchec = etat.messageEchecSync,
            )
        }

        etat.synchronisationReussie != null -> {
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.SYNC,
                reussi = etat.synchronisationReussie!!.reussie,
                dureeMs = etat.synchronisationReussie!!.dureeMs,
                messageEchec = etat.synchronisationReussie!!.messageEchec,
            )
        }

        else -> {
            DecisionNotificationTooling.Rien
        }
    }

/** Résultat de build selon son statut terminal. */
private fun resultatBuild(etat: EtatGradle): DecisionNotificationTooling =
    when (etat.statutBuild) {
        StatutBuild.REUSSI -> {
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.BUILD,
                reussi = true,
                dureeMs = etat.dureeBuildMs ?: 0,
            )
        }

        StatutBuild.ECHOUE -> {
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.BUILD,
                reussi = false,
                dureeMs = etat.dureeBuildMs ?: 0,
                messageEchec = etat.messageEchecBuild,
            )
        }

        StatutBuild.ANNULE -> {
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.BUILD,
                reussi = false,
                annule = true,
                dureeMs = etat.dureeBuildMs ?: 0,
            )
        }

        // EN_COURS est couvert par canalActif ; un build sans statut
        // n'a jamais démarré.
        else -> {
            DecisionNotificationTooling.Rien
        }
    }
