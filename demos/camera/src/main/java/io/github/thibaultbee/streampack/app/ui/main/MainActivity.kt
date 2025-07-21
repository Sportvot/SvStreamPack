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
import io.github.thibaultbee.streampack.app.studio.DeepLinkParams
import io.github.thibaultbee.streampack.app.studio.StudioConstants
import androidx.lifecycle.lifecycleScope

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
            val deepLinkParams = DeepLinkParams.fromUri(intent.data)
            val matchId = deepLinkParams.matchId ?: intent.getStringExtra("MATCH_ID")
            val refreshId = deepLinkParams.refreshId
            val refreshToken = deepLinkParams.refreshToken

            val fragment = PreviewFragment()
            val args = Bundle()
            args.putString(StudioConstants.MATCH_ID_KEY, matchId)
            args.putString(StudioConstants.REFRESH_ID_KEY, refreshId)
            args.putString(StudioConstants.REFRESH_TOKEN_KEY, refreshToken)
            fragment.arguments = args
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow()
        }

        bindProperties()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val deepLinkParams = DeepLinkParams.fromUri(intent.data)
        val matchId = deepLinkParams.matchId
        val refreshId = deepLinkParams.refreshId
        val refreshToken = deepLinkParams.refreshToken

        val fragment = PreviewFragment()
        val args = Bundle()
        args.putString(StudioConstants.MATCH_ID_KEY, matchId)
        args.putString(StudioConstants.REFRESH_ID_KEY, refreshId)
        args.putString(StudioConstants.REFRESH_TOKEN_KEY, refreshToken)
        fragment.arguments = args

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commitNow()
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
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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
        val params = DeepLinkParams.fromUri(intent?.data)
        lifecycleScope.launch(Dispatchers.IO) {
            applicationContext.dataStore.edit { prefs ->
                params.resolution?.let { prefs[stringPreferencesKey("video_resolution_key")] = it }
                params.fps?.let { prefs[stringPreferencesKey("video_fps_key")] = it }
                params.ip?.let { prefs[stringPreferencesKey("srt_server_ip_key")] = it }
                params.port?.let { prefs[stringPreferencesKey("srt_server_port_key")] = it }
                params.srtStreamId?.let { prefs[stringPreferencesKey("server_stream_id_key")] = it }
                params.bitrate?.let { prefs[intPreferencesKey("live_video_bitrate_key")] = it }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
