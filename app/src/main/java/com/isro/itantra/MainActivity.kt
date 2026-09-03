package com.isro.itantra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.isro.itantra.core.LanguageCode
import com.isro.itantra.core.OperatingMode
import com.isro.itantra.core.SessionController
import com.isro.itantra.databinding.ActivityMainBinding
import com.isro.itantra.transport.BluetoothTransportManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionController
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val transcriptLog = StringBuilder()

    private val requiredPermissions: Array<String> by lazy {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.BLUETOOTH
            perms += Manifest.permission.BLUETOOTH_ADMIN
            perms += Manifest.permission.ACCESS_FINE_LOCATION // required pre-S for BT device discovery
        }
        perms.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onPermissionsGranted()
        } else {
            binding.statusText.text = getString(R.string.permissions_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bind hardware volume keys to control active playback volume
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // Route call/speech audio to main speakerphone instead of earpiece
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (_: Exception) {}

        val btManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = btManager.adapter

        val modelsRoot = java.io.File(getExternalFilesDir(null), "models")
        com.isro.itantra.core.ModelAssetsExtractor.copyAllAssetsToInternalStorage(this, modelsRoot)
        session = SessionController(modelsRoot, bluetoothAdapter, this)

        setupLanguageSpinner()
        setupModeToggle()
        setupConnectionButtons()
        setupPushToTalk()
        setupSpeakerphoneToggle()
        wireSessionCallbacks()

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        binding.statusText.text = getString(R.string.status_ready)
        lifecycleScope.launch {
            val ready = session.prepareEngines()
            binding.statusText.text = if (ready) {
                getString(R.string.status_engines_ready)
            } else {
                getString(R.string.status_engines_missing)
            }
        }
    }

    private fun setupLanguageSpinner() {
        val names = LanguageCode.entries.map { it.displayName }
        binding.languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        binding.languageSpinner.setSelection(LanguageCode.entries.indexOf(LanguageCode.ENGLISH))
        binding.languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val lang = LanguageCode.entries[position]
                session.onLanguageChanged(lang)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupModeToggle() {
        binding.modeToggle.check(R.id.modePhoneCall)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.modeWalkieTalkie -> {
                    session.setMode(OperatingMode.WALKIE_TALKIE)
                    binding.pttButton.visibility = android.view.View.VISIBLE
                }
                R.id.modePhoneCall -> {
                    session.setMode(OperatingMode.PHONE_CALL)
                    binding.pttButton.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun setupConnectionButtons() {
        binding.hostButton.setOnClickListener {
            session.startAsHost()
            binding.statusText.text = getString(R.string.status_waiting_for_peer)
        }
        binding.joinButton.setOnClickListener {
            showPairedDevicePicker()
        }
        binding.disconnectButton.setOnClickListener {
            session.disconnect()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showPairedDevicePicker() {
        val paired: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices ?: emptySet()
        if (paired.isEmpty()) {
            binding.statusText.text = getString(R.string.status_no_paired_devices)
            return
        }
        val names = paired.map { "${it.name} (${it.address})" }.toTypedArray()
        val devices = paired.toList()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.pick_paired_device)
            .setItems(names) { _, which ->
                session.connectToDevice(devices[which])
                binding.statusText.text = getString(R.string.status_connecting)
            }
            .show()
    }

    private fun setupPushToTalk() {
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    session.onPttDown()
                    binding.pttButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.bg_transcript)
                    )
                    binding.pttButton.setTextColor(
                        androidx.core.content.ContextCompat.getColor(this, R.color.accent_teal)
                    )
                    binding.pttButton.text = "TALKING..."
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    session.onPttUp()
                    binding.pttButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.primary_indigo)
                    )
                    binding.pttButton.setTextColor(
                        androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
                    )
                    binding.pttButton.text = getString(R.string.hold_to_talk)
                }
            }
            true
        }
        binding.pttButton.visibility = android.view.View.GONE
    }

    private fun wireSessionCallbacks() {
        session.onConnectionState = { state ->
            runOnUiThread {
                binding.statusText.text = when (state) {
                    is BluetoothTransportManager.ConnectionState.Connected ->
                        getString(R.string.status_connected_to, state.remoteDeviceName)
                    is BluetoothTransportManager.ConnectionState.Failed ->
                        getString(R.string.status_failed, state.reason)
                    BluetoothTransportManager.ConnectionState.Listening -> getString(R.string.status_waiting_for_peer)
                    BluetoothTransportManager.ConnectionState.Connecting -> getString(R.string.status_connecting)
                    BluetoothTransportManager.ConnectionState.Disconnected -> getString(R.string.status_disconnected)
                }
            }
        }
        session.onTranscript = { text, isOutgoing ->
            runOnUiThread {
                val prefix = if (isOutgoing) "You: " else "Peer: "
                transcriptLog.insert(0, "$prefix$text\n")
                binding.transcriptText.text = transcriptLog.toString()
            }
        }
        session.onLatencyMeasured = { millis ->
            runOnUiThread {
                binding.latencyText.text = getString(R.string.latency_ms, millis)
            }
        }
        session.onEnsureSpeakerRouting = {
            runOnUiThread {
                setSpeakerphoneState(binding.speakerphoneSwitch.isChecked)
            }
        }
    }

    private fun setupSpeakerphoneToggle() {
        binding.speakerphoneSwitch.isChecked = true
        setSpeakerphoneState(true)
        binding.speakerphoneSwitch.setOnCheckedChangeListener { _, isChecked ->
            setSpeakerphoneState(isChecked)
        }
    }

    fun setSpeakerphoneState(enableSpeaker: Boolean) {
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            // Set the audio mode to communication for VoIP/Custom calls
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            // Toggle the hardware routing
            audioManager.isSpeakerphoneOn = enableSpeaker

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetType = if (enableSpeaker) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val targetDevice = devices.firstOrNull { it.type == targetType }
                if (targetDevice != null) {
                    audioManager.setCommunicationDevice(targetDevice)
                }
            }
        } catch (_: Exception) {}
    }

    fun resetAudioMode() {
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        resetAudioMode()
        session.shutdown()
    }
}
