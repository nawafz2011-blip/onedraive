package com.cloudplay.bridge.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.cloudplay.bridge.core.Core
import java.util.concurrent.Executors

class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val core = Core.get(this)
        Executors.newSingleThreadExecutor().execute {
            val message = try {
                core.auth.handleRedirect(uri)
                Core.notifyRootsChanged(applicationContext)
                "تم ربط وندرايف"
            } catch (e: Exception) {
                "فشل تسجيل الدخول: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
        }
    }
}
