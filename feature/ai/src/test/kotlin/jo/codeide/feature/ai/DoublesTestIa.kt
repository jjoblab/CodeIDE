package jo.codeide.feature.ai

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.FabriqueFournisseurIa
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.ConversationIa
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

// Doubles de test de la mission IA (lot IA-2) — en mémoire, aucun
// secret réel (valeurs factices évidentes, règle 10 du plan).

/** Réglages IA en mémoire : état mutable contrôlé par les tests. */
internal class FakeReglagesIa(valeur: AiSettings = AiSettings()) : ReglagesIaRepository {
    val flux = MutableStateFlow(valeur)
    var ecritures = 0

    override fun observeAiSettings(): Flow<AiSettings> = flux

    override suspend fun courant(): AppResult<AiSettings> = AppResult.Success(flux.value)

    override suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit> {
        flux.value = update(flux.value)
        ecritures++
        return AppResult.Success(Unit)
    }
}

/** Coffre en mémoire : suit le port à la lettre (apercu, perte simulable). */
internal class FakeCoffre : CoffreClesIa {
    /** Clés stockées en clair (test) + drapeau « maître perdue ». */
    private val cles = mutableMapOf<String, String>()
    private val apercus = mutableMapOf<String, String>()
    var maitrePerdue = false
    var dernieresClesEnregistrees = mutableListOf<CharArray>()

    override suspend fun enregistrer(identifiant: String, cle: CharArray): AppResult<Unit> {
        dernieresClesEnregistrees.add(cle.copyOf())
        cles[identifiant] = cle.concatToString()
        apercus[identifiant] =
            if (cle.size <= 4) "•".repeat(cle.size) else "••••" + cle.concatToString().takeLast(4)
        cle.fill('\u0000')
        return AppResult.Success(Unit)
    }

    override suspend fun lire(identifiant: String): AppResult<CharArray> = when {
        !cles.containsKey(identifiant) -> AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
        maitrePerdue -> AppResult.Failure(AppError.Storage(AppError.StorageReason.KeyUnavailable, "test"))
        else -> AppResult.Success(cles.getValue(identifiant).toCharArray())
    }

    override suspend fun supprimer(identifiant: String): AppResult<Unit> {
        cles.remove(identifiant)
        apercus.remove(identifiant)
        return AppResult.Success(Unit)
    }

    override suspend fun contient(identifiant: String): Boolean = cles.containsKey(identifiant)

    override suspend fun apercu(identifiant: String): String? = apercus[identifiant]
}

/** Fournisseur factice : compte les sollicitations (jamais de réseau). */
internal class FauxFournisseur : AiProvider {
    var appelsStream = 0
    var appelsPing = 0
    var appelsListModels = 0

    override fun stream(request: AiRequest): Flow<AiEvent> {
        appelsStream++
        return flowOf(AiEvent.Failure(AppError.Unknown("hors périmètre")))
    }

    override suspend fun listModels(): AppResult<List<AiModelInfo>> {
        appelsListModels++
        return AppResult.Success(listOf(AiModelInfo("modele-test-1"), AiModelInfo("modele-test-2")))
    }

    override suspend fun ping(): AppResult<Unit> {
        appelsPing++
        return AppResult.Success(Unit)
    }
}

/** Fabrique factice : renvoie le fournisseur factice ou `null` (inconnu). */
internal class FauxFabrique : FabriqueFournisseurIa {
    val fournisseur = FauxFournisseur()
    var constructions = 0
    var dernieresReglages: AiSettings? = null

    override suspend fun creer(reglages: AiSettings): AiProvider? {
        constructions++
        dernieresReglages = reglages
        return if (reglages.fournisseurActif ==
            jo.codeide.core.domain.FournisseursIa.OPENAI_COMPATIBLE
        ) {
            null
        } else {
            fournisseur
        }
    }
}

/**
 * Fournisseur de contexte en mémoire (IA-4) : fichiers, chemins et
 * `ia-ignore` contrôlés par les tests — aucun disque. Ouvert (rapport
 * IA, lot 3) : les tests de la recherche surchargent `listerChemins`
 * pour éprouver le projet fermé (échec du port).
 */
internal open class FauxFournisseurContexte : jo.codeide.core.ai.FournisseurContexteProjet {
    val fichiers = mutableMapOf<String, String>()
    var chemins: List<String> = emptyList()
    var iaIgnore: List<String> = emptyList()
    var lectures = 0
    var listages = 0
    var recherches = 0
    var lecturesLignes = 0

    override suspend fun lireFichier(cheminRelatif: String): AppResult<String> {
        lectures++
        return fichiers[cheminRelatif]
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "absent"))
    }

    override suspend fun listerChemins(): AppResult<List<String>> {
        listages++
        return AppResult.Success(chemins)
    }

    override suspend fun lireIaIgnore(): List<String> = iaIgnore

    /** Recherche littérale dans la MAP des fichiers (miroir honnête du port réel). */
    override suspend fun rechercherTexte(
        motif: String,
        limite: Int,
    ): AppResult<List<jo.codeide.core.ai.OccurrenceRechercheIa>> {
        recherches++
        return AppResult.Success(occurrencesDe(motif, limite))
    }

    /** Scan plat : un fichier à la fois, occurrences plafonnées. */
    private fun occurrencesDe(motif: String, limite: Int): List<jo.codeide.core.ai.OccurrenceRechercheIa> {
        if (motif.isEmpty()) {
            return emptyList()
        }
        val occurrences = mutableListOf<jo.codeide.core.ai.OccurrenceRechercheIa>()
        for ((chemin, contenu) in fichiers) {
            ajouterOccurrences(chemin, contenu, motif, limite, occurrences)
        }
        return occurrences
    }

    /** Ajoute les occurrences d'un fichier (une par ligne porteuse). */
    private fun ajouterOccurrences(
        chemin: String,
        contenu: String,
        motif: String,
        limite: Int,
        occurrences: MutableList<jo.codeide.core.ai.OccurrenceRechercheIa>,
    ) {
        for ((indice, ligne) in contenu.lines().withIndex()) {
            if (occurrences.size < limite && ligne.contains(motif)) {
                occurrences.add(jo.codeide.core.ai.OccurrenceRechercheIa(chemin, indice + 1, ligne.trim().take(200)))
            }
        }
    }

    /** Lecture d'un intervalle de lignes numérotées (miroir honnête du port réel). */
    override suspend fun lireLignes(cheminRelatif: String, debut: Int, fin: Int): AppResult<String> {
        lecturesLignes++
        val contenu = fichiers[cheminRelatif]
        return if (contenu == null) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "absent"))
        } else {
            val lignes = contenu.lines()
            if (debut > lignes.size) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "plage hors fichier"))
            } else {
                val finBornee = fin.coerceAtMost(lignes.size)
                AppResult.Success((debut..finBornee).joinToString("\n") { "$it| ${lignes[it - 1]}" })
            }
        }
    }
}

/** Horloge figée : temps contrôlé (exigence section 12.5). */
internal val HorlogeFixe = TimeProvider { 1_000_000L }

/**
 * Historique en mémoire (IA-6) : conversations et messages listés,
 * opérations comptées — cascade simulée (supprimer vide la conversation
 * ET ses messages), purge bornée appliquée sur les dates.
 */
internal class FauxConversationStore : ConversationStore {
    val conversations = mutableMapOf<String, ConversationIa>()
    val messagesParConversation = mutableMapOf<String, MutableList<MessageConversationIa>>()
    var purges = 0
    var retentionDerniere: RetentionIa? = null

    /** Compteur d'appels ajouterUsage (Lot 5, point 4 — conversations
     *  inconnues ignorées, tests du chat). */
    var usagesAccumules = 0

    override fun conversations(recherche: String): Flow<List<ConversationIa>> = kotlinx.coroutines.flow.flow {
        // Miroir du tri SQL (rapport IA, lot 3) : épinglées d'abord,
        // puis de la plus récente à la plus ancienne.
        val liste =
            conversations.values
                .filter { recherche.isBlank() || it.titre.contains(recherche, ignoreCase = true) }
                .sortedWith(compareByDescending<ConversationIa> { it.epingle }.thenByDescending { it.modifieA })
        emit(liste)
    }

    override suspend fun messages(conversationId: String): List<MessageConversationIa> =
        messagesParConversation[conversationId].orEmpty()

    override suspend fun nombreMessages(conversationId: String): Int =
        messagesParConversation[conversationId].orEmpty().size

    override suspend fun ajouterMessage(conversationId: String, modele: String, message: MessageConversationIa) {
        if (conversationId !in conversations) {
            conversations[conversationId] =
                ConversationIa(
                    identifiant = conversationId,
                    titre = message.texte.take(60),
                    modele = modele,
                    creeA = message.creeA,
                    modifieA = message.creeA,
                )
            messagesParConversation[conversationId] = mutableListOf()
        }
        messagesParConversation.getValue(conversationId).add(message)
        conversations[conversationId] =
            conversations.getValue(conversationId).copy(modifieA = message.creeA)
    }

    override suspend fun ajouterUsage(conversationId: String, jetonsEntree: Long, jetonsSortie: Long) {
        val existante = conversations[conversationId] ?: return
        usagesAccumules += 1
        conversations[conversationId] =
            existante.copy(
                jetonsEntree = existante.jetonsEntree + jetonsEntree,
                jetonsSortie = existante.jetonsSortie + jetonsSortie,
            )
    }

    override suspend fun conversationParId(conversationId: String): ConversationIa? = conversations[conversationId]

    override suspend fun renommer(conversationId: String, titre: String): Int {
        val existante = conversations[conversationId] ?: return 0
        conversations[conversationId] = existante.copy(titre = titre)
        return 1
    }

    override suspend fun supprimer(conversationId: String): Int {
        val retiree = conversations.remove(conversationId)
        messagesParConversation.remove(conversationId)
        return if (retiree == null) 0 else 1
    }

    override suspend fun epingler(conversationId: String, epingle: Boolean) {
        val existante = conversations[conversationId] ?: return
        conversations[conversationId] = existante.copy(epingle = epingle)
    }

    override suspend fun restaurer(conversation: ConversationIa, messages: List<MessageConversationIa>) {
        conversations[conversation.identifiant] = conversation
        messagesParConversation[conversation.identifiant] = messages.toMutableList()
    }

    override suspend fun toutSupprimer() {
        conversations.clear()
        messagesParConversation.clear()
    }

    override suspend fun purgerPerimees(retention: RetentionIa) {
        purges++
        retentionDerniere = retention
        if (retention.jours <= 0) return
        val borne = System.currentTimeMillis() - retention.jours * 24L * 60 * 60 * 1000
        conversations
            .filterValues { it.modifieA < borne }
            .keys
            .toList()
            .forEach { id ->
                conversations.remove(id)
                messagesParConversation.remove(id)
            }
    }
}
