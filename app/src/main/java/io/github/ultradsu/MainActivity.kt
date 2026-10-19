package io.github.ultradsu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.ultradsu.ui.UltraDsuApp

class MainActivity : ComponentActivity() {
    private val model by viewModels<AppViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UltraDsuApp(model) }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
