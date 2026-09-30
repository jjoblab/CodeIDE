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
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textHello.text = "{{t:main.hello}} {{appName|kotlinString}}!"
    }
}
