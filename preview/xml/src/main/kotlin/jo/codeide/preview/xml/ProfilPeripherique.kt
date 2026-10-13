package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable
import jo.layoutlib.inflater.DeviceProfile

/**
 * Profil d'appareil cible du rendu (PE3, PREVIEW.md §3.3 : « sélecteur de
 * device-profile (taille × densité, appliquée au DimensionConverter
 * LayoutLib) ») — la preview rend le layout AUX dimensions du profil, pas
 * à celles de l'appareil qui exécute l'IDE.
 *
 * Valeurs SÛRES par construction (aucune donnée personnelle) : nom
 * affichable tel quel. Le catalogue miroir de [DeviceProfile.DEFAULTS]
 * de LayoutLib — la MÊME source de vérité sert au moteur et à l'UI ; le
 * cycle du sélecteur y puise.
 *
 * [Parcelable] : le profil voyage dans [RequeteRendu] à travers la
 * frontière AIDL du processus `:preview` (PE2).
 */
public class ProfilPeripherique(
    /** Nom affichable (ex. « Pixel 4 »). */
    public val nom: String,
    /** Largeur de l'écran en dp. */
    public val largeurDp: Int,
    /** Hauteur de l'écran en dp. */
    public val hauteurDp: Int,
    /** Densité de l'écran en dpi (ex. 440). */
    public val densiteDpi: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        nom = parcel.readString() ?: "",
        largeurDp = parcel.readInt(),
        hauteurDp = parcel.readInt(),
        densiteDpi = parcel.readInt(),
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeString(nom)
        parcel.writeInt(largeurDp)
        parcel.writeInt(hauteurDp)
        parcel.writeInt(densiteDpi)
    }

    override fun describeContents(): Int = 0

    /** Traduction vers le profil LayoutLib (taille × densité du rendu). */
    internal fun versDeviceProfile(): DeviceProfile =
        DeviceProfile(
            // name =
            nom,
            // widthDp =
            largeurDp,
            // heightDp =
            hauteurDp,
            // densityDpi =
            densiteDpi,
            // landscape =
            false,
        )

    /** Description courte affichable (ex. « Pixel 4 · 393×851dp · 440dpi »). */
    public fun description(): String = "$nom · $largeurDp×${hauteurDp}dp · ${densiteDpi}dpi"

    override fun equals(other: Any?): Boolean =
        other is ProfilPeripherique &&
            other.nom == nom &&
            other.largeurDp == largeurDp &&
            other.hauteurDp == hauteurDp &&
            other.densiteDpi == densiteDpi

    override fun hashCode(): Int = 31 * (31 * (31 * nom.hashCode() + largeurDp) + hauteurDp) + densiteDpi

    override fun toString(): String = description()

    public companion object {
        /** Pixel 4 — 393×851dp, 440dpi (miroir de DeviceProfile.PIXEL_4). */
        public val PIXEL_4: ProfilPeripherique = ProfilPeripherique("Pixel 4", 393, 851, 440)

        /** Pixel 7 Pro — 412×892dp, 420dpi (miroir de DeviceProfile.PIXEL_7_PRO). */
        public val PIXEL_7_PRO: ProfilPeripherique = ProfilPeripherique("Pixel 7 Pro", 412, 892, 420)

        /** Galaxy S24 — 360×780dp, 480dpi (miroir de DeviceProfile.GALAXY_S24). */
        public val GALAXY_S24: ProfilPeripherique = ProfilPeripherique("Galaxy S24", 360, 780, 480)

        /** Catalogue du sélecteur (miroir de DeviceProfile.DEFAULTS). */
        public val CATALOGUE: List<ProfilPeripherique> = listOf(PIXEL_4, PIXEL_7_PRO, GALAXY_S24)

        /** Profil par défaut du moteur (PE1 : PIXEL_4, inchangé). */
        public val PAR_DEFAUT: ProfilPeripherique = PIXEL_4

        @JvmField
        public val CREATOR: Parcelable.Creator<ProfilPeripherique> =
            object : Parcelable.Creator<ProfilPeripherique> {
                override fun createFromParcel(parcel: Parcel): ProfilPeripherique = ProfilPeripherique(parcel)

                override fun newArray(taille: Int): Array<ProfilPeripherique?> = arrayOfNulls(taille)
            }
    }
}
