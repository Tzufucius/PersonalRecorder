package io.github.tuzfucius.personalrecorder.collector

import android.app.Notification
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tuzfucius.personalrecorder.data.PersonalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationParserInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun parserPrefersBigTextAndReadsLines() {
        val notification = Notification.Builder(context, "test")
            .setContentTitle("标题")
            .setContentText("短正文")
            .setStyle(Notification.BigTextStyle().bigText("长正文"))
            .build()
        notification.extras.putCharSequenceArray(
            Notification.EXTRA_TEXT_LINES,
            arrayOf<CharSequence>("第一行", "第二行")
        )

        val event = NotificationParser.parse(statusBarNotification(notification))

        assertNotNull(event)
        assertEquals("长正文", event?.content)
        assertEquals(listOf("第一行", "第二行"), event?.textLines)
        assertEquals("source.app", event?.packageName)
    }

    @Test
    fun parserHandlesMissingExtras() {
        val event = NotificationParser.parse(
            statusBarNotification(Notification.Builder(context, "test").build())
        )

        assertNotNull(event)
        assertEquals(null, event?.title)
        assertEquals(null, event?.content)
        assertTrue(event?.textLines.orEmpty().isEmpty())
    }

    @Test
    fun parserReadsGroupSummaryFlag() {
        val notification = Notification.Builder(context, "test").build().apply {
            flags = flags or Notification.FLAG_GROUP_SUMMARY
        }

        val event = NotificationParser.parse(statusBarNotification(notification))

        assertTrue(event?.isGroupSummary == true)
    }

    @Test
    fun ownPackageIsFiltered() {
        val sbn = statusBarNotification(Notification.Builder(context, "test").build())

        assertFalse(NotificationFilter.shouldCollect(sbn, "source.app"))
        assertTrue(NotificationFilter.shouldCollect(sbn, "another.app"))
    }

    private fun statusBarNotification(notification: Notification): StatusBarNotification =
        StatusBarNotification(
            "source.app",
            "source.app",
            42,
            null,
            Process.myUid(),
            0,
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            1_700_000_000_000L
        )
}
