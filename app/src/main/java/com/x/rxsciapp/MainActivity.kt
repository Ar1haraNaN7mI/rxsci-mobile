package com.x.rxsciapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.x.rxsciapp.app.RxsciMobileApp
import com.x.rxsciapp.ui.theme.RxsciappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RxsciApplication).container
        setContent {
            RxsciappTheme {
                RxsciMobileApp(container = container)
            }
        }
    }
}
