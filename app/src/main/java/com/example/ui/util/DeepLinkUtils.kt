package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.model.Product
import com.example.data.model.StoreType

object DeepLinkUtils {

    /**
     * Executes transparent affiliate redirection:
     * 1. Attempts to open installed official app via Deep Link
     * 2. Fallbacks immediately to standard browser with affiliate tracking cookies
     */
    fun openAffiliateProduct(context: Context, product: Product) {
        val storePackage = when (product.store) {
            StoreType.ALIEXPRESS -> "com.alibaba.aliexpresshd"
            StoreType.SHOPEE -> "com.shopee.br"
        }

        val feedbackMsg = when (product.store) {
            StoreType.ALIEXPRESS -> "Abrindo oferta no AliExpress..."
            StoreType.SHOPEE -> "Abrindo oferta na Shopee..."
        }
        Toast.makeText(context, feedbackMsg, Toast.LENGTH_SHORT).show()

        var handled = false

        // Attempt 1: Deep Link direct to store app
        try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(product.deepLinkUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(storePackage)
            }
            if (deepLinkIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(deepLinkIntent)
                handled = true
            }
        } catch (_: Exception) {
            handled = false
        }

        // Attempt 2: Fallback to tracking affiliate link in Web Browser / Chrome Custom Tab
        if (!handled) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(product.affiliateUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao abrir link: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
