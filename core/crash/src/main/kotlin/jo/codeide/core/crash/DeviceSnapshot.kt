package jo.codeide.core.crash

import android.content.Context
import jo.codeide.core.model.DeviceInfo

/**
 * Photographie **non identifiante** de l'appareil, pour l'installation du
 * gestionnaire de plantages (section 5.8) : `app` fournit la lambda
 * `deviceInfo` sans connaître les détails de prélèvement.
 *
 * Toute défaillance retombe sur [DeviceInfo.inconnu] — jamais sur un échec
 * du gestionnaire (règle 16 : le chemin critique ne lève pas).
 */
public object DeviceSnapshot {
    /**
     * Prélève la photographie de l'appareil courant.
     *
     * @param contexte contexte applicatif (fabricant, ABI, stockage…).
     * @return les caractéristiques non identifiantes, ou la valeur de
     * repli si le prélèvement échoue.
     */
    @JvmStatic
    public fun depuisContext(contexte: Context): DeviceInfo = deviceInfoDepuisContext(contexte)
}
