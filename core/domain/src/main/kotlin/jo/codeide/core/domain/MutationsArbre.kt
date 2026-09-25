package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import javax.inject.Inject

/**
 * Noms anti-collision du presse-papiers de l'explorateur (étape 31,
 * ADR 0030 § coller) : la première collision prend le suffixe
 * « (copie) », les suivantes « (copie 2) », « (copie 3) »… — la
 * comparaison est insensible à la casse (même règle que le
 * pré-contrôle d'homonyme SAF).
 *
 * Exemple : coller « docs » dans un dossier contenant déjà « Docs »
 * crée « docs (copie) » ; un deuxième collage crée « docs (copie 2) ».
 */
public object NomsCopies {
    /** Suffixe de la première collision. */
    private const val SUFFIXE = " (copie)"

    /**
     * Nom garanti absent de [existants] : [nom] tel quel s'il est libre,
     * sinon le premier « nom (copie) » / « nom (copie N) » disponible
     * (insensible à la casse).
     */
    public fun prochain(
        nom: String,
        existants: Collection<String>,
    ): String {
        val occupes = existants.mapTo(HashSet()) { it.lowercase() }
        if (nom.lowercase() !in occupes) return nom
        var rang = 1
        while (true) {
            // « nom (copie) » puis « nom (copie 2) », « nom (copie 3) »…
            // (§ 11 : le numéro vit DANS la parenthèse).
            val candidat = if (rang == 1) "$nom$SUFFIXE" else "$nom (copie $rang)"
            if (candidat.lowercase() !in occupes) return candidat
            rang++
        }
    }
}

/**
 * Instantané mémoire d'un document (fichier ou dossier entier), pris
 * **avant** une suppression pour permettre l'annulation (étape 31,
 * § 11 « Supprimer » : « Annuler » restaure l'élément à sa place, y
 * compris ses onglets).
 *
 * Les octets sont portés par un [ByteArray] volontairement brut : un
 * instantané vit quelques secondes (jusqu'au masquage du snackbar) et
 * doit rester léger pour les gros fichiers — [equals] et [hashCode]
 * sont écrits à la main pour que la comparaison porte sur le contenu
 * (detekt « ArrayInDataClass » interdit les tableaux dans une data
 * class).
 *
 * @property nom nom du document (dernier segment).
 * @property estDossier `true` pour un dossier (récursif via [enfants]).
 * @property octets contenu binaire d'un fichier, `null` pour un dossier.
 * @property enfants sous-instantanés dans l'ordre du tri ADR 0027.
 */
public class ArbreMemoire(
    public val nom: String,
    public val estDossier: Boolean,
    public val octets: ByteArray?,
    public val enfants: List<ArbreMemoire> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArbreMemoire) return false
        return nom == other.nom &&
            estDossier == other.estDossier &&
            octets.contentEquals(other.octets) &&
            enfants == other.enfants
    }

    override fun hashCode(): Int {
        var resultat = nom.hashCode()
        resultat = 31 * resultat + estDossier.hashCode()
        resultat = 31 * resultat + (octets?.contentHashCode() ?: 0)
        resultat = 31 * resultat + enfants.hashCode()
        return resultat
    }

    override fun toString(): String = "ArbreMemoire(nom=$nom, estDossier=$estDossier, enfants=${enfants.size})"
}

/**
 * Lit un document (fichier ou dossier entier) en mémoire — l'instantané
 * de l'annulation d'une suppression (étape 31).
 *
 * Le système de fichiers est **paramètre** (projet SAF ou privé) : le même
 * use case sert les deux arbres de l'explorateur, la stratégie d'accès
 * reste le choix de l'appelant (§ 19 de la spécification v2).
 *
 * L'arborescence est parcourue récursivement dans l'ordre du tri de
 * l'explorateur (dossiers d'abord, puis nom insensible à la casse,
 * ADR 0027) : la restauration réinsère les enfants dans le même ordre.
 */
public class LireArbreUseCase
    @Inject
    constructor() {
        /** Lit [uri] et tout son contenu via [fichiers] ; échoue si le
         * document disparaît en cours de lecture. */
        public suspend operator fun invoke(
            fichiers: FileSystem,
            uri: String,
        ): AppResult<ArbreMemoire> {
            val statut =
                when (val resultat = fichiers.stat(uri)) {
                    is AppResult.Success -> resultat.value
                    is AppResult.Failure -> return resultat
                }
            return if (statut.isDirectory) {
                when (val enfants = enfantsTries(fichiers, uri)) {
                    is AppResult.Success -> {
                        AppResult.Success(
                            ArbreMemoire(nom = statut.name, estDossier = true, octets = null, enfants = enfants.value),
                        )
                    }

                    is AppResult.Failure -> {
                        enfants
                    }
                }
            } else {
                when (val lecture = fichiers.readBytes(uri)) {
                    is AppResult.Success -> {
                        AppResult.Success(ArbreMemoire(nom = statut.name, estDossier = false, octets = lecture.value))
                    }

                    is AppResult.Failure -> {
                        lecture
                    }
                }
            }
        }

        /** Enfants d'un dossier dans l'ordre ADR 0027 (dossiers, puis nom). */
        private suspend fun enfantsTries(
            fichiers: FileSystem,
            uriDossier: String,
        ): AppResult<List<ArbreMemoire>> {
            val liste =
                when (val resultat = fichiers.list(uriDossier)) {
                    is AppResult.Success -> resultat.value
                    is AppResult.Failure -> return resultat
                }
            val tries = liste.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            val instantanes = ArrayList<ArbreMemoire>(tries.size)
            var echec: AppResult.Failure? = null
            for (enfant in tries) {
                when (val instantane = this(fichiers, enfant.uri)) {
                    is AppResult.Success -> {
                        instantanes += instantane.value
                    }

                    is AppResult.Failure -> {
                        echec = instantane
                        break
                    }
                }
            }
            return echec ?: AppResult.Success(instantanes)
        }
    }

/**
 * Restaure un [ArbreMemoire] dans un dossier parent — l'action
 * « Annuler » d'une suppression (étape 31) : le document est recréé à
 * son nom d'origine (suffixe anti-collision si un homonyme est apparu
 * depuis), contenu et récursivité compris.
 */
public class RestaurerArbreUseCase
    @Inject
    constructor() {
        /**
         * Recrée [arbre] dans [uriDossierParent] via [fichiers] ; retourne
         * l'URI du document restauré (suffixée si un homonyme est apparu).
         */
        public suspend operator fun invoke(
            fichiers: FileSystem,
            uriDossierParent: String,
            arbre: ArbreMemoire,
        ): AppResult<String> {
            val nomVoulu = nomDisponible(fichiers, arbre.nom, uriDossierParent)
            return if (arbre.estDossier) {
                restaurerDossier(fichiers, uriDossierParent, nomVoulu, arbre.enfants)
            } else {
                restaurerFichier(fichiers, uriDossierParent, nomVoulu, arbre)
            }
        }

        /** Nom garanti sans collision dans le parent (§ 11). */
        private suspend fun nomDisponible(
            fichiers: FileSystem,
            nom: String,
            uriDossierParent: String,
        ): String =
            when (val existants = fichiers.list(uriDossierParent)) {
                is AppResult.Success -> NomsCopies.prochain(nom, existants.value.map { it.name })
                is AppResult.Failure -> nom
            }

        /** Recrée un dossier puis ses enfants, un par un. */
        private suspend fun restaurerDossier(
            fichiers: FileSystem,
            uriDossierParent: String,
            nomVoulu: String,
            enfants: List<ArbreMemoire>,
        ): AppResult<String> {
            val dossier = fichiers.createDirectory(uriDossierParent, nomVoulu)
            if (dossier is AppResult.Failure) return dossier
            var echec: AppResult.Failure? = null
            for (enfant in enfants) {
                val restauration = this(fichiers, (dossier as AppResult.Success).value, enfant)
                if (restauration is AppResult.Failure) {
                    echec = restauration
                    break
                }
            }
            return echec ?: AppResult.Success((dossier as AppResult.Success).value)
        }

        /** Recrée un fichier et son contenu. */
        private suspend fun restaurerFichier(
            fichiers: FileSystem,
            uriDossierParent: String,
            nomVoulu: String,
            arbre: ArbreMemoire,
        ): AppResult<String> {
            val creation = fichiers.createFile(uriDossierParent, nomVoulu, mimeFichierTexte(arbre.nom))
            if (creation is AppResult.Failure) return creation
            val ecriture = fichiers.writeBytes((creation as AppResult.Success).value, arbre.octets ?: ByteArray(0))
            return if (ecriture is AppResult.Failure) {
                ecriture
            } else {
                AppResult.Success(
                    (creation as AppResult.Success).value,
                )
            }
        }
    }

/**
 * Copie un document (fichier ou dossier entier) dans un dossier cible
 * (étape 31, presse-papiers « copier » puis « coller ») : le nom prend
 * le suffixe anti-collision de [NomsCopies] si un homonyme existe déjà
 * dans la cible, la récursion suit le même tri que l'explorateur.
 */
public class CopierArbreUseCase
    @Inject
    constructor() {
        /**
         * Copie [uriSource] dans [uriDossierCible] via [fichiers] (projet SAF
         * ou privé — le port est fourni par l'appelant) ; retourne l'URI de
         * la copie. Le nom prend le suffixe anti-collision de [NomsCopies] si
         * un homonyme existe déjà dans la cible, la récursion suit le même
         * tri que l'explorateur.
         */
        public suspend operator fun invoke(
            fichiers: FileSystem,
            uriSource: String,
            uriDossierCible: String,
        ): AppResult<String> {
            val statut =
                when (val resultat = fichiers.stat(uriSource)) {
                    is AppResult.Success -> resultat.value
                    is AppResult.Failure -> return resultat
                }
            val nomVoulu = nomDisponible(fichiers, statut.name, uriDossierCible)
            return if (statut.isDirectory) {
                copierDossier(fichiers, uriSource, uriDossierCible, nomVoulu)
            } else {
                copierFichier(fichiers, uriSource, uriDossierCible, nomVoulu, statut.name)
            }
        }

        /** Nom garanti sans collision dans la cible (§ 11) — le nom brut
         *  si l'énumération de la cible échoue (la création tranchera). */
        private suspend fun nomDisponible(
            fichiers: FileSystem,
            nom: String,
            uriDossierCible: String,
        ): String =
            when (val existants = fichiers.list(uriDossierCible)) {
                is AppResult.Success -> NomsCopies.prochain(nom, existants.value.map { it.name })
                is AppResult.Failure -> nom
            }

        /** Copie un dossier : création puis récursion sur les enfants triés. */
        private suspend fun copierDossier(
            fichiers: FileSystem,
            uriSource: String,
            uriDossierCible: String,
            nomVoulu: String,
        ): AppResult<String> {
            val dossier = fichiers.createDirectory(uriDossierCible, nomVoulu)
            val enfants =
                when (val resultat = fichiers.list(uriSource)) {
                    is AppResult.Success -> resultat.value
                    is AppResult.Failure -> return resultat
                }
            var echec: AppResult.Failure? = (dossier as? AppResult.Failure)
            if (echec == null) {
                val cible = (dossier as AppResult.Success).value
                for (enfant in enfants.sortedBy { it.name }) {
                    val copie = this(fichiers, enfant.uri, cible)
                    if (copie is AppResult.Failure) {
                        echec = copie
                        break
                    }
                }
            }
            return echec ?: AppResult.Success((dossier as AppResult.Success).value)
        }

        /** Copie un fichier : création puis octets. */
        private suspend fun copierFichier(
            fichiers: FileSystem,
            uriSource: String,
            uriDossierCible: String,
            nomVoulu: String,
            nomOriginal: String,
        ): AppResult<String> {
            val creation = fichiers.createFile(uriDossierCible, nomVoulu, mimeFichierTexte(nomOriginal))
            if (creation is AppResult.Failure) return creation
            val copie = copierOctets(fichiers, uriSource, (creation as AppResult.Success).value)
            return if (copie is AppResult.Failure) copie else AppResult.Success((creation as AppResult.Success).value)
        }

        /** Lit la source puis écrit la copie (fichier seul). */
        private suspend fun copierOctets(
            fichiers: FileSystem,
            uriSource: String,
            uriCopie: String,
        ): AppResult<Unit> =
            when (val lecture = fichiers.readBytes(uriSource)) {
                is AppResult.Success -> fichiers.writeBytes(uriCopie, lecture.value)
                is AppResult.Failure -> lecture
            }
    }

/**
 * Déplace un document (fichier ou dossier entier) vers un dossier
 * cible (étape 31 : « couper » puis « coller », ou « Déplacer vers… »)
 * : la copie suit [CopierArbreUseCase] (suffixe anti-collision) puis
 * la source est supprimée — le déplacement SAF par renommage est peu
 * fiable entre parents selon les fournisseurs, la copie-suppression
 * est le chemin robuste multi-fournisseurs.
 */
public class DeplacerArbreUseCase
    @Inject
    constructor() {
        /**
         * Déplace [uriSource] dans [uriDossierCible] via [fichiers] ; retourne
         * l'URI du document à sa nouvelle place. La suppression de la source
         * n'a lieu qu'après une copie complète réussie (jamais de perte).
         */
        public suspend operator fun invoke(
            fichiers: FileSystem,
            uriSource: String,
            uriDossierCible: String,
        ): AppResult<String> {
            val copieur = CopierArbreUseCase()
            return when (val copie = copieur(fichiers, uriSource, uriDossierCible)) {
                is AppResult.Success -> {
                    when (fichiers.delete(uriSource)) {
                        is AppResult.Success -> {
                            AppResult.Success(copie.value)
                        }

                        is AppResult.Failure -> {
                            AppResult.Failure(
                                AppError.Storage(
                                    reason = AppError.StorageReason.Io,
                                    details = "la copie a réussi mais la source n'a pas été supprimée",
                                ),
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    copie
                }
            }
        }
    }
