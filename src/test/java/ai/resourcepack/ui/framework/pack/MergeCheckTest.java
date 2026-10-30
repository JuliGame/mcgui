package ai.resourcepack.ui.framework.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's overlay entries against the game's rules (checked by hand against the 1.21.11 server jar's own
 * PackMetadataSection and OverlayMetadataSection codecs).
 */
class MergeCheckTest {
    /** ItemsAdder's overlays as a real ItemsAdder 4.0 wrote them (PluginBugs/Issues-ItemsAdder#5185). */
    private static final String ITEMSADDER = """
            [{"directory":"ia_overlay_1_20_5_plus","formats":[32,9999],"min_format":32,"max_format":9999},
             {"directory":"ia_overlay_1_21_4_to_5","formats":[46,55]},
             {"directory":"ia_overlay_1_21_6_plus","formats":[63,9999],"min_format":63,"max_format":9999}]""";

    private static JsonArray ours() {
        return JsonParser.parseString(ResourcePack.mcmeta()).getAsJsonObject().getAsJsonObject("overlays").getAsJsonArray("entries");
    }

    private static JsonArray merged(JsonArray theirs, JsonArray ours) {
        JsonArray all = theirs.deepCopy();
        all.addAll(ours);
        return all;
    }

    @Test
    void ourOverlaysAreValidAloneAndMergedIntoAPackWithOldRanges() {
        assertNull(MergeCheck.invalidOverlays(ours()));
        assertNull(MergeCheck.invalidOverlays(merged(JsonParser.parseString(ITEMSADDER).getAsJsonArray(), ours())));
    }

    @Test
    void overlaysWithoutFormatsBreakAPackWithOldRanges() {
        // The pack before 0.6.7: min_format/max_format only. Alone it is fine; next to ItemsAdder's the game refuses
        // every overlay, and the pack with them.
        JsonArray before = JsonParser.parseString("""
                [{"directory": "uiengine_1_21_11", "min_format": 75, "max_format": 83},
                 {"directory": "uiengine_26_1", "min_format": 84, "max_format": 87},
                 {"directory": "uiengine_26_2", "min_format": 88, "max_format": 96}]""").getAsJsonArray();
        assertNull(MergeCheck.invalidOverlays(before));
        String why = MergeCheck.invalidOverlays(merged(JsonParser.parseString(ITEMSADDER).getAsJsonArray(), before));
        assertNotNull(why);
        assertTrue(why.contains("uiengine_1_21_11"), why);
    }

    @Test
    void formatsAboveSixtyFourAloneAreRefused() {
        JsonArray formatsOnly = JsonParser.parseString("""
                [{"directory": "uiengine_26_2", "min_format": 88, "max_format": 96, "formats": [88, 96]}]""").getAsJsonArray();
        assertNotNull(MergeCheck.invalidOverlays(formatsOnly));
    }

    @Test
    void eachVersionGetsItsOwnOverlay() {
        JsonArray o = ours();
        int[][] formats = {{75, 0}, {84, 0}, {88, 0}, {97, 1}};
        String[] dirs = {"uiengine_1_21_11", "uiengine_26_1", "uiengine_26_2", "uiengine_26_3"};
        for (int v = 0; v < formats.length; v++) {
            String last = null;
            for (var e : o) {
                JsonObject entry = e.getAsJsonObject();
                if (MergeCheck.applies(entry, formats[v])) last = entry.get("directory").getAsString();
            }
            assertEquals(dirs[v], last);
        }
        for (var e : o) assertFalse(MergeCheck.applies(e.getAsJsonObject(), new int[]{74, 0}) && !e.getAsJsonObject().get("directory").getAsString().equals("uiengine_none"));
    }
}
