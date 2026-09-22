package jo.codeide.core.model

/**
 * Photographie **non identifiante** de l'appareil au moment d'un plantage
 * (section 5.8 : fabricant, modèle, version Android/API, ABI, locale,
 * mémoire max/libre, stockage libre).
 *
 * Règle 15 du prompt maître : jamais d'identifiant propriétaire, jamais de
 * numéro de série, jamais de données personnelles — uniquement ce qui aide
 * à reproduire un défaut (la locale explique un formatage, l'ABI un plantage
 * natif, la mémoire une pression).
 *
 * @property manufacturer fabricant (Build.MANUFACTURER).
 * @property model modèle commercial (Build.MODEL).
 * @property androidVersion version d'Android affichée (Build.VERSION.RELEASE).
 * @property apiLevel niveau d'API (Build.VERSION.SDK_INT).
 * @property abi principale architecture binaire supportée.
 * @property locale langue et région du système.
 * @property maxMemoryBytes mémoire maximum du tas (Runtime.maxMemory).
 * @property freeMemoryBytes mémoire libre estimée du tas au moment du plantage.
 * @property storageFreeBytes espace libre du stockage interne.
 */
public data class DeviceInfo(
    public val manufacturer: String,
    public val model: String,
    public val androidVersion: String,
    public val apiLevel: Int,
    public val abi: String,
    public val locale: String,
    public val maxMemoryBytes: Long,
    public val freeMemoryBytes: Long,
    public val storageFreeBytes: Long,
) {
    public companion object {
        /**
         * Appareil inconnu — utilisé quand la photographie ne peut pas être
         * prise (chemin critique réduit à l'essentiel) et comme valeur de
         * repli des rapports reconstruits a posteriori.
         */
        public fun inconnu(): DeviceInfo =
            DeviceInfo(
                manufacturer = "inconnu",
                model = "inconnu",
                androidVersion = "inconnue",
                apiLevel = 0,
                abi = "inconnue",
                locale = "inconnue",
                maxMemoryBytes = 0,
                freeMemoryBytes = 0,
                storageFreeBytes = 0,
            )
    }
}
