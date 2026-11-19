package com.nicedevelopments.vomblatt.pro

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.nicedevelopments.vomblatt.BuildConfig
import com.nicedevelopments.vomblatt.R
import com.nicedevelopments.vomblatt.VomBlattApp
import com.nicedevelopments.vomblatt.ui.PrimaryButton
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type
import java.io.File

/** Produkt-ID des Einmalkaufs in der Play Console (In-App-Produkt, nicht verbrauchbar). */
const val PRO_PRODUCT_ID = "notescan_pro"

/**
 * „NoteScan Pro“: einmaliger Kauf über Google Play. Kostenlos bleibt Fotografieren (eine Seite),
 * Erkennen, Anhören, Korrigieren und die Bibliothek; Pro schaltet mehrere Seiten/PDF und die
 * Übe-Werkzeuge frei (Hand stumm, Schleife, Tempo, Metronom).
 *
 * Der zuletzt von Play gemeldete Stand liegt in den App-Einstellungen („pro“), damit Pro auch ohne
 * Verbindung zum Play Store gilt. Die Bibliothek spricht mit der Play-Store-App, nicht mit uns.
 * Testfassung: Datei `files/pro` im App-Speicher schaltet Pro ein (für Screenshots und Tests).
 */
class ProBilling(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    val isPro = mutableStateOf(prefs.getBoolean("pro", false) || debugOverride())
    val price = mutableStateOf<String?>(null)
    /** Pro-Seite sichtbar (von [require] geöffnet). */
    val sheet = mutableStateOf(false)
    /** Kurze Rückmeldung auf der Pro-Seite, z. B. „nur über Google Play“. */
    val message = mutableStateOf<Int?>(null)

    private var details: ProductDetails? = null

    private fun debugOverride() = BuildConfig.DEBUG && File(app.filesDir, "pro").exists()

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases)
            else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refreshPurchases()
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (client.isReady) { refresh(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /** Führt [action] aus, wenn Pro freigeschaltet ist – sonst öffnet sich die Pro-Seite. */
    fun require(action: () -> Unit) {
        if (isPro.value) action() else { message.value = null; sheet.value = true; start() }
    }

    fun buy(activity: Activity) {
        val product = details ?: run { message.value = R.string.pro_unavailable; start(); return }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        if (client.launchBillingFlow(activity, params).responseCode != BillingClient.BillingResponseCode.OK) {
            message.value = R.string.pro_unavailable
        }
    }

    fun restore() {
        if (!client.isReady) { message.value = R.string.pro_unavailable; start(); return }
        refreshPurchases(reportNone = true)
    }

    private fun refresh() {
        refreshPurchases()
        val query = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRO_PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        client.queryProductDetailsAsync(query) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val product = list.productDetailsList.firstOrNull { it.productId == PRO_PRODUCT_ID }
            details = product
            price.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    private fun refreshPurchases(reportNone: Boolean = false) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handle(purchases)
                if (reportNone && !isPro.value) message.value = R.string.pro_none_found
            }
        }
    }

    private fun handle(purchases: List<Purchase>) {
        val owned = purchases.filter { PRO_PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Unbestätigte Käufe erstattet Google nach drei Tagen zurück.
        for (p in owned.filterNot { it.isAcknowledged }) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        val has = owned.isNotEmpty() || debugOverride()
        val was = isPro.value
        isPro.value = has
        prefs.edit().putBoolean("pro", owned.isNotEmpty()).apply()
        if (has && !was && sheet.value) message.value = R.string.pro_thanks
    }
}

/** Kurzform für die Oberfläche: `pro { … }` führt nur mit Pro aus, sonst Pro-Seite. */
@Composable
fun rememberPro(): ProBilling = (LocalContext.current.applicationContext as VomBlattApp).pro

/** Die Pro-Seite: was Pro enthält, Kaufknopf mit Preis, Kauf wiederherstellen. */
@Composable
fun ProSheet(pro: ProBilling, activity: Activity) {
    if (!pro.sheet.value) return
    val ink = LocalInk.current
    val unlocked = pro.isPro.value
    AlertDialog(
        onDismissRequest = { pro.sheet.value = false },
        title = { Text(stringResource(R.string.pro_title), style = Type.title) },
        text = {
            Column {
                Text(stringResource(R.string.pro_lead), style = Type.body, color = ink.ink2)
                Spacer(Modifier.height(14.dp))
                for (f in listOf(R.string.pro_f_pages, R.string.pro_f_hands, R.string.pro_f_loop, R.string.pro_f_tempo)) {
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, null, tint = ink.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(f), style = Type.body, color = ink.ink)
                    }
                }
                pro.message.value?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(it), style = Type.label, color = if (it == R.string.pro_thanks) ink.accent else ink.warn)
                }
                if (!unlocked) {
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(
                        pro.price.value?.let { stringResource(R.string.pro_buy, it) } ?: stringResource(R.string.pro_buy_noprice),
                        null, { pro.buy(activity) }, Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!unlocked) TextButton({ pro.restore() }) { Text(stringResource(R.string.pro_restore)) }
                TextButton({ pro.sheet.value = false }) { Text(stringResource(if (unlocked) R.string.done else R.string.pro_later)) }
            }
        },
    )
}
