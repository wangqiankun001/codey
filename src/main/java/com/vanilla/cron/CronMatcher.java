package com.vanilla.cron;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** Five-field cron matcher with the Python implementation's DOM/DOW OR semantics. */
public final class CronMatcher {
    private static final int[][] BOUNDS = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 6}};

    private CronMatcher() {}

    public static boolean matches(String expression, LocalDateTime time) {
        String[] fields = split(expression);
        if (fields == null) return false;
        int dow = time.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : time.getDayOfWeek().getValue();
        boolean minute = fieldMatches(fields[0], time.getMinute());
        boolean hour = fieldMatches(fields[1], time.getHour());
        boolean dom = fieldMatches(fields[2], time.getDayOfMonth());
        boolean month = fieldMatches(fields[3], time.getMonthValue());
        boolean dayOfWeek = fieldMatches(fields[4], dow);
        if (!minute || !hour || !month) return false;
        if (fields[2].equals("*") && fields[4].equals("*")) return true;
        if (fields[2].equals("*")) return dayOfWeek;
        if (fields[4].equals("*")) return dom;
        return dom || dayOfWeek;
    }

    public static void validate(String expression) {
        String[] fields = split(expression);
        if (fields == null) throw new IllegalArgumentException("expected 5 fields");
        for (int i = 0; i < fields.length; i++) validateField(fields[i], BOUNDS[i][0], BOUNDS[i][1]);
    }

    private static String[] split(String expression) {
        if (expression == null) return null;
        String value = expression.trim();
        if (value.isEmpty()) return null;
        String[] fields = value.split("\\s+");
        return fields.length == 5 ? fields : null;
    }

    private static boolean fieldMatches(String field, int value) {
        if (field.equals("*")) return true;
        if (field.startsWith("*/")) return Integer.parseInt(field.substring(2)) > 0
                && value % Integer.parseInt(field.substring(2)) == 0;
        if (field.contains(",")) return Arrays.stream(field.split(","))
                .anyMatch(part -> fieldMatches(part.trim(), value));
        if (field.contains("-")) {
            String[] range = field.split("-", -1);
            return range.length == 2 && value >= Integer.parseInt(range[0]) && value <= Integer.parseInt(range[1]);
        }
        return value == Integer.parseInt(field);
    }

    private static void validateField(String field, int low, int high) {
        if (field.equals("*")) return;
        if (field.startsWith("*/")) {
            String step = field.substring(2);
            if (!step.matches("\\d+") || Integer.parseInt(step) <= 0) throw new IllegalArgumentException("invalid step: " + field);
            return;
        }
        if (field.contains(",")) {
            List<String> parts = Arrays.asList(field.split(",", -1));
            if (parts.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("invalid list: " + field);
            parts.forEach(part -> validateField(part.trim(), low, high));
            return;
        }
        if (field.contains("-")) {
            String[] range = field.split("-", -1);
            if (range.length != 2 || !range[0].matches("\\d+") || !range[1].matches("\\d+")) throw new IllegalArgumentException("invalid range: " + field);
            int a = Integer.parseInt(range[0]), b = Integer.parseInt(range[1]);
            if (a < low || b > high || a > b) throw new IllegalArgumentException("range out of bounds: " + field);
            return;
        }
        if (!field.matches("\\d+")) throw new IllegalArgumentException("invalid field: " + field);
        int value = Integer.parseInt(field);
        if (value < low || value > high) throw new IllegalArgumentException("value out of bounds: " + field);
    }
}
