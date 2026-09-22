package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Cas d'usage « résoudre l'emplacement de création » (étape 10 — section 12.3).
 *
 * L'étape « Informations et emplacement » du wizard propose par défaut le
 * dossier de travail des Paramètres ; l'utilisateur peut **changer de dossier
 * pour cette création uniquement** (bouton « Changer de dossier », sélecteur
 * SAF). Ce choix suit exactement la politique d'héritage de l'import de
 * dossier existant (ADR 0015) :
 *
 * - un dossier **dans l'arbre** du dossier de travail hérite de sa
 *   permission persistante — rien à prendre, rien à libérer ;
 * - un dossier **ailleurs** prend sa propre permission persistante après les
 *   contrôles de l'onboarding : dossiers refusés par Android 11+ **avant**
 *   toute prise de permission, puis test d'écriture témoin.
 *
 * Le résultat n'est **jamais persisté dans les Paramètres** : il vit dans
 * l'état du wizard ([jo.codeide.feature.newproject] via `SavedStateHandle`)
 * et doit être relâché à l'abandon ([ReleaseCreationLocationUseCase]) s'il
 * n'est ni le dossier de travail ni l'arbre d'un projet du registre.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount") // Clauses de garde : URI, refus, permission, témoin (règle 16).
public class ResolveCreationLocationUseCase
    @Inject
    constructor(
        private val arborescences: ArborescencesSaf,
        private val fichiers: FileSystem,
        private val parametres: SettingsRepository,
        private val horloge: TimeProvider,
    ) {
        /**
         * Résout [grantUri] (retour du sélecteur SAF) en emplacement de
         * création.
         *
         * @param grantUri URI d'arborescence proposée par le sélecteur.
         * @return la validation : [ValidationDossier.Valide] (emplacement
         * utilisable, permission héritée ou propre), [ValidationDossier.Refuse]
         * (dossier interdit par Android 11+) ou [ValidationDossier.Erreur].
         */
        public suspend operator fun invoke(grantUri: String): ValidationDossier {
            val idChoisi =
                arborescences.idDocument(grantUri)
                    ?: return ValidationDossier.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI d'arborescence illisible"),
                    )

            // Refus plateforme d'abord : jamais de permission prise sur un
            // dossier interdit (même garde que l'onboarding).
            val refus = ForbiddenFolders.reasonFor(idChoisi)
            if (refus != null) return ValidationDossier.Refuse(refus)

            val uriChoisi =
                arborescences.uriDocument(grantUri)
                    ?: return ValidationDossier.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI de document illisible"),
                    )

            return when (val emplacement = resoudreEmplacement(arborescences, parametres, uriChoisi, idChoisi)) {
                // Dans l'arbre du dossier de travail : permission héritée,
                // aucune à prendre — l'URI est réadressée dans l'arbre.
                is EmplacementSaf.Herite -> {
                    ValidationDossier.Valide(
                        StorageLocation(
                            grantUri = emplacement.grantUri,
                            documentUri = emplacement.uriDocument,
                            displayPath = libelleLisible(fichiers, emplacement.uriDocument, idChoisi),
                        ),
                    )
                }

                // Hors arbre : permission propre, témoin d'écriture, libération
                // à tout échec (même contrat que l'onboarding).
                is EmplacementSaf.Propre -> {
                    enEmplacementPropre(grantUri, uriChoisi, idChoisi)
                }

                is EmplacementSaf.Illisible -> {
                    ValidationDossier.Erreur(emplacement.erreur)
                }
            }
        }

        /** Prend la permission propre, éprouve le dossier, puis valide. */
        private suspend fun enEmplacementPropre(
            grantUri: String,
            uriChoisi: String,
            idChoisi: String,
        ): ValidationDossier {
            when (val permission = fichiers.takePersistablePermission(grantUri)) {
                is AppResult.Failure -> return ValidationDossier.Erreur(permission.error)
                is AppResult.Success -> Unit
            }
            val echec = testerEcriture(fichiers, uriChoisi, horloge, PREFIXE_TEMOIN)
            return when (echec) {
                null -> {
                    ValidationDossier.Valide(
                        StorageLocation(
                            grantUri = grantUri,
                            documentUri = uriChoisi,
                            displayPath = libelleLisible(fichiers, uriChoisi, idChoisi),
                        ),
                    )
                }

                is AppError -> {
                    fichiers.releasePersistablePermission(grantUri)
                    ValidationDossier.Erreur(echec)
                }
            }
        }

        private companion object {
            /** Préfixe du fichier témoin du test d'écriture. */
            const val PREFIXE_TEMOIN = "codeide-temoin"
        }
    }

/**
 * Cas d'usage « relâcher l'emplacement de création » (étape 10).
 *
 * À l'abandon du wizard, un emplacement choisi « pour cette création
 * uniquement » peut détenir une permission persistante propre (choix hors
 * du dossier de travail). Elle n'est libérée que si elle ne sert plus à
 * rien : ni le dossier de travail courant, ni l'arbre d'un projet du
 * registre ne la référencent (même équilibre que l'étape 7, ADR 0016).
 *
 * L'écran de création (étape 11) appellera ce cas d'usage sur son propre
 * échec ; en cas de succès, le projet fraîchement enregistré vit dans
 * l'arbre et la règle du registre s'applique à la suppression.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount") // Clauses de garde : rien à faire, échec de lecture (règle 16).
public class ReleaseCreationLocationUseCase
    @Inject
    constructor(
        private val parametres: SettingsRepository,
        private val projets: ProjectRepository,
        private val fichiers: FileSystem,
    ) {
        /**
         * Relâche la permission propre de [emplacement] si elle est devenue
         * inutile.
         *
         * @param emplacement l'emplacement éphémère choisi dans le wizard
         * (`null` : le dossier de travail était utilisé, rien à faire).
         */
        public suspend operator fun invoke(emplacement: StorageLocation?) {
            if (emplacement == null) return
            val dossierTravail = parametres.getSettings().getOrNull()?.workspace
            if (dossierTravail != null && dossierTravail.grantUri == emplacement.grantUri) return
            if (projetsSousDossier(projets, emplacement)) return
            fichiers.releasePersistablePermission(emplacement.grantUri)
        }
    }

/**
 * Résultat de la vérification asynchrone de la cible de création (étape 10 —
 * section 12.3, « vérifications asynchrones avec debounce »).
 *
 * Machine explicite : chaque échec garde sa raison pour un message inline
 * clair et actionnable dans la carte d'emplacement.
 */
public sealed interface VerificationCible {
    /** Emplacement joignable et aucun enfant ne porte le nom du projet. */
    public data object Valide : VerificationCible

    /** L'emplacement n'est plus joignable (permission perdue, dossier parti). */
    public data class EmplacementInaccessible(
        val erreur: AppError,
    ) : VerificationCible

    /** Un enfant porte déjà le nom du projet (comparaison insensible à la casse). */
    public data object NomDejaPris : VerificationCible

    /** Échec du listing (erreur typée, distincte de l'inaccessibilité). */
    public data class Erreur(
        val erreur: AppError,
    ) : VerificationCible
}

/**
 * Cas d'usage « vérifier la cible de création » (étape 10 — section 12.3).
 *
 * À chaque frappe (avec délai côté ViewModel) sur le nom ou l'emplacement :
 * l'emplacement est-il **toujours joignable** ([FileSystem.stat] : une
 * permission révoquée se voit comme `NotFound`, jamais comme un crash) et
 * **`<nom>` n'existe pas déjà** dans le dossier parent (comparaison
 * insensible à la casse, fichiers compris — SAF refuse toute collision de
 * nom dans un même dossier).
 *
 * L'inscriptibilité, elle, a été **prouvée au moment du choix** du dossier
 * (test d'écriture témoin de [ResolveCreationLocationUseCase] ou dossier de
 * travail validé à l'onboarding) : on ne recrée pas un témoin à chaque
 * frappe.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount") // Clauses de garde : stat, type, listing (règle 16).
public class VerifyCreationTargetUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Vérifie que [nomProjet] peut être créé dans [documentUri].
         *
         * @param documentUri URI du document **parent** (emplacement).
         * @param nomProjet nom du dossier projet à créer.
         * @return la vérification, jamais d'exception.
         */
        public suspend operator fun invoke(
            documentUri: String,
            nomProjet: String,
        ): VerificationCible {
            val parent =
                when (val stat = fichiers.stat(documentUri)) {
                    is AppResult.Failure -> return VerificationCible.EmplacementInaccessible(stat.error)
                    is AppResult.Success -> stat.value
                }
            if (!parent.isDirectory) {
                return VerificationCible.Erreur(
                    AppError.Storage(AppError.StorageReason.Io, "l'emplacement n'est pas un dossier"),
                )
            }
            return when (val enfants = fichiers.list(documentUri)) {
                is AppResult.Failure -> {
                    VerificationCible.Erreur(enfants.error)
                }

                is AppResult.Success -> {
                    if (enfants.value.any { it.name.equals(nomProjet.trim(), ignoreCase = true) }) {
                        VerificationCible.NomDejaPris
                    } else {
                        VerificationCible.Valide
                    }
                }
            }
        }
    }
