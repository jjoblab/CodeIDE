package jo.codeide.core.domain

import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Traduit un identifiant de document SAF « volume:chemin/relatif » en
 * chemin FUSE réel (`/storage/emulated/0/…` ou `/storage/<uuid>/…`),
 * ou `null` si le volume est inconnu ou le chemin malformé.
 *
 * Cartographie `ExternalStorageProvider` (fait de plateforme, pas une
 * préférence) : le volume `primary` désigne le stockage émulé interne,
 * tout autre identifiant est un UUID de volume amovible (`1A2B-3C4D`)
 * monté sous `/storage/<uuid>` — racine dérivable, donc acceptée sans
 * figer la liste des volumes possibles.
 *
 * Durcissement anti-traversée : chaque segment décodé doit être un nom
 * ordinaire — les segments vides, `.` et `..` rejettent l'identifiant
 * (l'identifiant vient du fournisseur SAF, mais la défense en profondeur
 * est la règle du projet depuis le moteur de templates).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque retour est
 * une **issue** typée du parcours (identifiant sans séparateur, volume
 * inconnu, racine du volume, segment hostile, chemin assemblé) ; les
 * imbriquer masquerait le code de sécurité.
 */
@Suppress("ReturnCount")
internal fun cheminFuseDepuisIdDocument(
    idDocument: String,
    racinesVolume: Map<String, String>,
): String? {
    val indexSeparateur = idDocument.indexOf(':')
    if (indexSeparateur <= 0) return null

    val volume = idDocument.substring(0, indexSeparateur)
    val relatif = idDocument.substring(indexSeparateur + 1)
    val racine =
        racinesVolume[volume]
            ?: racineVolumeAmovible(volume)
            ?: return null

    if (relatif.isBlank()) return racine

    val segments = relatif.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null

    val chemin = StringBuilder(racine)
    for (segment in segments) {
        chemin.append('/').append(segment)
    }
    return chemin.toString()
}

/** Racine FUSE d'un volume amovible par UUID (`1A2B-3C4D`), sinon `null`. */
private fun racineVolumeAmovible(volume: String): String? {
    val motifs = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
    return volume.takeIf { motifs.matches(it) }?.let { "/storage/$it" }
}

/** Racines FUSE par volume (cartographie `ExternalStorageProvider`). */
internal val RACINES_FUSE_PAR_DEFAUT: Map<String, String> =
    mapOf(
        "primary" to "/storage/emulated/0",
    )

/**
 * Cas d'usage « répertoire du projet pour le terminal » (Terminal T6) :
 * résout le dossier du projet courant en chemin utilisable par un shell
 * du bootstrap, ou `null` si ce n'est pas possible.
 *
 * Pourquoi ce pont existe alors que le stockage applicatif est SAF
 * (ADR 0003) : une session de terminal est un **processus fils** de
 * l'app — même UID, même vue FUSE. Un `cd /storage/emulated/0/…` y
 * fonctionne pour les arborescences dont l'app tient la permission
 * persistante. Le shell ne parle pas SAF ; sans ce pont, « ouvrir le
 * terminal dans ce projet » serait un mensonge d'interface.
 *
 * Le futur tooling (exécution Gradle sur l'appareil) réutilisera ce
 * cas d'usage : c'est la même traduction arborescence SAF → chemin
 * réel, une seule source de vérité.
 *
 * Comportement :
 * - identifiant SAF illisible → `null` (l'appelant repliera sur le
 *   `HOME` du terminal, jamais de chemin inventé) ;
 * - volume inconnu ou segments hostiles → `null` ;
 * - chemin résolu mais **absent du système de fichiers** (carte SD
 *   retirée, volume non monté) → `null` : jamais de session ouverte
 *   dans un répertoire fantôme.
 *
 * Contexte d'exécution attendu : suspendante (stat FUSE), hors thread
 * principal.
 */
public class ResoudreRepertoireProjet
    @Inject
    constructor(
        private val arborescences: ArborescencesSaf,
        private val repartiteurs: DispatcherProvider,
    ) {
        /**
         * Résout le chemin FUSE du dossier désigné par l'URI d'arborescence
         * [grantUri], s'il existe réellement sur le volume monté.
         */
        public suspend operator fun invoke(grantUri: String): String? =
            withContext(repartiteurs.io) {
                val idDocument = arborescences.idDocument(grantUri) ?: return@withContext null
                cheminFuseDepuisIdDocument(idDocument, RACINES_FUSE_PAR_DEFAUT)
                    ?.let { chemin -> chemin.takeIf { File(chemin).isDirectory() } }
            }
    }
