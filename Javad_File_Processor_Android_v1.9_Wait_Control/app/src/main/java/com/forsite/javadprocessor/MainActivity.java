package com.forsite.javadprocessor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.method.ScrollingMovementMethod;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_INPUT = 201;
    private static final int PICK_OUTPUT = 202;
    private static final int ARCGIS_AUTH = 203;
    private static final String ARCGIS_CLIENT_ID = "dQyBiXgt1Jxsze5s";
    private static final Uri ARCGIS_REDIRECT_URI = Uri.parse("javadprocessor://oauth2redirect");
    private static final String MOBILE_MAP_GROUP_ID = "d6b2f634bee9492ca827512a3eb6a5d6";
    private static final String UPLOAD_URL = "https://app.javad.com/jca/#/dpos/upload";
    private static final int REPORT_PAGE_SIZE = 8;
    private static final String REPORT_URL = "https://app.javad.com/jca/#/dpos/report/list/%d/8/0/d/0";
    private static final int MAX_REPORT_PAGES = 10;

    private WebView web;
    private WebView loginWindow;
    private ScrollView controls;
    private FrameLayout browserPanel;
    private TextView inputPath, outputPath, arcGisStatus, uploadText, downloadText, status, failedText, log;
    private StripedProgressView uploadBar, downloadBar;
    private Button start, stop, arcGisLogin;
    private Button backToControls;
    private CheckBox reprocess, recentSubmissionCheck;
    private EditText maxWait;
    private Spinner projectSpinner;
    private final List<ProjectStore.Project> projects = new ArrayList<>();
    private ArrayAdapter<ProjectStore.Project> projectAdapter;
    private AuthorizationService authorizationService;
    private AuthState arcGisAuthState;
    private Uri inputTree, outputTree;
    private final List<Observation> observations = new ArrayList<>();
    private final List<Observation> submitted = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private int uploadIndex = 0, downloadIndex = 0, reportPage = 1;
    private int uploadAttempt = 0;
    private int preflightPage = 1;
    private final Set<String> recentNames = new HashSet<>();
    private int chosenUploadIndex = -1;
    private int downloadSuccess = 0;
    private long reportDeadline = 0;
    private boolean running = false, stopping = false, selectingForUpload = false;
    private boolean downloadInProgress = false;
    private boolean loginConfirmed = false;
    private Phase phase = Phase.IDLE;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reportCheckTask = this::checkReportPage;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashRecorder();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bindViews();

        configureWebView(web);
        authorizationService = new AuthorizationService(this);
        restoreArcGisAuth();
        loadProjects();
        if (arcGisAuthState != null && arcGisAuthState.isAuthorized()) refreshGroupProjects();

        findViewById(R.id.inputButton).setOnClickListener(v -> chooseTree(PICK_INPUT));
        findViewById(R.id.outputButton).setOnClickListener(v -> chooseTree(PICK_OUTPUT));
        findViewById(R.id.loginButton).setOnClickListener(v -> showBrowser(UPLOAD_URL));
        arcGisLogin.setOnClickListener(v -> signInToArcGis());
        findViewById(R.id.addProjectButton).setOnClickListener(v -> showAddProjectDialog());
        findViewById(R.id.refreshProjectsButton).setOnClickListener(v -> refreshGroupProjects());
        backToControls = findViewById(R.id.backToControls);
        backToControls.setOnClickListener(v -> showControls());
        start.setOnClickListener(v -> startRun());
        findViewById(R.id.viewFullLogButton).setOnClickListener(v -> showFullLog());
        stop.setOnClickListener(v -> { stopping = true; setStatus("Stopping safely after the current step…"); });
        restoreFolders();
        showRecordedCrash();
    }

    @Override protected void onDestroy() {
        if (authorizationService != null) authorizationService.dispose();
        super.onDestroy();
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("last_crash", trace.toString()).commit();
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void showRecordedCrash() {
        SharedPreferences diagnostics = getSharedPreferences("diagnostics", MODE_PRIVATE);
        String crash = diagnostics.getString("last_crash", "");
        if (!crash.isEmpty()) {
            diagnostics.edit().remove("last_crash").apply();
            String[] lines = crash.split("\\n");
            StringBuilder important = new StringBuilder("Previous crash:\n");
            for (int i = 0; i < Math.min(lines.length, 14); i++) important.append(lines[i]).append('\n');
            failedText.setText(important.toString());
            log.setText("LAST CRASH:\n" + crash);
            log.scrollTo(0, 0);
            status.setText("Diagnostic information recovered from the previous crash.");
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(target, true);
        target.addJavascriptInterface(new WebBridge(), "AndroidProcessor");
        target.setWebViewClient(new ProcessorWebClient());
        target.setWebChromeClient(new ProcessorChromeClient());
        target.setDownloadListener(new ReportDownloadListener());
    }

    private void bindViews() {
        web = findViewById(R.id.webView); controls = findViewById(R.id.controlScroll);
        browserPanel = findViewById(R.id.browserPanel); inputPath = findViewById(R.id.inputPath);
        outputPath = findViewById(R.id.outputPath); arcGisStatus = findViewById(R.id.arcGisStatus);
        uploadText = findViewById(R.id.uploadText);
        downloadText = findViewById(R.id.downloadText); status = findViewById(R.id.statusText);
        failedText = findViewById(R.id.failedText); log = findViewById(R.id.logText);
        uploadBar = findViewById(R.id.uploadProgress); downloadBar = findViewById(R.id.downloadProgress);
        start = findViewById(R.id.startButton); stop = findViewById(R.id.stopButton);
        arcGisLogin = findViewById(R.id.arcGisLoginButton); projectSpinner = findViewById(R.id.projectSpinner);
        reprocess = findViewById(R.id.reprocessCheck);
        recentSubmissionCheck = findViewById(R.id.recentSubmissionCheck);
        recentSubmissionCheck.setChecked(getPreferences(MODE_PRIVATE).getBoolean("recent_submission_check", false));
        recentSubmissionCheck.setOnCheckedChangeListener((button, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("recent_submission_check", checked).apply());
        maxWait = findViewById(R.id.maxWait);
        maxWait.setText(String.valueOf(getPreferences(MODE_PRIVATE).getInt("wait_minutes", 10)));
        findViewById(R.id.waitMinus).setOnClickListener(v -> adjustWaitMinutes(-1));
        findViewById(R.id.waitPlus).setOnClickListener(v -> adjustWaitMinutes(1));
        maxWait.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveWaitMinutes(); });
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setVerticalScrollBarEnabled(true);
        log.setTextIsSelectable(true);
    }

    private void restoreArcGisAuth() {
        String saved = getSharedPreferences("arcgis_auth", MODE_PRIVATE).getString("state", "");
        if (!saved.isEmpty()) {
            try { arcGisAuthState = AuthState.jsonDeserialize(saved); }
            catch (JSONException ignored) { arcGisAuthState = null; }
        }
        updateArcGisStatus();
    }

    private void persistArcGisAuth() {
        if (arcGisAuthState == null) return;
        getSharedPreferences("arcgis_auth", MODE_PRIVATE).edit()
                .putString("state", arcGisAuthState.jsonSerializeString()).apply();
    }

    private void updateArcGisStatus() {
        boolean signedIn = arcGisAuthState != null && arcGisAuthState.isAuthorized();
        arcGisStatus.setText(signedIn ? "ArcGIS: signed in" : "ArcGIS: sign-in required");
        arcGisLogin.setText(signedIn ? "ArcGIS signed in — sign in again" : "4. Sign in to ArcGIS");
    }

    private void signInToArcGis() {
        AuthorizationServiceConfiguration service = new AuthorizationServiceConfiguration(
                Uri.parse("https://www.arcgis.com/sharing/rest/oauth2/authorize"),
                Uri.parse("https://www.arcgis.com/sharing/rest/oauth2/token/"));
        AuthorizationRequest request = new AuthorizationRequest.Builder(
                service, ARCGIS_CLIENT_ID, ResponseTypeValues.CODE, ARCGIS_REDIRECT_URI)
                .setAdditionalParameters(java.util.Collections.singletonMap("expiration", "20160"))
                .build();
        startActivityForResult(authorizationService.getAuthorizationRequestIntent(request), ARCGIS_AUTH);
    }

    private void handleArcGisAuthorization(Intent data) {
        if (data == null) { toast("ArcGIS sign-in was cancelled."); return; }
        AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
        AuthorizationException exception = AuthorizationException.fromIntent(data);
        arcGisAuthState = new AuthState(response, exception);
        if (response == null) {
            updateArcGisStatus();
            toast("ArcGIS sign-in failed" + (exception == null ? "." : ": " + exception.errorDescription));
            return;
        }
        authorizationService.performTokenRequest(response.createTokenExchangeRequest(), (tokenResponse, tokenError) -> {
            arcGisAuthState.update(tokenResponse, tokenError);
            persistArcGisAuth();
            runOnUiThread(() -> {
                updateArcGisStatus();
                if (arcGisAuthState.isAuthorized()) {
                    toast("ArcGIS sign-in complete.");
                    refreshGroupProjects();
                }
                else toast("ArcGIS token exchange failed.");
            });
        });
    }

    private interface TokenAction { void run(String token); }

    private void withArcGisToken(TokenAction action, Runnable failed) {
        if (arcGisAuthState == null || !arcGisAuthState.isAuthorized()) {
            runOnUiThread(failed);
            return;
        }
        arcGisAuthState.performActionWithFreshTokens(authorizationService, (accessToken, idToken, error) -> {
            persistArcGisAuth();
            if (error != null || accessToken == null) runOnUiThread(failed);
            else action.run(accessToken);
        });
    }

    private void loadProjects() {
        projects.clear();
        projects.addAll(ProjectStore.load(this));
        projectAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, projects);
        projectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        projectSpinner.setAdapter(projectAdapter);
        projectSpinner.setSelection(ProjectStore.selected(this, projects.size()));
    }

    private ProjectStore.Project selectedProject() {
        int position = projectSpinner.getSelectedItemPosition();
        return position >= 0 && position < projects.size() ? projects.get(position) : null;
    }

    private void showAddProjectDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText name = new EditText(this); name.setHint("Project name"); form.addView(name);
        EditText url = new EditText(this); url.setHint("Plot layer URL ending in FeatureServer/number"); form.addView(url);
        EditText offset = new EditText(this); offset.setHint("Maximum offset in meters"); offset.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); offset.setText("50"); form.addView(offset);
        new android.app.AlertDialog.Builder(this).setTitle("Add ArcGIS project").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String projectName = name.getText().toString().trim();
                    String layerUrl = url.getText().toString().trim();
                    double limit;
                    try { limit = Double.parseDouble(offset.getText().toString().trim()); }
                    catch (Exception error) { toast("Offset must be a number."); return; }
                    if (projectName.isEmpty() || !layerUrl.matches("(?i)^https://.+/FeatureServer/\\d+/?$")) {
                        toast("Enter a project name and the full Plot FeatureServer layer URL."); return;
                    }
                    projects.add(new ProjectStore.Project(projectName, layerUrl, limit, true));
                    projectAdapter.notifyDataSetChanged();
                    int selected = projects.size() - 1;
                    projectSpinner.setSelection(selected);
                    ProjectStore.save(this, projects, selected);
                }).show();
    }

    private void refreshGroupProjects() {
        if (arcGisAuthState == null || !arcGisAuthState.isAuthorized()) {
            toast("Sign in to ArcGIS before loading group projects.");
            return;
        }
        arcGisStatus.setText("ArcGIS: loading NMI Mobile Map projects…");
        ProjectStore.Project previouslySelected = selectedProject();
        String previousUrl = previouslySelected == null ? "" : previouslySelected.layerUrl;
        withArcGisToken(token -> ArcGisPlotClient.loadGroupProjects(MOBILE_MAP_GROUP_ID, token, (loaded, error) -> runOnUiThread(() -> {
            if (error != null) {
                arcGisStatus.setText("ArcGIS: signed in — project refresh failed");
                appendLog("ArcGIS group refresh failed: " + error);
                return;
            }
            List<ProjectStore.Project> manual = new ArrayList<>();
            for (ProjectStore.Project project : projects) if (project.manual) manual.add(project);
            projects.clear();
            for (ArcGisPlotClient.GroupProject project : loaded) {
                projects.add(new ProjectStore.Project(project.name, project.layerUrl, 50d, false));
            }
            for (ProjectStore.Project project : manual) {
                boolean duplicate = false;
                for (ProjectStore.Project existing : projects) if (existing.layerUrl.equalsIgnoreCase(project.layerUrl)) duplicate = true;
                if (!duplicate) projects.add(project);
            }
            projectAdapter.notifyDataSetChanged();
            int selected = 0;
            for (int i = 0; i < projects.size(); i++) {
                if (projects.get(i).layerUrl.equalsIgnoreCase(previousUrl)) { selected = i; break; }
            }
            if (!projects.isEmpty()) projectSpinner.setSelection(selected);
            ProjectStore.save(this, projects, selected);
            arcGisStatus.setText("ArcGIS: signed in — " + loaded.size() + " group project(s) loaded");
        })), () -> {
            arcGisStatus.setText("ArcGIS: sign-in expired");
            toast("ArcGIS sign-in expired. Sign in again.");
        });
    }

    private void chooseTree(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ARCGIS_AUTH) {
            handleArcGisAuthorization(data);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, flags);
        if (request == PICK_INPUT) { inputTree = uri; inputPath.setText(displayTree(uri)); }
        if (request == PICK_OUTPUT) { outputTree = uri; outputPath.setText(displayTree(uri)); }
        SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
        if (inputTree != null) edit.putString("input", inputTree.toString());
        if (outputTree != null) edit.putString("output", outputTree.toString());
        edit.apply();
    }

    private void restoreFolders() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String in = p.getString("input", ""), out = p.getString("output", "");
        if (!in.isEmpty()) { inputTree = Uri.parse(in); inputPath.setText(displayTree(inputTree)); }
        if (!out.isEmpty()) { outputTree = Uri.parse(out); outputPath.setText(displayTree(outputTree)); }
    }

    private String displayTree(Uri uri) {
        String id = DocumentsContract.getTreeDocumentId(uri);
        return id.replace("primary:", "Internal storage/");
    }

    private void startRun() {
        saveWaitMinutes();
        if (inputTree == null || outputTree == null) { toast("Choose both folders first."); return; }
        if (!loginConfirmed) { toast("Sign in to JAVAD first and wait for the login-confirmed message."); return; }
        if (arcGisAuthState == null || !arcGisAuthState.isAuthorized()) { toast("Sign in to ArcGIS before processing."); return; }
        ProjectStore.Project project = selectedProject();
        if (project == null) { toast("Select an ArcGIS project first."); return; }
        ProjectStore.save(this, projects, projectSpinner.getSelectedItemPosition());
        observations.clear(); submitted.clear(); failures.clear(); uploadIndex = 0; downloadIndex = 0; downloadSuccess = 0;
        downloadInProgress = false;
        scanTree(inputTree, DocumentsContract.getTreeDocumentId(inputTree), observations);
        if (!reprocess.isChecked()) {
            Set<String> completed = new HashSet<>();
            scanTxtNames(outputTree, DocumentsContract.getTreeDocumentId(outputTree), completed);
            observations.removeIf(item -> completed.contains(item.name.toLowerCase(Locale.US)));
        }
        observations.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
        if (observations.isEmpty()) { toast("No .jps observation files were found."); return; }
        running = true; stopping = false; phase = Phase.UPLOAD;
        start.setEnabled(false); stop.setEnabled(true); failedText.setText(""); log.setText("");
        // A zero-byte observation cannot be submitted to JAVAD. Keep its failure
        // in the results while allowing the other files to continue normally.
        List<Observation> empty = new ArrayList<>();
        for (Observation item : observations) {
            try (java.io.InputStream in = getContentResolver().openInputStream(item.uri)) {
                if (in == null || in.read() == -1) empty.add(item);
            } catch (Exception error) {
                empty.add(item);
                appendLog("Cannot read " + item.name + ": " + error.getMessage());
            }
        }
        observations.removeAll(empty);
        for (Observation item : empty) {
            failures.add(item.name + " — upload: empty or unreadable observation file");
            appendLog("UPLOAD FAILED " + item.name + ": empty or unreadable observation file; skipped");
            markArcGisFailure(item, "Empty or unreadable JAVAD observation file");
        }
        if (observations.isEmpty()) {
            updateProgress(true, 0, 0); updateProgress(false, 0, 0);
            finishRun();
            return;
        }
        updateProgress(true, 0, observations.size()); updateProgress(false, 0, observations.size());
        appendLog("UPLOAD PHASE — " + observations.size() + " valid observation file(s), " + empty.size() + " empty/unreadable skipped");
        appendLog("Maximum wait per plot: " + readWaitMinutes() + " minutes");
        if (recentSubmissionCheck.isChecked()) {
            phase = Phase.PREFLIGHT;
            preflightPage = 1;
            recentNames.clear();
            showBrowser(null);
            inspectRecentPage();
        } else showBrowser(UPLOAD_URL);
    }

    private void inspectRecentPage() {
        if (!running || phase != Phase.PREFLIGHT) return;
        if (stopping) { finishStopped(); return; }
        final int page = preflightPage;
        setStatus("Checking JAVAD submissions from the last hour — page " + page + " of " + MAX_REPORT_PAGES);
        web.loadUrl(String.format(Locale.US, REPORT_URL, page));
        // Hash-route changes do not reliably invoke onPageFinished in WebView.
        handler.postDelayed(() -> inspectRecentRows(page), 4500);
    }

    private void inspectRecentRows(int page) {
        if (!running || phase != Phase.PREFLIGHT || preflightPage != page) return;
        String js = "(function(){var found=[],now=Date.now();" +
                "var rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "rows.forEach(function(row){var c=row.querySelectorAll('td');if(c.length<3)return;" +
                "var name=(c[1].innerText||'').trim(),date=(c[2].innerText||'').trim();" +
                "var m=date.match(/(\\d{1,2})\\/(\\d{1,2})\\/(\\d{4})\\s+at\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)/i);" +
                "if(!m)return;var hour=(+m[4]%12)+(m[6].toUpperCase()==='PM'?12:0);" +
                "var stamp=new Date(+m[3],+m[1]-1,+m[2],hour,+m[5]).getTime();" +
                "if(now-stamp>=0&&now-stamp<3600000)found.push(name);});return found;})()";
        web.evaluateJavascript(js, result -> {
            if (!running || phase != Phase.PREFLIGHT || preflightPage != page) return;
            try {
                org.json.JSONArray names = new org.json.JSONArray(result);
                for (int i = 0; i < names.length(); i++) recentNames.add(names.getString(i).toLowerCase(Locale.US));
            } catch (Exception error) {
                appendLog("Could not read submission times on page " + page + "; no files skipped from this page.");
            }
            if (page < MAX_REPORT_PAGES) { preflightPage++; inspectRecentPage(); }
            else finishRecentCheck();
        });
    }

    private void finishRecentCheck() {
        if (!running || phase != Phase.PREFLIGHT) return;
        if (stopping) { finishStopped(); return; }
        List<Observation> alreadySubmitted = new ArrayList<>();
        for (Observation item : observations)
            if (recentNames.contains(item.name.toLowerCase(Locale.US))) alreadySubmitted.add(item);
        observations.removeAll(alreadySubmitted);
        for (Observation item : alreadySubmitted) {
            submitted.add(item);
            appendLog("SKIPPED UPLOAD " + item.name + ": same filename submitted to JAVAD within the last hour; checking for TXT");
        }
        phase = Phase.UPLOAD;
        updateProgress(true, 0, observations.size());
        appendLog("Recent submission check complete: " + alreadySubmitted.size() + " upload(s) skipped, " + observations.size() + " remaining");
        if (observations.isEmpty()) beginDownloads();
        else web.loadUrl(UPLOAD_URL);
    }

    private void scanTree(Uri tree, String parentId, List<Observation> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTree(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".jps"))
                    found.add(new Observation(name, DocumentsContract.buildDocumentUriUsingTree(tree, id)));
            }
        } catch (Exception e) { appendLog("Folder scan warning: " + e.getMessage()); }
    }

    private void scanTxtNames(Uri tree, String parentId, Set<String> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTxtNames(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".txt")) found.add(stem(name).toLowerCase(Locale.US));
            }
        } catch (Exception e) { appendLog("Results scan warning: " + e.getMessage()); }
    }

    private void showBrowser(String url) {
        controls.setVisibility(View.GONE); browserPanel.setVisibility(View.VISIBLE);
        if (url != null) web.loadUrl(url);
    }
    private void showControls() { browserPanel.setVisibility(View.GONE); controls.setVisibility(View.VISIBLE); }

    private class ProcessorWebClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) { return false; }
        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            CookieManager.getInstance().flush();
            if (!running && url.contains("#/dpos/upload")) {
                handler.postDelayed(() -> verifyLoginPage(), 3000);
            }
            if (!running || stopping) return;
            if (phase == Phase.UPLOAD && url.contains("#/dpos/upload")) handler.postDelayed(() -> prepareUploadPage(), 5000);
            else if (phase == Phase.DOWNLOAD && url.contains("#/dpos/report")) scheduleReportCheck(1800);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            appendLog("JAVAD browser restarted while loading reports; recovering automatically…");
            if (view == loginWindow) {
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
                loginWindow = null;
                web.loadUrl(UPLOAD_URL);
                return true;
            }
            if (view == web) {
                browserPanel.removeView(web);
                web.destroy();
                web = new WebView(MainActivity.this);
                browserPanel.addView(web, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                configureWebView(web);
                backToControls.bringToFront();
                if (running && phase == Phase.DOWNLOAD) handler.postDelayed(() -> loadReportPage(reportPage), 1500);
                else if (running && phase == Phase.UPLOAD) handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 1500);
                return true;
            }
            return false;
        }
    }

    private void verifyLoginPage() {
        String js = "(function(){var text=(document.body.innerText||'');" +
                "if(/drop files/i.test(text))AndroidProcessor.loginReady();" +
                "else AndroidProcessor.loginWaiting();})();";
        web.evaluateJavascript(js, null);
    }

    private class ProcessorChromeClient extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (!running || !selectingForUpload || uploadIndex >= observations.size()) return false;
            if (chosenUploadIndex == uploadIndex) {
                callback.onReceiveValue(null);
                appendLog("Ignored a duplicate file request for " + observations.get(uploadIndex).name);
                return true;
            }
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback; selectingForUpload = false;
            chosenUploadIndex = uploadIndex;
            callback.onReceiveValue(new Uri[]{observations.get(uploadIndex).uri}); pendingFileCallback = null;
            return true;
        }

        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (loginWindow != null) {
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
            }
            loginWindow = new WebView(MainActivity.this);
            WebSettings s = loginWindow.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setAllowContentAccess(true);
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setSupportMultipleWindows(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(loginWindow, true);
            loginWindow.setWebChromeClient(this);
            loginWindow.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView child, android.webkit.WebResourceRequest request) {
                    return false;
                }
                @Override public void onPageFinished(WebView child, String url) {
                    CookieManager.getInstance().flush();
                    setStatus("Complete the JAVAD sign-in shown on this screen.");
                    if (url != null && url.startsWith("https://app.javad.com/jca/")) {
                        handler.postDelayed(() -> onCloseWindow(child), 1500);
                    }
                }
            });
            browserPanel.addView(loginWindow, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            loginWindow.bringToFront();
            backToControls.bringToFront();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(loginWindow);
            resultMsg.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView window) {
            if (window == loginWindow) {
                CookieManager.getInstance().flush();
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
                loginWindow = null;
                web.bringToFront();
                backToControls.bringToFront();
                setStatus("Sign-in window closed. Verifying JAVAD login…");
                web.loadUrl(UPLOAD_URL);
            }
        }
    }

    private void prepareUploadPage() {
        // Ignore delayed callbacks left over from the final upload page.
        if (phase != Phase.UPLOAD) return;
        if (!running || stopping) { finishStopped(); return; }
        if (uploadIndex >= observations.size()) { beginDownloads(); return; }
        Observation item = observations.get(uploadIndex);
        setStatus("Uploading " + (uploadIndex + 1) + " of " + observations.size() + ": " + item.name);
        appendLog("[Upload " + (uploadIndex + 1) + "/" + observations.size() + "] " + item.name);
        selectingForUpload = true;
        String filename = jsQuote(item.name);
        final int expectedUploadIndex = uploadIndex;
        String js = "(function(){var tries=0,name='" + filename + "'.toLowerCase(),uploadIndex=" + expectedUploadIndex + ";" +
                "var findDrop=function(){var all=[].slice.call(document.querySelectorAll('div,span,p,a,button'));" +
                "return all.filter(function(x){return /drop files.*click here/i.test((x.innerText||'').trim());})" +
                ".sort(function(a,b){return (a.innerText||'').length-(b.innerText||'').length;})[0];};" +
                "var open=setInterval(function(){tries++;var inputs=document.querySelectorAll('input[type=file]'),drop=findDrop();" +
                "if(drop){clearInterval(open);var r=drop.getBoundingClientRect();AndroidProcessor.tapUploadArea(r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio||1);afterPick();}" +
                "else if(inputs.length){clearInterval(open);inputs[inputs.length-1].click();afterPick();}" +
                "else if(tries>=45){clearInterval(open);AndroidProcessor.uploadError(uploadIndex,'Drop files area did not appear');}},1000);" +
                "function afterPick(){var n=0,added=setInterval(function(){n++;var body=(document.body.innerText||'').toLowerCase();" +
                "if(body.indexOf(name)>=0){clearInterval(added);submit();}" +
                "else if(n>=30){clearInterval(added);AndroidProcessor.uploadError(uploadIndex,'JAVAD did not display the selected file');}},1000);}" +
                "function submit(){var b=[].slice.call(document.querySelectorAll('button,a')).find(function(x){return /^submit$/i.test((x.innerText||'').trim())});" +
                "if(!b||b.disabled){AndroidProcessor.uploadError(uploadIndex,'Submit button not ready');return;}b.click();var n=0,t=setInterval(function(){n++;var x=(document.body.innerText||'').toLowerCase();" +
                "if(/observe the progress|has been submitted|submitted successfully/.test(x)){clearInterval(t);AndroidProcessor.submitted(uploadIndex);}" +
                "else if(n>=60){clearInterval(t);AndroidProcessor.uploadError(uploadIndex,'JAVAD did not confirm submission');}},1000);}})();";
        web.evaluateJavascript(js, null);
    }

    private class WebBridge {
        @JavascriptInterface public void tapUploadArea(float cssX, float cssY, float pixelRatio) { runOnUiThread(() -> {
            float x = cssX * pixelRatio;
            float y = cssY * pixelRatio;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(down);
            web.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
            if (running && phase == Phase.UPLOAD && uploadIndex >= 0 && uploadIndex < observations.size())
                appendLog("Tapped JAVAD's Drop files area for " + observations.get(uploadIndex).name);
        }); }
        @JavascriptInterface public void loginReady() { runOnUiThread(() -> {
            loginConfirmed = true;
            setStatus("JAVAD login confirmed — Upload Data is ready.");
            toast("JAVAD login confirmed. Tap Return to Processor.");
        }); }
        @JavascriptInterface public void loginWaiting() { runOnUiThread(() ->
                { loginConfirmed = false; setStatus("JAVAD is not signed in yet. Complete the login in this app."); }); }
        @JavascriptInterface public void submitted(int expectedIndex) { runOnUiThread(() -> {
            // A JAVAD page can leave timers/callbacks alive after we move to the next file.
            // Only accept the callback that belongs to the upload currently being processed.
            if (!running || stopping || phase != Phase.UPLOAD) return;
            if (expectedIndex != uploadIndex) {
                appendLog("Ignored stale submit callback for upload index " + expectedIndex);
                return;
            }
            if (uploadIndex < 0 || uploadIndex >= observations.size()) {
                appendLog("Ignored submit callback after upload queue completed");
                return;
            }
            Observation item = observations.get(uploadIndex); submitted.add(item);
            uploadAttempt = 0; chosenUploadIndex = -1; selectingForUpload = false;
            appendLog("CONFIRMED submitted: " + item.name); uploadIndex++; updateProgress(true, uploadIndex, observations.size());
            if (stopping) { finishStopped(); return; }
            if (uploadIndex >= observations.size()) { handler.postDelayed(() -> beginDownloads(), 3000); return; }
            setStatus("Resetting Upload Data for the next plot…");
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
        }); }
        @JavascriptInterface public void uploadError(int expectedIndex, String message) { runOnUiThread(() -> {
            if (!running || stopping || phase != Phase.UPLOAD) return;
            if (expectedIndex != uploadIndex) {
                appendLog("Ignored stale upload error for index " + expectedIndex + ": " + message);
                return;
            }
            failUpload(message);
        }); }
        @JavascriptInterface public void reportReady() { runOnUiThread(() -> clickTxtDownload()); }
        @JavascriptInterface public void reportMissing() { runOnUiThread(() -> nextReportPage()); }
        @JavascriptInterface public void reportText(String text) { runOnUiThread(() -> {
            if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
            Observation item = submitted.get(downloadIndex);
            boolean saved = saveTextResult(text, txtName(item.name));
            if (!saved) completeDownload(item, "Could not save TXT to results folder");
            else syncTextReport(item, text);
        }); }
        @JavascriptInterface public void reportDownloadError(String message) { runOnUiThread(() -> {
            if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
            completeDownload(submitted.get(downloadIndex), "Could not read JAVAD TXT: " + message);
        }); }
    }

    private void failUpload(String message) {
        // Never index the queue after it has completed. Delayed JavaScript callbacks
        // from the previous JAVAD page can arrive after uploadIndex was incremented.
        if (!running || stopping || phase != Phase.UPLOAD) return;
        if (uploadIndex < 0 || uploadIndex >= observations.size()) {
            appendLog("Ignored upload error after queue completed: " + message);
            if (uploadIndex >= observations.size()) beginDownloads();
            return;
        }
        Observation item = observations.get(uploadIndex);
        selectingForUpload = false;
        uploadAttempt++;
        if (uploadAttempt < 3) {
            appendLog("JAVAD was not ready for " + item.name + "; retrying (" + uploadAttempt + "/3)");
            chosenUploadIndex = -1;
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
            return;
        }
        failures.add(item.name + " — upload: " + message);
        appendLog("UPLOAD FAILED " + item.name + ": " + message);
        markArcGisFailure(item, "JAVAD upload failed: " + message);
        uploadAttempt = 0; chosenUploadIndex = -1; uploadIndex++; updateProgress(true, uploadIndex, observations.size());
        handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 2000);
    }

    private void beginDownloads() {
        try {
        if (!running) return;
        // Several WebView callbacks may notice the final submitted plot. Only
        // the first one is allowed to transition into the download phase.
        if (phase == Phase.DOWNLOAD) return;
        phase = Phase.DOWNLOAD;
        handler.removeCallbacks(reportCheckTask);
        appendLog("DOWNLOAD PHASE — " + submitted.size() + " submitted file(s)");
        if (submitted.isEmpty()) { finishRun(); return; }
        downloadIndex = 0; reportPage = 1;
        resetReportDeadline();
        loadReportPage(reportPage);
        } catch (Throwable error) {
            handleDownloadFailure("Starting Browse Reports", error);
        }
    }

    private void loadReportPage(int pageNumber) {
        try {
            web.loadUrl(String.format(Locale.US, REPORT_URL, pageNumber));
            // JAVAD uses Angular hash routes. WebView may change the route without
            // calling onPageFinished, so always schedule the report inspection.
            scheduleReportCheck(4000);
        } catch (Throwable error) {
            handleDownloadFailure("Opening Browse Reports page " + pageNumber, error);
        }
    }

    private void handleDownloadFailure(String step, Throwable error) {
        String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        appendLog("DOWNLOAD ENGINE ERROR during " + step + " — " + detail);
        failures.add("Download engine — " + detail);
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls();
        failedText.setText("Download engine error: " + detail);
        status.setText("Download stopped safely. Send a photo of this error.");
    }

    private void scheduleReportCheck(long delayMs) {
        handler.removeCallbacks(reportCheckTask);
        handler.postDelayed(reportCheckTask, delayMs);
    }

    private void checkReportPage() {
        if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
        String name = jsQuote(submitted.get(downloadIndex).name);
        setStatus("Checking reports for " + submitted.get(downloadIndex).name + " — page " + reportPage);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r&&r.querySelectorAll('save-as').length>=2)AndroidProcessor.reportReady();else AndroidProcessor.reportMissing();})();";
        web.evaluateJavascript(js, null);
    }

    private void clickTxtDownload() {
        String name = jsQuote(submitted.get(downloadIndex).name);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r){var s=r.querySelectorAll('save-as');if(s.length>1){var t=s[1].querySelector('a,button,[role=button]')||s[1];" +
                "t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));t.click();}}})()";
        web.evaluateJavascript(js, null);
    }

    private void nextReportPage() {
        if (System.currentTimeMillis() >= reportDeadline) {
            completeDownload(submitted.get(downloadIndex), "No downloadable TXT report appeared within the maximum wait");
            return;
        }
        // A matched report can be older than page one even for a single file.
        int pages = MAX_REPORT_PAGES;
        if (reportPage < pages) { reportPage++; loadReportPage(reportPage); }
        else handler.postDelayed(() -> { reportPage = 1; loadReportPage(1); }, 15000);
    }

    private class ReportDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
            if (!running || downloadIndex >= submitted.size()) return;
            if ((mime != null && mime.toLowerCase(Locale.US).contains("pdf")) ||
                    (url != null && url.toLowerCase(Locale.US).contains(".pdf"))) {
                appendLog("Ignored PDF download"); return;
            }
            if (downloadInProgress) {
                appendLog("Ignored a duplicate TXT download event");
                return;
            }
            downloadInProgress = true;
            Observation item = submitted.get(downloadIndex);
            try {
                if (url != null && url.startsWith("blob:")) {
                    appendLog("Reading JAVAD TXT report for " + item.name);
                    String quotedUrl = org.json.JSONObject.quote(url);
                    String js = "fetch(" + quotedUrl + ").then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();})" +
                            ".then(function(t){AndroidProcessor.reportText(t);})" +
                            ".catch(function(e){AndroidProcessor.reportDownloadError(String(e));});";
                    web.evaluateJavascript(js, null);
                    return;
                }
                if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                    downloadInProgress = false;
                    completeDownload(item, "Unsupported JAVAD download address");
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType("text/plain");
                request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, txtName(item.name));
                long id = ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                waitForDownload(id, item);
            } catch (Throwable error) {
                downloadInProgress = false;
                completeDownload(item, "TXT download error: " + error.getMessage());
            }
        }
    }

    private boolean saveTextResult(String text, String filename) {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", filename);
            if (file == null) return false;
            try (OutputStream out = getContentResolver().openOutputStream(file, "w")) {
                if (out == null) return false;
                out.write((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            appendLog("Save error: " + e.getMessage());
            return false;
        }
    }

    private void waitForDownload(long id, Observation item) {
        new Thread(() -> {
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            for (int tries=0; tries<180; tries++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) {
                        int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (state == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri local = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                            String reportText = readLocalText(local);
                            boolean copied = copyResult(local, txtName(item.name));
                            runOnUiThread(() -> {
                                if (!copied) completeDownload(item, "Could not copy TXT to results folder");
                                else if (reportText == null) completeDownload(item, "Could not read copied TXT for ArcGIS update");
                                else syncTextReport(item, reportText);
                            }); return;
                        }
                        if (state == DownloadManager.STATUS_FAILED) { runOnUiThread(() -> completeDownload(item, "Android download failed")); return; }
                    }
                }
            }
            runOnUiThread(() -> completeDownload(item, "TXT download timed out"));
        }).start();
    }

    private boolean copyResult(Uri source, String filename) {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", filename);
            if (file == null) return false;
            try (FileInputStream in = new FileInputStream(new File(source.getPath())); OutputStream out = getContentResolver().openOutputStream(file, "w")) {
                byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n);
            }
            return true;
        } catch (Exception e) { appendLog("Save error: " + e.getMessage()); return false; }
    }

    private String readLocalText(Uri source) {
        try (FileInputStream in = new FileInputStream(new File(source.getPath()));
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        } catch (Exception error) {
            appendLog("Could not read downloaded TXT for ArcGIS: " + error.getMessage());
            return null;
        }
    }

    private void syncTextReport(Observation item, String text) {
        ProjectStore.Project project = selectedProject();
        if (project == null) { completeDownload(item, "No ArcGIS project selected"); return; }
        final JavadReportParser.Result report;
        try {
            report = JavadReportParser.parse(text);
            if (!report.plotId.equalsIgnoreCase(stem(item.name))) {
                completeDownload(item, "TXT plot ID " + report.plotId + " did not match " + stem(item.name));
                return;
            }
        } catch (Exception error) {
            completeDownload(item, "TXT parsing failed: " + error.getMessage());
            return;
        }
        setStatus("Updating ArcGIS plot " + report.plotId + "…");
        withArcGisToken(token -> ArcGisPlotClient.syncReport(
                project.layerUrl, project.maxOffsetMeters, token, report, result -> runOnUiThread(() -> {
                    if (!result.updated) {
                        completeDownload(item, "ArcGIS update failed: " + result.message);
                    } else if (result.failedQa) {
                        appendLog(String.format(Locale.US, "ARCGIS QA FAILED %s — offset %.1f m", report.plotId, result.offsetMeters));
                        completeDownload(item, result.message);
                    } else {
                        appendLog(String.format(Locale.US, "ArcGIS updated %s — offset %.1f m", report.plotId, result.offsetMeters));
                        completeDownload(item, null);
                    }
                })), () -> completeDownload(item, "ArcGIS sign-in expired; sign in again"));
    }

    private void markArcGisFailure(Observation item, String reason) {
        ProjectStore.Project project = selectedProject();
        if (project == null || arcGisAuthState == null || !arcGisAuthState.isAuthorized()) return;
        withArcGisToken(token -> ArcGisPlotClient.markFailed(
                project.layerUrl, token, stem(item.name), reason,
                result -> { if (!result.updated) appendLog("Could not mark ArcGIS failure for " + item.name + ": " + result.message); }),
                () -> appendLog("Could not mark ArcGIS failure; sign-in expired"));
    }

    private void completeDownload(Observation item, String error) {
        downloadInProgress = false;
        if (error == null) { downloadSuccess++; appendLog("Downloaded TXT report for " + item.name); }
        else {
            failures.add(item.name + " — processing: " + error);
            appendLog("PROCESSING FAILED " + item.name + ": " + error);
            markArcGisFailure(item, error);
        }
        downloadIndex++; updateProgress(false, downloadIndex, submitted.size());
        if (downloadIndex >= submitted.size() || stopping) finishRun();
        else { reportPage = 1; resetReportDeadline(); loadReportPage(1); }
    }

    private void finishRun() {
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls();
        failedText.setText(failures.isEmpty() ? "No files failed." : "Failed files:\n" + String.join("\n", failures));
        setStatus("Finished: " + downloadSuccess + " succeeded, " + failures.size() + " failed.");
        saveFailureList();
    }
    private void finishStopped() {
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls(); setStatus("Processing stopped safely.");
    }

    private void saveFailureList() {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", "failed_files_latest.txt");
            if (file == null) return;
            String body = failures.isEmpty() ? "No files failed during the latest run.\n" : "Failed files from the latest run:\n\n" + String.join("\n", failures) + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(file, "w")) { out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } catch (Exception e) { appendLog("Could not save failure list: " + e.getMessage()); }
    }

    private void updateProgress(boolean upload, int current, int total) {
        int pct = total == 0 ? 0 : Math.round(current * 100f / total);
        if (upload) { uploadBar.setProgress(pct); uploadText.setText("Uploading: " + pct + "% — " + current + " of " + total); }
        else { downloadBar.setProgress(pct); downloadText.setText("Downloading: " + pct + "% — " + current + " of " + total); }
    }
    private void setStatus(String message) { status.setText(message); }
    private void showFullLog() {
        ScrollView scroller = new ScrollView(this);
        TextView full = new TextView(this);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        full.setPadding(padding, padding, padding, padding);
        full.setTextColor(android.graphics.Color.WHITE);
        full.setBackgroundColor(android.graphics.Color.rgb(13, 64, 57));
        full.setTextSize(14);
        full.setTypeface(android.graphics.Typeface.MONOSPACE);
        full.setTextIsSelectable(true);
        full.setText(log.getText().length() == 0 ? "No process messages yet." : log.getText());
        scroller.addView(full);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Full process log")
                .setView(scroller)
                .setPositiveButton("Close", (view, which) -> {})
                .create();
        dialog.show();
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.95f),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
    }
    private void appendLog(String message) {
        runOnUiThread(() -> { String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); log.append(time + "  " + message + "\n"); });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String jsQuote(String value) { return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " "); }
    private static String txtName(String value) {
        int dot = value.lastIndexOf('.');
        return (dot > 0 ? value.substring(0, dot) : value) + ".txt";
    }
    private static String stem(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
    private void resetReportDeadline() {
        reportDeadline = System.currentTimeMillis() + readWaitMinutes() * 60_000L;
    }

    private int readWaitMinutes() {
        try { return Math.max(1, Math.min(180, Integer.parseInt(maxWait.getText().toString().trim()))); }
        catch (NumberFormatException ignored) { return getPreferences(MODE_PRIVATE).getInt("wait_minutes", 10); }
    }

    private void saveWaitMinutes() {
        int minutes = readWaitMinutes();
        maxWait.setText(String.valueOf(minutes));
        getPreferences(MODE_PRIVATE).edit().putInt("wait_minutes", minutes).apply();
    }

    private void adjustWaitMinutes(int delta) {
        int minutes = Math.max(1, Math.min(180, readWaitMinutes() + delta));
        maxWait.setText(String.valueOf(minutes));
        getPreferences(MODE_PRIVATE).edit().putInt("wait_minutes", minutes).apply();
    }

    private static class Observation {
        final String name; final Uri uri;
        Observation(String name, Uri uri) { this.name = name; this.uri = uri; }
    }
    private enum Phase { IDLE, PREFLIGHT, UPLOAD, DOWNLOAD }
}
