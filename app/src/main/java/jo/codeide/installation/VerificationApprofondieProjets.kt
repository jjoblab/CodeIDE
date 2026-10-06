package jo.codeide.installation

import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DeleteProjectOnDiskUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.VerificationApprofondie
import jo.codeide.core.domain.templates.CreateProjectUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.CreationProgress.Termine
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vérification approfondie de l'environnement (§ 5.4.5, E4 — ADR 0089) :
 * génère un **vrai projet** depuis le template `android-app` (pipeline
 * de création complet, valeurs par défaut du manifeste du modèle), lance
 * un **vrai `assembleDebug`** via le `gradlew` du projet, puis supprime
 * le projet de contrôle — quoi qu'il arrive.
 *
 * Le `gradlew` est invoqué directement (bit d'exécution posé par le
 * pipeline de création sur le stockage) avec l'environnement des
 * sous-processus du bootstrap (`JAVA_HOME`, `ANDROID_HOME`,
 * `GRADLE_USER_HOME`, override `aapt2` du bloc géré — posé par l'étape
 * `cablage`). Première exécution : le wrapper télécharge la distribution
 * Gradle dans `GRADLE_USER_HOME` (réutilisée ensuite) — délai borné en
 * conséquence.
 *
 * Implémentation applicative du port du domaine : l'orchestrateur du
 * parcours (`core:bootstrap`) la consomme, jamais l'inverse.
 */
@Singleton
internal class VerificationApprofondieProjets
    @Inject
    constructor(
        private val creation: CreateProjectUseCase,
        private val parametres: SettingsRepository,
        private val repertoireProjet: ResoudreRepertoireProjet,
        private val commandes: CommandRunner,
        private val suppression: DeleteProjectOnDiskUseCase,
        private val horloge: TimeProvider,
    ) : VerificationApprofondie {
        // Exemptions detekt ciblées (règle 16) : ReturnCount — chaque sortie
        // porte une DÉCISION distincte (dossier de travail absent, création
        // impossible, dossier introuvable, gradlew absent, succès, échec) ;
        // les factoriser en états intermédiaires masquerait la chaîne de
        // préconditions de la vérification.
        @Suppress("ReturnCount")
        override suspend fun executer(): AppResult<Unit> {
            val nom = "verification-approfondie-${horloge.nowMillis()}"
            val creation = creerProjetDeControle(nom)
            val cree =
                creation.getOrNull()
                    ?: return AppResult.Failure(erreurDeCreation(creation as AppResult.Failure))
            try {
                return assembler(cree.location.documentUri, nom)
            } finally {
                // Nettoyage de contrôle : le projet éphémère disparaît,
                // succès comme échec (jamais de résidu dans le dossier de travail).
                suppression(ProjectId(cree.id.value))
            }
        }

        /** Erreur typée de l'échec de création : une `EnvironmentSetup`
         * passe telle quelle, toute autre est enveloppée. */
        private fun erreurDeCreation(echec: AppResult.Failure): AppError.EnvironmentSetup =
            echec.error as? AppError.EnvironmentSetup
                ?: AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Commande,
                    details = "la génération du projet de contrôle a échoué : ${echec.error}",
                )

        /** Crée le projet de contrôle (template `android-app`, défauts du manifeste). */
        private suspend fun creerProjetDeControle(nom: String): AppResult<Project> {
            val emplacement =
                parametres.observeSettings().first().workspace
                    ?: return AppResult.Failure(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Permissions,
                            details =
                                "aucun dossier de travail configuré — choisissez-en un " +
                                    "dans l'écran Projets avant la vérification approfondie",
                        ),
                    )
            val terminal =
                creation
                    .create(
                        CreateProjectRequest(
                            templateId = TemplateId("android-app"),
                            name = nom,
                            description =
                                "Projet de contrôle généré par la vérification approfondie de l'environnement",
                            parentLocation = emplacement,
                            parameterValues = emptyMap(),
                            manuallySetParameters = emptySet(),
                            cheminsRenommes = emptyMap(),
                            options =
                                TemplateOptions(
                                    includeReadme = false,
                                    includeGitignore = false,
                                    includeEditorconfig = false,
                                ),
                        ),
                    ).first { it is Termine } as Termine
            return terminal.result
        }

        /** Lance `gradlew assembleDebug` dans le dossier réel du projet de contrôle. */
        @Suppress("ReturnCount")
        private suspend fun assembler(
            documentUri: String,
            nom: String,
        ): AppResult<Unit> {
            val dossier =
                repertoireProjet(documentUri)
                    ?: return AppResult.Failure(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Permissions,
                            details = "le dossier du projet de contrôle « $nom » est introuvable sur le stockage",
                        ),
                    )
            val gradlew = File(dossier, "gradlew")
            if (!gradlew.isFile) {
                return AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.ManifesteInvalide,
                        details =
                            "le template android-app ne fournit pas de gradlew — " +
                                "vérification approfondie impossible",
                    ),
                )
            }
            val resultat =
                commandes.run(
                    CommandSpec(
                        program = gradlew.absolutePath,
                        arguments = listOf("assembleDebug", "--no-daemon", "--console=plain", "--stacktrace"),
                        workingDir = File(dossier),
                        timeoutMillis = DELAI_ASSEMBLAGE,
                    ),
                )
            return if (resultat.succeeded) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Commande,
                        details =
                            "assembleDebug du projet de contrôle échoue" +
                                if (resultat.timedOut) {
                                    " : délai maximal dépassé (processus détruit)"
                                } else {
                                    " (code ${resultat.exitCode})"
                                },
                        sortie =
                            CommandOutput(
                                commande = "gradlew assembleDebug",
                                exitCode = resultat.exitCode,
                                lastLines = resultat.tail(BORNE_SORTIE),
                            ),
                    ),
                )
            }
        }

        private companion object {
            /**
             * Première exécution : téléchargement de la distribution
             * Gradle + dépendances sur réseau mobile — 30 minutes.
             */
            private const val DELAI_ASSEMBLAGE: Long = 30 * 60_000L

            /** Bornage des sorties attachées aux échecs (§ 3.4 du cahier). */
            private const val BORNE_SORTIE: Int = 200
        }
    }
