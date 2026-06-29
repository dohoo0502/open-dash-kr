package com.example.opendash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.opendash.data.SyncRepository
import com.example.opendash.ui.navigation.AppNavigation
import com.example.opendash.ui.theme.OpenDashTheme
import com.example.opendash.viewmodel.RouteViewModel
import com.google.firebase.auth.FirebaseAuth
import com.kakaomobility.knsdk.KNSDK
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    private val routeViewModel: RouteViewModel by viewModels()
    private var authListener: FirebaseAuth.AuthStateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logRuntimeKakaoInfo()
        initializeKakaoNaviSdk()

        enableEdgeToEdge()

        // Firestore sync follows sign-in: start mirroring/listening when signed in,
        // stop on sign-out. Only wired when Firebase is configured (bring-your-own-project);
        // otherwise the app runs fully local with no sync.
        val sync = SyncRepository.get(applicationContext)
        if (com.example.opendash.data.FirebaseGate.isConfigured(applicationContext)) {
            authListener = FirebaseAuth.AuthStateListener { fa ->
                if (fa.currentUser != null) sync.startSync() else sync.stopSync()
            }.also { FirebaseAuth.getInstance().addAuthStateListener(it) }
        }

        // Maintenance reminders on app open (fires even if the Garage screen is never opened).
        Thread {
            com.example.opendash.data.MaintenanceNotifier.check(
                applicationContext,
                sync.maintenanceItems(),
                sync.odometer(),
            )
        }.start()

        handleIntent(intent)

        setContent {
            OpenDashTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation(routeViewModel = routeViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't leak / accumulate the auth listener across Activity recreations.
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        authListener = null
    }

    private fun initializeKakaoNaviSdk() {
        val nativeKey = BuildConfig.KAKAO_NATIVE_APP_KEY

        if (nativeKey.isBlank()) {
            Log.e("KNSDK", "KAKAO_NATIVE_APP_KEY is empty. Check local.properties.")
            return
        }

        Log.i("KNSDK", "Installing KNSDK...")
        KNSDK.install(application, "${filesDir}/knsdk")

        Log.i("KNSDK", "Initializing KNSDK...")
        KNSDK.initializeWithAppKey(
            nativeKey,
            BuildConfig.VERSION_NAME,
            "opendashkr-debug-user",
            "ko",
            aCompletion = { error ->
                if (error != null) {
                    Log.e("KNSDK", "KNSDK init error: ${error.code} / $error")
                } else {
                    Log.i("KNSDK", "KNSDK initialized")
                }
            },
        )
    }

    private fun logRuntimeKakaoInfo() {
        val nativeKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        val restKey = BuildConfig.KAKAO_REST_API_KEY

        Log.i("KNSDK", "runtime packageName=$packageName")
        Log.i("KNSDK", "runtime applicationId=${BuildConfig.APPLICATION_ID}")
        Log.i("KNSDK", "runtime versionName=${BuildConfig.VERSION_NAME}")

        Log.i("KNSDK", "nativeKey length=${nativeKey.length}")
        Log.i(
            "KNSDK",
            "nativeKey masked=${nativeKey.take(4)}...${nativeKey.takeLast(4)}",
        )

        Log.i("KNSDK", "restKey length=${restKey.length}")
        Log.i(
            "KNSDK",
            "restKey masked=${restKey.take(4)}...${restKey.takeLast(4)}",
        )

        val hashes = getRuntimeKeyHashes()
        if (hashes.isEmpty()) {
            Log.e("KNSDK", "runtime keyHash list is empty")
        } else {
            hashes.forEachIndexed { index, hash ->
                Log.i("KNSDK", "runtime keyHash[$index]=$hash")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getRuntimeKeyHashes(): List<String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                packageInfo.signatures
            } ?: return emptyList()

            signatures.map { signature ->
                val md = MessageDigest.getInstance("SHA")
                md.update(signature.toByteArray())
                Base64.encodeToString(md.digest(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("KNSDK", "Failed to get runtime key hash", e)
            emptyList()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }

            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}