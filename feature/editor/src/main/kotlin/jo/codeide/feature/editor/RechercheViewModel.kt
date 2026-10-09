package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.OptionsRecherche
import jo.codeide.core.domain.RechercheMoteur
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.ResultatRecherche
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel de la section Recherche du tiroir (mission Recherche S2-S5,
 * ADR 0094).
 *
 * S2 : recherche en direct avec temporisation (≈ 300 ms), résultats
 * groupés par fichier, annulation immédiate quand la requête change.
 *
 * S3 : navigation — toucher une occurrence ouvre le fichier à la ligne,
 * boutons précédent/suivant.
 *
 * S4 : remplacement — champ de remplacement dépliable, regex avec
 * groupes de capture, confirmation avant « tout remplacer ».
 *
 * S5 : Go to File — bascule Texte | Fichiers, correspondance floue,
 * suffixe `:ligne`.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class RechercheViewModel
    @Inject
    constructor(
        private val moteur: RechercheMoteur,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreRepertoire: ResoudreRepertoireProjet,
        @Suppress("UnusedPrivateProperty") // S4 : utilisé dans remplacerDansFichier (évolution future).
        private val fichiers: FileSystem,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val projectId: ProjectId? =
            savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID)?.let { ProjectId(it) }

        private val _etat = MutableStateFlow(EtatRecherche())
        val etat: StateFlow<EtatRecherche> = _etat.asStateFlow()

        private val _effets = Channel<EffetRecherche>(Channel.BUFFERED)
        val effets: Flow<EffetRecherche> = _effets.receiveAsFlow()

        private var jobRecherche: Job? = null

        // ------------------------------------------------------------------
        // S2 — Recherche en direct
        // ------------------------------------------------------------------

        /** Met à jour la requête et lance la recherche (temporisation 300 ms). */
        fun requete(nouveau: String) {
            _etat.value = _etat.value.copy(requete = nouveau, indexOccurrence = -1)
            jobRecherche?.cancel()
            if (nouveau.isBlank()) {
                _etat.value = _etat.value.copy(resultats = emptyList(), chargement = false)
                return
            }
            if (_etat.value.modeFichiers) {
                jobRecherche =
                    viewModelScope.launch {
                        delay(DELAI_RECHERCHE_MS)
                        lancerRechercheFichiers()
                    }
            } else {
                jobRecherche =
                    viewModelScope.launch {
                        delay(DELAI_RECHERCHE_MS)
                        lancerRecherche()
                    }
            }
        }

        fun basculerCasse() {
            _etat.value = _etat.value.copy(ignorerCasse = !_etat.value.ignorerCasse)
            relancer()
        }

        fun basculerMotEntier() {
            _etat.value = _etat.value.copy(motEntier = !_etat.value.motEntier)
            relancer()
        }

        fun basculerRegex() {
            _etat.value = _etat.value.copy(regex = !_etat.value.regex)
            relancer()
        }

        fun effacer() {
            requete("")
        }

        // ------------------------------------------------------------------
        // S3 — Navigation
        // ------------------------------------------------------------------

        /** Ouvre le fichier à la ligne/colonne de l'occurrence [index]. */
        fun ouvrirOccurrence(index: Int) {
            val resultats = _etat.value.resultats
            if (index !in resultats.indices) return
            _etat.value = _etat.value.copy(indexOccurrence = index)
            val r = resultats[index]
            _effets.trySend(EffetRecherche.OuvrirFichier(r.chemin, r.numeroLigne, r.colonneDebut))
        }

        /** Occurrence précédente. */
        fun occurrencePrecedente() {
            val index = _etat.value.indexOccurrence
            if (index > 0) ouvrirOccurrence(index - 1)
        }

        /** Occurrence suivante. */
        fun occurrenceSuivante() {
            val index = _etat.value.indexOccurrence
            val max = _etat.value.resultats.lastIndex
            if (index in 0 until max) ouvrirOccurrence(index + 1)
        }

        // ------------------------------------------------------------------
        // S4 — Remplacement
        // ------------------------------------------------------------------

        /** Affiche/masque le champ de remplacement. */
        fun basculerRemplacement() {
            _etat.value = _etat.value.copy(afficherRemplacement = !_etat.value.afficherRemplacement)
        }

        /** Met à jour le texte de remplacement. */
        fun texteRemplacement(nouveau: String) {
            _etat.value = _etat.value.copy(texteRemplacement = nouveau)
        }

        /** Remplace une occurrence (celle à [index]). */
        fun remplacerUne(index: Int) {
            val resultats = _etat.value.resultats
            if (index !in resultats.indices) return
            val r = resultats[index]
            viewModelScope.launch {
                remplacerDansFichier(r.chemin, listOf(r))
            }
        }

        /** Remplace toutes les occurrences (après confirmation de l'UI). */
        fun toutRemplacer() {
            val resultats = _etat.value.resultats
            if (resultats.isEmpty()) return
            viewModelScope.launch {
                val parFichier = resultats.groupBy { it.chemin }
                var appliques = 0
                var echecs = 0
                parFichier.forEach { (chemin, occurrences) ->
                    when (remplacerDansFichier(chemin, occurrences)) {
                        true -> appliques += occurrences.size
                        false -> echecs++
                    }
                }
                _effets.trySend(EffetRecherche.RemplacementTermine(appliques, echecs))
                relancer()
            }
        }

        // ------------------------------------------------------------------
        // S5 — Go to File
        // ------------------------------------------------------------------

        /** Bascule entre recherche de texte et recherche de fichiers. */
        fun basculerModeFichiers() {
            _etat.value =
                _etat.value.copy(
                    modeFichiers = !_etat.value.modeFichiers,
                    resultats = emptyList(),
                    indexOccurrence = -1,
                )
            if (_etat.value.requete.isNotBlank()) {
                requete(_etat.value.requete)
            }
        }

        // ------------------------------------------------------------------
        // Logique interne
        // ------------------------------------------------------------------

        private fun relancer() {
            if (_etat.value.requete.isNotBlank()) {
                jobRecherche?.cancel()
                jobRecherche =
                    viewModelScope.launch {
                        if (_etat.value.modeFichiers) lancerRechercheFichiers() else lancerRecherche()
                    }
            }
        }

        @Suppress("ReturnCount")
        private suspend fun lancerRecherche() {
            val id = projectId ?: return
            val projet = observerProjet(id).first() ?: return
            val cheminFuse =
                resoudreRepertoire(projet.location.documentUri) ?: run {
                    _etat.value = _etat.value.copy(chargement = false, erreur = "Chemin inaccessible")
                    return
                }
            _etat.value = _etat.value.copy(chargement = true, resultats = emptyList(), erreur = null)
            val options =
                OptionsRecherche(
                    ignorerCasse = _etat.value.ignorerCasse,
                    motEntier = _etat.value.motEntier,
                    regex = _etat.value.regex,
                )
            val tousResultats = mutableListOf<ResultatRecherche>()
            var fichiersBalayes = 0
            var plafondAtteint = false
            moteur.rechercher(cheminFuse, _etat.value.requete, options).collect { lot ->
                tousResultats.addAll(lot.resultats)
                fichiersBalayes = lot.fichiersBalayes
                plafondAtteint = lot.plafondAtteint
                _etat.value =
                    _etat.value.copy(
                        resultats = tousResultats.toList(),
                        fichiersBalayes = fichiersBalayes,
                        plafondAtteint = plafondAtteint,
                        chargement = !lot.termine,
                    )
            }
        }

        @Suppress("ReturnCount")
        private suspend fun lancerRechercheFichiers() {
            val id = projectId ?: return
            val projet = observerProjet(id).first() ?: return
            val cheminFuse =
                resoudreRepertoire(projet.location.documentUri) ?: return
            _etat.value = _etat.value.copy(chargement = true, resultats = emptyList(), erreur = null)
            val requete = _etat.value.requete
            // Sépare le suffixe :ligne (ex. "Main.kt:42").
            val (nomRecherche, ligne) = parserSuffixeLigne(requete)
            val racine = File(cheminFuse)
            val fichiersTrouves = mutableListOf<ResultatRecherche>()
            racine.walkTopDown().forEach { fichier ->
                if (fichiersTrouves.size >= PLAFOND_FICHIERS) return@forEach
                if (!fichier.isFile) return@forEach
                if (estExcluFichier(fichier, racine)) return@forEach
                val cheminRelatif = fichier.relativeTo(racine).path
                if (correspondanceFloue(nomRecherche, fichier.name, cheminRelatif)) {
                    fichiersTrouves +=
                        ResultatRecherche(
                            chemin = cheminRelatif,
                            numeroLigne = ligne,
                            colonneDebut = 0,
                            colonneFin = 0,
                            extrait = fichier.name,
                            nomFichier = fichier.name,
                        )
                }
            }
            _etat.value =
                _etat.value.copy(
                    resultats = fichiersTrouves.toList(),
                    chargement = false,
                )
        }

        /** Parse le suffixe `:ligne` d'une requête Go to File. */
        internal fun parserSuffixeLigne(requete: String): Pair<String, Int> {
            val match = Regex("^(.+):(\\d+)$").matchEntire(requete)
            if (match != null) {
                return match.groupValues[1] to (match.groupValues[2].toIntOrNull() ?: 0)
            }
            return requete to 0
        }

        /** Correspondance floue : initiales camelCase, segments de chemin, sous-chaîne. */
        @Suppress("ReturnCount")
        internal fun correspondanceFloue(
            requete: String,
            nomFichier: String,
            cheminRelatif: String,
        ): Boolean {
            val r = requete.lowercase()
            val n = nomFichier.lowercase()
            val c = cheminRelatif.lowercase()
            // Sous-chaîne directe.
            if (n.contains(r) || c.contains(r)) return true
            // Initiales camelCase (ex. "MC" → MainController).
            if (requete.length > 1 && requete.first().isLetter()) {
                val initiales = nomFichier.filter { it.isUpperCase() }.lowercase()
                if (initiales.startsWith(r)) return true
            }
            // Segments de chemin (ex. "feat/main" → feature/Main.kt).
            val segmentsRequete = requete.split("/")
            val segmentsChemin = cheminRelatif.split("/")
            if (segmentsRequete.size <= segmentsChemin.size) {
                val dernierSegment = segmentsRequete.last()
                val dernierFichier = segmentsChemin.last().lowercase()
                if (dernierFichier.contains(dernierSegment)) {
                    return segmentsRequete.dropLast(1).all { segment ->
                        segmentsChemin.any { it.contains(segment, ignoreCase = true) }
                    }
                }
            }
            return false
        }

        /** Exclut les dossiers connus (.git, build, .gradle, node_modules). */
        private fun estExcluFichier(
            fichier: File,
            racine: File,
        ): Boolean {
            val relatif = fichier.relativeTo(racine).path
            val exclus = setOf(".git", "build", ".gradle", "node_modules")
            return exclus.any { relatif.startsWith("$it/") || relatif.contains("/$it/") }
        }

        /** Remplace les occurrences dans un fichier (atomique). */
        @Suppress("ReturnCount")
        private suspend fun remplacerDansFichier(
            cheminRelatif: String,
            occurrences: List<ResultatRecherche>,
        ): Boolean {
            val id = projectId ?: return false
            val projet = observerProjet(id).first() ?: return false
            val cheminFuse = resoudreRepertoire(projet.location.documentUri) ?: return false
            val fichier = File(cheminFuse, cheminRelatif)
            if (!fichier.isFile) return false
            val lignes = fichier.readLines().toMutableList()
            val remplacement = _etat.value.texteRemplacement
            val regex = _etat.value.regex
            val requete = _etat.value.requete
            occurrences.forEach { occ ->
                val index = occ.numeroLigne - 1
                if (index in lignes.indices) {
                    val ligne = lignes[index]
                    if (regex) {
                        lignes[index] = ligne.replace(Regex(requete), remplacement)
                    } else {
                        lignes[index] = ligne.replace(requete, remplacement)
                    }
                }
            }
            val temp = File(fichier.parentFile, "${fichier.name}.tmp")
            temp.writeText(lignes.joinToString("\n"))
            return temp.renameTo(fichier)
        }

        private companion object {
            const val DELAI_RECHERCHE_MS = 300L
            const val PLAFOND_FICHIERS = 200
        }
    }

/** Effets ponctuels de la section Recherche. */
sealed interface EffetRecherche {
    /** S3 : ouvrir un fichier à une ligne/colonne. */
    data class OuvrirFichier(
        val chemin: String,
        val ligne: Int,
        val colonne: Int,
    ) : EffetRecherche

    /** S4 : remplacement terminé (N appliqués, M échecs). */
    data class RemplacementTermine(
        val appliques: Int,
        val echecs: Int,
    ) : EffetRecherche
}

/**
 * État observable de la section Recherche.
 */
data class EtatRecherche(
    val requete: String = "",
    val resultats: List<ResultatRecherche> = emptyList(),
    val chargement: Boolean = false,
    val ignorerCasse: Boolean = false,
    val motEntier: Boolean = false,
    val regex: Boolean = false,
    val fichiersBalayes: Int = 0,
    val plafondAtteint: Boolean = false,
    val erreur: String? = null,
    val indexOccurrence: Int = -1,
    val afficherRemplacement: Boolean = false,
    val texteRemplacement: String = "",
    val modeFichiers: Boolean = false,
) {
    val nbFichiers: Int get() = resultats.map { it.chemin }.toSet().size

    val resume: String
        get() =
            if (resultats.isEmpty()) {
                ""
            } else {
                "${resultats.size} résultat" +
                    "${if (resultats.size > 1) "s" else ""} dans $nbFichiers fichier" +
                    "${if (nbFichiers > 1) "s" else ""}"
            }

    val occurrencePrecedentePossible: Boolean get() = indexOccurrence > 0

    val occurrenceSuivantePossible: Boolean
        get() = indexOccurrence in 0 until resultats.lastIndex

    val remplacementPossible: Boolean get() = texteRemplacement.isNotEmpty() && resultats.isNotEmpty()
}
