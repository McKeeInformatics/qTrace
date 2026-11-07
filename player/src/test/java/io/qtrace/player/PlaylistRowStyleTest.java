package io.qtrace.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PlaylistRowStyleTest {

    private static String borderWidth(String style) {
        Matcher m = Pattern.compile("-fx-border-width: ([^;]+);").matcher(style);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void theCurrentRowTakesNoMoreRoomThanTheOthers() {
        String base = "-fx-background-color: #111;";
        // The bar is a border: if only the current row had one, its content would sit 3 px to the right.
        assertEquals(borderWidth(QTraceReplayPlayer.rowStyle(base, false)),
                     borderWidth(QTraceReplayPlayer.rowStyle(base, true)));
        assertNotEquals(QTraceReplayPlayer.rowStyle(base, false), QTraceReplayPlayer.rowStyle(base, true));
    }
}
