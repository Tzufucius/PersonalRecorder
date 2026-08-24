package io.github.tuzfucius.personalrecorder.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuzfucius.personalrecorder.PersonalRecorderApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalRecorderApplicationInstrumentedTest {
    @Test
    fun manifestUsesProcessApplication() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue(application is PersonalRecorderApplication)
    }
}
