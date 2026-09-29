package org.pocketworkstation.pckeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class EmojiDataTest {
    private static final String ASSETS = "src/main/assets/emoji/";

    private static Reader asset(String name) throws IOException {
        return new InputStreamReader(new FileInputStream(ASSETS + name), "UTF-8");
    }

    private static EmojiData load(String... languages) throws IOException {
        EmojiData data = EmojiData.parse(asset("emoji.txt"));
        for (String lang : languages) data.addKeywords(asset("keywords_" + lang + ".txt"));
        return data;
    }

    private static final EmojiData.GlyphChecker ALL = new EmojiData.GlyphChecker() {
        public boolean canRender(String emoji) {
            return true;
        }
    };

    @Test
    public void generatedDataIsConsistent() throws IOException {
        EmojiData data = load();
        assertEquals(9, data.getCategories().size());
        assertEquals("smileys", data.getCategories().get(0).id);
        assertEquals("flags", data.getCategories().get(8).id);
        assertTrue(data.getAll().size() > 1800);
        Set<String> forms = new HashSet<String>();
        for (EmojiData.Emoji e : data.getAll()) {
            assertTrue("duplicate " + e.base, forms.add(e.base));
            int n = e.variants.length;
            assertTrue(e.base + " has " + n + " variants", n == 0 || n == 5 || n == 25);
            for (String v : e.variants) {
                assertTrue("duplicate " + v, forms.add(v));
                assertSame(e, data.find(v));
            }
        }
    }

    @Test
    public void twoPersonVariantsFormToneGrid() throws IOException {
        EmojiData.Emoji handshake = load().find("\uD83E\uDD1D"); // 🤝
        assertEquals(25, handshake.variants.length);
        // The diagonal of the 5x5 grid is "both hands the same tone".
        assertEquals("\uD83E\uDD1D\uD83C\uDFFB", handshake.variants[0]);  // 🤝🏻
        assertEquals("\uD83E\uDD1D\uD83C\uDFFF", handshake.variants[24]); // 🤝🏿
        // Row = first hand's tone, column = second hand's: 🫱🏻‍🫲🏼
        assertEquals("\uD83E\uDEF1\uD83C\uDFFB\u200D\uD83E\uDEF2\uD83C\uDFFC", handshake.variants[1]);
    }

    @Test
    public void findIgnoresMissingVariationSelector() throws IOException {
        EmojiData data = load();
        EmojiData.Emoji heart = data.find("\u2764\uFE0F"); // ❤️
        assertNotNull(heart);
        assertSame(heart, data.find("\u2764"));
    }

    @Test
    public void filterDropsUnrenderableEmojiAndPartialVariants() throws IOException {
        EmojiData data = load();
        final String handshake = "\uD83E\uDD1D";
        final String wave = "\uD83D\uDC4B"; // 👋
        final String pizza = "\uD83C\uDF55"; // 🍕
        data.filter(new EmojiData.GlyphChecker() {
            public boolean canRender(String emoji) {
                if (emoji.equals(pizza)) return false;
                // Mixed tone handshakes (🫱🏻‍🫲🏼) are not supported, same tone ones are.
                if (emoji.contains("\uD83E\uDEF1")) return false;
                // Only one tone of the waving hand is supported: drop them all.
                if (emoji.startsWith(wave) && emoji.endsWith("\uD83C\uDFFF")) return false;
                return true;
            }
        }, true);
        assertEquals(null, data.find(pizza));
        assertEquals(5, data.find(handshake).variants.length);
        assertEquals("\uD83E\uDD1D\uD83C\uDFFD", data.find(handshake).variants[2]);
        assertFalse(data.find(wave).hasVariants());

        data.filter(ALL, false);
        assertFalse(data.find(handshake).hasVariants());
    }

    @Test
    public void searchRanksNameMatchesFirst() throws IOException {
        EmojiData data = load("en");
        assertEquals("\uD83C\uDF55", data.search("pizza", 10).get(0).base); // 🍕
        assertEquals("\u2764\uFE0F", data.search("red heart", 10).get(0).base); // ❤️
        // Prefixes match while typing.
        List<EmojiData.Emoji> partial = data.search("piz", 10);
        assertEquals("\uD83C\uDF55", partial.get(0).base);
        // Every word must match.
        assertTrue(data.search("pizza xyzzy", 10).isEmpty());
        assertTrue(data.search("   ", 10).isEmpty());
        // Flags are found by country name.
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", data.search("germany", 5).get(0).base); // 🇩🇪
    }

    @Test
    public void searchPrefersShortNamesAndUsedEmoji() throws IOException {
        EmojiData data = load("en");
        // 🐈 "cat" beats 🐱 "cat face" and 😺 "grinning cat".
        assertEquals("\uD83D\uDC08", data.search("cat", 5).get(0).base);
        // Recently used emoji move up among equally good matches, not above better ones.
        EmojiData.Emoji redHeart = data.find("\u2764\uFE0F");
        assertFalse(redHeart == data.search("heart", 20).get(0));
        Set<EmojiData.Emoji> recents = new HashSet<EmojiData.Emoji>();
        recents.add(redHeart);
        assertSame(redHeart, data.search("heart", 20, recents).get(0));
        recents.clear();
        recents.add(data.find("\uD83C\uDF44\u200D\uD83D\uDFEB")); // 🍄‍🟫, keyword "pizza"
        assertEquals("\uD83C\uDF55", data.search("piz", 5, recents).get(0).base);
    }

    @Test
    public void searchIgnoresCaseAndAccents() throws IOException {
        EmojiData data = load("de", "en");
        List<EmojiData.Emoji> results = data.search("HANDSCHLAG", 5);
        assertEquals("\uD83E\uDD1D", results.get(0).base);
        assertFalse(data.search("hande", 5).isEmpty()); // "Hände"
        // English keywords still work alongside the German ones.
        assertEquals("\uD83E\uDD1D", data.search("handshake", 5).get(0).base);
        assertEquals("Handschlag", data.find("\uD83E\uDD1D").getName());
    }

    @Test
    public void namesComeFromTheBaseEmojiNotSkinToneVariants() throws IOException {
        EmojiData data = load("en");
        // 👩‍❤️‍💋‍👨
        EmojiData.Emoji kiss = data.find("\uD83D\uDC69\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68");
        assertEquals("kiss: woman, man", kiss.getName());
    }

    @Test
    public void suggestNeedsWholeWords() throws IOException {
        EmojiData data = load("en");
        List<EmojiData.Emoji> dog = data.suggest("Dog", 2);
        assertEquals(2, dog.size());
        assertEquals("\uD83D\uDC15", dog.get(0).base); // 🐕 is named just "dog"
        assertFalse(data.suggest("do", 2).contains(dog.get(0))); // prefixes are not enough
        assertTrue(data.suggest("x", 2).isEmpty());
        assertTrue(data.suggest("qwertyuiop", 2).isEmpty());
        // Short and common words are CLDR keywords too, but too noisy while typing.
        assertTrue(data.suggest("do", 2).isEmpty());   // 📋 "do"
        assertTrue(data.suggest("not", 2).isEmpty());  // ⛔ "not"
        assertFalse(data.suggest("love", 2).isEmpty());
        assertFalse(data.search("not", 5).isEmpty());  // search still finds them
    }

    @Test
    public void parseRejectsMalformedData() {
        try {
            EmojiData.parse(new StringReader("\uD83D\uDE00 1.0\n"));
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
        }
    }

    private static int lastLen(String s) {
        return EmojiData.lastSequenceLength(s);
    }

    @Test
    public void lastSequenceLength() {
        assertEquals(0, lastLen(""));
        assertEquals(1, lastLen("abc"));
        assertEquals(1, lastLen("e\u0301")); // combining mark: left to the editor
        assertEquals(2, lastLen("a\uD83D\uDE00")); // 😀
        assertEquals(2, lastLen("\u2764\uFE0F")); // ❤️
        assertEquals(4, lastLen("\uD83D\uDC4B\uD83C\uDFFD")); // 👋🏽
        assertEquals(3, lastLen("x#\uFE0F\u20E3")); // #️⃣
        // 👩‍💻 and 👨‍👩‍👧‍👦
        assertEquals(5, lastLen("hi \uD83D\uDC69\u200D\uD83D\uDCBB"));
        assertEquals(11, lastLen("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"));
        // 🧑🏻‍❤️‍💋‍🧑🏼
        String kiss = "\uD83E\uDDD1\uD83C\uDFFB\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83E\uDDD1\uD83C\uDFFC";
        assertEquals(kiss.length(), lastLen("a" + kiss));
        // Flags pair up from the start of a run of regional indicators: 🇩🇪🇫🇷 -> 🇫🇷
        String de = "\uD83C\uDDE9\uD83C\uDDEA", fr = "\uD83C\uDDEB\uD83C\uDDF7";
        assertEquals(4, lastLen(de + fr));
        assertEquals(2, lastLen(de + fr + "\uD83C\uDDEB")); // dangling indicator
        // 🏴󠁧󠁢󠁥󠁮󠁧󠁿 (England): black flag + tag sequence
        String england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F";
        assertEquals(england.length(), lastLen("go " + england));
        assertTrue(EmojiData.MAX_SEQUENCE_LENGTH >= england.length());
        // ZWJ inside Indic words is not an emoji joiner: क्‍ष keeps its half form.
        assertEquals(1, lastLen("\u0915\u094D\u200D\u0937"));
        // 🏳️‍⚧️ joins a BMP symbol.
        assertEquals(6, lastLen("\uD83C\uDFF3\uFE0F\u200D\u26A7\uFE0F"));
    }
}
