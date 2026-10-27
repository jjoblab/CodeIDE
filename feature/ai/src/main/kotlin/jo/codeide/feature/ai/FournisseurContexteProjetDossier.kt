package jo.codeide.feature.ai

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.ai.OccurrenceRechercheIa
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liaison Hilt du fournisseur de contexte projet (mission IA, lot IA-4,
 * ADR 0122) : le port vit dans `core:ai`, l'implémentation ici — le
 * dossier réel est publié par l'éditeur dans [RegistreDossierProjetIa]
 * (miroir du port d'éditeur actif), `:core:ai` ne touche jamais le disque.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ContexteIaModule {
    @Binds
    internal abstract fun lierFournisseurContexteProjet(
        impl: FournisseurContexteProjetDossier,
    ): FournisseurContexteProjet
}

/**
 * Lecteur du projet adossé au dossier réel (mission IA, lot IA-4) :
 * garde d'évasion (chemin canonique SOUS la racine — `..` et liens
 * sortants refusés), contenus bornés (troncature documentée — la puce
 * reflète le contenu tronqué EFFECTIVEMENT envoyé), balayage élagué
 * (dossiers lourds sautés au fil du parcours, comptes plafonnés).
 */
@Singleton
internal class FournisseurContexteProjetDossier
@Inject
constructor(
    private val registreDossier: RegistreDossierProjetIa,
    private val repartiteurs: DispatcherProvider,
) : FournisseurContexteProjet {
    override suspend fun lireFichier(cheminRelatif: String): AppResult<String> = withContext(repartiteurs.io) {
        val racine = racineSur() ?: return@withContext echecAucunProjet()
        // Garde d'évasion : le chemin canonique doit rester SOUS
        // la racine canonique (../, liens symboliques sortants).
        val fichier = File(racine, cheminRelatif)
        if (!fichier.canonicalFile.path.startsWith(racine.canonicalFile.path + File.separator)) {
            return@withContext AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, "chemin hors projet"),
            )
        }
        if (!fichier.isFile) {
            return@withContext AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, "fichier absent"),
            )
        }
        try {
            val contenu = fichier.readText()
            AppResult.Success(
                if (contenu.length > MAX_CARACTERES_FICHIER) {
                    contenu.take(MAX_CARACTERES_FICHIER) + MARQUEUR_TRONCATURE
                } else {
                    contenu
                },
            )
        } catch (ignore: java.io.IOException) {
            AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, "lecture impossible"),
            )
        }
    }

    override suspend fun listerChemins(): AppResult<List<String>> = withContext(repartiteurs.io) {
        val racine = racineSur() ?: return@withContext echecAucunProjet()
        val chemins = mutableListOf<String>()
        balayer(racine, racine, 0, chemins)
        AppResult.Success(chemins)
    }

    override suspend fun lireIaIgnore(): List<String> = withContext(repartiteurs.io) {
        val racine = racineSur() ?: return@withContext emptyList()
        val fichier = File(racine, CHEMIN_IA_IGNORE)
        if (fichier.isFile) {
            try {
                fichier.readLines()
            } catch (ignore: java.io.IOException) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    override suspend fun rechercherTexte(motif: String, limite: Int): AppResult<List<OccurrenceRechercheIa>> =
        withContext(repartiteurs.io) {
            if (motif.isEmpty()) {
                return@withContext AppResult.Success(emptyList())
            }
            val racine = racineSur() ?: return@withContext echecAucunProjet()
            AppResult.Success(ChercheurTexteProjetIa(racine).chercher(motif, limite))
        }

    override suspend fun lireLignes(cheminRelatif: String, debut: Int, fin: Int): AppResult<String> =
        withContext(repartiteurs.io) {
            val racine = racineSur() ?: return@withContext echecAucunProjet()
            val fichier = File(racine, cheminRelatif)
            if (!fichier.canonicalFile.path.startsWith(racine.canonicalFile.path + File.separator) ||
                !fichier.isFile
            ) {
                return@withContext AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.NotFound, "fichier absent ou hors projet"),
                )
            }
            try {
                val lignes = fichier.readLines()
                if (debut > lignes.size) {
                    return@withContext AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.NotFound, "plage au-delà de la fin du fichier"),
                    )
                }
                val finBornee = fin.coerceAtMost(lignes.size)
                val debutBorne = debut.coerceAtLeast(1)
                // Plafond de lignes par appel (A5 — maîtrise du contexte).
                val fenetre = (finBornee - debutBorne + 1).coerceAtMost(LIGNES_PAR_APPEL_MAX)
                val texte =
                    (0 until fenetre).joinToString("\n") { indice ->
                        val numero = debutBorne + indice
                        "$numero| ${lignes[numero - 1]}"
                    }
                AppResult.Success(texte)
            } catch (ignore: java.io.IOException) {
                AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.NotFound, "lecture impossible"),
                )
            }
        }

    /** La racine publiée, ou `null` (aucun projet résolu). */
    private fun racineSur(): File? = registreDossier.dossier()?.let(::File)?.takeIf { it.isDirectory }

    private fun echecAucunProjet(): AppResult.Failure = AppResult.Failure(
        AppError.Storage(AppError.StorageReason.NotFound, "aucun projet ouvert"),
    )

    /** Parcours borné : dossiers lourds élagués, plafonds stricts. */
    private fun balayer(racine: File, courant: File, profondeur: Int, accumul: MutableList<String>) {
        val enfants =
            if (profondeur <= PROFONDEUR_MAX && accumul.size < CHEMINS_MAX) {
                courant.listFiles()
            } else {
                null
            }
        for (enfant in enfants.orEmpty()) {
            if (accumul.size >= CHEMINS_MAX) {
                break
            }
            val nom = enfant.name
            if (enfant.isDirectory) {
                if (nom !in DOSSIERS_ELAGUES && !nom.startsWith(".")) {
                    balayer(racine, enfant, profondeur + 1, accumul)
                }
            } else {
                accumul.add(enfant.relativeTo(racine).path)
            }
        }
    }

    private companion object {
        /** Fichier optionnel qui ÉTEND la liste d'exclusion (ADR 0124 §4). */
        const val CHEMIN_IA_IGNORE = ".codeide/ia-ignore"

        /** Contenu maximal lu par fichier — au-delà, tronqué (documenté). */
        const val MAX_CARACTERES_FICHIER = 120_000

        /** Marqueur de troncature joint au contenu borné. */
        const val MARQUEUR_TRONCATURE = "\n[… contenu tronqué — seul cet extrait part vers le modèle]"

        /** Plafonds du balayage (perf appareil) — l'exclusion AUTHORITATIVE reste celle de l'assembleur. */
        const val CHEMINS_MAX = 2_000

        const val PROFONDEUR_MAX = 20

        /** Dossiers sautés au PARCOURS (perf — pas une règle de confidentialité). */
        val DOSSIERS_ELAGUES = setOf("build", ".gradle", ".git", ".idea")

        /** Plafond de lignes par appel `read_lines` (A5 — maîtrise du contexte). */
        const val LIGNES_PAR_APPEL_MAX = 400
    }
}

/**
 * Chercheur TEXTE borné du projet (rapport IA, lot 2, A5) : motif
 * LITTÉRAL, une occurrence par ligne porteuse — dossiers lourds
 * (`.git`, `build`…) et fichiers binaires écartés, occurrences
 * plafonnées, fichiers trop lourds sautés.
 */
internal class ChercheurTexteProjetIa(private val racine: File) {
    /** Cherche le motif (récursif, borné par [limite]). */
    internal fun chercher(motif: String, limite: Int): List<OccurrenceRechercheIa> {
        val occurrences = mutableListOf<OccurrenceRechercheIa>()
        visiter(racine, motif, limite, occurrences)
        return occurrences
    }

    /** Parcours borné : dossiers lourds et binaires écartés, occurrences plafonnées. */
    private fun visiter(courant: File, motif: String, limite: Int, accumul: MutableList<OccurrenceRechercheIa>) {
        val enfants = if (accumul.size < limite) courant.listFiles() else null
        for (enfant in enfants.orEmpty()) {
            if (accumul.size >= limite) {
                return
            }
            val nom = enfant.name
            if (enfant.isDirectory) {
                if (nom !in DOSSIERS_ELAGUES && !nom.startsWith(".")) {
                    visiter(enfant, motif, limite, accumul)
                }
            } else if (!estBinaire(nom) && enfant.length() <= OCTETS_FICHIER_MAX) {
                visiterFichier(enfant, motif, limite, accumul)
            }
        }
    }

    /** Cherche le motif ligne par ligne (une occurrence par ligne porteuse). */
    private fun visiterFichier(
        fichier: File,
        motif: String,
        limite: Int,
        accumul: MutableList<OccurrenceRechercheIa>,
    ) {
        val lignes =
            try {
                fichier.readLines()
            } catch (ignore: java.io.IOException) {
                return
            }
        val chemin = fichier.relativeTo(racine).path
        for ((indice, ligne) in lignes.withIndex()) {
            if (accumul.size >= limite) {
                return
            }
            if (ligne.contains(motif)) {
                accumul.add(
                    OccurrenceRechercheIa(
                        cheminRelatif = chemin,
                        ligne = indice + 1,
                        extrait = ligne.trim().take(EXTRAIT_LIGNE_MAX),
                    ),
                )
            }
        }
    }

    /** Extensions écartées de la recherche (binaires et artefacts). */
    private fun estBinaire(nom: String): Boolean = EXTENSIONS_BLOQUEES.contains(nom.substringAfterLast('.', ""))

    private companion object {
        /** Fichiers plus lourds que ça ne sont pas cherchés (perf appareil). */
        const val OCTETS_FICHIER_MAX = 512L * 1024L

        /** Extrait d'une occurrence de recherche (caractères). */
        const val EXTRAIT_LIGNE_MAX = 200

        /** Extensions binaires/artefacts écartées de la recherche texte (A5). */
        val EXTENSIONS_BLOQUEES =
            setOf(
                "png", "jpg", "jpeg", "gif", "webp", "ico", "apk", "jar", "class",
                "dex", "so", "bin", "zip", "gz", "7z", "pdf", "keystore", "jks",
            )

        /** Dossiers sautés au PARCOURS de recherche (A5). */
        val DOSSIERS_ELAGUES = setOf("build", ".gradle", ".git", ".idea")
    }
}
