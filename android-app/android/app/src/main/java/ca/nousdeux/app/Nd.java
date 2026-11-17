package ca.nousdeux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

/**
 * Outils partagés : canaux de notification, sonnerie d'appel, état de l'app.
 * Le contenu reste chiffré : les notifications disent seulement qui a fait quoi.
 */
public final class Nd {
    private Nd() {}

    // Canaux (Android les garde en mémoire : changer l'id pour changer leurs réglages par défaut)
    static final String CH_CALLS = "appels_v1";
    static final String CH_MISSED = "manques_v1";
    static final String CH_MESSAGES = "messages_v1";
    static final String CH_LISTS = "listes_v1";
    static final String CH_AGENDA = "agenda_v1";

    static final int ID_CALL = 1;
    static final int ID_MESSAGE = 10;
    static final int ID_LISTS = 11;
    static final int ID_AGENDA = 12;
    static final int ID_MISSED = 13;
    static final int ID_TEST = 14;

    static final String ACTION_CALL_END = "ca.nousdeux.app.CALL_END";
    static final String ACTION_DECLINE = "ca.nousdeux.app.DECLINE";
    static final String ACTION_ANSWER = "ca.nousdeux.app.ANSWER";

    static final int ACCENT = Color.parseColor("#D9506A");
    static final long[] RING_VIBRATION = { 0, 900, 400, 900, 400, 900, 400, 900 };

    // L'app est-elle à l'écran? (dans ce cas, l'app web gère elle-même le son)
    static volatile boolean foreground = false;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nd", Context.MODE_PRIVATE);
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_CALLS) == null) {
            NotificationChannel ch = new NotificationChannel(CH_CALLS, "Appels", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Sonnerie quand on t'appelle dans Nous deux");
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            );
            ch.enableVibration(true);
            ch.setVibrationPattern(RING_VIBRATION);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.enableLights(true);
            ch.setLightColor(ACCENT);
            nm.createNotificationChannel(ch);
        }
        createSimple(nm, CH_MISSED, "Appels manqués", "Quand tu as manqué un appel", NotificationManager.IMPORTANCE_HIGH);
        createSimple(nm, CH_MESSAGES, "Messages", "Quand on t'écrit dans Discussion", NotificationManager.IMPORTANCE_HIGH);
        createSimple(nm, CH_LISTS, "Listes", "Quand on ajoute quelque chose à une liste", NotificationManager.IMPORTANCE_HIGH);
        createSimple(nm, CH_AGENDA, "Agenda", "Quand on ajoute un événement", NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static void createSimple(NotificationManager nm, String id, String name, String desc, int importance) {
        if (Build.VERSION.SDK_INT < 26 || nm.getNotificationChannel(id) != null) return;
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(desc);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[] { 0, 250, 120, 250 });
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    static PendingIntent openAppIntent(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ---------- Appels ---------- */

    static boolean isRinging(Context c) {
        if (Build.VERSION.SDK_INT < 23) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == ID_CALL) return true;
        return false;
    }

    /** « Ignorer » : on ne sonne plus pendant une minute (le serveur relance l'alerte toutes les 5 s). */
    static void ignoreFor(Context c, long ms) {
        prefs(c).edit().putLong("ignoreUntil", System.currentTimeMillis() + ms).apply();
    }

    static boolean ignored(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong("ignoreUntil", 0);
    }

    static void showRinging(Context c, String who, boolean video, String body) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String name = (who == null || who.isEmpty()) ? "Ton amour" : who;
        String text = (body == null || body.isEmpty()) ? (name + " t'appelle" + (video ? " en vidéo" : "")) : body;

        Intent full = new Intent(c, CallActivity.class)
            .putExtra("who", name).putExtra("video", video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, 100, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent ans = new Intent(c, CallActivity.class).setAction(ACTION_ANSWER)
            .putExtra("who", name).putExtra("video", video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent ansPi = PendingIntent.getActivity(c, 101, ans, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dec = new Intent(c, CallActionReceiver.class).setAction(ACTION_DECLINE);
        PendingIntent decPi = PendingIntent.getBroadcast(c, 102, dec, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_nd)
            .setColor(ACCENT)
            .setContentTitle(name)
            .setContentText(video ? "Appel vidéo entrant — Nous deux" : "Appel entrant — Nous deux")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(50_000)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioManager.STREAM_RING)
            .setVibrate(RING_VIBRATION);

        Notification n;
        try {
            // Style « appel » d'Android : gros boutons Répondre / Refuser
            Person caller = new Person.Builder().setName(name).setImportant(true).build();
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decPi, ansPi).setIsVideo(video));
            n = b.build();
        } catch (Exception e) {
            b.setStyle(null);
            b.addAction(0, "Ignorer", decPi).addAction(0, "Répondre", ansPi);
            n = b.build();
        }
        n.flags |= Notification.FLAG_INSISTENT; // la sonnerie tourne en boucle jusqu'à la réponse
        try {
            nm.notify(ID_CALL, n);
        } catch (Exception e) {
            // dernier recours : notification d'appel simple
            b.setStyle(null);
            Notification n2 = b.build();
            n2.flags |= Notification.FLAG_INSISTENT;
            nm.notify(ID_CALL, n2);
        }
    }

    static void stopRinging(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(ID_CALL);
        c.sendBroadcast(new Intent(ACTION_CALL_END).setPackage(c.getPackageName()));
    }

    /** Après « Répondre » : l'app web répondra toute seule dès qu'elle sera déverrouillée. */
    static void setPendingAnswer(Context c) {
        prefs(c).edit().putLong("answerAt", System.currentTimeMillis()).apply();
    }

    static String takeLaunchAction(Context c) {
        long at = prefs(c).getLong("answerAt", 0);
        prefs(c).edit().remove("answerAt").apply();
        return (at > 0 && System.currentTimeMillis() - at < 90_000) ? "answer" : "";
    }

    /* ---------- Notifications ordinaires ---------- */

    static void showSimple(Context c, String channel, int id, String title, String body) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_nd)
            .setColor(ACCENT)
            .setContentTitle(title == null || title.isEmpty() ? "Nous deux" : title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(id == ID_MESSAGE ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, 200 + id))
            .setDefaults(NotificationCompat.DEFAULT_ALL);
        if (id == ID_MISSED) b.setCategory(NotificationCompat.CATEGORY_MISSED_CALL);
        nm.notify(id, b.build());
    }

    /** À l'ouverture de l'app : on enlève les notifications (sauf un appel qui sonne). */
    static void clearAll(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        for (int id : new int[] { ID_MESSAGE, ID_LISTS, ID_AGENDA, ID_MISSED, ID_TEST }) nm.cancel(id);
    }

    static Uri ringtone() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }
}
