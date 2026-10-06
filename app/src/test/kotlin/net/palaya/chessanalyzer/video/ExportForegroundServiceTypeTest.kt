package net.palaya.chessanalyzer.video

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The export service's foreground-service type by SDK level (D1): `mediaProcessing` from Android 15,
 * `dataSync` on 10-14, none below. The literal values are the platform's (ServiceInfo), restated so
 * a wrong constant in the code cannot make the test agree with itself.
 */
class ExportForegroundServiceTypeTest {

    private val dataSync = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val mediaProcessing = 0x2000 // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING

    @Test
    fun android15AndLaterUseMediaProcessing() {
        assertEquals(mediaProcessing, ExportForegroundServiceType.forSdk(35))
        assertEquals(mediaProcessing, ExportForegroundServiceType.forSdk(36))
        assertEquals(mediaProcessing, ExportForegroundServiceType.forSdk(37))
    }

    @Test
    fun android10To14UseDataSync() {
        for (sdk in 29..34) assertEquals("sdk $sdk", dataSync, ExportForegroundServiceType.forSdk(sdk))
    }

    @Test
    fun belowAndroid10HasNoType() {
        for (sdk in 26..28) assertEquals("sdk $sdk", 0, ExportForegroundServiceType.forSdk(sdk))
    }

    @Test
    fun manifestDeclaresBothTypesTheCodeCanPick() {
        val f = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
        val services = root.getElementsByTagName("service")
        val export = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttribute("android:name").endsWith("VideoExportService") }
        assertEquals(
            setOf("dataSync", "mediaProcessing"),
            export.getAttribute("android:foregroundServiceType").split('|').toSet(),
        )
    }
}
