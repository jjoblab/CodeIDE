package jo.codeide.core.ai

import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.ValidationUrlIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Fournisseur **actif** (mission IA, lot IA-2) : la seule porte d'entrée
 * IA de l'application, qui applique dans l'ordre (ADR 0121 point 3,
 * critère IA-2 « sans consentement, aucune requête ne part ») :
 *
 * 1. **garde d'accès** — assistant désactivé ou consentement absent ⇒
 *    `AppError.Ai(ConsentRequired)` AVANT toute construction de fournisseur ;
 * 2. **garde de configuration** — fournisseur inconnu, URL invalide ou
 *    modèle vide ⇒ `AppError.Ai(Configuration)` ;
 * 3. **délégation** — le fournisseur concret (fabrique) émet le flux réel ;
 *    le modèle des réglages fait autorité (jamais un nom codé en dur,
 *    ADR 0123 point 6).
 *
 * Testé en JVM pur (fabrique et réglages factices) — critère IA-2 :
 * sans consentement, la fabrique n'est **jamais** sollicitée.
 */
public class FournisseurIaActif
@Inject
constructor(
    private val reglages: ReglagesIaRepository,
    private val fabrique: FabriqueFournisseurIa,
) : AiProvider {
    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        when (val acces = acceder()) {
            is AccesIa.Refuse -> emit(AiEvent.Failure(acces.erreur))
            is AccesIa.Accorde -> emitAll(acces.fournisseur.stream(request.copy(model = acces.modele)))
        }
    }

    override suspend fun listModels(): AppResult<List<AiModelInfo>> = when (val acces = acceder()) {
        is AccesIa.Refuse -> AppResult.Failure(acces.erreur)
        is AccesIa.Accorde -> acces.fournisseur.listModels()
    }

    override suspend fun ping(): AppResult<Unit> = when (val acces = acceder()) {
        is AccesIa.Refuse -> AppResult.Failure(acces.erreur)
        is AccesIa.Accorde -> acces.fournisseur.ping()
    }

    /**
     * Garde d'accès + garde de configuration, dans cet ordre — le refus
     * court-circuite toute construction de fournisseur et toute requête.
     *
     * `@Suppress("ReturnCount")` justifiée : la fonction EST la chaîne
     * de gardes — un retour par refus (consentement, URL, modèle,
     * fournisseur) est la lisibilité visée (même motif que les tables
     * d'incidents du dépôt).
     */
    @Suppress("ReturnCount")
    private suspend fun acceder(): AccesIa {
        val courant = lireReglages()
        val refusAcces = refuserAcces(courant)
        if (refusAcces != null) {
            return AccesIa.Refuse(refusAcces)
        }
        val refusConfiguration = refuserConfiguration(courant)
        if (refusConfiguration != null) {
            return AccesIa.Refuse(refusConfiguration)
        }
        val refusModele = refuserModele(courant)
        if (refusModele != null) {
            return AccesIa.Refuse(refusModele)
        }
        val adaptateur = fabrique.creer(courant)
        if (adaptateur == null) {
            return AccesIa.Refuse(AppError.Ai(AppError.AiReason.Configuration, "fournisseur inconnu"))
        }
        return AccesIa.Accorde(adaptateur, courant.modeleActif)
    }

    /** Lecture des réglages — un échec de stockage reste modélisé. */
    private suspend fun lireReglages(): AiSettings = when (val lecture = reglages.courant()) {
        is AppResult.Success -> lecture.value
        is AppResult.Failure -> AiSettings()
    }

    /** Garde 1 : assistant actif ET consentement donné (ADR 0121 point 3). */
    private fun refuserAcces(reglages: AiSettings): AppError? {
        if (reglages.assistantActive && reglages.consentementDonne) {
            return null
        }
        return AppError.Ai(
            AppError.AiReason.ConsentRequired,
            "assistant inactif ou consentement absent",
        )
    }

    /** Garde 2 : URL de base valide (HTTPS, boucle locale admise). */
    private fun refuserConfiguration(reglages: AiSettings): AppError? {
        if (ValidationUrlIa.erreur(reglages.urlBaseActive) == null) {
            return null
        }
        return AppError.Ai(
            AppError.AiReason.Configuration,
            "URL de base absente ou invalide",
        )
    }

    /** Garde 3 : modèle configuré (jamais codé en dur — ADR 0123 §6). */
    private fun refuserModele(reglages: AiSettings): AppError? {
        if (reglages.modeleActif.isNotBlank()) {
            return null
        }
        return AppError.Ai(
            AppError.AiReason.Configuration,
            "aucun modèle configuré",
        )
    }
}

/** Verdict de [FournisseurIaActif.acceder] — le refus court-circuite tout. */
private sealed interface AccesIa {
    /** Accès refusé (consentement, configuration) — aucune requête ne part. */
    public data class Refuse(public val erreur: AppError) : AccesIa

    /** Accès accordé : fournisseur construit + modèle des réglages. */
    public data class Accorde(public val fournisseur: AiProvider, public val modele: String) : AccesIa
}
