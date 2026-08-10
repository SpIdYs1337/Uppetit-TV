package com.uppetit.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Проверяем, что событие — это именно загрузка системы
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            // Создаем команду на запуск нашего главного экрана
            val mainActivityIntent = Intent(context, MainActivity::class.java)

            // Добавляем флаг, чтобы создать новый экран вне существующей иерархии
            mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Запускаем приложение
            context.startActivity(mainActivityIntent)
        }
    }
}