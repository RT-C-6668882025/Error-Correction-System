package com.ecs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ecs.ui.AppViewModel
import com.ecs.ui.nav.EcsNavHost
import com.ecs.ui.theme.EcsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EcsTheme {
                Surface(Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel()
                    EcsNavHost(vm)
                }
            }
        }
    }
}
