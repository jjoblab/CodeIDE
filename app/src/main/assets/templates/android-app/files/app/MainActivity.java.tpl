package {{packageName}};

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import {{packageName}}.databinding.ActivityMainBinding;

/**
 * {{t:main.kdoc}}
 */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding liaison = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(liaison.getRoot());

        Greeter greeter = new Greeter("{{t:app.greeting}}");
        liaison.texteBienvenue.setText(greeter.greet("{{appName|javaString}}"));
    }
}
