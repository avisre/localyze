package com.localassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localassistant.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreSmokeInstrumentationTest {
    @Test
    fun connectedDevice_coreDaosAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.localassistant", context.packageName)

        val db = AppDatabase.getInstance(context)
        assertNotNull(db.conversationDao())
        assertNotNull(db.messageDao())
        assertNotNull(db.memoryDao())
        assertNotNull(db.taskDao())
        assertNotNull(db.attachmentMemoryDao())
        assertNotNull(db.toolAuditDao())
        assertNotNull(db.replyDraftDao())
    }
}
