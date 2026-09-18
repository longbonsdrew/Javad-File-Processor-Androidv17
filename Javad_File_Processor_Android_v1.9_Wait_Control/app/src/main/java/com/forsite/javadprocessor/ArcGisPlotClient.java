package com.forsite.javadprocessor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

final class ArcGisPlotClient {
    interface Callback {
        void complete(SyncResult result);
    }

    interface ProjectsCallback {
        void complete(List<GroupProject> projects, String error);
    }

    static void loadGroupProjects(String groupId, String token, ProjectsCallback callback) {
        new Thread(() -> {
            try {
                String query = "group:" + groupId + " AND type:\"Feature Service\"";
                JSONObject search = post("https://www.arcgis.com/sharing/rest/search",
                        form("f", "json", "token", token, "q", query, "num", "100", "sortField", "title", "sortOrder", "asc"));
                throwArcGisError(search);
                JSONArray results = search.optJSONArray("results");
                List<GroupProject> projects = new ArrayList<>();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String title = item.optString("title", "").trim();
                        String serviceUrl = item.optString("url", "").trim();
                        if (title.isEmpty() || serviceUrl.isEmpty()) continue;
                        try {
                            JSONObject service = post(serviceUrl, form("f", "json", "token", token));
                            if (service.has("error")) continue;
                            JSONArray layers = service.optJSONArray("layers");
                            if (layers == null) continue;
                            for (int j = 0; j < layers.length(); j++) {
                                JSONObject layer = layers.getJSONObject(j);
                                if ("Plots".equalsIgnoreCase(layer.optString("name", "").trim())) {
                                    projects.add(new GroupProject(title, normalizeServiceUrl(serviceUrl) + "/" + layer.getInt("id")));
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                projects.sort(Comparator.comparing(project -> project.name.toLowerCase(Locale.US)));
                callback.complete(projects, null);
            } catch (Exception error) {
                callback.complete(new ArrayList<>(), readable(error));
            }
        }).start();
    }

    static void syncReport(String layerUrl, double maxOffsetMeters, String token,
                           JavadReportParser.Result report, Callback callback) {
        new Thread(() -> {
            try {
                Feature feature = findPlot(layerUrl, token, report.plotId);
                double offset = haversineMeters(feature.latitude, feature.longitude, report.latitude, report.longitude);
                boolean locationFailed = offset > maxOffsetMeters;
                String reason = locationFailed
                        ? String.format(Locale.US, "Processed location is %.1f m from original plot; limit is %.1f m", offset, maxOffsetMeters)
                        : "";

                JSONObject attributes = new JSONObject();
                attributes.put(feature.objectIdField, feature.objectId);
                feature.requireFields("Javad_X", "Javad_Y", "Javad_UTM_Zone", "Javad_Elev", "Javad_Process_Date", "Javad_Status");
                attributes.put("Javad_X", report.x);
                attributes.put("Javad_Y", report.y);
                attributes.put("Javad_UTM_Zone", report.utmZone);
                attributes.put("Javad_Elev", report.elevation);
                attributes.put("Javad_Process_Date", report.processDate);
                attributes.put("Javad_Status", locationFailed ? "Failed" : "Processed");
                if (feature.hasField("Javad_Offset")) attributes.put("Javad_Offset", round(offset, 3));
                if (feature.hasField("Javad_Fail_Reason")) attributes.put("Javad_Fail_Reason", reason);
                updateFeature(layerUrl, token, attributes);
                callback.complete(new SyncResult(true, locationFailed, offset, reason));
            } catch (Exception error) {
                callback.complete(new SyncResult(false, false, Double.NaN, readable(error)));
            }
        }).start();
    }

    static void markFailed(String layerUrl, String token, String plotId, String reason, Callback callback) {
        new Thread(() -> {
            try {
                Feature feature = findPlot(layerUrl, token, plotId);
                JSONObject attributes = new JSONObject();
                attributes.put(feature.objectIdField, feature.objectId);
                feature.requireFields("Javad_Status");
                attributes.put("Javad_Status", "Failed");
                if (feature.hasField("Javad_Fail_Reason")) attributes.put("Javad_Fail_Reason", truncate(reason, 150));
                updateFeature(layerUrl, token, attributes);
                callback.complete(new SyncResult(true, true, Double.NaN, reason));
            } catch (Exception error) {
                callback.complete(new SyncResult(false, false, Double.NaN, readable(error)));
            }
        }).start();
    }

    private static Feature findPlot(String layerUrl, String token, String plotId) throws Exception {
        String where = "Plot_ID='" + plotId.replace("'", "''") + "'";
        String body = form(
                "f", "json",
                "token", token,
                "where", where,
                "outFields", "*",
                "returnGeometry", "true",
                "outSR", "4326");
        JSONObject response = post(normalizeLayerUrl(layerUrl) + "/query", body);
        throwArcGisError(response);
        JSONArray features = response.optJSONArray("features");
        if (features == null || features.length() == 0) throw new Exception("No ArcGIS plot matched Plot_ID " + plotId);
        if (features.length() > 1) throw new Exception("More than one ArcGIS plot matched Plot_ID " + plotId);
        String objectIdField = response.optString("objectIdFieldName", "OBJECTID");
        JSONObject feature = features.getJSONObject(0);
        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry == null || !geometry.has("x") || !geometry.has("y")) throw new Exception("Matched plot has no point geometry");
        Object objectId = feature.getJSONObject("attributes").get(objectIdField);
        Set<String> fieldNames = new HashSet<>();
        JSONArray fields = response.optJSONArray("fields");
        if (fields != null) for (int i = 0; i < fields.length(); i++) fieldNames.add(fields.getJSONObject(i).optString("name", "").toLowerCase(Locale.US));
        return new Feature(objectIdField, objectId, geometry.getDouble("y"), geometry.getDouble("x"), fieldNames);
    }

    private static void updateFeature(String layerUrl, String token, JSONObject attributes) throws Exception {
        JSONArray features = new JSONArray().put(new JSONObject().put("attributes", attributes));
        JSONObject response = post(normalizeLayerUrl(layerUrl) + "/updateFeatures", form("f", "json", "token", token, "features", features.toString()));
        throwArcGisError(response);
        JSONArray results = response.optJSONArray("updateResults");
        if (results == null || results.length() == 0 || !results.getJSONObject(0).optBoolean("success")) {
            JSONObject error = results == null || results.length() == 0 ? null : results.getJSONObject(0).optJSONObject("error");
            throw new Exception(error == null ? "ArcGIS did not confirm the plot update" : error.optString("description", error.toString()));
        }
    }

    private static JSONObject post(String endpoint, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (input != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) text.append(line);
        }
        connection.disconnect();
        if (text.length() == 0) throw new Exception("ArcGIS returned HTTP " + status + " with no response");
        return new JSONObject(text.toString());
    }

    private static String form(String... values) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(values[i], "UTF-8")).append('=').append(URLEncoder.encode(values[i + 1], "UTF-8"));
        }
        return body.toString();
    }

    private static String normalizeLayerUrl(String value) throws Exception {
        if (value == null) throw new Exception("No ArcGIS Plot layer is selected");
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.matches("(?i)^https://.+/FeatureServer/\\d+$")) {
            throw new Exception("Plot layer URL must end with FeatureServer followed by its layer number");
        }
        return url;
    }

    private static String normalizeServiceUrl(String value) {
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static void throwArcGisError(JSONObject response) throws Exception {
        JSONObject error = response.optJSONObject("error");
        if (error != null) throw new Exception(error.optString("message", "ArcGIS request failed") + ": " + error.optString("details", ""));
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private static final class Feature {
        final String objectIdField;
        final Object objectId;
        final double latitude;
        final double longitude;
        final Set<String> fieldNames;

        Feature(String objectIdField, Object objectId, double latitude, double longitude, Set<String> fieldNames) {
            this.objectIdField = objectIdField;
            this.objectId = objectId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.fieldNames = fieldNames;
        }

        boolean hasField(String name) {
            return fieldNames.contains(name.toLowerCase(Locale.US));
        }

        void requireFields(String... names) throws Exception {
            List<String> missing = new ArrayList<>();
            for (String name : names) if (!hasField(name)) missing.add(name);
            if (!missing.isEmpty()) throw new Exception("Plot layer is missing JAVAD field(s): " + String.join(", ", missing));
        }
    }

    static final class SyncResult {
        final boolean updated;
        final boolean failedQa;
        final double offsetMeters;
        final String message;

        SyncResult(boolean updated, boolean failedQa, double offsetMeters, String message) {
            this.updated = updated;
            this.failedQa = failedQa;
            this.offsetMeters = offsetMeters;
            this.message = message;
        }
    }

    static final class GroupProject {
        final String name;
        final String layerUrl;

        GroupProject(String name, String layerUrl) {
            this.name = name;
            this.layerUrl = layerUrl;
        }
    }
}
