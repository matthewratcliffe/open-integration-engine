/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One expectation about a channel's traffic: "at least {@code minCount} messages in the
 * last {@code windowCount} {@code windowUnit}".
 *
 * <p>Only a floor, never a ceiling. A quiet feed is the failure people actually care
 * about -- an interface that has silently stopped looks exactly like a healthy one on a
 * dashboard of green channels, because nothing is erroring. A burst above normal is
 * usually a backlog clearing, which is not a fault.
 *
 * <p>Serialised as a single tab-separated line so it can live in one row of the
 * {@code configuration} table. The format is versioned: XStream is the obvious
 * alternative and is what the engine uses elsewhere, but its output for collections has
 * already proved unreadable to the console's JSON decoder in this repository, and a rule
 * is a handful of scalars.
 *
 * <p>The name is the one free-text field, so {@link #setName(String)} strips tabs and
 * newlines on the way in. A single stray tab would otherwise shift every later field by
 * one and quietly corrupt the rule on the next read.
 */
public final class VolumeRule {

    /** Window unit. Deliberately coarse: these are the periods people state SLAs in. */
    public enum Window {
        HOUR(60),
        DAY(60 * 24),
        WEEK(60 * 24 * 7);

        private final int minutes;

        Window(int minutes) {
            this.minutes = minutes;
        }

        public int minutes() {
            return minutes;
        }
    }

    /** Minutes in a day, used as the "until midnight" sentinel. */
    public static final int DAY_MINUTES = 24 * 60;

    /**
     * Written as v2, which appends the name. v1 rows are still read: they simply have
     * no name, and a rule saved before names existed should not vanish because of it.
     */
    private static final String FORMAT_VERSION = "v2";
    private static final String LEGACY_VERSION = "v1";
    private static final String SEP = "\t";

    /** Longest name kept. Past this it stops being a label and starts being a note. */
    private static final int NAME_LIMIT = 120;

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String channelId = "";
    private boolean enabled = true;
    private int minCount = 1;
    private int windowCount = 1;
    private Window windowUnit = Window.HOUR;

    /** Days the rule is checked on. Empty means every day. */
    private final Set<DayOfWeek> activeDays = EnumSet.noneOf(DayOfWeek.class);

    /** Minutes from midnight, server local time. from == until means all day. */
    private int activeFromMinute;
    private int activeUntilMinute = DAY_MINUTES;

    /**
     * How long to wait before repeating a breach that has not recovered.
     *
     * <p>Without this, a feed that dies at 02:00 writes a server event every evaluation
     * cycle until someone notices -- hundreds of identical rows, which is how people learn
     * to filter out a monitor rather than read it. 0 means notify once per breach only.
     */
    private int renotifyMinutes = 60;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id.trim();
        }
    }

    /** Optional label. Empty means "call it after its channel". */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null) {
            this.name = "";
            return;
        }
        // Tabs and newlines are the field and record separators in both the stored
        // format and the API's rows, so they cannot survive in a value.
        String cleaned = name.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        this.name = cleaned.length() > NAME_LIMIT ? cleaned.substring(0, NAME_LIMIT) : cleaned;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId == null ? "" : channelId.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinCount() {
        return minCount;
    }

    public void setMinCount(int minCount) {
        this.minCount = Math.max(0, minCount);
    }

    public int getWindowCount() {
        return windowCount;
    }

    public void setWindowCount(int windowCount) {
        this.windowCount = Math.max(1, windowCount);
    }

    public Window getWindowUnit() {
        return windowUnit;
    }

    public void setWindowUnit(Window windowUnit) {
        if (windowUnit != null) {
            this.windowUnit = windowUnit;
        }
    }

    public Set<DayOfWeek> getActiveDays() {
        return activeDays;
    }

    public void setActiveDays(Set<DayOfWeek> days) {
        activeDays.clear();
        if (days != null) {
            activeDays.addAll(days);
        }
    }

    public int getActiveFromMinute() {
        return activeFromMinute;
    }

    public void setActiveFromMinute(int minute) {
        this.activeFromMinute = clampMinute(minute);
    }

    public int getActiveUntilMinute() {
        return activeUntilMinute;
    }

    public void setActiveUntilMinute(int minute) {
        this.activeUntilMinute = clampMinute(minute);
    }

    public int getRenotifyMinutes() {
        return renotifyMinutes;
    }

    public void setRenotifyMinutes(int minutes) {
        this.renotifyMinutes = Math.max(0, minutes);
    }

    private static int clampMinute(int minute) {
        return Math.min(DAY_MINUTES, Math.max(0, minute));
    }

    /** Total length of the counting window in minutes. */
    public int windowMinutes() {
        return windowCount * windowUnit.minutes();
    }

    /** "500 per day", "100 per 4 hours" -- for log lines and event text. */
    public String describe() {
        String unit = windowUnit.name().toLowerCase(Locale.ROOT);
        return minCount + " per " + (windowCount == 1 ? unit : windowCount + " " + unit + "s");
    }

    /** True when the schedule leaves the rule always on. */
    public boolean isAlwaysActive() {
        return activeDays.isEmpty() && coversWholeDay();
    }

    private boolean coversWholeDay() {
        return activeFromMinute == activeUntilMinute
            || (activeFromMinute == 0 && activeUntilMinute == DAY_MINUTES);
    }

    /**
     * Whether the rule should be evaluated at {@code at}.
     *
     * <p>For a window shorter than a day the window's *start* has to be inside the active
     * period too, not just the moment of checking. Otherwise a rule of "100 per hour,
     * 08:00-18:00" is evaluated at 08:05 against 07:05-08:05 -- an hour that is mostly
     * outside business hours -- and reports a breach every single morning. Requiring the
     * whole window pushes the first check of the day out to 09:00, which is the first hour
     * the rule can actually be true about.
     *
     * <p>For a window of a day or longer that test is dropped: a 24-hour window checked on
     * Monday necessarily reaches back into Sunday, so demanding an active window start
     * would mean a Mon-Fri daily rule never ran on a Monday. Such a window already spans
     * the quiet hours, and the threshold is stated in full knowledge of that.
     */
    public boolean isActiveAt(ZonedDateTime at) {
        if (isAlwaysActive()) {
            return true;
        }
        if (!isActiveMoment(at)) {
            return false;
        }
        if (windowMinutes() >= DAY_MINUTES) {
            return true;
        }
        return isActiveMoment(at.minusMinutes(windowMinutes()));
    }

    private boolean isActiveMoment(ZonedDateTime at) {
        if (!activeDays.isEmpty() && !activeDays.contains(at.getDayOfWeek())) {
            return false;
        }
        if (coversWholeDay()) {
            return true;
        }
        int minute = at.getHour() * 60 + at.getMinute();
        if (activeFromMinute < activeUntilMinute) {
            return minute >= activeFromMinute && minute < activeUntilMinute;
        }
        // Wraps midnight, e.g. 22:00-06:00: active in either of the two spans.
        return minute >= activeFromMinute || minute < activeUntilMinute;
    }

    /** Problems that should stop the rule being saved. Empty means valid. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (channelId.isBlank()) {
            out.add("a channel must be selected");
        }
        if (minCount < 1) {
            // A floor of zero is always met, so it would sit there looking like monitoring
            // while being incapable of ever reporting anything.
            out.add("the expected count must be at least 1");
        }
        if (windowCount < 1) {
            out.add("the window must be at least 1");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    public String serialise() {
        return String.join(SEP,
            FORMAT_VERSION,
            id,
            channelId,
            Boolean.toString(enabled),
            Integer.toString(minCount),
            Integer.toString(windowCount),
            windowUnit.name(),
            serialiseDays(),
            Integer.toString(activeFromMinute),
            Integer.toString(activeUntilMinute),
            Integer.toString(renotifyMinutes),
            name);
    }

    private String serialiseDays() {
        if (activeDays.isEmpty()) {
            return "*";
        }
        List<String> names = new ArrayList<>();
        // Sorted by the enum's own order so a stored rule is byte-identical after a
        // round trip, which keeps "did this change?" answerable by string comparison.
        for (DayOfWeek d : DayOfWeek.values()) {
            if (activeDays.contains(d)) {
                names.add(d.name());
            }
        }
        return String.join(",", names);
    }

    /**
     * Reads a rule back.
     *
     * @return the rule, or null when the line cannot be understood -- a rule that cannot
     *     be parsed is dropped with a warning rather than failing the whole load, so one
     *     bad row cannot take out every other rule on the instance
     */
    public static VolumeRule parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = line.split(SEP, -1);
        boolean known = FORMAT_VERSION.equals(f[0]) || LEGACY_VERSION.equals(f[0]);
        if (f.length < 11 || !known) {
            return null;
        }
        VolumeRule r = new VolumeRule();
        r.setId(f[1]);
        r.setChannelId(f[2]);
        r.enabled = Boolean.parseBoolean(f[3]);
        r.setMinCount(parseInt(f[4], 1));
        r.setWindowCount(parseInt(f[5], 1));
        r.setWindowUnit(parseWindow(f[6]));
        r.setActiveDays(parseDays(f[7]));
        r.setActiveFromMinute(parseInt(f[8], 0));
        r.setActiveUntilMinute(parseInt(f[9], DAY_MINUTES));
        r.setRenotifyMinutes(parseInt(f[10], 60));
        // Absent in v1 rows.
        if (f.length > 11) {
            r.setName(f[11]);
        }
        return r;
    }

    static Window parseWindow(String s) {
        if (s != null) {
            for (Window w : Window.values()) {
                if (w.name().equalsIgnoreCase(s.trim())) {
                    return w;
                }
            }
        }
        return Window.HOUR;
    }

    static Set<DayOfWeek> parseDays(String s) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        if (s == null || s.isBlank() || "*".equals(s.trim())) {
            return out;
        }
        for (String part : s.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            for (DayOfWeek d : DayOfWeek.values()) {
                // Accepts both MON and MONDAY, since the UI sends the short form.
                if (d.name().equals(name) || d.name().startsWith(name)) {
                    out.add(d);
                    break;
                }
            }
        }
        return out;
    }

    static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
