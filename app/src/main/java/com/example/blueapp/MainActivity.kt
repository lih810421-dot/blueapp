package com.example.blueapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.blueapp.bluetooth.BluetoothAssistantApp
import com.example.blueapp.ui.theme.BlueappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 固定浅色：用户不喜欢深色模式
            BlueappTheme(darkTheme = false, dynamicColor = true) { BluetoothAssistantApp() }
        }
    }
}