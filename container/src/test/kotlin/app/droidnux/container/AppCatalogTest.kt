package app.droidnux.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class AppCatalogTest {

    private val sample = """
        ---
        File: DEP-11
        Version: '1.0'
        ---
        Type: desktop-application
        ID: org.gimp.GIMP
        Package: gimp
        Name:
          C: GNU Image Manipulation Program
          it: Programma di manipolazione immagini GNU
        Summary:
          C: Create images and edit photographs
          it: 'Crea immagini e modifica fotografie, l''originale'
        Description:
          C: >-
            <p>Name: not this</p>
        Categories:
        - Graphics
        - RasterGraphics
        Keywords:
          C:
          - Photo
          it:
          - Foto
        Icon:
          cached:
          - name: gimp_gimp.png
            width: 64
            height: 64
          remote:
          - url: org/gimp/GIMP/abc/icons/128x128/gimp_gimp.png
            width: 128
            height: 128
          stock: gimp
        Screenshots:
        - default: true
          caption:
            C: A long caption
          thumbnails:
          - url: org/gimp/GIMP/abc/screenshots/image-1_1248x702@1.png
            width: 1248
            height: 702
          - url: org/gimp/GIMP/abc/screenshots/image-1_752x423@1.png
            width: 752
            height: 423
          source-image:
            url: org/gimp/GIMP/abc/screenshots/image-1_orig.png
            width: 1920
            height: 1080
        - thumbnails:
          - url: org/gimp/GIMP/abc/screenshots/image-2_752x423@1.png
            width: 752
            height: 423
        ---
        Type: addon
        ID: org.gimp.GIMP.data_extras
        Package: gimp-data-extras
        Name:
          C: Extra files for GIMP
        Summary:
          C: Patterns
        ---
        Type: desktop-application
        ID: kate.desktop
        Package: kate
        Extends:
        - org.kde.something
        Name:
          C: Extension
        Summary:
          C: Not an app
        ---
        Type: desktop-application
        ID: org.example.Long
        Package: long-app
        Name:
          C: Long &amp; Co
        Summary:
          C: A summary that goes on
            over two lines
    """.trimIndent()

    @Test
    fun parsesAppsInTheLanguage() {
        val apps = AppCatalog.parse(sample.byteInputStream(), "it")
        assertEquals(listOf("org.gimp.GIMP", "org.example.Long"), apps.map { it.id })
        val gimp = apps[0]
        assertEquals("gimp", gimp.pkg)
        assertEquals("Programma di manipolazione immagini GNU", gimp.name)
        assertEquals("Crea immagini e modifica fotografie, l'originale", gimp.summary)
        assertEquals(listOf("Graphics", "RasterGraphics"), gimp.categories)
        assertEquals("photo foto", gimp.keywords)
        assertEquals("https://appstream.debian.org/media/trixie/org/gimp/GIMP/abc/screenshots/image-1_752x423@1.png", gimp.screenshot)
        val long = apps[1]
        assertEquals("Long & Co", long.name)
        assertEquals("A summary that goes on over two lines", long.summary)
        assertNull(long.screenshot)
    }

    @Test
    fun fallsBackToTheUntranslatedTexts() {
        val gimp = AppCatalog.parse(sample.byteInputStream(), "de").first()
        assertEquals("GNU Image Manipulation Program", gimp.name)
        assertEquals("photo", gimp.keywords)
    }

    /** Debian's real catalog, when its path is given: DN_DEP11=.../Components-arm64.yml.gz */
    @Test
    fun parsesDebiansCatalog() {
        val path = System.getenv("DN_DEP11") ?: return
        val apps = File(path).inputStream().use { AppCatalog.parse(GZIPInputStream(it), "it") }
        println("apps: ${apps.size}, with screenshot: ${apps.count { it.screenshot != null }}")
        apps.filter { it.pkg in setOf("gimp", "krita", "vlc", "firefox-esr", "freedv") }.forEach(::println)
        assertTrue(apps.size > 1500)
        assertTrue(apps.all { '\t' !in it.name && '\t' !in it.summary && it.name.isNotBlank() })
    }
}
