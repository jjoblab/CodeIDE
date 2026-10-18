package jo.codeide.core.ai

/**
 * Outil `list_files` (ADR 0125, V1) — adaptateur de l'écran Agent
 * (IA-7b) : le port [FournisseurContexteProjet] porte l'accès réel
 * (exclusions ADR 0124 + garde d'évasion), l'outil le rend appelable
 * par le modèle. LECTURE : aucune approbation.
 */
public class OutilListeFichiersIa(
    private val port: FournisseurContexteProjet,
) : OutilIa {
    override val identifiant: String = "list_files"
    override val description: String = "Liste les chemins du projet (ou d'un sous-dossier)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.LECTURE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"dossier":{"type":"string"}}}
        """.trimIndent()

    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa =
        when (val resultat = port.listerChemins()) {
            is jo.codeide.core.model.AppResult.Success -> {
                ResultatOutilIa.Succes(resultat.value.joinToString("\n").ifEmpty { "(aucun fichier)" })
            }

            is jo.codeide.core.model.AppResult.Failure -> {
                ResultatOutilIa.Refuse("listing indisponible")
            }
        }
}

/** Outil `read_file` (ADR 0125, V1) — adaptateur de l'écran Agent (IA-7b). */
public class OutilLireFichierIa(
    private val port: FournisseurContexteProjet,
) : OutilIa {
    override val identifiant: String = "read_file"
    override val description: String = "Lit un fichier du projet (contenu borné)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.LECTURE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"}},
         "required":["chemin"]}
        """.trimIndent()

    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa =
        when (val resultat = port.lireFichier(arguments["chemin"].orEmpty())) {
            is jo.codeide.core.model.AppResult.Success -> {
                ResultatOutilIa.Succes(resultat.value)
            }

            is jo.codeide.core.model.AppResult.Failure -> {
                ResultatOutilIa.Refuse("fichier introuvable, exclu ou illisible : ${arguments["chemin"].orEmpty()}")
            }
        }
}

/** Outil `propose_edit` (ADR 0125, V1, ÉCRITURE ATTENDUE) — l'écriture reste dans le ChangeSet. */
public class OutilProposerModificationIa(
    private val outils: OutilsEcritureIa,
) : OutilIa {
    override val identifiant: String = "propose_edit"
    override val description: String = "Propose le remplacement du contenu d'un fichier (sans écrire)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.ECRITURE_ATTENTE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"},"contenu":{"type":"string"},"raison":{"type":"string"}},
         "required":["chemin","contenu","raison"]}
        """.trimIndent()

    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa =
        outils.proposerModification(
            arguments["chemin"].orEmpty(),
            arguments["contenu"].orEmpty(),
            arguments["raison"].orEmpty(),
        )
}

/** Outil `create_file` (ADR 0125, V1, ÉCRITURE ATTENDUE) — l'écriture reste dans le ChangeSet. */
public class OutilProposerCreationIa(
    private val outils: OutilsEcritureIa,
) : OutilIa {
    override val identifiant: String = "create_file"
    override val description: String = "Propose la création d'un nouveau fichier (sans écrire)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.ECRITURE_ATTENTE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"},"contenu":{"type":"string"},"raison":{"type":"string"}},
         "required":["chemin","contenu","raison"]}
        """.trimIndent()

    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa =
        outils.proposerCreation(
            arguments["chemin"].orEmpty(),
            arguments["contenu"].orEmpty(),
            arguments["raison"].orEmpty(),
        )
}

/** Outil `delete_file` (ADR 0125, V1, ÉCRITURE ATTENDUE) — la suppression reste dans le ChangeSet. */
public class OutilProposerSuppressionIa(
    private val outils: OutilsEcritureIa,
) : OutilIa {
    override val identifiant: String = "delete_file"
    override val description: String = "Propose la suppression d'un fichier (sans écrire, examen renforcé)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.ECRITURE_ATTENTE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"},"raison":{"type":"string"}},
         "required":["chemin","raison"]}
        """.trimIndent()

    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa =
        outils.proposerSuppression(
            arguments["chemin"].orEmpty(),
            arguments["raison"].orEmpty(),
        )
}
