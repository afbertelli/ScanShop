package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.model.StoreType

object DeepLinkUtils {

    const val MERCADO_LIVRE_MATT_TOOL = "75816733"
    const val MERCADO_LIVRE_MATT_WORD = "scanshop"

    fun formatMercadoLivreAffiliateUrl(url: String): String {
        if (url.isBlank()) return url
        var clean = url.trim()
        if (clean.startsWith("http://", ignoreCase = true)) {
            clean = "https://" + clean.substring(7)
        }
        val baseUrl = clean.substringBefore("#")
        if (baseUrl.contains("matt_tool=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}matt_tool=$MERCADO_LIVRE_MATT_TOOL&matt_word=$MERCADO_LIVRE_MATT_WORD&forceInApp=true"
    }

    fun formatAliExpressAffiliateUrl(url: String): String {
        if (url.isBlank()) return url
        var clean = url.trim()
        if (clean.startsWith("http://")) {
            clean = "https://" + clean.removePrefix("http://")
        }
        val sep = if (clean.contains("?")) "&" else "?"
        return if (!clean.contains("tracking_id=") && !clean.contains("sk=")) {
            "$clean${sep}tracking_id=scanshop&aff_platform=true"
        } else {
            clean
        }
    }

    fun formatShopeeAffiliateUrl(url: String): String {
        if (url.isBlank()) return url
        var clean = url.trim()
        if (clean.startsWith("http://")) {
            clean = "https://" + clean.removePrefix("http://")
        }
        val sep = if (clean.contains("?")) "&" else "?"
        return if (!clean.contains("utm_source=")) {
            "$clean${sep}utm_source=an_18380240153&utm_medium=affiliates"
        } else {
            clean
        }
    }

    fun openAffiliateUrl(
        context: Context,
        store: StoreType,
        affiliateUrl: String,
        deepLinkUrl: String = ""
    ) {
        val storePackage = when (store) {
            StoreType.ALIEXPRESS -> "com.alibaba.aliexpresshd"
            StoreType.SHOPEE -> "com.shopee.br"
            StoreType.MERCADO_LIVRE -> "com.mercadolibre"
        }

        val targetAffiliateUrl = when (store) {
            StoreType.MERCADO_LIVRE -> formatMercadoLivreAffiliateUrl(affiliateUrl)
            StoreType.ALIEXPRESS -> formatAliExpressAffiliateUrl(affiliateUrl)
            StoreType.SHOPEE -> formatShopeeAffiliateUrl(affiliateUrl)
        }

        var handled = false
        try {
            val targetDeepLink = if (deepLinkUrl.isNotBlank() && !deepLinkUrl.startsWith("http")) {
                deepLinkUrl
            } else {
                targetAffiliateUrl
            }
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetDeepLink)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(storePackage)
            }
            context.startActivity(deepLinkIntent)
            handled = true
        } catch (_: Exception) {
            handled = false
        }

        if (!handled) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetAffiliateUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Não foi possível abrir o link.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
