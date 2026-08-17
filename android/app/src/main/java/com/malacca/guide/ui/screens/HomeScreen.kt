package com.malacca.guide.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.android.gms.location.LocationServices
import com.malacca.guide.ble.ConnectionState
import com.malacca.guide.ble.GlassesManager
import com.malacca.guide.camera.CameraManager
import com.malacca.guide.ui.components.BatteryIndicator
import com.malacca.guide.ui.navigation.ROUTE_LISTENING
import com.malacca.guide.ui.theme.BackgroundDark
import com.malacca.guide.ui.theme.ErrorRed
import com.malacca.guide.ui.theme.MalaccaTeal
import com.malacca.guide.ui.theme.SuccessGreen
import com.malacca.guide.ui.theme.SurfaceDark
import com.malacca.guide.ui.theme.TextPrimary
import com.malacca.guide.ui.theme.TextSecondary
import com.malacca.guide.ui.theme.WarningAmber
import com.malacca.guide.ui.viewmodel.AppMode
import com.malacca.guide.ui.viewmodel.GuideViewModel
import com.malacca.guide.voice.GlassesAudioRouter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: GuideViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val selectedLanguage = viewModel.selectedLanguage
    val appMode = viewModel.appMode
    val languages = listOf("EN", "MS")

    val glassesState by GlassesManager.connectionState.collectAsState()
    // Re-ask on (re)connect, since onSdkReady may fire before this screen exists.
    LaunchedEffect(glassesState) {
        if (glassesState == ConnectionState.Connected) GlassesManager.refreshBattery()
    }
    val scanResults by GlassesManager.scanResults.collectAsState()
    val audioAvailable by GlassesAudioRouter.headsetAvailable.collectAsState()
    val battery by GlassesManager.batteryPercent.collectAsState()
    var showScanSheet by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val scanSheetState = rememberModalBottomSheetState()

    // Home is the resting screen, so it owns releasing the glasses audio route.
    // Holding SCO open outside an interaction would hijack phone audio and
    // drain the battery.
    LaunchedEffect(Unit) {
        GlassesAudioRouter.deactivate()
        GlassesAudioRouter.refreshAvailability()
        // The glasses only volunteer a battery level every few minutes, so ask
        // for a fresh one whenever the wearer is actually looking at this screen.
        GlassesManager.refreshBattery()
    }

    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            showScanSheet = true
            GlassesManager.startScan()
        }
    }

    fun requestScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) perms += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (perms.isEmpty()) {
            showScanSheet = true
            GlassesManager.startScan()
        } else {
            btPermLauncher.launch(perms.toTypedArray())
        }
    }

    val titleText = when {
        appMode == AppMode.RESTAURANT -> when (selectedLanguage) {
            "MS" -> "Ambil gambar papan\ntanda restoran"
            else -> "Point at a restaurant\nsign to identify it"
        }
        else -> when (selectedLanguage) {
            "MS" -> "Apa yang anda\ningin tahu?"
            else -> "What would you like\nto know about?"
        }
    }
    val subtitleText = when {
        appMode == AppMode.RESTAURANT -> when (selectedLanguage) {
            "MS" -> "Ketuk untuk ambil gambar\npapan tanda restoran"
            else -> "Tap to capture the\nrestaurant signage"
        }
        else -> when (selectedLanguage) {
            "MS" -> "Ketuk dan tanya HeyCyan\ntentang apa yang anda lihat"
            else -> "Tap and ask HeyCyan\nabout what you see"
        }
    }
    val galleryText = when (selectedLanguage) {
        "MS" -> "Pilih dari galeri"
        else -> "Pick from gallery"
    }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val inputStream = context.contentResolver.openInputStream(uri)
        val original = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()
        if (original != null) {
            val bitmap = scaleBitmap(original, 1280)
            viewModel.clearForNewSession()
            viewModel.storeBitmap(bitmap)
            if (appMode == AppMode.RESTAURANT) {
                fetchLocationThenNavigate(context, viewModel, navController)
            } else {
                navController.navigate(ROUTE_LISTENING)
            }
        }
    }

    fun captureFromGlassesThen(navigate: () -> Unit) {
        scope.launch {
            val bytes = GlassesManager.takePhoto()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            capturing = false
            if (bitmap != null) {
                Log.d("ImagePipeline", "captureFromGlassesThen: bitmap ${bitmap.width}x${bitmap.height}")
                viewModel.storeBitmap(scaleBitmap(bitmap, 1280))
                navigate()
            } else {
                Log.e("ImagePipeline", "captureFromGlassesThen: capture failed, bitmap null")
                Toast.makeText(context, "Image capture failed, try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startCapture() {
        Log.d("ImagePipeline", "startCapture: glassesState=$glassesState")
        if (glassesState == ConnectionState.Connected) {
            capturing = true
            captureFromGlassesThen {
                navController.navigate(ROUTE_LISTENING)
            }
            return
        }
        CameraManager.capturePhoto(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onCaptured = { bitmap ->
                viewModel.storeBitmap(scaleBitmap(bitmap, 1280))
                navController.navigate(ROUTE_LISTENING)
            },
            onFailed = { navController.navigate(ROUTE_LISTENING) }
        )
    }

    fun startCaptureWithLocation() {
        if (glassesState == ConnectionState.Connected) {
            // Fetch GPS first, then take photo via glasses
            capturing = true
            fetchLocationThenGlassesCapture(context, viewModel, scope) { ok ->
                capturing = false
                if (ok) navController.navigate(ROUTE_LISTENING)
                else Toast.makeText(context, "Image capture failed, try again.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        fetchLocationThenCapture(context, lifecycleOwner, viewModel, navController)
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (appMode == AppMode.RESTAURANT) startCaptureWithLocation()
            else startCapture()
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCaptureWithLocation()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    fun onMicTap() {
        if (capturing) {
            Log.d("HomeScreen", "onMicTap: ignored, capture already in progress")
            return
        }
        viewModel.clearForNewSession()
        if (appMode == AppMode.RESTAURANT) {
            val hasLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            when {
                !hasLocation -> locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                !hasCamera -> cameraPermLauncher.launch(Manifest.permission.CAMERA)
                else -> startCaptureWithLocation()
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCapture()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    Scaffold(containerColor = BackgroundDark) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar: title + language selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HeyCyan Guide",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row {
                    languages.forEach { lang ->
                        val isSelected = lang == selectedLanguage
                        TextButton(
                            onClick = { viewModel.setLanguage(lang) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) MalaccaTeal else TextSecondary
                            )
                        ) {
                            Text(
                                text = lang,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Mode toggle: LANDMARK / RESTAURANT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                listOf(AppMode.LANDMARK to "Landmark", AppMode.RESTAURANT to "Restaurant").forEach { (mode, label) ->
                    val isSelected = appMode == mode
                    Button(
                        onClick = { viewModel.setMode(mode) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MalaccaTeal else SurfaceDark
                        )
                    ) {
                        Text(
                            text = label,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = titleText,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            // With the glasses connected the wearer starts a session from the
            // temple button, so the on-screen control stops being the primary
            // affordance. It stays tappable as a fallback — if the glasses
            // button misbehaves, losing it entirely would leave no way in.
            val handsFree = glassesState == ConnectionState.Connected
            Button(
                onClick = { onMicTap() },
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (handsFree) SurfaceDark else MalaccaTeal
                )
            ) {
                Text(
                    text = when {
                        handsFree -> "PRESS\nGLASSES"
                        appMode == AppMode.RESTAURANT -> "CAM"
                        else -> "MIC"
                    },
                    color = if (handsFree) TextSecondary else TextPrimary,
                    fontSize = if (handsFree) 13.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (handsFree) {
                    when (selectedLanguage) {
                        "MS" -> "Tekan butang pada cermin mata anda\n(atau ketuk di sini)"
                        else -> "Press the button on your glasses\n(or tap here)"
                    }
                } else {
                    subtitleText
                },
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.padding(horizontal = 32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MalaccaTeal)
            ) {
                Text(
                    text = galleryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.weight(1f))

            val (glassesText, glassesColor) = when (glassesState) {
                ConnectionState.Connected -> "Glasses: Connected (tap to disconnect)" to SuccessGreen
                ConnectionState.Connecting -> "Glasses: Connecting..." to WarningAmber
                ConnectionState.Scanning -> "Glasses: Scanning..." to WarningAmber
                ConnectionState.Disconnected -> "Glasses: Not connected (tap to scan)" to TextSecondary
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable {
                        if (glassesState == ConnectionState.Connected) {
                            GlassesManager.disconnect()
                        } else if (glassesState == ConnectionState.Disconnected) {
                            requestScan()
                        }
                    },
                color = SurfaceDark,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Weighted so the longest label ("Connected (tap to
                        // disconnect)") gives way instead of squeezing the battery
                        // readout off the right edge and clipping the "%".
                        Text(
                            text = glassesText,
                            color = glassesColor,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(end = 8.dp)
                        )
                        // Reported by the glasses on event 0x05, and prompted via
                        // addBatteryCallBack + syncBattery() on connect.
                        battery?.let { BatteryIndicator(percent = it) }
                    }
                    if (glassesState == ConnectionState.Connected) {
                        val audioText = if (audioAvailable) {
                            when (selectedLanguage) {
                                "MS" -> "Mikrofon & pembesar suara cermin mata sedia"
                                else -> "Glasses mic & speaker ready"
                            }
                        } else {
                            when (selectedLanguage) {
                                "MS" -> "Audio pada telefon — gandingkan cermin mata sebagai peranti audio Bluetooth"
                                else -> "Audio on phone — pair the glasses as a Bluetooth audio device"
                            }
                        }
                        Text(
                            text = audioText,
                            color = if (audioAvailable) SuccessGreen else WarningAmber,
                            fontSize = 11.sp
                        )
                        Text(
                            text = when (selectedLanguage) {
                                "MS" -> "Atau tekan butang pada cermin mata untuk mula"
                                else -> "Or press the button on your glasses to start"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    if (showScanSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                GlassesManager.stopScan()
                showScanSheet = false
            },
            sheetState = scanSheetState,
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Available glasses",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (glassesState == ConnectionState.Scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MalaccaTeal,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (scanResults.isEmpty() && glassesState != ConnectionState.Scanning) {
                    Text(
                        text = "No devices found. Make sure your glasses are on and in pairing mode.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                LazyColumn {
                    items(scanResults, key = { it.address }) { dev ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    GlassesManager.connect(dev.address)
                                    showScanSheet = false
                                },
                            color = BackgroundDark,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = dev.name,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${dev.address}  ·  ${dev.rssi} dBm",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { GlassesManager.startScan() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MalaccaTeal)
                    ) { Text("Re-scan") }
                    OutlinedButton(
                        onClick = {
                            GlassesManager.stopScan()
                            showScanSheet = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                    ) { Text("Close") }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchLocationThenCapture(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    viewModel: GuideViewModel,
    navController: NavController
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation
        .addOnSuccessListener { location ->
            viewModel.updateLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
            CameraManager.capturePhoto(
                context = context,
                lifecycleOwner = lifecycleOwner,
                onCaptured = { bitmap ->
                    viewModel.storeBitmap(bitmap)
                    navController.navigate(ROUTE_LISTENING)
                },
                onFailed = { navController.navigate(ROUTE_LISTENING) }
            )
        }
        .addOnFailureListener {
            viewModel.updateLocation(0.0, 0.0)
            CameraManager.capturePhoto(
                context = context,
                lifecycleOwner = lifecycleOwner,
                onCaptured = { bitmap ->
                    viewModel.storeBitmap(bitmap)
                    navController.navigate(ROUTE_LISTENING)
                },
                onFailed = { navController.navigate(ROUTE_LISTENING) }
            )
        }
}

@SuppressLint("MissingPermission")
private fun fetchLocationThenNavigate(
    context: Context,
    viewModel: GuideViewModel,
    navController: NavController
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation
        .addOnSuccessListener { location ->
            viewModel.updateLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
            navController.navigate(ROUTE_LISTENING)
        }
        .addOnFailureListener {
            viewModel.updateLocation(0.0, 0.0)
            navController.navigate(ROUTE_LISTENING)
        }
}

@SuppressLint("MissingPermission")
private fun fetchLocationThenGlassesCapture(
    context: Context,
    viewModel: GuideViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (Boolean) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val proceed = {
        scope.launch {
            val bytes = GlassesManager.takePhoto()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                Log.d("ImagePipeline", "fetchLocationThenGlassesCapture: bitmap ${bitmap.width}x${bitmap.height}")
                viewModel.storeBitmap(scaleBitmap(bitmap, 1280))
                onResult(true)
            } else {
                Log.e("ImagePipeline", "fetchLocationThenGlassesCapture: capture failed, bitmap null")
                onResult(false)
            }
        }
    }
    client.lastLocation
        .addOnSuccessListener { location ->
            viewModel.updateLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
            proceed()
        }
        .addOnFailureListener {
            viewModel.updateLocation(0.0, 0.0)
            proceed()
        }
}

internal fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
    if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt(),
        (bitmap.height * scale).toInt(),
        true
    )
}
