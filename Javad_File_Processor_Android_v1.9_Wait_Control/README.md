# Javad File Processor — Android

## Version 1.9: adjustable report wait

The maximum wait per plot defaults to 10 minutes on a fresh installation.
Use the large minus and plus buttons to change it by one minute, or type a
number between 1 and 180. The app remembers the selected value and displays
it in the process log when processing begins. Existing installations retain
their previously saved selection.

## Version 1.8: optional recent-submission check

The new checkbox **Skip upload if same filename was submitted to JAVAD in last
hour (check all projects)** is off by default. When enabled, the app checks
the first 10 JAVAD Reports pages and matches the exact filename against the
Created time shown there. Recent matches bypass upload but are still searched
for TXT reports and processed in ArcGIS. JAVAD's list does not identify an
ArcGIS project, so filenames used in more than one project can be mistaken for
one another. Leave this option off for projects that reuse filenames. When
the date cannot be read, the app uploads rather than silently skipping.

## Version 1.7: empty files and readable log

Before upload, the app checks each selected `.jps` file for readable content.
Empty or unreadable files are skipped, shown in the failed files list, and marked
Failed in the selected ArcGIS plot layer when a matching plot is available. Other
files continue processing. **View full process log** opens a large, independently
scrollable window. Close and reopen it to see newer messages.

## Version 1.6 ArcGIS project integration

The app now signs in to ArcGIS Online with the registered OAuth application,
loads projects from the **NMI Mobile Map** group, and automatically selects a
layer named `Plots` within each feature service. A manual project option remains
available for services outside the group.

After each JAVAD TXT report is saved locally, the app matches its filename to
`Plot_ID`, copies the processed UTM values to the selected Plot layer, and
calculates the distance from the original feature geometry. The default maximum
offset is 50 meters. Reports outside that limit retain their coordinates but are
marked `Failed` with the measured offset and reason. ArcGIS failures never
delete the downloaded TXT report.

## Version 1.5 transparent UI logo

The approved red FORSITE and JAVAD composition now appears without a separate
green background in the upper-left of the existing dark-green app banner. The
launcher icon retains its green background.

## Version 1.4 launcher-icon placement

The JAVAD logo on the launcher icon now follows the supplied red-box placement
guide, with the logo larger and positioned farther left and higher.

## Version 1.3 final branding

The banner now uses one transparent red FORSITE and JAVAD lockup in the
upper-left, replacing the two previous banner images. The launcher icon keeps
the JAVAD logo in the lower-right with slightly larger lettering and improved
edge spacing.

## Version 1.2 branded app icon

The installed app now uses the dark-green icon with the centered red FORSITE
mark and the small green-and-white JAVAD wordmark in the lower-right corner.

## Version 1.1 download repair

JAVAD supplies TXT results to Android as temporary `blob:` addresses. Version
1.1 reads those reports inside the signed-in JAVAD page and writes the TXT
content directly to the selected results folder. Normal HTTP/HTTPS downloads
remain supported, PDFs are ignored, and duplicate download events are blocked.

This is the tablet edition of the Northwest Management | FORSITE Javad File Processor. It is configured for the Samsung Galaxy Tab Active3 (SM-T570) running Android 13 and also supports Android 10 or newer.

## Field workflow

1. Install the APK and open **Javad File Processor**.
2. Tap **Choose observation folder** and select the folder containing the `.jps` files.
3. Tap **Choose results folder** and select the folder where TXT reports should be saved.
4. Tap **Sign in / open JAVAD**, sign in, and confirm that Upload Data is visible.
5. Tap **Return to Processor**.
6. Tap **Sign in to ArcGIS** and use an organization account that can edit the project.
7. Refresh the **NMI Mobile Map** projects and select the correct project.
8. Tap **Start Processing**. Keep the app open until both orange progress bars reach 100%.
9. Review any failed filenames shown under the status. The same list is saved as `failed_files_latest.txt` in the results folder.

The app keeps the screen awake, submits one file at a time, resets the JAVAD Upload Data page between files, checks up to 10 Browse Reports pages, ignores PDF downloads, and saves only TXT results.

## Required Plot fields

Every project used for ArcGIS updates must have a point layer named `Plots`, a
unique text field named `Plot_ID`, and these fields:

| Field name | Type |
| --- | --- |
| `Javad_X` | Double |
| `Javad_Y` | Double |
| `Javad_UTM_Zone` | Short Integer |
| `Javad_Elev` | Double |
| `Javad_Process_Date` | Date |
| `Javad_Status` | Text (20) |
| `Javad_Offset` | Double |
| `Javad_Fail_Reason` | Text (150) |

The app writes `Processed` or `Failed` to `Javad_Status`. Existing plots can be
initialized to `Unprocessed` before field work begins.

## Build an installable APK

The easiest method does not require Android Studio. Follow `BUILD_APK_WITH_GITHUB.md` to build and download the APK using a free GitHub account.

### Android Studio alternative

1. Install the current Android Studio on a Windows computer.
2. Open the `android_javad_processor` folder as a project.
3. Allow Android Studio to install Android SDK 35 and finish Gradle synchronization.
4. Connect the Tab Active3 by USB with USB debugging enabled.
5. Use **Build > Generate App Bundles or APKs > Generate APKs**.
6. Copy `app/build/outputs/apk/debug/app-debug.apk` to the tablet and open it to install.

Android may ask whether to allow installation from the file-management app. Approve that prompt only for this APK.

## Important first-release note

JAVAD controls its website and can change the Upload Data or Browse Reports page without notice. Test this first release with two or three copied plots before running a large field folder. If a page element has changed, the on-screen log and failed-file list identify where the workflow stopped.
