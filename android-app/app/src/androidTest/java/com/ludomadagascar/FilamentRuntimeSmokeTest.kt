package com.ludomadagascar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentRuntimeSmokeTest {
    @Test
    fun filamentClassesArePackaged() {
        InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Filament classes must be packaged", FilamentRuntimeSupport.isAvailable())
    }
}
