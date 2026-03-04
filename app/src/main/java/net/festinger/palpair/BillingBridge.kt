package net.festinger.palpair

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.android.billingclient.api.*

/**
 * Google Play Billing bridge for WebView.
 *
 * Setup in your Activity/Fragment:
 * 1. Add dependency in build.gradle:
 *    implementation "com.android.billingclient:billing:7.0.0"
 *
 * 2. In your WebView setup:
 *    val billingBridge = BillingBridge(this, webView)
 *    webView.addJavascriptInterface(billingBridge, "PalpairApp")
 *
 * 3. Define your product IDs in Google Play Console matching these:
 *    - palpair_coins_100  ($1.00 → 100 coins)
 *    - palpair_coins_600  ($5.00 → 600 coins)
 *    - palpair_coins_1500 ($10.00 → 1,500 coins)
 */
class BillingBridge(
    private val activity: Activity,
    private val webView: WebView
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "PalpairBilling"

        // Map web package IDs to Google Play product IDs
        val PRODUCT_MAP = mapOf(
            "pack_100" to "palpair_coins_100",
            "pack_600" to "palpair_coins_600",
            "pack_1500" to "palpair_coins_1500"
        )
    }

    private var billingClient: BillingClient
    private var pendingPackageId: String? = null
    private var pendingSocketId: String? = null
    private var productDetailsList: List<ProductDetails> = emptyList()

    init {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        billingClient = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()

        connectBillingClient()
    }

    private fun connectBillingClient() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected")
                    queryProducts()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected, reconnecting...")
                connectBillingClient()
            }
        })
    }

    private fun queryProducts() {
        val productList = PRODUCT_MAP.values.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, resultData ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetailsList = resultData.productDetailsList
                Log.d(TAG, "Loaded ${productDetailsList.size} products")
            } else {
                Log.e(TAG, "Failed to query products: ${result.debugMessage}")
            }
        }
    }

    /**
     * Called from JavaScript: window.PalpairApp.purchaseCoins("pack_100", "socketId123")
     */
    @JavascriptInterface
    fun purchaseCoins(packageId: String, socketId: String) {
        val googleProductId = PRODUCT_MAP[packageId]
        if (googleProductId == null) {
            notifyWebView(packageId, "", "", false)
            return
        }

        pendingPackageId = packageId
        pendingSocketId = socketId

        val productDetails = productDetailsList.find { it.productId == googleProductId }
        if (productDetails == null) {
            Log.e(TAG, "Product not found: $googleProductId")
            notifyWebView(packageId, "", "", false)
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
            )
            .build()

        activity.runOnUiThread {
            val result = billingClient.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Launch billing flow failed: ${result.debugMessage}")
                notifyWebView(packageId, "", "", false)
            }
        }
    }

    /**
     * Called by Google Play when purchase completes
     */
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val pkgId = pendingPackageId ?: return

        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        Log.d(TAG, "Purchase successful: ${purchase.orderId}")

                        // Consume the purchase so it can be bought again
                        val consumeParams = ConsumeParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()

                        billingClient.consumeAsync(consumeParams) { consumeResult, _ ->
                            if (consumeResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                Log.d(TAG, "Purchase consumed successfully")
                            }
                        }

                        // Notify web page of success
                        notifyWebView(
                            pkgId,
                            purchase.purchaseToken,
                            purchase.orderId ?: "",
                            true
                        )
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase cancelled by user")
                notifyWebView(pkgId, "", "", false)
            }
            else -> {
                Log.e(TAG, "Purchase error: ${result.responseCode} - ${result.debugMessage}")
                notifyWebView(pkgId, "", "", false)
            }
        }

        pendingPackageId = null
        pendingSocketId = null
    }

    /**
     * Call back into the WebView with purchase result
     */
    private fun notifyWebView(packageId: String, purchaseToken: String, orderId: String, success: Boolean) {
        val js = "javascript:window.onGooglePlayPurchaseResult('$packageId','$purchaseToken','$orderId',$success)"
        activity.runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    fun destroy() {
        billingClient.endConnection()
    }
}