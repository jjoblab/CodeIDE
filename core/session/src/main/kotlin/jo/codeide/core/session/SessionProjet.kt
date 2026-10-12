package jo.codeide.core.session

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * États observables de la session de projet (prompt 3 §5.1 — machine à
 * états du contrat de domaine).
 *
 * ```
 *  Fermee ──ouvrir(P)──► Ouverture(P) ──travaux ok──► Ouverte(P)
 *     ▲                        │                        │
 *     │                 travaux échoué              fermer(raison)
 *     │                        │                        │
 *     └─── Fermee ◄── Fermeture(P, Nettoyage) ◄── Fermeture(P, raison)
 * ```
 *
 * Un changement de configuration ne passe JAMAIS par ici (I3) : la
 * session vit hors de l'Activity ; seules les demandes explicites
 * `ouvrir`/`fermer` la font transitionner.
 */
public sealed interface EtatSession {
    /** Aucun projet ouvert — état initial et état après chaque fermeture. */
    public object Fermee : EtatSession

    /** Ouverture en cours : les travaux de démarrage s'exécutent. */
    public data class Ouverture(
        public val projet: ProjectId,
    ) : EtatSession

    /** Projet ouvert : les ressources peuvent s'enregistrer. */
    public data class Ouverte(
        public val projet: ProjectId,
    ) : EtatSession

    /** Fermeture en cours : les ressources se libèrent en ordre inverse. */
    public data class Fermeture(
        public val projet: ProjectId,
        public val raison: RaisonFermeture,
    ) : EtatSession
}

/**
 * Session de projet — le point d'ancrage unique du « projet ouvert »
 * (prompt 3 §5.1, étape B ; ADR 0114).
 *
 * La session est un contrat de DOMAINE : elle ne démarre aucune ressource
 * elle-même — les travaux d'ouverture lui sont fournis par l'appelant
 * (étape C : cas d'usage de l'éditeur), et chaque ressource qui démarre
 * s'enregistre ([enregistrer]) pour être libérée en ordre inverse à la
 * fermeture. Les ViewModels **observent** [etat] ; ils n'en sont plus
 * propriétaires (§5.1).
 *
 * Sémantique des transitions concurrentes (cas limites §9 du prompt) :
 *
 * - **fermeture pendant l'ouverture** : `fermer` attend la fin des travaux
 *   d'ouverture PUIS referme tout ce qui a été démarré ;
 * - **ouverture pendant la fermeture** : `ouvrir` attend la fin de la
 *   fermeture en vol PUIS ouvre (jamais de session intermédiaire) ;
 * - **deux fermetures concurrentes** : sérialisées ; la seconde reçoit le
 *   rapport MÉMORISÉ de la première — les ressources ne sont jamais
 *   libérées deux fois (I2) ;
 * - **réentrance** : tout appel (`ouvrir` ou `fermer`) déclenché depuis
 *   la libération d'un `Fermable` est refusé (`Reentrance`) — jamais
 *   d'interblocage (garde par marque de contexte) ;
 * - **annulation de l'appelant** : les éléments non annulables sont quand
 *   même libérés (I7), l'état repart à `Fermee`, et
 *   [FermetureInterrompue] est relancée (règle projet : l'annulation ne
 *   s'avale jamais) ;
 * - **idempotence de la fermeture** : fermer une session déjà fermée
 *   renvoie le rapport mémorisé de la dernière fermeture, sans rien
 *   refaire.
 *
 * La fermeture doit être lancée depuis une portée qui SURVIT à l'écran
 * demandeur (étape C : portée application) : une fermeture annulée avant
 * d'avoir démarré ne libère rien.
 *
 * @param journal port optionnel de journalisation (seule voie autorisée,
 * règle 14) — `null` dans les tests unitaires purs.
 * @param horloge horloge injectable des durées du rapport de fermeture.
 */
public class SessionProjet(
    private val journal: AppLogger? = null,
    horloge: TimeProvider = TimeProvider { System.currentTimeMillis() },
) {
    private val registre = RegistreFermeture(journal, horloge)
    private val verrou = Mutex()
    private val _etat = MutableStateFlow<EtatSession>(EtatSession.Fermee)

    /** Rapport de la dernière fermeture aboutie (idempotence de [fermer]). */
    @Volatile
    private var dernierRapport: RapportFermeture? = null

    /**
     * État observable de la session — les observateurs (ViewModels de
     * l'étape C) survivent aux transitions (I3).
     */
    public val etat: StateFlow<EtatSession>
        get() = _etat.asStateFlow()

    /**
     * Abonne une ressource à la session — accepté en `Ouverture` ou
     * `Ouverte` (la ressource démarre), refusé sinon.
     *
     * @param fermable la ressource qui démarre.
     * @param delai borne maximale de sa libération (strictement positif).
     * @param annulable `false` = « ne pas annuler » (I7 — flush des
     * sauvegardes en étape C).
     * @return la poignée de désenregistrement, ou l'échec typé.
     */
    public fun enregistrer(
        fermable: Fermable,
        delai: Duration,
        annulable: Boolean = true,
    ): AppResult<Desenregistrement> =
        when (_etat.value) {
            is EtatSession.Ouverture, is EtatSession.Ouverte -> {
                registre.enregistrer(fermable, delai, annulable)
            }

            is EtatSession.Fermee -> {
                AppResult.Failure(
                    AppError.Session(
                        reason = AppError.SessionReason.AucuneSession,
                        details = "aucune session ouverte n'enregistre ${fermable.nom}",
                    ),
                )
            }

            is EtatSession.Fermeture -> {
                AppResult.Failure(
                    AppError.Session(
                        reason = AppError.SessionReason.FermetureEnCours,
                        details = "fermeture en cours, ${fermable.nom} arrive trop tard",
                    ),
                )
            }
        }

    /**
     * Ouvre la session sur [projet] : transition `Fermee → Ouverture →
     * Ouverte`, [travaux] s'exécutent entre les deux.
     *
     * Refusé (échec typé `DejaOuverte`) si une session vit déjà — le
     * changement de projet passe par le gestionnaire (I1) ou par un
     * `fermer()` explicite. En cas d'échec des travaux (échec applicatif
     * ou exception), tout ce qu'ils ont démarré est refermé (`Nettoyage`,
     * ordre inverse, contexte non annulable) et l'état repart à `Fermee` :
     * une session n'est JAMAIS `Ouverte` sur des travaux en échec.
     *
     * @param projet identité du projet ouvert.
     * @param travaux démarrage des ressources (elles s'enregistrent au fil
     * de l'eau) ; renvoie un échec applicatif en cas de refus.
     */
    public suspend fun ouvrir(
        projet: ProjectId,
        travaux: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) },
    ): AppResult<Unit> {
        if (currentCoroutineContext()[MarqueFermeture] != null) {
            return AppResult.Failure(
                AppError.Session(
                    reason = AppError.SessionReason.Reentrance,
                    details = "ouvrir() appelé depuis la libération d'un Fermable",
                ),
            )
        }
        return verrou.withLock {
            when (_etat.value) {
                is EtatSession.Fermee -> {
                    _etat.value = EtatSession.Ouverture(projet)
                }

                else -> {
                    return@withLock AppResult.Failure(
                        AppError.Session(
                            reason = AppError.SessionReason.DejaOuverte,
                            details = "une session vit déjà : ${_etat.value}",
                        ),
                    )
                }
            }
            executerTravauxOuverture(projet, travaux)
        }
    }

    /**
     * Exécute les travaux d'ouverture (le verrou de session est détenu) :
     * tout échec — applicatif, exception ou annulation — referme CE que
     * les travaux ont démarré (Nettoyage, ordre inverse, contexte non
     * annulable) : une session n'est jamais `Ouverte` sur un échec.
     */
    private suspend fun executerTravauxOuverture(
        projet: ProjectId,
        travaux: suspend () -> AppResult<Unit>,
    ): AppResult<Unit> {
        val resultatTravaux =
            try {
                travaux()
            } catch (annulation: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    // annulation née DANS les travaux (appelant actif) : panne
                    val nettoyage = nettoyerApresEchec(projet)
                    AppResult.Failure(
                        AppError.Unknown(
                            "travaux d'ouverture annulés en interne : " +
                                "${annulation.message ?: annulation.javaClass.simpleName} " +
                                "(nettoyage : ${nettoyage.elements.size} élément(s))",
                        ),
                    )
                } else {
                    // annulation de l'APPELANT : refermer ce qui a démarré
                    // (I4) puis relancer (règle projet)
                    nettoyerApresEchec(projet)
                    throw annulation
                }
            } catch (panne: Throwable) {
                val nettoyage = nettoyerApresEchec(projet)
                AppResult.Failure(
                    AppError.Unknown(
                        "travaux d'ouverture en échec : ${panne.message ?: panne.javaClass.simpleName} " +
                            "(nettoyage : ${nettoyage.elements.size} élément(s))",
                    ),
                )
            }
        return when (resultatTravaux) {
            is AppResult.Failure -> {
                // un échec APPLICATIF des travaux referme tout aussi :
                // jamais de session Ouverte sur un démarrage refusé
                val nettoyage = nettoyerApresEchec(projet)
                journal?.w(TAG) {
                    "ouverture de ${projet.value} refusée : nettoyage de " +
                        "${nettoyage.elements.size} élément(s)"
                }
                resultatTravaux
            }

            is AppResult.Success -> {
                _etat.value = EtatSession.Ouverte(projet)
                journal?.i(TAG) { "session ouverte (${projet.value})" }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Ferme la session — `Ouverte → Fermeture → Fermee`, registre libéré
     * en ordre inverse (I2 : idempotente, bornée, tolérante aux pannes).
     *
     * Fermer une session déjà fermée renvoie le rapport MÉMORISÉ sans
     * rien refaire. Une fermeture concurrente attend la première et
     * reçoit le même rapport (mémoïsation). Un appel depuis la libération
     * d'un `Fermable` reçoit `Reentrance` (garde d'interblocage).
     *
     * @param raison pourquoi la session se ferme (remise aux ressources).
     * @return le rapport de fermeture (durée par élément).
     */
    public suspend fun fermer(raison: RaisonFermeture): AppResult<RapportFermeture> {
        if (currentCoroutineContext()[MarqueFermeture] != null) {
            return AppResult.Failure(
                AppError.Session(
                    reason = AppError.SessionReason.Reentrance,
                    details = "fermer() appelé depuis la libération d'un Fermable",
                ),
            )
        }
        return verrou.withLock {
            when (val courant = _etat.value) {
                is EtatSession.Fermee -> {
                    AppResult.Success(
                        dernierRapport ?: RapportFermeture(raison, Duration.ZERO, emptyList()),
                    )
                }

                is EtatSession.Ouverte -> {
                    _etat.value = EtatSession.Fermeture(courant.projet, raison)
                    executerFermeture(courant.projet, raison)
                }

                // Inatteignables sous CE verrou : Ouverture et Fermeture ne
                // vivent qu'à l'intérieur d'ouvrir()/fermer(), qui le
                // détiennent — défense en profondeur si une future évolution
                // introduisait une transition hors verrou.
                is EtatSession.Ouverture -> {
                    error("état Ouverture inatteignable sous le verrou de session")
                }

                is EtatSession.Fermeture -> {
                    error("état Fermeture inatteignable sous le verrou de session")
                }
            }
        }
    }

    /** Exécute la fermeture du registre, publie l'état et mémorise le rapport. */
    private suspend fun executerFermeture(
        projet: ProjectId,
        raison: RaisonFermeture,
    ): AppResult<RapportFermeture> {
        try {
            val rapport = registre.fermerTout(ContexteFermeture(raison))
            _etat.value = EtatSession.Fermee
            dernierRapport = rapport
            journal?.i(TAG) {
                "session fermée (${projet.value}, ${rapport.elements.size} élément(s), ${rapport.dureeTotale})"
            }
            return AppResult.Success(rapport)
        } catch (interrompue: FermetureInterrompue) {
            // l'appelant a annulé : les non-annulables sont faits (I7),
            // l'état repart à Fermee, l'annulation est RELANCÉE
            _etat.value = EtatSession.Fermee
            dernierRapport = interrompue.rapport
            journal?.w(TAG) {
                "fermeture de ${projet.value} interrompue par l'appelant : " +
                    "${interrompue.rapport.nbAnnules} élément(s) annulé(s)"
            }
            throw interrompue
        } catch (annulation: CancellationException) {
            // annulation AVANT que le registre ne démarre (acquisition de
            // son verrou) : RIEN n'a été fermé, la session reste OUVERTE
            _etat.value = EtatSession.Ouverte(projet)
            throw annulation
        }
    }

    /** Referme ce que des travaux d'ouverture ont démarré, quoi qu'il arrive. */
    private suspend fun nettoyerApresEchec(projet: ProjectId): RapportFermeture {
        _etat.value = EtatSession.Fermeture(projet, RaisonFermeture.Nettoyage)
        val rapport =
            withContext(NonCancellable) {
                try {
                    registre.fermerTout(ContexteFermeture(RaisonFermeture.Nettoyage))
                } catch (interrompue: FermetureInterrompue) {
                    // inatteignable sous NonCancellable (l'appelant y est
                    // toujours actif) — défense en profondeur (I2)
                    interrompue.rapport
                }
            }
        _etat.value = EtatSession.Fermee
        dernierRapport = rapport
        return rapport
    }

    private companion object {
        private const val TAG = "SessionProjet"
    }
}
