package com.securetalk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.securetalk.app.navigation.SecureTalkApp
import com.securetalk.app.session.SessionController
import com.securetalk.core.design.SecureTalkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionController: SessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sessionController.start()
        setContent {
            SecureTalkTheme {
                SecureTalkApp()
            }
        }
    }
}
