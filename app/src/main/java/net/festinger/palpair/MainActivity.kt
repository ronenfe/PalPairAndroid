package net.festinger.palpair

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity with WebView + WebRTC camera/mic + BillingBridge.
 *
 * build.gradle dependencies:
 *   implementation "com.android.billingclient:billing:7.0.0"
 *   implementation "androidx.appcompat:appcompat:1.7.0"
 *   implementation "androidx.webkit:webkit:1.11.0"
 *
 * AndroidManifest.xml permissions:
 *   <uses-permission android:name="android.permission.INTERNET" />
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *   <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
 *   <uses-permission android:name="com.android.vending.BILLING" />
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var webView: WebView
    private lateinit var billingBridge: BillingBridge

    // Queued WebView permission request waiting for Android runtime grant
    private var pendingPermissionRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // WebView settings for WebRTC + camera/mic
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
        }

        // Handle camera/mic permission requests from the web page
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                // If Android runtime permissions already granted, approve immediately
                if (hasAllPermissions()) {
                    request.grant(request.resources)
                } else {
                    // Queue the WebView request and ask the user for runtime permissions
                    pendingPermissionRequest = request
                    requestRuntimePermissions()
                }
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.javaScriptEnabled = true
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        // Billing bridge — exposes window.PalpairApp in JavaScript
        billingBridge = BillingBridge(this, webView)
        webView.addJavascriptInterface(billingBridge, "PalpairApp")

        // Ask for camera + mic up front so they're ready when getUserMedia fires
        if (!hasAllPermissions()) {
            requestRuntimePermissions()
        }

        webView.loadUrl("https://app.palpair.lol/")
    }

    /** Check if CAMERA and RECORD_AUDIO are both granted */
    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Show the Android runtime permission dialog for camera + mic */
    private fun requestRuntimePermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    /** Called when user responds to the runtime permission dialog */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            pendingPermissionRequest?.let { req ->
                if (allGranted) {
                    req.grant(req.resources)
                } else {
                    req.deny()
                }
                pendingPermissionRequest = null
            }
        }
    }

    override fun onDestroy() {
        billingBridge.destroy()
        webView.destroy()
        super.onDestroy()
    }
}