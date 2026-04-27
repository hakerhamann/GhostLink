package com.rezerv.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rezerv.app.auth.LoginActivity
import com.rezerv.app.chat.ChatListActivity
import com.rezerv.app.databinding.ActivityMainBinding
import com.rezerv.app.notifications.PushManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            launch { runCatching { AppContainer.updateRepository.checkForUpdatesAndDownload() } }
            val profile = AppContainer.authRepository.currentUserProfile()
            val intent = if (profile == null) {
                Intent(this@MainActivity, LoginActivity::class.java)
            } else {
                PushManager.syncTokenNow(this@MainActivity, force = false)
                Intent(this@MainActivity, ChatListActivity::class.java)
            }
            startActivity(intent)
            finish()
        }
    }
}
