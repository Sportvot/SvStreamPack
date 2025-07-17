/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import io.github.thibaultbee.streampack.app.R
import io.github.thibaultbee.streampack.app.databinding.MainActivityBinding
import io.github.thibaultbee.streampack.app.ui.settings.SettingsActivity
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.thibaultbee.streampack.app.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Parse deep link parameters
        handleDeepLink(intent)

        if (savedInstanceState == null) {
            // Pass MATCH_ID from intent extras to PreviewFragment if present
            val matchId = intent.getStringExtra("MATCH_ID")
            val fragment = PreviewFragment()
            if (matchId != null) {
                val args = Bundle()
                args.putString("MATCH_ID", matchId)
                fragment.arguments = args
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow()
        }

        bindProperties()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Parse deep link parameters
        handleDeepLink(intent)
    }

    private fun bindProperties() {
        binding.actions.setOnClickListener {
            showPopup()
        }
    }

    private fun showPopup() {
        val popup = PopupMenu(this, binding.actions)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(R.menu.actions, popup.menu)
        
        // Disable settings menu item if streaming
        popup.menu.findItem(R.id.action_settings)?.isEnabled = !isStreaming
        
        popup.show()
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                if (!isStreaming) {
                    goToSettingsActivity()
                } else {
                    Toast.makeText(this, "Settings cannot be changed while streaming", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                Log.e(TAG, "Unknown menu item ${it.itemId}")
                false
            }
        }
    }

    private fun goToSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actions, menu)
        // Disable settings menu item if streaming
        menu.findItem(R.id.action_settings)?.isEnabled = !isStreaming
        return true
    }

    fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming
        // Update menu items when streaming state changes
        invalidateOptionsMenu()
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            val query = uri.query
            val params = mutableMapOf<String, MutableList<String>>()

            query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                val key = android.net.Uri.decode(parts[0])
                val value = if (parts.size > 1) android.net.Uri.decode(parts[1]) else ""
                params.getOrPut(key) { mutableListOf() }.add(value)
            }

            val resolution = params["enc[vid][res]"]?.firstOrNull()
            val fps = params["enc[vid][fps]"]?.firstOrNull()
            val srtstreamid = params["conn[][srtstreamid]"]?.firstOrNull()
            val bitrate = params["enc[vid][bitrate]"]?.firstOrNull()?.toIntOrNull()
            val connUrl = params["conn[][url]"]?.firstOrNull()
            var ip: String? = null
            var port: String? = null
            connUrl?.let { url ->
                val regex = Regex("""srt://([\w.]+):(\d+)""")
                val match = regex.find(url)
                if (match != null && match.groupValues.size >= 3) {
                    ip = match.groupValues[1]
                    port = match.groupValues[2]
                }
            }

            val message = """
                Resolution: ${resolution ?: "-"}
                FPS: ${fps ?: "-"}
                IP: ${ip ?: "-"}
                Port: ${port ?: "-"}
                SRT Stream ID: ${srtstreamid ?: "-"}
                Bitrate: ${bitrate?.toString() ?: "-"}
            """.trimIndent()
            AlertDialog.Builder(this)
                .setTitle("Streaming Parameters")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()

            // Save to DataStore
            CoroutineScope(Dispatchers.IO).launch {
                applicationContext.dataStore.edit { prefs ->
                    resolution?.let { prefs[stringPreferencesKey("video_resolution_key")] = it }
                    fps?.let { prefs[stringPreferencesKey("video_fps_key")] = it }
                    ip?.let { prefs[stringPreferencesKey("srt_server_ip_key")] = it }
                    port?.let { prefs[stringPreferencesKey("srt_server_port_key")] = it }
                    srtstreamid?.let { prefs[stringPreferencesKey("server_stream_id_key")] = it }
                    bitrate?.let { prefs[intPreferencesKey("live_video_bitrate_key")] = it }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
