package dev.slagathore.curbroute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.slagathore.curbroute.ui.CurbRouteApp

class MainActivity : ComponentActivity() {
    private val viewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CurbRouteApp(viewModel) }
    }
}
