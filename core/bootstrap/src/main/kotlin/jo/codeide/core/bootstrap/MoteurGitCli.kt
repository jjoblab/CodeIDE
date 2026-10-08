package jo.codeide.core.bootstrap

import jo.codeide.core.domain.BrancheGit
import jo.codeide.core.domain.CommitGit
import jo.codeide.core.domain.ModificationFichier
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ProgressionGit
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutFichier
import jo.codeide.core.domain.StatutGit
import kotlinx.coroutines.flow.toList
import java.io.File
import java.io.IOException

/**
 * Implémentation de production de [MoteurGit] (ADR 0092) : exécute le
 * binaire `git` du bootstrap via [NativeProcessLauncher], sur le chemin
 * FUSE réel du projet.
 *
 * Toutes les sorties utilisent le format `--porcelain` (stable, parsable)
 * ou `-z` (séparateur NUL) — jamais la sortie « human-readable » dont le
 * format change selon la locale et la version de git.
 *
 * L'environnement du processus hérite de celui du bootstrap (`PATH`,
 * `HOME`, `TMPDIR`) via `NativeProcessLauncher` ; les variables
 * d'identité (`GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`, `GIT_COMMITTER_NAME`,
 * `GIT_COMMITTER_EMAIL`) sont passées via `extraEnv` depuis les paramètres
 * utilisateur.
 *
 * @param lanceur port d'exécution des sous-processus natifs.
 * @param binaireGit chemin absolu vers le binaire `git` (typiquement
 * `$PREFIX/bin/git`), ou `null` si git n'est pas installé — les
 * opérations retournent alors [ResultatGit.Echec] avec un message
 * explicite.
 * @param identite identité Git (nom, email) depuis les Paramètres, ou
 * `null` si non configurée — le commit refuse avec un message clair.
 */
@Suppress("TooManyFunctions") // Port Git : une fonction par opération, hérité de MoteurGit.
internal class MoteurGitCli(
    private val lanceur: NativeProcessLauncher,
    private val binaireGit: String?,
    private val identite: IdentiteGit?,
) : MoteurGit {
    /** Identité Git pour les commits (nom + email utilisateur). */
    internal data class IdentiteGit(
        val nom: String,
        val email: String,
    )

    override suspend fun statut(cheminFuse: String): ResultatGit<StatutGit> {
        val sortie = executer(cheminFuse, listOf("status", "--porcelain=v1", "-z"))
        if (sortie is ResultatGit.Echec) return sortie
        val texte = (sortie as ResultatGit.Succes).valeur
        return ResultatGit.Succes(StatutGit(parseurStatut(texte)))
    }

    override suspend fun journal(
        cheminFuse: String,
        limite: Int,
    ): ResultatGit<List<CommitGit>> {
        // Format : un commit par ligne, champs séparés par NUL (%x00).
        // Pas de -z : on utilise le newline comme séparateur de commits
        // (le sujet %s est une ligne sans newline).
        val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s"
        val sortie = executer(cheminFuse, listOf("log", "--max-count=$limite", "--format=$format"))
        if (sortie is ResultatGit.Echec) return sortie
        val texte = (sortie as ResultatGit.Succes).valeur
        return ResultatGit.Succes(parseurJournal(texte))
    }

    override suspend fun indexer(
        cheminFuse: String,
        chemins: List<String>,
    ): ResultatGit<Unit> {
        val args = if (chemins.isEmpty()) listOf("add", "-A") else listOf("add", "--") + chemins
        return unitSiSucces(executer(cheminFuse, args))
    }

    override suspend fun desindexer(
        cheminFuse: String,
        chemins: List<String>,
    ): ResultatGit<Unit> {
        val args = if (chemins.isEmpty()) listOf("reset", "HEAD", "--") else listOf("reset", "HEAD", "--") + chemins
        return unitSiSucces(executer(cheminFuse, args))
    }

    @Suppress("ReturnCount") // Gardes : identité absente, échec commit.
    override suspend fun committer(
        cheminFuse: String,
        message: String,
    ): ResultatGit<String> {
        val id = identite ?: return ResultatGit.Echec("Identité Git non configurée", "")
        val sortie = executer(cheminFuse, listOf("commit", "--message=$message"), envIdentite(id))
        if (sortie is ResultatGit.Echec) return sortie
        val hash = executer(cheminFuse, listOf("rev-parse", "HEAD"))
        return if (hash is ResultatGit.Succes) ResultatGit.Succes(hash.valeur.trim()) else ResultatGit.Succes("")
    }

    override suspend fun tirer(
        cheminFuse: String,
        rebase: Boolean,
    ): ResultatGit<Unit> {
        val args = if (rebase) listOf("pull", "--rebase") else listOf("pull", "--no-rebase")
        return unitSiSucces(executer(cheminFuse, args))
    }

    override suspend fun pousser(
        cheminFuse: String,
        forceWithLease: Boolean,
    ): ResultatGit<Unit> {
        val args = if (forceWithLease) listOf("push", "--force-with-lease") else listOf("push")
        return unitSiSucces(executer(cheminFuse, args))
    }

    override suspend fun brancheCourante(cheminFuse: String): ResultatGit<String> {
        val sortie = executer(cheminFuse, listOf("branch", "--show-current"))
        return if (sortie is ResultatGit.Succes) ResultatGit.Succes(sortie.valeur.trim()) else sortie
    }

    override suspend fun branches(cheminFuse: String): ResultatGit<List<BrancheGit>> {
        val sortie =
            executer(
                cheminFuse,
                listOf("branch", "--list", "--all", "--format=%(HEAD)%00%(refname:short)%00%(upstream:short)"),
            )
        if (sortie is ResultatGit.Echec) return sortie
        val lignes = (sortie as ResultatGit.Succes).valeur.lines().filter { it.isNotBlank() }
        val resultat =
            lignes.map { ligne ->
                val parts = ligne.split('\u0000')
                val courante = parts.getOrNull(0)?.trim() == "*"
                val nom = parts.getOrNull(1)?.trim() ?: ""
                val estDistance = nom.contains("/")
                BrancheGit(
                    nom = nom,
                    estCourante = courante,
                    estDistance = estDistance,
                    remote = if (estDistance) nom.substringBefore('/') else null,
                )
            }
        return ResultatGit.Succes(resultat)
    }

    override suspend fun basculerBranche(
        cheminFuse: String,
        nom: String,
    ): ResultatGit<Unit> = unitSiSucces(executer(cheminFuse, listOf("checkout", nom)))

    override suspend fun cloner(
        url: String,
        cible: String,
        progression: ((ProgressionGit) -> Unit)?,
    ): ResultatGit<Unit> = unitSiSucces(executer(cible, listOf("clone", url, cible)))

    override suspend fun initialiser(cheminFuse: String): ResultatGit<Unit> =
        unitSiSucces(executer(cheminFuse, listOf("init")))

    override suspend fun estDepot(cheminFuse: String): Boolean {
        val sortie = executer(cheminFuse, listOf("rev-parse", "--is-inside-work-tree"))
        return sortie is ResultatGit.Succes && sortie.valeur.trim() == "true"
    }

    // G3 — Diff

    override suspend fun diff(
        cheminFuse: String,
        chemin: String,
    ): ResultatGit<String> = executer(cheminFuse, listOf("diff", "--", chemin))

    // G7 — Stash

    @Suppress("ReturnCount") // Gardes : échec stash create, rien à stasher.
    override suspend fun stasher(cheminFuse: String): ResultatGit<String> {
        val sortie = executer(cheminFuse, listOf("stash", "create"))
        if (sortie is ResultatGit.Echec) return sortie
        val hash = (sortie as ResultatGit.Succes).valeur.trim()
        if (hash.isEmpty()) return ResultatGit.Succes("")
        val store = executer(cheminFuse, listOf("stash", "store", hash))
        return if (store is ResultatGit.Echec) store else ResultatGit.Succes(hash)
    }

    override suspend fun restaurerStash(cheminFuse: String): ResultatGit<Unit> =
        unitSiSucces(executer(cheminFuse, listOf("stash", "pop")))

    override fun annuler() {
        // L'annulation cooperative est gérée par awaitExit() (coroutine).
        // L'UI appelle annuler() depuis un scope coroutine annulable.
    }

    /** Convertit un [ResultatGit] de String en [ResultatGit] de Unit. */
    private fun unitSiSucces(resultat: ResultatGit<String>): ResultatGit<Unit> =
        when (resultat) {
            is ResultatGit.Succes -> ResultatGit.Succes(Unit)
            is ResultatGit.Echec -> resultat
        }

    /**
     * Exécute une commande git et retourne la sortie stdout (succès) ou
     * un échec avec stderr. Vérifie d'abord que le binaire git existe.
     */
    @Suppress("ReturnCount") // Gardes : binaire absent, échec lancement, code non-zéro.
    private suspend fun executer(
        cheminFuse: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): ResultatGit<String> {
        val git =
            binaireGit ?: return ResultatGit.Echec("git n'est pas installé. Installez-le via pkg install git.", "")
        return try {
            val processus =
                lanceur.launch(
                    command = listOf(git) + args,
                    extraEnv = env,
                    workingDir = File(cheminFuse),
                )
            val sortie = SupervisionProcessus.attendre(processus)
            if (sortie.code == 0) {
                val stdout = processus.stdoutLines().toList().joinToString("\n")
                ResultatGit.Succes(stdout)
            } else {
                ResultatGit.Echec(
                    message = sortie.erreurs.joinToString("\n").ifBlank { "git a échoué (code ${sortie.code})" },
                    sortieErreur = sortie.erreurs.joinToString("\n"),
                )
            }
        } catch (e: IOException) {
            ResultatGit.Echec("Lancement de git impossible : ${e.message}", "")
        }
    }

    /** Variables d'environnement pour l'identité Git. */
    private fun envIdentite(identite: IdentiteGit): Map<String, String> =
        mapOf(
            "GIT_AUTHOR_NAME" to identite.nom,
            "GIT_AUTHOR_EMAIL" to identite.email,
            "GIT_COMMITTER_NAME" to identite.nom,
            "GIT_COMMITTER_EMAIL" to identite.email,
        )

    internal companion object {
        /** Longueur du préfixe `XY ` dans `--porcelain` (statut index + statut travail + espace). */
        private const val PREFIXE_PORCELAIN = 3

        /**
         * Parse la sortie de `git status --porcelain=v1 -z` en liste de
         * modifications. Format : `XY chemin\0XY chemin\0…` (séparateur
         * NUL). `X` = statut index, `Y` = statut travail.
         */
        internal fun parseurStatut(sortie: String): List<ModificationFichier> {
            if (sortie.isBlank()) return emptyList()
            val entrees = sortie.split('\u0000').filter { it.isNotBlank() }
            return entrees.map { entree ->
                val x = entree.getOrNull(0) ?: ' '
                val y = entree.getOrNull(1) ?: ' '
                val chemin = entree.drop(PREFIXE_PORCELAIN)
                ModificationFichier(
                    chemin = chemin,
                    statutIndex = codeVersStatut(x),
                    statutTravail = codeVersStatut(y),
                )
            }
        }

        /**
         * Parse la sortie de `git log --format=…` (sans -z) en liste de
         * commits. Format : un commit par ligne, champs séparés par NUL
         * (%x00) : `hash\0hashCourt\0auteur\0email\0date\0sujet`.
         */
        @Suppress("MagicNumber") // Indices de champs du format --format défini ci-dessus.
        internal fun parseurJournal(sortie: String): List<CommitGit> {
            if (sortie.isBlank()) return emptyList()
            // Sépare par newline (un commit par ligne), puis par NUL (champs).
            val lignes = sortie.lines().filter { it.isNotBlank() }
            return lignes.map { ligne ->
                val parts = ligne.split('\u0000')
                CommitGit(
                    hash = parts.getOrNull(0) ?: "",
                    hashCourt = parts.getOrNull(1) ?: "",
                    auteur = parts.getOrNull(2) ?: "",
                    email = parts.getOrNull(3) ?: "",
                    date = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                    message = parts.getOrNull(5) ?: "",
                )
            }
        }

        /** Convertit un code `--porcelain` en [StatutFichier]. */
        internal fun codeVersStatut(code: Char): StatutFichier =
            when (code) {
                'M' -> StatutFichier.MODIFIE
                'A' -> StatutFichier.AJOUTE
                'D' -> StatutFichier.SUPPRIME
                'R' -> StatutFichier.RENOMME
                'C' -> StatutFichier.COPIE
                '?' -> StatutFichier.NON_SUIVI
                '!' -> StatutFichier.IGNORE
                'U' -> StatutFichier.CONFLIT
                else -> StatutFichier.NON_MODIFIE
            }
    }
}
