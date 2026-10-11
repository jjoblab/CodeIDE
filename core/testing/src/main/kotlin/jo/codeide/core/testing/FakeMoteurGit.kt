package jo.codeide.core.testing

import jo.codeide.core.domain.BrancheGit
import jo.codeide.core.domain.CommitGit
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.ProgressionGit
import jo.codeide.core.domain.RapportDiagnosticGit
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutGit
import kotlinx.coroutines.delay

/**
 * [MoteurGit](jo.codeide.core.domain.MoteurGit) en mémoire : chaque
 * opération retourne un résultat piloté par le test — successeur des
 * robinets de défaillance à la [FakeProjectRepository] (même idiom que
 * la suite `core:testing`, ADR 0092 §Conséquences).
 *
 * Comportements observables :
 * - [operations] enregistre `opération(chemin)` dans l'ordre — les
 *   assertions savent QUOI a été exécuté et sur quel dossier ;
 * - [resultatCloner] / [resultatInitialiser] pilotent les échecs git
 *   (réseau, git absent…) : un échec de clone par défaut honnête ;
 * - [etatDepot] pilote les trois états du port (dépôt sain, pas un
 *   dépôt, inaccessible) — v0.90.1, mission « section Git figée » ;
 * - [rapportDiagnostic] pilote [diagnostiquer] (sondes globales par
 *   défaut, aucun projet) ;
 * - [latence] ralentit chaque appel — éprouver les états de chargement.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * TooManyFunctions — le fake implémente le contrat complet de
 * [MoteurGit] (une fonction par opération, ADR 0092), sans logique
 * supplémentaire.
 */
@Suppress("TooManyFunctions")
public class FakeMoteurGit : MoteurGit {
    /** Journal des appels, au format `opération:chemin`. */
    public val operations: MutableList<String> = mutableListOf()

    /** Latence artificielle de chaque opération (0 par défaut). */
    public var latence: Long = 0L

    /** Réponse de [etatDepot] (pas un dépôt par défaut : l'état le plus neutre). */
    public var reponseEtatDepot: EtatDepot = EtatDepot.PasUnDepot

    /** Rapport retourné par [diagnostiquer] (sondes globales, aucun projet, par défaut). */
    public var rapportDiagnostic: RapportDiagnosticGit =
        RapportDiagnosticGit(
            nomProjet = null,
            cheminFuse = null,
            cheminBinaire = null,
            versionGit = null,
            uidEffectif = null,
            uidProprietaireDossier = null,
            pointDeMontage = null,
            typeSystemeFichiers = null,
            revParse = null,
            configList = null,
            environnement = emptyMap(),
        )

    /** Résultat de [cloner] (succès silencieux par défaut). */
    public var resultatCloner: ResultatGit<Unit> = ResultatGit.Succes(Unit)

    /** Résultat de [initialiser] (succès silencieux par défaut). */
    public var resultatInitialiser: ResultatGit<Unit> = ResultatGit.Succes(Unit)

    /** Résultat de [committer] (hash factice par défaut). */
    public var resultatCommitter: ResultatGit<String> = ResultatGit.Succes("abc123")

    /** Résultat de [statut] (statut vide par défaut). */
    public var resultatStatut: ResultatGit<StatutGit> =
        ResultatGit.Succes(StatutGit(modifications = emptyList()))

    /** Résultat de [brancheCourante] (`main` par défaut). */
    public var resultatBrancheCourante: ResultatGit<String> = ResultatGit.Succes("main")

    /** Résultat de [journal] (liste vide par défaut). */
    public var resultatJournal: ResultatGit<List<CommitGit>> = ResultatGit.Succes(emptyList())

    /** Résultat de [branches] (liste vide par défaut). */
    public var resultatBranches: ResultatGit<List<BrancheGit>> = ResultatGit.Succes(emptyList())

    /** Résultat de [diff] (texte vide par défaut). */
    public var resultatDiff: ResultatGit<String> = ResultatGit.Succes("")

    /** Résultat de [stasher] (hash factice par défaut). */
    public var resultatStasher: ResultatGit<String> = ResultatGit.Succes("stash-1")

    /** Résultat de [restaurerStash] (succès par défaut). */
    public var resultatRestaurerStash: ResultatGit<Unit> = ResultatGit.Succes(Unit)

    /** Résultat de [tirer] / [pousser] / [indexer] / [desindexer] / [basculerBranche]. */
    public var resultatUnitaire: ResultatGit<Unit> = ResultatGit.Succes(Unit)

    override suspend fun statut(cheminFuse: String): ResultatGit<StatutGit> {
        attendre()
        operations += "statut:$cheminFuse"
        return resultatStatut
    }

    override suspend fun journal(
        cheminFuse: String,
        limite: Int,
    ): ResultatGit<List<CommitGit>> {
        attendre()
        operations += "journal:$cheminFuse"
        return resultatJournal
    }

    override suspend fun indexer(
        cheminFuse: String,
        chemins: List<String>,
    ): ResultatGit<Unit> {
        attendre()
        operations += "indexer:$cheminFuse"
        return resultatUnitaire
    }

    override suspend fun desindexer(
        cheminFuse: String,
        chemins: List<String>,
    ): ResultatGit<Unit> {
        attendre()
        operations += "desindexer:$cheminFuse"
        return resultatUnitaire
    }

    override suspend fun committer(
        cheminFuse: String,
        message: String,
    ): ResultatGit<String> {
        attendre()
        operations += "committer:$cheminFuse"
        return resultatCommitter
    }

    override suspend fun tirer(
        cheminFuse: String,
        rebase: Boolean,
    ): ResultatGit<Unit> {
        attendre()
        operations += "tirer:$cheminFuse"
        return resultatUnitaire
    }

    override suspend fun pousser(
        cheminFuse: String,
        forceWithLease: Boolean,
    ): ResultatGit<Unit> {
        attendre()
        operations += "pousser:$cheminFuse"
        return resultatUnitaire
    }

    override suspend fun brancheCourante(cheminFuse: String): ResultatGit<String> {
        attendre()
        operations += "branche:$cheminFuse"
        return resultatBrancheCourante
    }

    override suspend fun branches(cheminFuse: String): ResultatGit<List<BrancheGit>> {
        attendre()
        operations += "branches:$cheminFuse"
        return resultatBranches
    }

    override suspend fun basculerBranche(
        cheminFuse: String,
        nom: String,
    ): ResultatGit<Unit> {
        attendre()
        operations += "checkout:$cheminFuse"
        return resultatUnitaire
    }

    override suspend fun cloner(
        url: String,
        cible: String,
        progression: ((ProgressionGit) -> Unit)?,
    ): ResultatGit<Unit> {
        attendre()
        operations += "cloner:$url->$cible"
        return resultatCloner
    }

    override suspend fun initialiser(cheminFuse: String): ResultatGit<Unit> {
        attendre()
        operations += "init:$cheminFuse"
        return resultatInitialiser
    }

    override suspend fun etatDepot(cheminFuse: String): EtatDepot {
        attendre()
        operations += "etatDepot:$cheminFuse"
        return reponseEtatDepot
    }

    override suspend fun diagnostiquer(
        nomProjet: String?,
        cheminFuse: String?,
    ): RapportDiagnosticGit {
        attendre()
        operations += "diagnostiquer:${nomProjet ?: "-"}"
        return rapportDiagnostic
    }

    override suspend fun diff(
        cheminFuse: String,
        chemin: String,
    ): ResultatGit<String> {
        attendre()
        operations += "diff:$cheminFuse"
        return resultatDiff
    }

    override suspend fun stasher(cheminFuse: String): ResultatGit<String> {
        attendre()
        operations += "stash:$cheminFuse"
        return resultatStasher
    }

    override suspend fun restaurerStash(cheminFuse: String): ResultatGit<Unit> {
        attendre()
        operations += "stashPop:$cheminFuse"
        return resultatRestaurerStash
    }

    override fun annuler() {
        // Rien à tuer en mémoire — l'annulation coopérative passe par la
        // cancellation de la coroutine appelante (le test collecte).
    }

    /** Latence éventuelle (éprouver les chargements). */
    private suspend fun attendre() {
        if (latence > 0) delay(latence)
    }
}
