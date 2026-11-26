package br.edu.uff.rescuenet.data.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano do tipo "location".
 *
 * Ele não rastreia nada sozinho: o GPS continua no LocationTracker do
 * ViewModel. Enquanto este serviço está ativo, o Android trata o app como
 * "em uso" e continua entregando posições com a tela bloqueada ou com o app
 * em segundo plano (sem ele, a entrega cai para poucas vezes por hora).
 */
class LocationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        // Se o sistema matar o processo, o rastreamento (no ViewModel) também
        // acaba; não faz sentido recriar só a notificação.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Compartilhamento de localização",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indica que o RescueNet está enviando a localização à central."
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RescueNet")
            .setContentText("Compartilhando localização com a central de operações")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    // Toque na notificação: traz o app de volta para a frente.
    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null

        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val CHANNEL_ID = "rescuenet_location"
        private const val NOTIFICATION_ID = 1001

        /**
         * Inicia o serviço. Deve ser chamado com o app em primeiro plano
         * (Android 12+ proíbe iniciar serviço em primeiro plano a partir do
         * segundo plano) e com a permissão de localização já concedida.
         *
         * @return null se iniciou, ou a mensagem de erro.
         */
        fun start(context: Context): String? = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationForegroundService::class.java)
            )
            null
        } catch (error: Exception) {
            error.message ?: error.javaClass.simpleName
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }
}
