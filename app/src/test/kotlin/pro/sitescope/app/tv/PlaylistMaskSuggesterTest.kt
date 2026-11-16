package pro.sitescope.app.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMaskSuggesterTest {
    @Test fun findsPortIpAndPathMasksFromHttpPlaylist() {
        val source = "http://85.14.32.26:8800/live/123/index.m3u8"
        val suggestions = PlaylistMaskSuggester.suggest(source)
        assertTrue(suggestions.any { it.template == "http://85.14.32.26:88%02d{0-99}/live/123/index.m3u8" && it.variants == 100 })
        assertTrue(suggestions.any { it.template == "http://85.14.32.%d{0-255}:8800/live/123/index.m3u8" && it.variants == 256 })
        assertTrue(suggestions.any { it.template == "http://85.14.32.26:8800/live/%03d{0-999}/index.m3u8" && it.variants == 1000 })
    }

    @Test fun keepsHttpsAndSignedQueryUnchanged() {
        val url = "https://tv.example.org:443/live/09.ts?token=12345&expires=42"
        val suggestions = PlaylistMaskSuggester.suggest(url)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.template.startsWith("https://") })
        assertTrue(suggestions.all { it.template.endsWith("?token=12345&expires=42") })
        assertTrue(suggestions.any { it.template.contains("/%02d{0-99}.ts?") })
    }

    @Test fun ignoresUnsupportedOrMalformedEntries() {
        assertEquals(emptyList<PlaylistMaskSuggester.Suggestion>(), PlaylistMaskSuggester.suggest("udp://@239.1.1.1:1234"))
        assertEquals(emptyList<PlaylistMaskSuggester.Suggestion>(), PlaylistMaskSuggester.suggest("not a url"))
    }

    @Test fun twoDigitPortMaskKeepsColon() {
        assertTrue(PlaylistMaskSuggester.suggest("http://test.example:88/live").any {
            it.template == "http://test.example:%02d{0-99}/live"
        })
    }
}
