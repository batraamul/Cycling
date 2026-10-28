package com.batraamul.cycling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.batraamul.cycling.ui.CyclingAppUi

/** The app's only Activity. Everything visible is drawn by Jetpack Compose inside [CyclingAppUi]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CyclingAppUi() }
    }
}
