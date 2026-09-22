package jo.codeide.debug

import androidx.appcompat.app.AppCompatActivity
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem

/**
 * Menu de diagnostic — **version release** : rien ne s'installe.
 *
 * Le menu debug (source set `debug`, section 5.8) propose de provoquer un
 * plantage, une exception non fatale, une salve de journaux et les essais
 * SAF de la couche données ; il n'a pas sa place dans une build diffusée,
 * d'où ce no-op de même signature — `MainActivity` n'a ainsi aucune
 * connaissance de la variante.
 */
object MenuDebug {
    /**
     * Installe le menu debug sur l'activité hôte.
     *
     * @param activite activité principale.
     * @param logger journal applicatif (inutilisé ici).
     * @param fichiers port d'accès aux documents (inutilisé ici).
     */
    fun installer(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        // Volontairement vide en release — la variante debug porte
        // l'implémentation réelle, de même signature.
    }
}
