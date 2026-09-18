package com.forsite.javadprocessor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ProjectStore {
    private static final String PREFS = "arcgis_projects";
    private static final String PROJECTS = "projects";
    private static final String SELECTED = "selected";

    static List<Project> load(Context context) {
        List<Project> projects = new ArrayList<>();
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROJECTS, "");
        if (!saved.isEmpty()) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    projects.add(new Project(item.getString("name"), item.getString("url"), item.optDouble("offset", 50d), item.optBoolean("manual", false)));
                }
            } catch (Exception ignored) {}
        }
        if (projects.isEmpty()) {
            projects.add(new Project("2026 Taos", "https://services6.arcgis.com/liOmVtyPQnsGcqaw/arcgis/rest/services/2026_Taos_WFL1/FeatureServer/1", 50d, false));
            save(context, projects, 0);
        }
        return projects;
    }

    static int selected(Context context, int size) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SELECTED, 0);
        return value >= 0 && value < size ? value : 0;
    }

    static void save(Context context, List<Project> projects, int selected) {
        JSONArray array = new JSONArray();
        try {
            for (Project project : projects) {
                array.put(new JSONObject().put("name", project.name).put("url", project.layerUrl)
                        .put("offset", project.maxOffsetMeters).put("manual", project.manual));
            }
        } catch (Exception ignored) {}
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PROJECTS, array.toString()).putInt(SELECTED, selected).apply();
    }

    static final class Project {
        final String name;
        final String layerUrl;
        final double maxOffsetMeters;
        final boolean manual;

        Project(String name, String layerUrl, double maxOffsetMeters, boolean manual) {
            this.name = name;
            this.layerUrl = layerUrl;
            this.maxOffsetMeters = maxOffsetMeters;
            this.manual = manual;
        }

        @Override public String toString() { return name; }
    }
}
