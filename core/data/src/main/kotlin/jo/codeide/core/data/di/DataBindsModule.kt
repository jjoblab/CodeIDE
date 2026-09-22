package jo.codeide.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.data.ProjectRepositoryImpl
import jo.codeide.core.data.SettingsRepositoryImpl
import jo.codeide.core.domain.ProjectRepository
import jo.codeide.core.domain.SettingsRepository
import javax.inject.Singleton

/**
 * Assemblage Hilt de la couche données (étape 4) : liaisons des
 * repositories du domaine vers leurs implémentations au-dessus des
 * sources réelles (Room pour le registre, DataStore pour les
 * paramètres).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface DataBindsModule {
    /** Registre des projets : Room (index unique sur documentUri). */
    @Binds
    @Singleton
    fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    /** Paramètres applicatifs : Preferences DataStore. */
    @Binds
    @Singleton
    fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
