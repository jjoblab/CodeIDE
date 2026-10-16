package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * Demande de rendu paramétrée (PE3, PREVIEW.md §3.4 : « profils device +
 * jour/nuit + drawables/values previewables ») — TOUT ce que le processus
 * `:preview` doit savoir pour rendre : le texte, les ressources du projet
 * (P5), le GENRE (le pipeline dispatche dessus : layout inflaté, drawable
 * dessiné, values en tableau), le profil d'appareil cible et le régime
 * jour/nuit.
 *
 * [Parcelable] : la requête voyage à travers la frontière AIDL (PE2) ;
 * le transport LOCAL la consomme telle quelle. L'échec reste en DONNÉE
 * dans [ResultatRendu], jamais en exception à travers Binder.
 */
@Suppress("LongParameterList") // RequeteRendu porte TOUS les paramètres du rendu — AIDL + Parcelable
public class RequeteRendu(
    /** Texte du document (aucune hypothèse de validité — validé au pipeline). */
    public val texte: String,
    /** Dossier `res/` du projet (null = repli ressources natives). */
    public val dossierRes: String?,
    /** Genre du document (dispatch du pipeline). */
    public val genre: GenrePreview,
    /** Profil d'appareil cible (dimensions et densité du rendu). */
    public val profil: ProfilPeripherique,
    /** Régime nuit : thèmes et ressources `values-night` priorisés. */
    public val nuit: Boolean,
    /** Langue de l'aperçu (PE5a — LayoutLib v3.0.0 P1 résolu) :
     *  `null` = défaut système, `"fr"` = français, `"en"` = anglais.
     *  Le résolveur `ResourceResolverImpl.setLanguage()` bascule les
     *  chaînes `values-fr` / `values-en`. */
    public val langue: String? = null,
    /** Nom du thème activité à appliquer (v0.131.0 / G2) — ex. :
     *  `"Theme.AppCompat.DayNight.NoActionBar"` ou `"@style/Theme.MyApp"`.
     *  `null` = thème système (repli). Le `ThemeResolver` résout les
     *  `?attr/colorPrimary` etc. en marchant la chaîne d'héritage du
     *  style nommé. Le préfixe `@style/` est optionnel (strippé). */
    public val themeName: String? = null,
    /** Titre de l'action bar du chrome (v0.134.4) : `android:label` de
     *  l'ACTIVITÉ du manifest (à défaut `android:label` de
     *  l'application) — littéral (`"Ma vue"`) ou référence
     *  `@string/…` (résolue par le résolveur du projet au rendu).
     *  `null` = repli sur [themeName] (comportement d'avant v0.134.4). */
    public val labelActivite: String? = null,
    /** Classpath projet pour vues custom (v0.132.0 / G1) — liste des
     *  entrées (JAR, AAR, DOSSIER, MODULE) à donner au `DexViewFactory`.
     *  Vide = pas de vues custom (repli ViewFactory par défaut + G15
     *  MockView fallback pour classes introuvables). */
    public val classpath: List<EntreeClasspath> = emptyList(),
    /** Orientation PAYSAGE du rendu (PE6e-f — puce Portrait/Paysage de la
     *  maquette, miroir `S.land` : les dimensions du profil sont PERMUTÉES
     *  et le qualifieur `values-land` priorisé). `false` = portrait. */
    public val paysage: Boolean = false,
    /** Échelle de police de l'aperçu (PE6e-f — puce « Police ×1,0 » de la
     *  maquette, miroir `S.fs` : ×1,0 / ×1,3 / ×2,0). Vaut pour le
     *  `scaledDensity` du rendu (convertisseur dp→px et moteur de mesure —
     *  LayoutLib v3 `setFontScale`). Borne : > 0. */
    public val echellePolice: Float = ECHELLE_POLICE_DEFAUT,
) : Parcelable {
    // Le profil est APLATI au parcel (pas de readParcelable déprécié
    // API 33 — le module reste compatible minSdk 26 sans
    // pragma de dépréciation).
    private constructor(parcel: Parcel) : this(
        texte = parcel.readString() ?: "",
        dossierRes = parcel.readString(),
        genre =
            GenrePreview.entries.getOrElse(parcel.readInt()) { GenrePreview.LAYOUT },
        profil =
            ProfilPeripherique(
                nom = parcel.readString() ?: "",
                largeurDp = parcel.readInt(),
                hauteurDp = parcel.readInt(),
                densiteDpi = parcel.readInt(),
            ),
        nuit = parcel.readByte() != 0.toByte(),
        langue = parcel.readString(),
        themeName = parcel.readString(),
        labelActivite = parcel.readString(),
        classpath = parcel.createTypedArrayList(EntreeClasspath.CREATOR) ?: emptyList(),
        paysage = parcel.readByte() != 0.toByte(),
        echellePolice = parcel.readFloat(),
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeString(texte)
        parcel.writeString(dossierRes)
        parcel.writeInt(genre.ordinal)
        parcel.writeString(profil.nom)
        parcel.writeInt(profil.largeurDp)
        parcel.writeInt(profil.hauteurDp)
        parcel.writeInt(profil.densiteDpi)
        parcel.writeByte(if (nuit) 1 else 0)
        parcel.writeString(langue)
        parcel.writeString(themeName)
        parcel.writeString(labelActivite)
        parcel.writeTypedList(classpath)
        parcel.writeByte(if (paysage) 1 else 0)
        parcel.writeFloat(echellePolice)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is RequeteRendu &&
            other.texte == texte &&
            other.dossierRes == dossierRes &&
            other.genre == genre &&
            other.profil == profil &&
            other.nuit == nuit &&
            other.langue == langue &&
            other.themeName == themeName &&
            other.labelActivite == labelActivite &&
            other.classpath == classpath &&
            other.paysage == paysage &&
            other.echellePolice == echellePolice

    override fun hashCode(): Int {
        var resultat = texte.hashCode()
        resultat = 31 * resultat + (dossierRes?.hashCode() ?: 0)
        resultat = 31 * resultat + genre.hashCode()
        resultat = 31 * resultat + profil.hashCode()
        resultat = 31 * resultat + if (nuit) 1 else 0
        resultat = 31 * resultat + (langue?.hashCode() ?: 0)
        resultat = 31 * resultat + (themeName?.hashCode() ?: 0)
        resultat = 31 * resultat + (labelActivite?.hashCode() ?: 0)
        resultat = 31 * resultat + classpath.hashCode()
        resultat = 31 * resultat + if (paysage) 1 else 0
        resultat = 31 * resultat + echellePolice.hashCode()
        return resultat
    }

    public companion object {
        /** Échelle de police nominale (miroir `S.fs = 1` de la maquette). */
        public const val ECHELLE_POLICE_DEFAUT: Float = 1f

        @JvmField
        public val CREATOR: Parcelable.Creator<RequeteRendu> =
            object : Parcelable.Creator<RequeteRendu> {
                override fun createFromParcel(parcel: Parcel): RequeteRendu = RequeteRendu(parcel)

                override fun newArray(taille: Int): Array<RequeteRendu?> = arrayOfNulls(taille)
            }
    }
}
