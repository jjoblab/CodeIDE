package jo.codeide.core.testing

import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import jo.codeide.core.model.EtatInstallationBootstrap.Annulee
import jo.codeide.core.model.EtatInstallationBootstrap.Echouee
import jo.codeide.core.model.EtatInstallationBootstrap.EnCours
import jo.codeide.core.model.EtatInstallationBootstrap.NonDemarree
import jo.codeide.core.model.EtatInstallationBootstrap.OutilsEchoues
import jo.codeide.core.model.EtatInstallationBootstrap.Terminee
import jo.codeide.core.model.OutilResume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [BootstrapInstaller](jo.codeide.core.domain.BootstrapInstaller) en
 * mémoire pour les tests de ViewModel : le test pilote l'état partagé
 * (aucune installation réelle) et observe les ordres reçus.
 */
public class FakeBootstrapInstaller : BootstrapInstaller {
    private val _etat = MutableStateFlow<EtatInstallationBootstrap>(NonDemarree)
    override val etat: StateFlow<EtatInstallationBootstrap> = _etat.asStateFlow()

    private val _journal = MutableStateFlow<List<String>>(emptyList())
    override val journal: StateFlow<List<String>> = _journal.asStateFlow()

    /** Paquets d'outils exposés (v0.31.4 : proposition à l'écran). */
    public var paquets: List<String> = emptyList()
        private set

    override val paquetsOutils: List<String>
        get() = paquets

    /** Ordres `demarrer()` reçus. */
    public var demarrages: Int = 0
        private set

    /** Ordres `installerOutils()` reçus (v0.31.4). */
    public var installationsOutils: Int = 0
        private set

    /** Ordres `annuler()` reçus. */
    public var annulations: Int = 0
        private set

    public override fun demarrer() {
        demarrages++
    }

    public override fun installerOutils() {
        installationsOutils++
    }

    public override fun annuler() {
        annulations++
    }

    /** Fait passer l'état partagé à `EnCours` de l'étape indiquée. */
    public fun simulerEnCours(etape: EtapeInstallation) {
        _etat.value = EnCours(etape)
    }

    /** Fait passer l'état partagé à `Terminee` avec les outils indiqués. */
    public fun simulerTerminee(outils: List<OutilResume> = emptyList()) {
        _etat.value = Terminee(outils)
    }

    /** Fait passer l'état partagé à `Echouee` avec l'erreur indiquée. */
    public fun simulerEchouee(erreur: AppError) {
        _etat.value = Echouee(erreur)
    }

    /** Fait passer l'état partagé à `OutilsEchoues` (v0.31.4). */
    public fun simulerOutilsEchoues(
        erreur: AppError,
        outils: List<OutilResume> = emptyList(),
    ) {
        _etat.value = OutilsEchoues(erreur, outils)
    }

    /** Fait passer l'état partagé à `Annulee`. */
    public fun simulerAnnulee() {
        _etat.value = Annulee
    }

    /** Publie des lignes dans le journal (vérification du rendu en direct). */
    public fun simulerJournal(lignes: List<String>) {
        _journal.value = lignes
    }

    /** Sème les paquets d'outils proposés (vérification de l'affichage). */
    public fun semerPaquetsOutils(paquets: List<String>) {
        this.paquets = paquets
    }
}
