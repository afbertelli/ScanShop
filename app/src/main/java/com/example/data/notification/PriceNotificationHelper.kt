package com.example.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Utilitário Oficial de Notificações do Sistema Android para Queda de Preços:
 * - Canal de alta prioridade (NotificationChannel) com som, vibração e badge.
 * - Suporte completo ao Android 13+ (Tiramisu / API 33) com verificação de POST_NOTIFICATIONS.
 * - Abre o aplicativo diretamente na tela de favoritos quando o usuário clica na notificação.
 */
object PriceNotificationHelper {

    const val CHANNEL_ID = "channel_price_drop_alerts"
    const val CHANNEL_NAME = "Alertas de Queda de Preço"
    const val CHANNEL_DESC = "Notificações automáticas quando produtos favoritados sofrem queda de valor superior a 10%"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun sendPriceDropNotification(
        context: Context,
        productId: String,
        productTitle: String,
        storeName: String,
        oldPrice: Double,
        newPrice: Double,
        dropPercent: Double
    ) {
        // Garante que o canal existe
        createNotificationChannel(context)

        // Verificação segura de permissão no Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                // Permissão ainda não concedida pelo usuário
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAVIGATE_TAB", 2) // Aba de Favoritos
            putExtra("EXTRA_HIGHLIGHT_PRODUCT_ID", productId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            productId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val formattedOld = String.format("R$ %.2f", oldPrice).replace('.', ',')
        val formattedNew = String.format("R$ %.2f", newPrice).replace('.', ',')
        val percentText = "${dropPercent.toInt()}%"

        val title = "🔥 Queda de $percentText no $storeName!"
        val contentText = "${productTitle.take(45)}... caiu de $formattedOld para $formattedNew!"
        val bigText = "Boa notícia! O produto que você favoritou sofreu uma redução de $percentText:\n\n" +
                "📦 $productTitle\n" +
                "📉 De: $formattedOld  ➔  Por: $formattedNew\n" +
                "🏪 Plataforma: $storeName"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notificationId = productId.hashCode()
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Tratamento silencioso caso a permissão tenha sido revogada
        }
    }
}
