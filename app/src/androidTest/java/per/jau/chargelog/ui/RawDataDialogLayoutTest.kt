package per.jau.chargelog.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import per.jau.chargelog.R

@RunWith(AndroidJUnit4::class)
class RawDataDialogLayoutTest {

    @Test
    fun dialogRawDataInflatesWithFastScroller() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val themedContext = ContextThemeWrapper(targetContext, R.style.Theme_ChargeLog)

        val dialogView = LayoutInflater.from(themedContext)
            .inflate(R.layout.dialog_raw_data, null, false)

        assertNotNull(dialogView.findViewById<RecyclerView>(R.id.rvRawData))
    }
}
