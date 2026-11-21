package com.hackathon.alerting.delivery.routing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An hour window {@code HH-HH} of a channel preference ({@code "08-20"}), end exclusive, in the user's time zone.
 * A window may wrap around midnight ({@code "22-06"}); {@code "00-24"} is the whole day.
 *
 * @param fromHour first hour inside the window, 0..23
 * @param toHour   first hour after the window, 0..24
 */
public record HourWindow(int fromHour, int toHour) {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{2})-(\\d{2})$");

    /** Parses {@code HH-HH}. @throws IllegalArgumentException if malformed */
    public static HourWindow parse(String text) {
        Matcher matcher = FORMAT.matcher(text == null ? "" : text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Hours must look like 08-20, got '" + text + "'");
        }
        int from = Integer.parseInt(matcher.group(1));
        int to = Integer.parseInt(matcher.group(2));
        if (from > 23 || to > 24 || from == to) {
            throw new IllegalArgumentException("Invalid hour window '" + text + "'");
        }
        return new HourWindow(from, to);
    }

    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether the hour of day (0..23) is inside the window. */
    public boolean contains(int hour) {
        return fromHour < toHour
                ? hour >= fromHour && hour < toHour
                : hour >= fromHour || hour < toHour;
    }
}
