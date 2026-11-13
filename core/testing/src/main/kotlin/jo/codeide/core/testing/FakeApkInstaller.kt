package jo.codeide.core.testing

import jo.codeide.core.domain.ApkInstaller
import jo.codeide.core.domain.EtapeInstallationApk
import jo.codeide.core.domain.ResultatInstallationApk
import jo.codeide.core.domain.ResultatLancementApk
import java.io.File

/**
 * Faux [ApkInstaller] pour les tests JVM de la mission « Exécuter »
 * (R1, ADR 0102) : résultats semés par le test, appels et étapes
 * journalisés — le faux n'exécute AUCUN `PackageInstaller`.
 *
 * - [resultatInstallation] pilote [installer] (succès par défaut) ;
 * - [resultatLancement] pilote [lancer] (succès par défaut) ;
 * - [etapesEmises] retransmet les étapes REVUES du faux, le test
 *   vérifie la chaîne de progression jusqu'à l'interface ;
 * - [apksInstalles] / [paquetsLances] attestent des appels réels.
 */
public class FakeApkInstaller : ApkInstaller {
    /** Résultat retourné par [installer] (succès par défaut). */
    public var resultatInstallation: ResultatInstallationApk =
        ResultatInstallationApk.Succes(confirmationUtilisateur = false)

    /** Résultat retourné par [lancer] (succès par défaut). */
    public var resultatLancement: ResultatLancementApk =
        ResultatLancementApk.Succes(NOM_PAQUET_DEFAUT)

    /** APKs passés à [installer] (chemins réels). */
    public val apksInstalles: MutableList<File> = mutableListOf()

    /** Paquets passés à [lancer]. */
    public val paquetsLances: MutableList<String> = mutableListOf()

    /** Étapes revues du faux — la chaîne de progression est éprouvée. */
    public val etapesEmises: MutableList<EtapeInstallationApk> = mutableListOf()

    override suspend fun installer(
        apk: File,
        progression: ((EtapeInstallationApk) -> Unit)?,
    ): ResultatInstallationApk {
        apksInstalles += apk
        progression?.invoke(EtapeInstallationApk.CopieApk)
        etapesEmises += EtapeInstallationApk.CopieApk
        return resultatInstallation
    }

    override suspend fun lancer(nomPaquet: String): ResultatLancementApk {
        paquetsLances += nomPaquet
        return resultatLancement
    }

    private companion object {
        /** Paquet par défaut des succès semés. */
        const val NOM_PAQUET_DEFAUT = "com.exemple.monapp"
    }
}
