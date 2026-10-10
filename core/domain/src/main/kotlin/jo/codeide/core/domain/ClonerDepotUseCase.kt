package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Cas d'usage « cloner un dépôt Git dans le dossier de travail » (mission
 * Git G5, v0.80.4) — l'équivalent mobile du « Get from VCS » d'Android
 * Studio : URL + nom de dossier, cible = un enfant du dossier de travail.
 *
 * Pipeline séquentielle (même esprit que `CreateProjectUseCase`) :
 * 1. résoudre le **dossier de travail** (Paramètres) — non configuré :
 *    échec explicite, jamais de destination inventée ;
 * 2. **valider le nom** du dossier cible (validateur partagé `file-name`,
 *    une seule source de vérité avec le wizard) ;
 * 3. **vérifier la cible** (joignable + aucun enfant homonyme,
 *    `VerifyCreationTargetUseCase`) ;
 * 4. **créer le dossier** via SAF (`FileSystem.createDirectory`) — l'URI
 *    retournée fait foi (SAF peut renommer silencieusement en cas de
 *    collision) ;
 * 5. **cloner** via le port [MoteurGit] sur le chemin FUSE du dossier
 *    créé ([ResolveurCheminFuse] — lié à `ResoudreRepertoireProjet` en
 *    production) — le clone échoue → **rollback** (dossier supprimé),
 *    le disque reste propre ;
 * 6. **enregistrer au registre** en dernier — échec → rollback aussi.
 *
 * Le clone est annoncé « superficiel » ni autre option : la v1 du bouton
 * reste honnête (`git clone` intégral), les options suivront avec la
 * progression fine.
 *
 * Contexte d'exécution attendu : suspendante (réseau + SAF), hors thread
 * principal.
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — une issue typée
 * par étape du pipeline ; LongParameterList — chaque paramètre est une
 * dépendance distincte du domaine (même exemption que HomeViewModel).
 */
@Suppress("ReturnCount", "LongParameterList")
public class ClonerDepotUseCase
    @Inject
    constructor(
        private val parametres: SettingsRepository,
        private val fichiers: FileSystem,
        private val projets: ProjectRepository,
        private val moteurGit: MoteurGit,
        private val resoudreChemin: ResolveurCheminFuse,
        private val evaluerNom: EvaluerNomFichierUseCase,
        private val verifierCible: VerifyCreationTargetUseCase,
    ) {
        /**
         * Clone le dépôt [url] dans un dossier [nom] du dossier de travail.
         *
         * Étapes 1 à 3 (gardes) vivent ici ; la pipeline à effet de bord
         * (créer → cloner → enregistrer, avec rollback) vit dans
         * [clonerEtEnregistrer] — une responsabilité par méthode.
         *
         * @param url URL du dépôt (HTTPS en v1 — `git clone` nu).
         * @param nom nom du dossier cible sous le dossier de travail.
         * @return le résultat typé — jamais d'exception vers l'appelant.
         */
        public suspend operator fun invoke(
            url: String,
            nom: String,
        ): ResultatClonage {
            val urlNettoyee = url.trim()
            if (urlNettoyee.isEmpty()) {
                return ResultatClonage.Erreur(
                    AppError.Validation("URL du dépôt vide"),
                    ROLLBACK_SANS_OBJET,
                )
            }

            val dossierTravail =
                parametres.getSettings().getOrNull()?.workspace
                    ?: return ResultatClonage.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "dossier de travail non configuré"),
                        ROLLBACK_SANS_OBJET,
                    )

            val nomNettoye = nom.trim()
            val raison = evaluerNom(nomNettoye)
            if (raison != null || nomNettoye.isEmpty()) {
                return ResultatClonage.Refuse(raison ?: RaisonValidation.LongueurNom)
            }

            when (val cible = verifierCible(dossierTravail.documentUri, nomNettoye)) {
                is VerificationCible.Valide -> {
                    return clonerEtEnregistrer(urlNettoyee, nomNettoye, dossierTravail)
                }

                is VerificationCible.NomDejaPris -> {
                    return ResultatClonage.Erreur(
                        AppError.Storage(AppError.StorageReason.AlreadyExists, "un dossier porte déjà ce nom"),
                        ROLLBACK_SANS_OBJET,
                    )
                }

                is VerificationCible.EmplacementInaccessible -> {
                    return ResultatClonage.Erreur(cible.erreur, ROLLBACK_SANS_OBJET)
                }

                is VerificationCible.Erreur -> {
                    return ResultatClonage.Erreur(cible.erreur, ROLLBACK_SANS_OBJET)
                }
            }
        }

        /**
         * Étapes 4 à 6 : créer le dossier, cloner, enregistrer — tout
         * échec POSTÉRIEUR à la création déclenche le rollback
         * ([nettoyer]) ; le disque reste propre, l'échec reste honnête.
         */
        @Suppress("ReturnCount") // Une issue typée par étape (création, FUSE, git, registre).
        private suspend fun clonerEtEnregistrer(
            url: String,
            nom: String,
            dossierTravail: StorageLocation,
        ): ResultatClonage {
            val uriDossier =
                fichiers.createDirectory(dossierTravail.documentUri, nom).getOrNull()
                    ?: return ResultatClonage.Erreur(
                        AppError.Storage(AppError.StorageReason.NotWritable, "dossier cible incréable"),
                        ROLLBACK_SANS_OBJET,
                    )

            val cheminFuse =
                resoudreChemin(uriDossier)
                    ?: return ResultatClonage.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "chemin du dossier cible irrésolvable"),
                        nettoyer(uriDossier),
                    )

            when (val clone = moteurGit.cloner(url, cheminFuse)) {
                is ResultatGit.Succes -> {
                    Unit
                }

                is ResultatGit.Echec -> {
                    return ResultatClonage.ErreurGit(clone.message, nettoyer(uriDossier))
                }
            }

            val insertion =
                projets.addProject(
                    name = nom,
                    description = DESCRIPTION_CLONE,
                    location =
                        StorageLocation(
                            grantUri = dossierTravail.grantUri,
                            documentUri = uriDossier,
                            displayPath = dossierTravail.displayPath + "/" + nom,
                        ),
                    templateId = TemplateId.IMPORTED,
                )
            return when (insertion) {
                is AppResult.Success -> ResultatClonage.Succes(insertion.value)
                is AppResult.Failure -> ResultatClonage.Erreur(insertion.error, nettoyer(uriDossier))
            }
        }

        /**
         * Rollback : supprime le dossier fraîchement créé ; `true` si le
         * disque est propre. Un échec de nettoyage reste signalé au
         * résultat — jamais de résidu silencieux.
         */
        private suspend fun nettoyer(uriDossier: String): Boolean = fichiers.delete(uriDossier) is AppResult.Success

        private companion object {
            /** Description des projets clonés (libellé libre, registre). */
            const val DESCRIPTION_CLONE = "Cloné depuis un dépôt Git"

            /** Rien n'a été créé : aucun rollback à tenter. */
            const val ROLLBACK_SANS_OBJET = false
        }
    }

/**
 * Résultat typé du clonage (v0.80.4) : chaque issue garde son contexte
 * pour un message d'interface actionnable — jamais une exception.
 */
public sealed interface ResultatClonage {
    /** Dépôt cloné, dossier créé, projet enregistré. */
    public data class Succes(
        public val projet: Project,
    ) : ResultatClonage

    /** Nom de dossier refusé par le validateur partagé (raison typée). */
    public data class Refuse(
        public val raison: RaisonValidation,
    ) : ResultatClonage

    /**
     * Échec applicatif (SAF, registre…) APRÈS création du dossier — le
     * rollback (`FileSystem.delete`) a été TENTÉ : [rollback] signale
     * son issue pour que le disque reste honnête (un résidu n'est jamais
     * silencieux) et [erreur] porte l'erreur d'origine.
     */
    public data class Erreur(
        public val erreur: AppError,
        public val rollback: Boolean,
    ) : ResultatClonage

    /**
     * Échec de git lui-même (stderr) APRÈS création du dossier — message
     * brut de git (réseau, authentification, dépôt introuvable…) :
     * l'UI l'affiche tel quel, c'est lui qui dit vrai ; [rollback] signale
     * l'issue du nettoyage.
     */
    public data class ErreurGit(
        public val message: String,
        public val rollback: Boolean,
    ) : ResultatClonage
}
