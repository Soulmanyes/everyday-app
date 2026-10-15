package app.everyday.android.ui.licenses

import org.junit.Assert.assertEquals
import org.junit.Test

class LicensesTest {
    private val template = License("BSD 3-Clause", null, "Copyright (c) <<var;name=copyright;original= <year> <owner>;match=.+>>.")
    private val filled = License("BSD 3-Clause (Protocol Buffers)", null, "Copyright 2008 Google Inc.")
    private val linkOnly = License("Android SDK License", "https://developer.android.com/studio/terms.html", null)

    @Test fun templateIsDroppedWhenAFilledLicenseExists() = assertEquals(listOf(filled), licensesToShow(listOf(filled, template)))

    @Test fun templateAloneIsStillShown() = assertEquals(listOf(template), licensesToShow(listOf(template)))

    @Test fun linkOnlyLicensesAreKept() = assertEquals(listOf(linkOnly), licensesToShow(listOf(linkOnly)))
}
