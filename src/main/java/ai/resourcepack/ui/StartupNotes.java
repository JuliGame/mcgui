package ai.resourcepack.ui;

import ai.resourcepack.common.banner.StartupBanner;

import java.util.logging.Logger;

/**
 * Where a line about MC GUI's start goes: into the startup banner's report
 * while {@link MCGUIPlugin#onEnable} runs, and to the log at any other time
 * (a pack rebuilt by a reload, a screen placed later).
 */
public final class StartupNotes {

    private static volatile StartupBanner banner;

    private StartupNotes() {
    }

    static void collect(StartupBanner into) {
        banner = into;
    }

    /** Says [text] under [key] in the banner, or [logged] to [log] when MC GUI is not starting. */
    public static void say(Logger log, String key, String text, String logged) {
        StartupBanner b = banner;
        if (b != null) b.ok(key, text);
        else log.info(logged);
    }
}
