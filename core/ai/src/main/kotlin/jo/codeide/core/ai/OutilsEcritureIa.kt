package jo.codeide.core.ai

import java.io.File

/**
 * Outils V1 d'ÉCRITURE MISE EN ATTENTE (mission IA, lot IA-7, ADR 0125)
 * : `propose_edit` / `create_file` / `delete_file` n'écrivent RIEN — ils
 * ajoutent un [ChangementIa] au [ChangeSetIa] fourni. Les chemins sont
 * résolus par [ResolveurCheminsProjetIa] (confinement) et l'empreinte du
 * contenu ACTUEL est capturée ICI (conflit = divergence détectable à
 * l'application).
 */
public class OutilsEcritureIa(
    private val resolveur: ResolveurCheminsProjetIa,
    private val changeSet: ChangeSetIaCourant,
) {
    /** `propose_edit(chemin, contenu, raison)` : remplacement proposé. */
    @Suppress("ReturnCount") // refus de garde + succès : sorties distinctes (résultats modélisés, ADR 0125).
    public suspend fun proposerModification(
        chemin: String,
        contenu: String,
        raison: String,
    ): ResultatOutilIa {
        val resolu = resolveur.resoudre(chemin)
        if (resolu is ResolveurCheminsProjetIa.ResultatFichierIa.Refuse) {
            return ResultatOutilIa.Refuse(resolu.motif)
        }
        val ok = resolu as ResolveurCheminsProjetIa.ResultatFichierIa.OK
        if (!ok.fichier.exists()) {
            return ResultatOutilIa.Refuse("fichier inexistant (crée-le d'abord) : ${ok.cheminRelatif}")
        }
        changeSet.depot = changeSet.depot +
            ChangementIa(
                cheminRelatif = ok.cheminRelatif,
                type = TypeChangementIa.MODIFIER,
                contenu = contenu,
                empreinteAttendue = empreinte(ok.fichier),
                raison = raison,
            )
        return ResultatOutilIa.Succes("modification proposée : ${ok.cheminRelatif} (en attente d'examen)")
    }

    /** `create_file(chemin, contenu, raison)` : création proposée. */
    @Suppress("ReturnCount") // refus de garde + succès : sorties distinctes (résultats modélisés, ADR 0125).
    public suspend fun proposerCreation(
        chemin: String,
        contenu: String,
        raison: String,
    ): ResultatOutilIa {
        val resolu = resolveur.resoudre(chemin)
        if (resolu is ResolveurCheminsProjetIa.ResultatFichierIa.Refuse) {
            return ResultatOutilIa.Refuse(resolu.motif)
        }
        val ok = resolu as ResolveurCheminsProjetIa.ResultatFichierIa.OK
        if (ok.fichier.exists()) {
            return ResultatOutilIa.Refuse("fichier déjà existant (utilise propose_edit) : ${ok.cheminRelatif}")
        }
        changeSet.depot =
            changeSet.depot +
            ChangementIa(
                cheminRelatif = ok.cheminRelatif,
                type = TypeChangementIa.CREER,
                contenu = contenu,
                empreinteAttendue = null,
                raison = raison,
            )
        return ResultatOutilIa.Succes("création proposée : ${ok.cheminRelatif} (en attente d'examen)")
    }

    /** `delete_file(chemin, raison)` : suppression proposée (renforcée). */
    @Suppress("ReturnCount") // refus de garde + succès : sorties distinctes (résultats modélisés, ADR 0125).
    public suspend fun proposerSuppression(
        chemin: String,
        raison: String,
    ): ResultatOutilIa {
        val resolu = resolveur.resoudre(chemin)
        if (resolu is ResolveurCheminsProjetIa.ResultatFichierIa.Refuse) {
            return ResultatOutilIa.Refuse(resolu.motif)
        }
        val ok = resolu as ResolveurCheminsProjetIa.ResultatFichierIa.OK
        if (!ok.fichier.exists()) {
            return ResultatOutilIa.Refuse("fichier introuvable : ${ok.cheminRelatif}")
        }
        changeSet.depot =
            changeSet.depot +
            ChangementIa(
                cheminRelatif = ok.cheminRelatif,
                type = TypeChangementIa.SUPPRIMER,
                contenu = "",
                empreinteAttendue = empreinte(ok.fichier),
                raison = raison,
            )
        return ResultatOutilIa.Succes("suppression proposée : ${ok.cheminRelatif} (examen renforcé)")
    }

    /** Empreinte SHA-256 du contenu actuel (conflit si divergence). */
    private fun empreinte(fichier: File): String? =
        if (!fichier.isFile) {
            null
        } else {
            val digeste = java.security.MessageDigest.getInstance("SHA-256")
            digeste
                .digest(fichier.readBytes())
                .joinToString("") { octet -> "%02x".format(octet) }
        }
}

/**
 * `run_gradle(tache, raison)` (ADR 0125, EXÉCUTION) : liste blanche
 * fermée + approbation explicite À CHAQUE appel ([PorteApprobationIa]) —
 * jamais de « toujours autoriser ». L'exécution passe par l'exécutant
 * injecté (port tooling de l'hôte) ; `null` = indisponible hors éditeur.
 */
public class OutilRunGradleIa(
    private val porte: PorteApprobationIa,
    private val executant: (suspend (tache: String) -> String)?,
) : OutilIa {
    override val identifiant: String = "run_gradle"

    override val description: String =
        "Exécute une tâche Gradle de la liste blanche (approbation requise)."

    override val niveau: NiveauRisqueIa = NiveauRisqueIa.EXECUTION
    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"tache":{"type":"string"},"raison":{"type":"string"}},
         "required":["tache","raison"]}
        """.trimIndent()

    @Suppress("ReturnCount") // clauses de garde (liste blanche, raison, refus) + succès : retours dédiés.
    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa {
        val tache = arguments["tache"].orEmpty().trim()
        val raison = arguments["raison"].orEmpty().trim()
        if (tache !in TACHES_AUTORISEES) {
            return ResultatOutilIa.Refuse(
                "tâche hors liste blanche : '$tache' (autorisées : ${TACHES_AUTORISEES.joinToString()})",
            )
        }
        if (raison.isEmpty()) {
            return ResultatOutilIa.Refuse("raison obligatoire pour une exécution (ADR 0125)")
        }
        val autorise = porte.approuver(identifiant, arguments, raison)
        if (!autorise) {
            return ResultatOutilIa.Refuse("exécution refusée par l'utilisateur")
        }
        val execute = executant ?: return ResultatOutilIa.Refuse("outil indisponible hors éditeur")
        return ResultatOutilIa.Succes(execute(tache).take(SORTIE_MAX))
    }

    private companion object {
        /** Liste blanche fermée (ADR 0125, table des outils). */
        private val TACHES_AUTORISEES =
            setOf("assembleDebug", "lintDebug", "testDebugUnitTest", "spotlessCheck", "detekt")

        /** Plafond de la sortie renvoyée au modèle. */
        private const val SORTIE_MAX = 4_000
    }
}
