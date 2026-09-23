package jo.codeide.feature.diagnostics.debug

import android.net.Uri
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem

/**
 * Menu de diagnostic — **version release** : rien ne s'installe.
 *
 * Le menu debug (source set `debug`, section 5.8) propose de provoquer un
 * plantage, une exception non fatale, une salve de journaux et les essais
 * SAF de la couche données ; il n'a pas sa place dans une build diffusée,
 * d'où ce no-op de même signature — l'écran Diagnostic n'a ainsi aucune
 * connaissance de la variante.
 */
object MenuDebug {
    /**
     * Installe le menu debug sur l'écran Diagnostic.
     *
     * @param fragment hôte (écran Diagnostic).
     * @param conteneur conteneur réservé (inutilisé ici).
     * @param logger journal applicatif (inutilisé ici).
     * @param fichiers port d'accès aux documents (inutilisé ici).
     * @param version nom de version affichable (inutilisé ici).
     * @param choisirArbre lance le sélecteur d'arborescence SAF
     * (inutilisé ici).
     */
    fun installer(
        fragment: Fragment,
        conteneur: ViewGroup,
        logger: AppLogger,
        fichiers: FileSystem,
        version: String,
        choisirArbre: () -> Unit,
    ) {
        // Volontairement vide en release — la variante debug porte
        // l'implémentation réelle, de même signature.
    }

    /**
     * Réagit au dossier choisi par le sélecteur SAF — no-op en release.
     *
     * @param fragment hôte (écran Diagnostic).
     * @param logger journal applicatif (inutilisé ici).
     * @param fichiers port d'accès aux documents (inutilisé ici).
     * @param uri arborescence choisie (inutilisée ici).
     */
    fun dossierChoisi(
        fragment: Fragment,
        logger: AppLogger,
        fichiers: FileSystem,
        uri: Uri?,
    ) {
        // Volontairement vide en release — la variante debug porte
        // l'implémentation réelle, de même signature.
    }
}
