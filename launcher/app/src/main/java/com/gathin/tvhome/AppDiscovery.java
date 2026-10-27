package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust app discovery and metadata cache for Android TV.
 *
 * Discovers:
 * 1. CATEGORY_LEANBACK_LAUNCHER (native Android TV activities)
 * 2. CATEGORY_LAUNCHER (standard mobile / sideloaded / stores / OEM tools)
 * 3. CATEGORY_INFO (system and OEM TV tools)
 * 4. OEM fallback via getLeanbackLaunchIntentForPackage / getLaunchIntentForPackage
 *
 * Deduplicates multiple entry points per package while preserving distinct OEM activities,
 * safely caches decoded Drawables/labels across reloads to eliminate UI-thread disk I/O,
 * and handles missing icons gracefully without crashing.
 */
final class AppDiscovery {
    private static final String TAG = "TvHome.Discovery";

    static final class Result {
        final List<ResolveInfo> allLaunchable;
        final Set<String> leanbackPackages;
        final Set<String> allPackages;

        Result(List<ResolveInfo> allLaunchable, Set<String> leanbackPackages, Set<String> allPackages) {
            this.allLaunchable = Collections.unmodifiableList(allLaunchable);
            this.leanbackPackages = Collections.unmodifiableSet(leanbackPackages);
            this.allPackages = Collections.unmodifiableSet(allPackages);
        }
    }

    static final class AppCache {
        final long lastUpdateTime;
        final String label;
        final Drawable banner;
        final Drawable icon;
        final int ambientColor;

        AppCache(long lastUpdateTime, String label, Drawable banner, Drawable icon, int ambientColor) {
            this.lastUpdateTime = lastUpdateTime;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.ambientColor = ambientColor;
        }
    }

    private static final Map<String, AppCache> CACHE = new ConcurrentHashMap<>();
    private static volatile int lastDiscoveredPackageCount = -1;

    private AppDiscovery() {}

    static void invalidateCache() {
        CACHE.clear();
        lastDiscoveredPackageCount = -1;
    }

    static void invalidatePackage(String packageName) {
        if (packageName != null) {
            CACHE.remove(packageName);
            lastDiscoveredPackageCount = -1;
        }
    }

    static AppCache getCached(String pkg) {
        return CACHE.get(pkg);
    }

    static void putCache(String pkg, AppCache entry) {
        if (pkg != null && entry != null) {
            CACHE.put(pkg, entry);
        }
    }

    static int getLastDiscoveredPackageCount() {
        return lastDiscoveredPackageCount;
    }

    static Result discover(Context context) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        Set<String> leanbackPackages = new HashSet<>();
        Set<String> allPackages = new HashSet<>();
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();

        // 1. Primary TV pass: CATEGORY_LEANBACK_LAUNCHER
        try {
            List<ResolveInfo> leanbackList = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            if (leanbackList != null) {
                for (ResolveInfo ri : leanbackList) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(self) || !ri.activityInfo.exported) continue;
                    leanbackPackages.add(pkg);
                    allPackages.add(pkg);
                    String key = pkg + "/" + ri.activityInfo.name;
                    if (!unique.containsKey(key)) {
                        unique.put(key, ri);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed querying leanback activities: " + e);
        }

        // 2. Standard Android pass: CATEGORY_LAUNCHER (sideloaded, mobile, App Store, file manager, etc.)
        try {
            List<ResolveInfo> launcherList = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            if (launcherList != null) {
                for (ResolveInfo ri : launcherList) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(self) || !ri.activityInfo.exported) continue;
                    allPackages.add(pkg);
                    // If package already has a leanback activity, skip the normal launcher activity to avoid duplicate tiles
                    if (leanbackPackages.contains(pkg)) continue;
                    String key = pkg + "/" + ri.activityInfo.name;
                    if (!unique.containsKey(key)) {
                        unique.put(key, ri);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed querying standard launcher activities: " + e);
        }

        // 3. Info pass: CATEGORY_INFO (used by some OEM TV utilities and settings apps)
        try {
            List<ResolveInfo> infoList = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO), 0);
            if (infoList != null) {
                for (ResolveInfo ri : infoList) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(self) || !ri.activityInfo.exported) continue;
                    if (allPackages.contains(pkg)) continue;
                    allPackages.add(pkg);
                    String key = pkg + "/" + ri.activityInfo.name;
                    if (!unique.containsKey(key)) {
                        unique.put(key, ri);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed querying info activities: " + e);
        }

        // 4. OEM fallback: check getLeanbackLaunchIntentForPackage / getLaunchIntentForPackage
        try {
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
            if (installedApps != null) {
                for (ApplicationInfo ai : installedApps) {
                    if (ai == null || ai.packageName == null) continue;
                    String pkg = ai.packageName;
                    if (pkg.equals(self) || allPackages.contains(pkg) || !ai.enabled) continue;

                    Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (launchIntent == null) {
                        launchIntent = pm.getLaunchIntentForPackage(pkg);
                    }
                    if (launchIntent != null) {
                        ResolveInfo ri = pm.resolveActivity(launchIntent, 0);
                        if (ri != null && ri.activityInfo != null && (ri.activityInfo.exported
                                || (ri.activityInfo.applicationInfo != null && (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0))) {
                            allPackages.add(pkg);
                            String key = pkg + "/" + ri.activityInfo.name;
                            if (!unique.containsKey(key)) {
                                unique.put(key, ri);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed OEM fallback package scan: " + e);
        }

        lastDiscoveredPackageCount = allPackages.size();
        return new Result(new ArrayList<>(unique.values()), leanbackPackages, allPackages);
    }
}
