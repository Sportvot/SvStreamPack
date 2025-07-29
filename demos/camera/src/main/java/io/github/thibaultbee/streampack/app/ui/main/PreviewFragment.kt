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

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.app.ApplicationConstants
import io.github.thibaultbee.streampack.app.R
import io.github.thibaultbee.streampack.app.databinding.MainFragmentBinding
import io.github.thibaultbee.streampack.app.utils.DialogUtils
import io.github.thibaultbee.streampack.app.utils.PermissionManager
import io.github.thibaultbee.streampack.core.interfaces.IStreamer
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.streamers.lifecycle.StreamerViewModelLifeCycleObserver
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.webkit.WebViewClient
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import io.github.thibaultbee.streampack.app.studio.StudioConstants
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import android.content.Context

class PreviewFragment : Fragment(R.layout.main_fragment) {
    private lateinit var binding: MainFragmentBinding
    private var isOverlayVisible = false
    private var isScoringVisible = false

    private val previewViewModel: PreviewViewModel by viewModels {
        PreviewViewModelFactory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this
        binding.viewmodel = previewViewModel

        bindProperties()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val matchId = arguments?.getString(StudioConstants.MATCH_ID_KEY)
        val refreshId = arguments?.getString(StudioConstants.REFRESH_ID_KEY)
        val refreshToken = arguments?.getString(StudioConstants.REFRESH_TOKEN_KEY)
        binding.toggleScoringButton.setOnClickListener {
            if (binding.scoringWebView.visibility == View.VISIBLE) {
                binding.scoringWebView.visibility = View.GONE
                binding.scoringWebView.loadUrl("about:blank")
                isScoringVisible = false
            } else {
                binding.scoringWebView.visibility = View.VISIBLE
                binding.scoringWebView.setBackgroundColor(Color.TRANSPARENT)
                binding.scoringWebView.webViewClient = WebViewClient()
                binding.scoringWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)

                val query = listOfNotNull(
                    refreshId?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                        ?.let { "refreshId=$it" },
                    refreshToken?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                        ?.let { "refreshToken=$it" }
                ).joinToString("&")
                val url =
                    "${StudioConstants.SCORING_OVERLAY_URL}/$matchId" + if (query.isNotEmpty()) "?$query" else ""

                Log.i("SCORING_URL", url)
                binding.scoringWebView.loadUrl(url)
                isScoringVisible = true
            }
            updateScoringButtonHighlight()
        }

        binding.toggleOverlayButton.setOnClickListener {
            if (binding.overlayWebView.visibility == View.VISIBLE) {
                binding.overlayWebView.visibility = View.GONE
                binding.overlayWebView.loadUrl("about:blank")
                isOverlayVisible = false
            } else {
                binding.overlayWebView.visibility = View.VISIBLE
                binding.overlayWebView.setBackgroundColor(Color.TRANSPARENT)
                binding.overlayWebView.webViewClient = WebViewClient()
                binding.overlayWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                val url = "${StudioConstants.OVERLAY_URL}/preview/$matchId"

                Log.i("SCORING_URL OVERLAY", url)
                binding.overlayWebView.loadUrl(url)
                isOverlayVisible = true
            }
            updateOverlayButtonHighlight()
        }

        binding.showCopyScoringUrlButton?.setOnClickListener {
            val matchId = arguments?.getString(StudioConstants.MATCH_ID_KEY)
            val refreshId = arguments?.getString(StudioConstants.REFRESH_ID_KEY)
            val refreshToken = arguments?.getString(StudioConstants.REFRESH_TOKEN_KEY)
            val query = listOfNotNull(
                refreshId?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                    ?.let { "refreshId=$it" },
                refreshToken?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
                    ?.let { "refreshToken=$it" }
            ).joinToString("&")
            val url = "${StudioConstants.SCORING_OVERLAY_URL}/$matchId" + if (query.isNotEmpty()) "?$query" else ""
            val ottUrl = "${StudioConstants.OTT_URL}/stream/$matchId"
            showCopyLinkDialog(ottUrl, url)
        }
        // Ensure button highlight is correct on view creation
        updateOverlayButtonHighlight()
        updateScoringButtonHighlight()
    }

    @SuppressLint("MissingPermission")
    private fun bindProperties() {
        binding.liveButton.setOnClickListener { view ->
            view as ToggleButton
            if (view.isPressed) {
                if (view.isChecked) {
                    startStreamIfPermissions(previewViewModel.requiredPermissions)
                } else {
                    stopStream()
                }
            }
        }

        previewViewModel.streamerErrorLiveData.observe(viewLifecycleOwner) {
            showError("Oops", it)
        }

        previewViewModel.endpointErrorLiveData.observe(viewLifecycleOwner) {
            showError("Endpoint error", it)
        }

        previewViewModel.isStreamingLiveData.observe(viewLifecycleOwner) { isStreaming ->
            if (isStreaming) {
                lockOrientation()
            } else {
                unlockOrientation()
            }
            if (isStreaming) {
                binding.liveButton.isChecked = true
            } else if (previewViewModel.isTryingConnectionLiveData.value == true) {
                binding.liveButton.isChecked = true
            } else {
                binding.liveButton.isChecked = false
            }
            // Update MainActivity's streaming state
            (activity as? MainActivity)?.setStreamingState(isStreaming)
        }

        previewViewModel.isTryingConnectionLiveData.observe(viewLifecycleOwner) { isWaitingForConnection ->
            if (isWaitingForConnection) {
                binding.liveButton.isChecked = true
            } else if (previewViewModel.isStreamingLiveData.value == true) {
                binding.liveButton.isChecked = true
            } else {
                binding.liveButton.isChecked = false
            }
        }

        previewViewModel.streamerLiveData.observe(viewLifecycleOwner) { streamer ->
            if (streamer is IStreamer) {
                // TODO: Remove this observer when streamer is released
                lifecycle.addObserver(StreamerViewModelLifeCycleObserver(streamer))
            } else {
                Log.e(TAG, "Streamer is not a ICoroutineStreamer")
            }
            if (streamer is IWithVideoSource) {
                inflateStreamerPreview(streamer)
            } else {
                Log.e(TAG, "Can't start preview, streamer is not a IVideoStreamer")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            previewViewModel.deviceTemperature.collectLatest { temperature ->
                temperature?.let {
                    binding.temperatureText.text = "Temp: %.1f°C".format(it)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            previewViewModel.sendRateMbps.collectLatest { sendRateMbps ->
                sendRateMbps?.let {
                    val text = String.format("Bitrate: %.1f Mbps", it)
                    binding.sendRateMbpsText.text = text
                }
            }
        }

//        viewLifecycleOwner.lifecycleScope.launch {
//            previewViewModel.performanceMetrics.collectLatest { metrics ->
//                binding.cpuText.text = "Threads: %d (Priority: %d)".format(metrics.threadCount, metrics.processPriority)
//                binding.memoryText.text = "Memory: %.1f MB".format(metrics.memoryUsage)
//            }
//        }
    }

    private fun lockOrientation() {
        /**
         * Lock orientation while stream is running to avoid stream interruption if
         * user turns the device.
         * For landscape only mode, set [requireActivity().requestedOrientation] to
         * [ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE].
         */
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun unlockOrientation() {
        requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
    }

    private fun startStream() {
        previewViewModel.startStream()
    }

    private fun stopStream() {
        previewViewModel.stopStream()
    }

    private fun showPermissionError(vararg permissions: String) {
        Log.e(TAG, "Permission not granted: ${permissions.joinToString { ", " }}")
        DialogUtils.showPermissionAlertDialog(requireContext())
    }

    private fun showError(title: String, message: String) {
        Log.e(TAG, "Error: $title, $message")
        DialogUtils.showAlertDialog(requireContext(), "Error: $title", message)
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        requestCameraAndMicrophonePermissions()
    }

    override fun onPause() {
        super.onPause()
        stopStream()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun inflateStreamerPreview() {
        val streamer = previewViewModel.streamerLiveData.value
        if (streamer is SingleStreamer) {
            inflateStreamerPreview(streamer)
        } else {
            Log.e(TAG, "Can't start preview, streamer is not a SingleStreamer")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun inflateStreamerPreview(streamer: SingleStreamer) {
        val preview = binding.preview
        // Set camera settings button when camera is started
        preview.listener = object : PreviewView.Listener {
            override fun onPreviewStarted() {
                Log.i(TAG, "Preview started")
            }

            override fun onZoomRationOnPinchChanged(zoomRatio: Float) {
                previewViewModel.onZoomRationOnPinchChanged()
            }
        }

        // Wait till streamer exists to set it to the SurfaceView.
        preview.streamer = streamer
        if (PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            lifecycleScope.launch {
                try {
                    preview.startPreview()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error starting preview", t)
                }
            }
        } else {
            Log.e(TAG, "Camera permission not granted. Preview will not start.")
        }
    }

    private fun startStreamIfPermissions(permissions: List<String>) {
        when {
            PermissionManager.hasPermissions(
                requireContext(), *permissions.toTypedArray()
            ) -> {
                startStream()
            }

            else -> {
                requestLiveStreamPermissionsLauncher.launch(
                    permissions.toTypedArray()
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestCameraAndMicrophonePermissions() {
        when {
            PermissionManager.hasPermissions(
                requireContext(), Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
            ) -> {
                inflateStreamerPreview()
                previewViewModel.configureAudio()
                previewViewModel.initializeVideoSource()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionError(Manifest.permission.RECORD_AUDIO)
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionError(Manifest.permission.CAMERA)
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA
                    )
                )
            }

            else -> {
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
                    )
                )
            }
        }
    }

    private val requestLiveStreamPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val missingPermissions = permissions.toList().filter {
            !it.second
        }.map { it.first }

        if (missingPermissions.isEmpty()) {
            startStream()
        } else {
            showPermissionError(*missingPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private val requestCameraAndMicrophonePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val missingPermissions = permissions.toList().filter {
            !it.second
        }.map { it.first }

        if (permissions[Manifest.permission.CAMERA] == true) {
            inflateStreamerPreview()
            previewViewModel.initializeVideoSource()
        } else if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            previewViewModel.configureAudio()
        }
        if (missingPermissions.isNotEmpty()) {
            showPermissionError(*missingPermissions.toTypedArray())
        }
    }

    private fun showCopyLinkDialog(link1: String, link2: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_settings, null)
        val linkTextView1 = dialogView.findViewById<TextView>(R.id.linkTextView1)
        val copyButton1 = dialogView.findViewById<Button>(R.id.copyButton1)
        val linkTextView2 = dialogView.findViewById<TextView>(R.id.linkTextView2)
        val copyButton2 = dialogView.findViewById<Button>(R.id.copyButton2)

        linkTextView1.text = link1
        linkTextView2.text = link2

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Links")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        copyButton1.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Match View Link", link1)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Match view link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        copyButton2.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Scoring Link", link2)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Scoring link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun updateOverlayButtonHighlight() {
        if (isOverlayVisible) {
            binding.toggleOverlayButton.setBackgroundResource(R.drawable.button_highlight_background)
            binding.toggleOverlayButton.alpha = 1.0f
        } else {
            binding.toggleOverlayButton.setBackgroundResource(0)
            binding.toggleOverlayButton.alpha = 0.6f
        }
    }

    private fun updateScoringButtonHighlight() {
        if (isScoringVisible) {
            binding.toggleScoringButton.setBackgroundResource(R.drawable.button_highlight_background)
            binding.toggleScoringButton.alpha = 1.0f
        } else {
            binding.toggleScoringButton.setBackgroundResource(0)
            binding.toggleScoringButton.alpha = 0.6f
        }
    }

    companion object {
        private const val TAG = "PreviewFragment"
    }
}
