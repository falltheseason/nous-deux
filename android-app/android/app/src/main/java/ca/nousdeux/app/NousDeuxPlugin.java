package ca.nousdeux.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Pont entre l'app web et Android :
 * jeton de notification, permissions, sonnerie, réglages.
 * Appelé depuis la page avec Capacitor.nativePromise("NousDeux", "méthode", {...}).
 */
@CapacitorPlugin(
    name = "NousDeux",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class NousDeuxPlugin extends Plugin {

    @Override
    public void load() {
        Nd.ensureChannels(getContext());
    }

    /** Demande la permission d'afficher des notifications (Android 13+). */
    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            call.resolve(status());
            return;
        }
        requestPermissionForAlias("notifications", call, "notifPermCallback");
    }

    @PermissionCallback
    private void notifPermCallback(PluginCall call) {
        call.resolve(status());
    }

    /** Jeton FCM de cet appareil (à enregistrer dans Firebase). */
    @PluginMethod
    public void getToken(PluginCall call) {
        try {
            if (FirebaseApp.getApps(getContext()).isEmpty()) FirebaseApp.initializeApp(getContext());
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) {
                    JSObject r = new JSObject();
                    r.put("token", t.getResult());
                    call.resolve(r);
                } else {
                    call.reject(t.getException() != null ? t.getException().getMessage() : "Jeton indisponible");
                }
            });
        } catch (Exception e) {
            call.reject("Firebase n'est pas configuré dans l'app : " + e.getMessage());
        }
    }

    /** État des réglages importants pour ne rien manquer. */
    @PluginMethod
    public void getStatus(PluginCall call) {
        call.resolve(status());
    }

    private JSObject status() {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled());
        boolean fsi = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            fsi = nm.canUseFullScreenIntent();
        }
        r.put("fullScreen", fsi);
        boolean battery = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            battery = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        }
        r.put("batteryUnrestricted", battery);
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("maker", Build.MANUFACTURER);
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            r.put("version", pi.versionName);
        } catch (PackageManager.NameNotFoundException ignored) {}
        r.put("mic", ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        return r;
    }

    /** Ouvre un écran de réglages Android. which = notifications | fullScreen | battery | calls */
    @PluginMethod
    public void openSettings(PluginCall call) {
        Context c = getContext();
        String which = call.getString("which", "notifications");
        Intent i;
        String pkg = c.getPackageName();
        if ("fullScreen".equals(which) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg));
        } else if ("battery".equals(which) && Build.VERSION.SDK_INT >= 23) {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg));
        } else if ("calls".equals(which) && Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Nd.CH_CALLS);
        } else if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getActivity().startActivity(i);
        } catch (Exception e) {
            Intent fb = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(fb);
        }
        call.resolve();
    }

    /** « answer » si on vient de toucher Répondre sur l'écran d'appel. */
    @PluginMethod
    public void takeLaunchAction(PluginCall call) {
        JSObject r = new JSObject();
        r.put("action", Nd.takeLaunchAction(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void stopRinging(PluginCall call) {
        Nd.stopRinging(getContext());
        call.resolve();
    }

    @PluginMethod
    public void clearNotifications(PluginCall call) {
        Nd.clearAll(getContext());
        call.resolve();
    }

    /** Fait sonner pour essayer (sans appel réel). */
    @PluginMethod
    public void testRing(PluginCall call) {
        Nd.showRinging(getContext(), call.getString("who", "Test"), false, null);
        call.resolve();
    }

    /** Prévient la page quand l'utilisateur touche « Répondre » alors que l'app est déjà ouverte. */
    void notifyAnswer() {
        JSObject r = new JSObject();
        r.put("action", "answer");
        notifyListeners("launchAction", r, true);
    }
}
