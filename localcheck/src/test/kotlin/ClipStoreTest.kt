import com.typefacilitator.ClipStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipStoreTest {
    @Test fun holdsHundredClipsInAnyLanguage() {
        val f = File.createTempFile("clips", ".bin"); f.delete()
        val store = ClipStore(f)
        for (i in 1..105) store.add("clip number $i")
        assertEquals(100, store.items.size)
        assertEquals("clip number 105", store.items[0].text)
        val mixed = "Привет, мир! 你好，世界 مرحبا بالعالم こんにちは 🙂👍🏽 naïve café\nsecond line"
        store.add(mixed)
        store.togglePin(store.items[0])
        for (i in 1..150) store.add("later $i")
        assertEquals(100, store.items.size)
        assertTrue(store.items.any { it.text == mixed && it.pinned }, "pinned clip must survive")
        // copying the same text again moves it to the top instead of storing it twice
        store.add("later 7")
        assertEquals("later 7", store.items[0].text)
        assertEquals(1, store.items.count { it.text == "later 7" })
        // survives a restart, character for character
        val again = ClipStore(f)
        assertEquals(store.items.map { it.text }, again.items.map { it.text })
        assertEquals(mixed, again.ordered()[0].text)
        again.clear(true)
        assertEquals(listOf(mixed), again.items.map { it.text })
        again.clear(false)
        assertEquals(0, ClipStore(f).items.size)
        f.delete()
    }
}
