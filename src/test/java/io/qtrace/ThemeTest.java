package io.qtrace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {

    @Test
    void modeParsing_fallsBackOnDark() {
        assertEquals(Theme.Mode.LIGHT, Theme.Mode.parse("light"));
        assertEquals(Theme.Mode.AUTO, Theme.Mode.parse("AUTO"));
        assertEquals(Theme.Mode.DARK, Theme.Mode.parse(null));
        assertEquals(Theme.Mode.DARK, Theme.Mode.parse("blue"));
    }

    @Test
    void resolve_followsQuPathOnlyInAuto() {
        assertEquals(Theme.DARK,  Theme.resolve(Theme.Mode.DARK,  false));
        assertEquals(Theme.LIGHT, Theme.resolve(Theme.Mode.LIGHT, true));
        assertEquals(Theme.LIGHT, Theme.resolve(Theme.Mode.AUTO,  false));
        assertEquals(Theme.DARK,  Theme.resolve(Theme.Mode.AUTO,  true));
        assertEquals(Theme.DARK,  Theme.resolve(Theme.Mode.AUTO,  null));
    }

    @Test
    void bothPalettesDefineEveryToken() {
        assertEquals(Theme.TOKENS.size(), Theme.DARK.values().size());
        assertEquals(Theme.TOKENS.size(), Theme.LIGHT.values().size());
        for (String t : Theme.TOKENS) {
            assertTrue(Theme.DARK.get(t).matches("(?i)#[0-9a-f]{6}|rgba\\(.*\\)"), t);
            assertTrue(Theme.LIGHT.get(t).matches("(?i)#[0-9a-f]{6}|rgba\\(.*\\)"), t);
        }
    }

    @Test
    void lightTextIsReadableOnLightBackgrounds() {
        for (String text : new String[] {"text-main", "text-sub"}) {
            assertTrue(Theme.contrast(Theme.LIGHT.get(text), Theme.LIGHT.get("bg-base")) >= 4.5, text);
            assertTrue(Theme.contrast(Theme.LIGHT.get(text), Theme.LIGHT.get("bg-surface")) >= 4.5, text);
        }
        for (String accent : new String[] {"blue", "green", "red", "peach"}) {
            assertTrue(Theme.contrast(Theme.LIGHT.get(accent), Theme.LIGHT.get("bg-base")) >= 3.0, accent);
        }
    }

    @Test
    void css_definesTheLookedUpColors() {
        String css = Theme.css(Theme.LIGHT);
        assertTrue(css.contains(".root"));
        assertTrue(css.contains("-qt-bg-base: " + Theme.LIGHT.get("bg-base")));
        assertTrue(css.contains("-fx-base: " + Theme.LIGHT.get("bg-base")));
        assertEquals("-qt-text-main", Theme.v("text-main"));
    }

    @Test
    void alpha_isALookedUpColourEveryPaletteDefines() {
        assertEquals("-qt-blue-a40", Theme.alpha(Theme.v("blue"), 40));
        String dark = Theme.css(Theme.DARK), light = Theme.css(Theme.LIGHT);
        assertTrue(dark.contains("-qt-blue-a13: rgba(137,180,250,0.13);"), dark);
        assertTrue(light.contains("-qt-green-a27: rgba(47,138,30,0.27);"), light);
        // A token that is already translucent has no alpha variant.
        assertTrue(!dark.contains("-qt-cta-teal-soft-a"), dark);
    }

    @Test
    void alpha_refusesAShadeTheSheetDoesNotDefine() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Theme.alpha(Theme.v("blue"), 50));
    }

    @Test
    void ourSheetIsRecognisedAfterEncoding_soASwapReplacesItInsteadOfStacking() {
        String enc = "data:text/css," + java.net.URLEncoder.encode(Theme.css(Theme.DARK), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        assertTrue(Theme.isOurs(enc));
        assertTrue(!Theme.isOurs("data:text/css,.split-pane-divider"));
    }
}
