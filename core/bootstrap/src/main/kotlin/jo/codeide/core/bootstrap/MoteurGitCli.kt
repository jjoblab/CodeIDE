package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.BrancheGit
import jo.codeide.core.domain.CommitGit
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.ModificationFichier
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ProgressionGit
import jo.codeide.core.domain.RapportDiagnosticGit
import jo.codeide.core.domain.ResultatCommandeGit
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutFichier
import jo.codeide.core.domain.StatutGit
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
 * v0.90.1 (mission « section Git figée », étape A) : [etatDepot] remplace
 * l'ancien `estDepot(): Boolean` qui écrasait toute cause d'échec en
 * « pas un dépôt » ; chaque exécution git journalise commande, code de
 * sortie et stderr (expurgés par le pipeline, règle 11) via [journal] ;
 * [diagnostiquer] assemble le rapport de l'écran Diagnostic.
 *
 * Exemptions detekt ciblées (règle 16 du prompt maître) :
 * TooManyFunctions et LongParameterList — port Git, une fonction par
 * opération héritée de [MoteurGit], journal et sondes v0.90.1 dépendances
 * distinctes de l'exécution.
 *
 * @param lanceur port d'exécution des sous-processus natifs.
 * @param resoudreBinaireGit résolution du chemin du binaire `git`
 * (typiquement `$PREFIX/bin/git`), appelée **à chaque exécution**
 * (v0.80.5 — correctif « git installé après le démarrage ») : le
 * binaire absent vaut `null` et les opérations retournent
 * [ResultatGit.Echec] avec un message explicite. La détection FIGÉE
 * à la création du singleton figeait aussi l'absence : installer git
 * via `pkg install git` dans le terminal ne devenait utilisable
 * qu'après un redémarrage de l'application — la fenêtre Git d'Android
 * Studio, elle, découvre le binaire dès qu'il existe.
 * @param identite identité Git (nom, email) depuis les Paramètres, ou
 * `null` si non configurée — le commit refuse avec un message clair.
 * @param journal journal applicatif (commande/code/stderr expurgés en
 * aval par le pipeline — l'observabilité exigée par l'étape A).
 * @param sondes lectures système du diagnostic (uid, propriétaire,
 * montages, environnement) — couture de test.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class MoteurGitCli(
    private val lanceur: NativeProcessLauncher,
    private val resoudreBinaireGit: () -> String?,
    private val identite: IdentiteGit?,
    private val journal: AppLogger,
    private val sondes: SondesEnvironnementGit,
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

    override suspend fun etatDepot(cheminFuse: String): EtatDepot {
        val brute = executerBrut(cheminFuse, listOf("rev-parse", "--is-inside-work-tree"))
        return ClassificationEtatDepot.classer(brute)
    }

    override suspend fun diagnostiquer(
        nomProjet: String?,
        cheminFuse: String?,
    ): RapportDiagnosticGit {
        val binaire = resoudreBinaireGit()
        val version =
            binaire?.let {
                executerBrut(null, listOf("--version")).stdout.lines().firstOrNull { l -> l.isNotBlank() }
            }
        val revParse = cheminFuse?.let { executerDiagnostic(it, listOf("rev-parse", "--is-inside-work-tree")) }
        val configList = cheminFuse?.let { executerDiagnostic(it, listOf("config", "--list", "--show-origin")) }
        val uidEffectif = sondes.uidEffectif()
        val uidProprietaire = cheminFuse?.let { sondes.uidProprietaire(it) }
        val monts = pointDeMontage(cheminFuse, sondes.contenuMonts())
        return RapportDiagnosticGit(
            nomProjet = nomProjet,
            cheminFuse = cheminFuse,
            cheminBinaire = binaire,
            versionGit = version,
            uidEffectif = uidEffectif,
            uidProprietaireDossier = uidProprietaire,
            pointDeMontage = monts?.first,
            typeSystemeFichiers = monts?.second,
            revParse = revParse,
            configList = configList,
            environnement = environnementPertinent(),
        )
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
     *
     * v0.80.7 (correctif « section Git figée ») : la sortie standard est
     * lue dans le RÉSULTAT de [SupervisionProcessus.attendre]
     * ([SupervisionProcessus.Sortie.sortieStandard]) — JAMAIS par une
     * seconde collecte de `stdoutLines()` : les flux du port sont froids
     * et consommables UNE SEULE FOIS (le tuyau est refermé à l'EOF), la
     * seconde collecte rendait un stdout VIDE sur appareil réel et
     * `estDepot` répondait FAUX pour tout dépôt existant — cause racine
     * de la section figée sur « ce projet n'est pas un dépôt Git ».
     */
    @Suppress("ReturnCount") // Gardes : binaire absent, échec lancement, code non-zéro.
    private suspend fun executer(
        cheminFuse: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): ResultatGit<String> {
        val brute = executerBrut(cheminFuse, args, env)
        return brute.versResultat()
    }

    /**
     * Exécution brute d'une commande git (v0.90.1) : lancement dans le
     * répertoire [cheminFuse] (ou sans répertoire si `null`), drainage
     **unique** des flux ([SupervisionProcessus]), journal de la commande
     * (code + stderr, expurgé par le pipeline) — retourne la matière
     * première (code, stdout, stderr, échec éventuel).
     */
    @Suppress("ReturnCount") // Gardes : binaire absent, échec lancement.
    private suspend fun executerBrut(
        cheminFuse: String?,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        limiteStderr: Int = LIMITE_STDERR_METIER,
    ): ExecutionGitBrute {
        val git =
            resoudreBinaireGit()
                ?: return ExecutionGitBrute(
                    code = null,
                    stdout = "",
                    stderr = "",
                    causeLancement = CauseLancementGit.BINAIRE_ABSENT,
                    messageEchec = "git n'est pas installé. Installez-le via pkg install git.",
                )
        return try {
            val processus =
                lanceur.launch(
                    command = listOf(git) + args,
                    extraEnv = env,
                    workingDir = cheminFuse?.let(::File),
                )
            val sortie = SupervisionProcessus.attendre(processus, limiteStderr = limiteStderr)
            journalCommande(args, sortie.code, sortie.erreurs)
            ExecutionGitBrute(
                code = sortie.code,
                stdout = sortie.sortieStandard.joinToString("\n"),
                stderr = sortie.erreurs.joinToString("\n"),
                causeLancement = null,
                messageEchec = null,
            )
        } catch (e: IOException) {
            journal.e(TAG_JOURNAL) { "git ${args.joinToString(" ")} : lancement impossible (${e.message})" }
            ExecutionGitBrute(
                code = null,
                stdout = "",
                stderr = "",
                causeLancement = CauseLancementGit.ERREUR_IO,
                messageEchec = "Lancement de git impossible : ${e.message}",
            )
        }
    }

    /**
     * Exécution pour le Diagnostic Git : stderr capté intégral (borné
     * généreusement) — le diagnostic ne tronque pas la preuve.
     */
    private suspend fun executerDiagnostic(
        cheminFuse: String,
        args: List<String>,
    ): ResultatCommandeGit {
        val brute = executerBrut(cheminFuse, args, limiteStderr = SupervisionProcessus.LIMITE_STDERR_DIAGNOSTIC)
        return ResultatCommandeGit(
            code = brute.code,
            sortieStandard = brute.stdout,
            sortieErreur = brute.stderr.ifEmpty { brute.messageEchec ?: "" },
        )
    }

    /**
     * Journal d'une exécution (étape A : l'échec observable) : la
     * commande (sans chemin du binaire — identique à chaque fois), le
     * code de sortie et le stderr ; succès → DEBUG, échec → WARN.
     * L'expurgation (chemins, courriels) est appliquée en aval par le
     * pipeline de journalisation (règle 11) — le producteur reste sobre.
     */
    private fun journalCommande(
        args: List<String>,
        code: Int,
        stderr: List<String>,
    ) {
        if (code == 0 && stderr.isEmpty()) {
            journal.d(TAG_JOURNAL) { "git ${args.joinToString(" ")} -> code 0" }
            return
        }
        val extrait = stderr.joinToString(" | ").take(LONGUEUR_STDERR_JOURNAL)
        journal.w(TAG_JOURNAL) { "git ${args.joinToString(" ")} -> code $code, stderr : $extrait" }
    }

    /** Variables d'environnement pour l'identité Git. */
    private fun envIdentite(identite: IdentiteGit): Map<String, String> =
        mapOf(
            "GIT_AUTHOR_NAME" to identite.nom,
            "GIT_AUTHOR_EMAIL" to identite.email,
            "GIT_COMMITTER_NAME" to identite.nom,
            "GIT_COMMITTER_EMAIL" to identite.email,
        )

    /**
     * Environnement pertinent du diagnostic : `HOME`, `PATH`, `TMPDIR`,
     * `LD_LIBRARY_PATH`, `PREFIX` et toutes les `GIT_*` — trié par clé
     * pour un rapport reproductible d'une exécution à l'autre.
     */
    private fun environnementPertinent(): Map<String, String> =
        sondes
            .environnement()
            .filterKeys { it in VARIABLES_GARDEES || it.startsWith(PREFIXE_GIT) }
            .toSortedMap()

    internal companion object {
        /** Étiquette de journal des commandes git (identifiant court, anglais). */
        private const val TAG_JOURNAL = "git-cli"

        /** Limite du stderr pour les opérations métier (extrait historique). */
        private const val LIMITE_STDERR_METIER = 5

        /** Longueur maximale du stderr journalisé (extrait observable, pas un dump). */
        private const val LONGUEUR_STDERR_JOURNAL = 400

        /** Variables d'environnement toujours rapportées par le diagnostic. */
        private val VARIABLES_GARDEES =
            setOf("HOME", "PATH", "TMPDIR", "LD_LIBRARY_PATH", "PREFIX", "GRADLE_USER_HOME")

        /** Préfixe des variables git du diagnostic. */
        private const val PREFIXE_GIT = "GIT_"

        /** Longueur du préfixe `XY ` dans `--porcelain` (statut index + statut travail + espace). */
        private const val PREFIXE_PORCELAIN = 3

        /**
         * Point de montage du [chemin] dans la table des montages
         * ([contenu] brut de `/proc/self/mounts`) : le plus long point
         * de montage qui est un préfixe **par segments** du chemin.
         *
         * Format d'une ligne : `périphérique point type options 0 0`.
         * Retourne le couple (point de montage, type de système de
         * fichiers), ou `null` si introuvable/illisible.
         */
        internal fun pointDeMontage(
            chemin: String?,
            contenu: String?,
        ): Pair<String, String>? {
            if (chemin == null || contenu == null) return null
            val cible = normaliser(chemin)
            return contenu
                .lines()
                .mapNotNull(::champsMontage)
                .filter { (point, _) -> prefixeParSegments(cible, normaliser(point)) }
                .maxByOrNull { (point, _) -> normaliser(point).length }
        }

        /**
         * Champs (point de montage, type) d'une ligne de la table des
         * montages, ou `null` si la ligne est inexploitable.
         */
        @Suppress("MagicNumber") // Champs d'une ligne /proc/self/mounts.
        private fun champsMontage(ligne: String): Pair<String, String>? {
            val champs = ligne.split(' ', limit = CHAMPS_MONTS).filter { it.isNotBlank() }
            if (champs.size < MINIMUM_CHAMPS_MONTS) return null
            return champs[1] to champs[2]
        }

        /**
         * Retire le slash final (la racine `/` devient vide : préfixe de
         * tout chemin — le montage racine doit pouvoir matcher).
         */
        private fun normaliser(chemin: String): String =
            when {
                chemin == "/" -> ""
                chemin.length > 1 && chemin.endsWith('/') -> chemin.dropLast(1)
                else -> chemin
            }

        /** `a/b/c` est-il sous `a/b` ? (préfixe par segments entiers). */
        private fun prefixeParSegments(
            chemin: String,
            prefixe: String,
        ): Boolean =
            when {
                chemin == prefixe -> true
                prefixe.isEmpty() -> true
                else -> chemin.startsWith("$prefixe/")
            }

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

        /** Champs d'une ligne `/proc/self/mounts` : périph, point, type, options, dump, pass. */
        private const val CHAMPS_MONTS = 6

        /** Nombre minimal de champs exploitables d'une ligne de montages. */
        private const val MINIMUM_CHAMPS_MONTS = 3
    }

    /**
     * Traduction d'une exécution brute en résultat métier (succès =
     * stdout, échec = message + stderr).
     */
    private fun ExecutionGitBrute.versResultat(): ResultatGit<String> =
        when {
            messageEchec != null -> {
                ResultatGit.Echec(messageEchec ?: "", "")
            }

            code == 0 -> {
                ResultatGit.Succes(stdout)
            }

            else -> {
                ResultatGit.Echec(
                    message = stderr.ifBlank { "git a échoué (code $code)" },
                    sortieErreur = stderr,
                )
            }
        }
}
