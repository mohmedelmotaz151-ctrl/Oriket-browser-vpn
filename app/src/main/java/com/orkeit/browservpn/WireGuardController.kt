package com.orkeit.browservpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.Executors

/**
 * WireGuard adapter. A real provider/server configuration must be imported by the user.
 * UI status is only updated after GoBackend confirms the tunnel state.
 */
object WireGuardController {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "Oriket"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    fun validateConfig(text: String): String? {
        return try {
            Config.parse(BufferedReader(StringReader(text)))
            null
        } catch (error: Exception) {
            error.localizedMessage ?: "ملف WireGuard غير صالح"
        }
    }

    fun connect(context: Context, configText: String, callback: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                val config = Config.parse(BufferedReader(StringReader(configText)))
                val instance = backend ?: GoBackend(context.applicationContext).also { backend = it }
                val state = instance.setState(tunnel, Tunnel.State.UP, config)
                callback(
                    state == Tunnel.State.UP,
                    if (state == Tunnel.State.UP) "تم الاتصال بخادم WireGuard" else "لم يكتمل الاتصال"
                )
            } catch (error: Exception) {
                callback(false, error.localizedMessage ?: "تعذر إنشاء اتصال VPN")
            }
        }
    }

    fun disconnect(callback: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                val instance = backend
                if (instance == null) {
                    callback(true, "الاتصال غير نشط")
                } else {
                    instance.setState(tunnel, Tunnel.State.DOWN, null)
                    callback(true, "تم قطع اتصال VPN")
                }
            } catch (error: Exception) {
                callback(false, error.localizedMessage ?: "تعذر قطع اتصال VPN")
            }
        }
    }
}
