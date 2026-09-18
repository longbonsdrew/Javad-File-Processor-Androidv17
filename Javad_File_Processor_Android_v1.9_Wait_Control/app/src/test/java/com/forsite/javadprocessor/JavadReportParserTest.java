package com.forsite.javadprocessor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JavadReportParserTest {
    @Test public void parsesNad83AndUtmValues() throws Exception {
        String report =
                "USER:                                       DATE: 9/8/2026\n" +
                "FILE: CC_18.jps                             TIME: 7:26:54 PM\n" +
                "LAT:  N 42° 59' 20.62822\"   0.050(m)       N 42° 59' 20.63909\"\n" +
                "W LON: W 122° 57' 18.75352\"  0.045(m)      W 122° 57' 18.82225\"\n" +
                "ORTHO HGT:           919.208(m)   0.045(m)  [NAVD88 (Computed using GEOID18)]\n" +
                "UTM COORDINATES\n" +
                "UTM (Zone 10)\n" +
                "Northing (Y) [ meters]    4759601.279\n" +
                "Easting (X)  [ meters]    503651.463\n";

        JavadReportParser.Result result = JavadReportParser.parse(report);
        assertEquals("CC_18", result.plotId);
        assertEquals(503651.463, result.x, 0.000001);
        assertEquals(4759601.279, result.y, 0.000001);
        assertEquals(10, result.utmZone);
        assertEquals(919.208, result.elevation, 0.000001);
        assertEquals(42.989063394, result.latitude, 0.000001);
        assertEquals(-122.955209311, result.longitude, 0.000001);
    }
}
