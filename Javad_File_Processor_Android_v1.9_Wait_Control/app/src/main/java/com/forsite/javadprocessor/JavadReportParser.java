package com.forsite.javadprocessor;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavadReportParser {
    private static final Pattern FILE = Pattern.compile("(?m)^\\s*FILE:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?m)^\\s*USER:.*?DATE:\\s*(\\d{1,2}/\\d{1,2}/\\d{4})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAT = Pattern.compile("(?m)^\\s*LAT:\\s*([NS])\\s*(\\d+)°\\s*(\\d+)'\\s*([0-9.]+)\"");
    private static final Pattern W_LON = Pattern.compile("(?m)^\\s*W LON:\\s*([WE])\\s*(\\d+)°\\s*(\\d+)'\\s*([0-9.]+)\"");
    private static final Pattern ZONE = Pattern.compile("UTM\\s*\\(Zone\\s*(\\d{1,2})\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NORTHING = Pattern.compile("(?m)^\\s*Northing \\(Y\\)\\s*\\[\\s*meters\\]\\s*([-0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EASTING = Pattern.compile("(?m)^\\s*Easting \\(X\\)\\s*\\[\\s*meters\\]\\s*([-0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORTHO = Pattern.compile("(?m)^\\s*ORTHO HGT:\\s*([-0-9.]+)\\(m\\)", Pattern.CASE_INSENSITIVE);

    static Result parse(String text) throws ParseException {
        if (text == null || text.trim().isEmpty()) throw new ParseException("TXT report is empty", 0);
        String file = required(FILE, text, "FILE");
        String date = required(DATE, text, "DATE");
        Matcher lat = requiredMatcher(LAT, text, "NAD83 latitude");
        Matcher lon = requiredMatcher(W_LON, text, "NAD83 west longitude");
        double latitude = dms(lat.group(1), lat.group(2), lat.group(3), lat.group(4));
        double longitude = dms(lon.group(1), lon.group(2), lon.group(3), lon.group(4));
        double x = Double.parseDouble(required(EASTING, text, "UTM Easting"));
        double y = Double.parseDouble(required(NORTHING, text, "UTM Northing"));
        int zone = Integer.parseInt(required(ZONE, text, "UTM Zone"));
        double elevation = Double.parseDouble(required(ORTHO, text, "NAVD88 orthometric height"));

        SimpleDateFormat format = new SimpleDateFormat("M/d/yyyy", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date processDate = format.parse(date);
        if (processDate == null) throw new ParseException("Could not parse report date", 0);
        return new Result(stem(file), x, y, zone, elevation, processDate.getTime(), latitude, longitude);
    }

    private static Matcher requiredMatcher(Pattern pattern, String text, String label) throws ParseException {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) throw new ParseException("Missing " + label + " in JAVAD TXT", 0);
        return matcher;
    }

    private static String required(Pattern pattern, String text, String label) throws ParseException {
        return requiredMatcher(pattern, text, label).group(1);
    }

    private static double dms(String direction, String degrees, String minutes, String seconds) {
        double value = Double.parseDouble(degrees) + Double.parseDouble(minutes) / 60d + Double.parseDouble(seconds) / 3600d;
        if ("S".equalsIgnoreCase(direction) || "W".equalsIgnoreCase(direction)) value = -value;
        return value;
    }

    private static String stem(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    static final class Result {
        final String plotId;
        final double x;
        final double y;
        final int utmZone;
        final double elevation;
        final long processDate;
        final double latitude;
        final double longitude;

        Result(String plotId, double x, double y, int utmZone, double elevation,
               long processDate, double latitude, double longitude) {
            this.plotId = plotId;
            this.x = x;
            this.y = y;
            this.utmZone = utmZone;
            this.elevation = elevation;
            this.processDate = processDate;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
