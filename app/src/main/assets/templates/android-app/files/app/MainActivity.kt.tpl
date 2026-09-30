package {{packageName}}

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import {{packageName}}.databinding.ActivityMainBinding

/**
 * {{t:main.kdoc}}
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liaison = ActivityMainBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        val greeter = Greeter("{{t:app.greeting}}")
        liaison.texteBienvenue.text = greeter.greet("{{appName}}")
    }
}
