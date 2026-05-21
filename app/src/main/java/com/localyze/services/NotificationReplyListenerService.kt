package com.localyze.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.localyze.data.repository.ReplyDraftRepository
import com.localyze.domain.models.ReplyDraft
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationReplyListenerService : NotificationListenerService() {
    @Inject lateinit var replyDraftRepository: ReplyDraftRepository

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return
        if (!looksLikeCommunicationApp(packageName)) return

        val extras = sbn.notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank() || text.length < 3) return

        scope.launch {
            replyDraftRepository.save(
                ReplyDraft(
                    sourcePackage = packageName,
                    sender = sender.take(120),
                    originalText = text.take(2_000),
                    draftText = "Thanks for the message. I will get back to you soon.",
                    channel = "notification"
                )
            )
        }
    }

    private fun looksLikeCommunicationApp(packageName: String): Boolean {
        val name = packageName.lowercase()
        return listOf("messaging", "messages", "sms", "mms", "gmail", "mail", "email", "whatsapp", "telegram", "signal")
            .any { token -> name.contains(token) }
    }
}
