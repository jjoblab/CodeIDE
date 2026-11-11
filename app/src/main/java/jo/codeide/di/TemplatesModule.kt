package jo.codeide.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.templates.AssetTemplateAssetsSource
import jo.codeide.templates.GeneratorVersionImpl
import javax.inject.Singleton

/**
 * Assemblage Hilt du moteur de templates (étape 8 — section 11).
 *
 * Le fournisseur embarqué s'enregistre en **multibinding** (`@IntoSet`) :
 * ajouter des modèles ne modifie ni le moteur ni ce module — les futurs
 * plugins apporteront leur propre fournisseur sans recompilation du cœur
 * (aucun `when(templateId)` en dur nulle part).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface TemplatesModule {
    /** Port d'assets : AssetManager du contexte applicatif. */
    @Binds
    @Singleton
    fun bindTemplateAssetsSource(impl: AssetTemplateAssetsSource): TemplateAssetsSource

    /** Version du générateur : BuildConfig de l'application. */
    @Binds
    @Singleton
    fun bindGeneratorVersion(impl: GeneratorVersionImpl): GeneratorVersion

    /** Fournisseur embarqué : un élément du Set multibinding. */
    @Binds
    @IntoSet
    fun embeddedTemplatesProvider(impl: EmbeddedTemplatesProvider): ProjectTemplateProvider
}
