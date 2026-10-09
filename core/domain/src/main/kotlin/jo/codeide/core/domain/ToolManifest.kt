package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.File

/**
 * Manifeste d'outils v2 du dépôt `codeide-tools` — transcription à la
 * lettre du contrat commun (§ 12.2, ADR 0086). Produit par le dépôt
 * `codeide-tools` (prompt 2), consommé ici : **jamais modifié** par
 * l'application.
 *
 * Les champs inconnus du JSON sont ignorés à l'analyse ; un champ requis
 * absent rend le manifeste invalide (`AppError.EnvironmentSetup(
 * ManifesteInvalide)`).
 *
 * @property schemaVersion version du schéma du manifeste (exigée : 2).
 * @property generatedAtMillis date de génération, en millisecondes epoch (ISO 8601 UTC côté fichier).
 * @property components composants publiés, toutes architectures et révisions.
 * @property profiles profils nommés : listes de références `"<id>@<version>"`.
 * @property compat matrice de compatibilité AGP ↔ build-tools ↔ aapt2 ↔
 * compileSdk ↔ JDK.
 */
public data class ToolManifest(
    public val schemaVersion: Int,
    public val generatedAtMillis: Long,
    public val components: List<ManifestComponent>,
    public val profiles: Map<String, List<String>>,
    public val compat: List<CompatLine>,
) {
    public companion object {
        /** Version de schéma attendue par cette application (§ 12.2). */
        public const val SCHEMA_VERSION: Int = 2
    }
}

/**
 * Composant publié par le manifeste (§ 12.2 — tous champs requis sauf
 * [channel], [license] et [minAndroidApi]).
 *
 * @property id identifiant (`build-tools`, `platform-tools`,
 * `cmdline-tools`, `platform`, `aapt2`…).
 * @property version version amont (`36.0.0`) ; pour `platform` :
 * `android-36`.
 * @property revision révision de reconditionnement (`r1`, `r2`…) — un
 * rebuild sans changement de version amont.
 * @property arch architecture (`aarch64`, `arm`, `x86_64`, `any`).
 * @property channel `stable` (défaut) ou `preview` — l'application IGNORE
 * les composants `preview`.
 * @property sources URLs ordonnées : primaire d'abord, miroirs ensuite
 * (essayés dans l'ordre, journalisés).
 * @property sha256 somme SHA-256 de l'archive, hexadécimal minuscule.
 * @property sizeBytes taille de l'archive, en octets.
 * @property installPath chemin d'installation **relatif à la racine du
 * SDK**, exclusif au composant (vérifié à la résolution).
 * @property critical criticité : `cmdline-tools` seul non critique à ce
 * jour (AGP ne l'utilise pas pour compiler) — un échec isolé dégrade la
 * phase 4 sans la faire échouer.
 * @property requires dépendances : `"<id>"`, `"<id>@<version>"` ou
 * `"jdk>=<majeure>"`.
 * @property verify vérification fonctionnelle exécutée sans shell, avec
 * l'environnement du § 12.4, répertoire courant = racine du SDK.
 * @property license licence amont (informatif).
 * @property minAndroidApi API Android minimale (informatif).
 * @property archiveRoot racine de l'archive quand elle diffère de
 *        [installPath] (zips Google : `platform-36_r02.zip` contient
 *        `android-36/` à sa racine, PAS `platforms/android-36/`).
 *        L'installateur extrait puis déplace `archiveRoot` vers
 *        `installPath`. `null` = l'archive contient directement
 *        `installPath/...` (contrat § 12.3 par défaut).
 */
public data class ManifestComponent(
    public val id: String,
    public val version: String,
    public val revision: String,
    public val arch: String,
    public val channel: String = CHANNEL_STABLE,
    public val sources: List<String>,
    public val sha256: String,
    public val sizeBytes: Long,
    public val installPath: String,
    public val critical: Boolean,
    public val requires: List<String> = emptyList(),
    public val verify: VerifySpec,
    public val license: String? = null,
    public val minAndroidApi: Int? = null,
    public val archiveRoot: String? = null,
) {
    public companion object {
        /** Canal stable, seul canal consommé par l'application. */
        public const val CHANNEL_STABLE: String = "stable"

        /** Canal preview, ignoré par l'application (§ 12.2). */
        public const val CHANNEL_PREVIEW: String = "preview"

        /** Architecture « toute architecture » (composants purs). */
        public const val ARCH_ANY: String = "any"
    }
}

/**
 * Spécification de vérification fonctionnelle d'un composant (§ 12.2).
 *
 * Le succès exige le code de sortie attendu **et** la regex trouvée dans
 * stdout + stderr — exécuté **sans shell**.
 *
 * @property cmd commande : `"<chemin relatif à la racine du SDK>
 * <arguments>"`.
 * @property expect expression régulière attendue dans la sortie.
 * @property exitCode code de retour attendu (0 par défaut).
 */
public data class VerifySpec(
    public val cmd: String,
    public val expect: String,
    public val exitCode: Int = 0,
)

/**
 * Ligne de la matrice de compatibilité du manifeste (§ 12.2).
 *
 * @property agp version d'AGP.
 * @property buildTools version de build-tools.
 * @property aapt2 version d'aapt2.
 * @property compileSdk plateforme de compilation.
 * @property jdk version majeure de JDK exigée — **chaîne** du manifeste
 * (ex. `">=17"`, `"17"`, `"17+"`) : le manifeste v2 exprime une
 * contrainte, pas un entier. L'analyse de la contrainte (parsing du
 * `>=`, `>`, `=`) est laissée aux consommateurs.
 * @property status `tested` ou `untested` — informationnelle, affichée à
 * l'écran Environnement.
 */
public data class CompatLine(
    public val agp: String,
    public val buildTools: String,
    public val aapt2: String,
    public val compileSdk: String,
    public val jdk: String,
    public val status: String,
)

/**
 * Exigence du catalogue : un composant à une version **exacte** (ADR 0086).
 *
 * @property id identifiant du composant exigé.
 * @property version version amont exigée.
 */
public data class ComponentRequirement(
    public val id: String,
    public val version: String,
) {
    override fun toString(): String = "$id@$version"

    public companion object {
        /**
         * Analyse une référence `"<id>@<version>"` (forme des profils du
         * manifeste).
         *
         * @return l'exigence, ou `null` si la syntaxe est invalide.
         */
        public fun parse(reference: String): ComponentRequirement? {
            val partes = reference.split('@', limit = 2)
            if (partes.size != 2) return null
            return construireSiValide(partes[0].trim(), partes[1].trim())
        }

        private fun construireSiValide(
            id: String,
            version: String,
        ): ComponentRequirement? =
            if (estSegmentValide(id) && estSegmentValide(version)) {
                ComponentRequirement(id, version)
            } else {
                null
            }

        private fun estSegmentValide(segment: String): Boolean = segment.isNotEmpty() && !segment.contains(' ')
    }
}

/**
 * Composant retenu par le plan d'installation.
 *
 * @property component le composant du manifeste (révision la plus haute
 * retenue pour son couple (`id`, `version`) et son architecture).
 * @property requiredBy l'exigence du catalogue satisfaite, ou `null` si le
 * composant vient du profil sans être exigé (ex. `platform-tools`).
 */
public data class PlannedComponent(
    public val component: ManifestComponent,
    public val requiredBy: ComponentRequirement?,
)

/**
 * Plan d'installation résolu depuis le manifeste (ADR 0086, § 12.2) —
 * produit par [InstallPlanResolver], consommé par la phase 4.
 *
 * @property profile nom du profil consommé.
 * @property arch architecture de l'appareil (`aarch64`…).
 * @property components composants retenus, dans l'ordre du profil.
 */
public data class InstallPlan(
    public val profile: String,
    public val arch: String,
    public val components: List<PlannedComponent>,
) {
    /** Taille totale des archives du plan (octets) — contrôle d'espace. */
    public val totalSizeBytes: Long get() = components.sumOf { it.component.sizeBytes }

    /** Premier composant du plan portant cet [id], ou `null`. */
    public fun find(id: String): ManifestComponent? = components.firstOrNull { it.component.id == id }?.component

    /** Le plan contient-il le composant [id] à la version [version] ? */
    public fun contains(
        id: String,
        version: String,
    ): Boolean = find(id)?.version == version

    /**
     * Chemin du binaire `aapt2` résolu par le plan (§ 12.4) : composant
     * `aapt2` s'il existe, sinon composant `build-tools` — jamais un asset
     * ni une constante.
     *
     * @param sdkRoot racine du SDK Android.
     * @return le fichier `aapt2` attendu sous [sdkRoot], ou `null` si le
     * plan ne le fournit pas.
     */
    public fun aapt2Binary(sdkRoot: File): File? {
        val porteur = find("aapt2") ?: find("build-tools") ?: return null
        return File(File(sdkRoot, porteur.installPath), "aapt2")
    }
}

/**
 * Résolution du plan d'installation depuis le catalogue, le manifeste v2
 * et l'architecture de l'appareil — fonction **pure** (ADR 0086),
 * rejouée par les tests contre les vecteurs de référence.
 *
 * Règles (§ 12.2) :
 * 1. le plan contient, pour chaque composant du profil dont l'architecture
 *    est celle de l'appareil ou `any`, l'entrée de révision la plus haute
 *    pour le couple (`id`, `version`) — canal `stable` seul, `preview`
 *    ignoré ;
 * 2. si le plan ne contient pas une version exigée par le catalogue :
 *    échec **« manifeste incompatible »** (jamais une autre version, jamais
 *    une autre source) ;
 * 3. chaque `installPath` du plan est **exclusif** à un composant ;
 * 4. un profil inconnu, une référence mal formée ou un composant du profil
 *    introuvable pour l'architecture rendent le manifeste invalide.
 */
public object InstallPlanResolver {
    /**
     * Résout le plan.
     *
     * @param catalog exigences de l'application (profil, composants exigés).
     * @param manifest manifeste v2 validé.
     * @param arch architecture de l'appareil (`aarch64`, `arm`, `x86_64`).
     * @return le plan en `Success` ; `EnvironmentSetup(ManifesteInvalide)`
     * en `Failure` — détails en français pour les journaux.
     */
    public fun resolve(
        catalog: ToolchainCatalog,
        manifest: ToolManifest,
        arch: String,
    ): AppResult<InstallPlan> {
        val retenus = resoudreComposantsDuProfil(catalog, manifest, arch)
        if (retenus is AppResult.Failure) return retenus
        return construirePlan(catalog, arch, (retenus as AppResult.Success).value)
    }

    /**
     * Valide le schéma et le profil, puis retient chaque composant du
     * profil (révision la plus haute, architecture compatible, canal
     * stable) — la carte résultat conserve l'ordre du profil.
     */
    private fun resoudreComposantsDuProfil(
        catalog: ToolchainCatalog,
        manifest: ToolManifest,
        arch: String,
    ): AppResult<Pair<List<String>, Map<String, PlannedComponent>>> {
        val references = validerProfil(catalog, manifest)
        if (references is AppResult.Failure) return references
        return retenirComposants(manifest, catalog, arch, (references as AppResult.Success<List<String>>).value)
    }

    /** Contrôle la version de schéma puis lit le profil du catalogue. */
    private fun validerProfil(
        catalog: ToolchainCatalog,
        manifest: ToolManifest,
    ): AppResult<List<String>> =
        when {
            manifest.schemaVersion != ToolManifest.SCHEMA_VERSION -> {
                incompatible(
                    "version de schéma ${manifest.schemaVersion}, ${ToolManifest.SCHEMA_VERSION} attendue",
                )
            }

            else -> {
                manifest.profiles[catalog.sdkProfile]?.let { AppResult.Success(it) }
                    ?: incompatible("profil « ${catalog.sdkProfile} » absent du manifeste")
            }
        }

    /** Retient chaque référence du profil dans l'ordre, ou premier refus. */
    private fun retenirComposants(
        manifest: ToolManifest,
        catalog: ToolchainCatalog,
        arch: String,
        liste: List<String>,
    ): AppResult<Pair<List<String>, Map<String, PlannedComponent>>> {
        val retenus = LinkedHashMap<String, PlannedComponent>()
        for (reference in liste) {
            val echec = ajouterReference(retenus, manifest, catalog, arch, reference)
            if (echec != null) return echec
        }
        return AppResult.Success(liste to retenus)
    }

    /**
     * Retient la référence [reference] dans [retenus], ou explique le
     * refus — référence mal formée, doublon, composant introuvable pour
     * l'architecture (canal stable).
     */
    private fun ajouterReference(
        retenus: MutableMap<String, PlannedComponent>,
        manifest: ToolManifest,
        catalog: ToolchainCatalog,
        arch: String,
        reference: String,
    ): AppResult.Failure? {
        val exigence = ComponentRequirement.parse(reference)
        val composant = exigence?.let { meilleurCandidat(manifest, it, arch) }
        return when {
            exigence == null -> {
                incompatible("référence de profil mal formée : « $reference »")
            }

            retenus.containsKey(exigence.id) -> {
                incompatible("le profil référence « ${exigence.id} » plusieurs fois")
            }

            composant == null -> {
                incompatible("composant « $exigence » introuvable pour l'architecture $arch (canal stable)")
            }

            else -> {
                // L'exigence satisfaite n'est marquée que si le CATALOGUE
                // l'exige : les autres composants du profil (platform-tools,
                // cmdline-tools…) viennent sans être exigés (ADR 0086).
                retenus[exigence.id] =
                    PlannedComponent(composant, catalog.requiredComponents.firstOrNull { it == exigence })
                null
            }
        }
    }

    /** Révision la plus haute parmi les candidats de l'exigence, ou `null`. */
    private fun meilleurCandidat(
        manifest: ToolManifest,
        exigence: ComponentRequirement,
        arch: String,
    ): ManifestComponent? =
        manifest.components
            .filter { estCandidat(it, exigence, arch) }
            .maxWithOrNull(compareBy { numeroRevision(it.revision) })

    /**
     * Contrôle les exigences du catalogue (version exacte, § 12.2 règle 3)
     * puis l'exclusivité des `installPath`, et assemble le plan dans
     * l'ordre du profil.
     */
    private fun construirePlan(
        catalog: ToolchainCatalog,
        arch: String,
        retenus: Pair<List<String>, Map<String, PlannedComponent>>,
    ): AppResult<InstallPlan> {
        val (references, composants) = retenus
        val echec = exigenceInsatisfaite(catalog, composants) ?: collisionInstallPath(composants)
        if (echec != null) return echec
        return AppResult.Success(
            InstallPlan(
                profile = catalog.sdkProfile,
                arch = arch,
                components =
                    references.mapNotNull { reference ->
                        ComponentRequirement.parse(reference)?.let { composants[it.id] }
                    },
            ),
        )
    }

    /** Première exigence du catalogue non satisfaite par le plan, ou `null`. */
    private fun exigenceInsatisfaite(
        catalog: ToolchainCatalog,
        retenus: Map<String, PlannedComponent>,
    ): AppResult.Failure? =
        catalog.requiredComponents.firstNotNullOfOrNull { exigence ->
            val publiee = retenus[exigence.id]?.component?.version
            if (publiee == exigence.version) {
                null
            } else {
                incompatible(
                    "exigence « $exigence » non satisfaite" +
                        (publiee?.let { " — le manifeste publie « ${exigence.id}@$it »" } ?: ""),
                )
            }
        }

    /** Premier `installPath` partagé entre deux composants du plan, ou `null`. */
    private fun collisionInstallPath(retenus: Map<String, PlannedComponent>): AppResult.Failure? {
        val partages =
            retenus.values
                .groupBy { it.component.installPath }
                .filterValues { it.size > 1 }
        return if (partages.isEmpty()) {
            null
        } else {
            incompatible("installPath partagé entre composants : ${partages.keys.joinToString()}")
        }
    }

    /** Numéro d'une révision « rN » (croissant) ; toute autre forme vaut -1. */
    private fun numeroRevision(revision: String): Int =
        Regex("^r([0-9]+)$")
            .find(revision)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: -1
}

/** Un composant correspond à l'exigence ET s'exécute sur l'architecture. */
private fun estCandidat(
    composant: ManifestComponent,
    exigence: ComponentRequirement,
    arch: String,
): Boolean = correspondExigence(composant, exigence) && estExecutableSur(composant, arch)

/** Même identifiant et même version exacte que l'exigence (§ 12.2, règle 3). */
private fun correspondExigence(
    composant: ManifestComponent,
    exigence: ComponentRequirement,
): Boolean = composant.id == exigence.id && composant.version == exigence.version

/** Architecture de l'appareil ou `any`, canal stable (`preview` ignoré). */
private fun estExecutableSur(
    composant: ManifestComponent,
    arch: String,
): Boolean =
    (composant.arch == arch || composant.arch == ManifestComponent.ARCH_ANY) &&
        composant.channel != ManifestComponent.CHANNEL_PREVIEW

private fun incompatible(details: String): AppResult.Failure =
    AppResult.Failure(
        AppError.EnvironmentSetup(
            reason = AppError.EnvironmentSetupReason.ManifesteInvalide,
            details = "manifeste incompatible : $details",
        ),
    )
