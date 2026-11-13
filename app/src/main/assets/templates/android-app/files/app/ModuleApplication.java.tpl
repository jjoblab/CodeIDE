package {{packageName}};

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * Fournitures Hilt de l'application : la salutation est fournie puis
 * injectée dans les activités (voir MainActivity) — démonstration d'une
 * injection réelle, pas seulement de la déclaration du module.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class ModuleApplication {
    /** Fournit la salutation commune aux activités. */
    @Provides
    public static Greeter greeter() {
        return new Greeter("{{t:app.greeting}}");
    }

    private ModuleApplication() {
    }
}
