package ca.nousdeux.app;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Reçoit les alertes du serveur, même quand l'app est fermée.
 * Le serveur envoie seulement des données (pas de texte de message) :
 *   kind = ring     → fait sonner comme un vrai appel (plein écran)
 *   kind = callend  → arrête la sonnerie (répondu ailleurs ou raccroché)
 *   kind = missed   → arrête la sonnerie et affiche « Appel manqué »
 *   sinon           → notification ordinaire (message, liste, agenda, test)
 */
public class NdMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        String kind = s(d.get("kind"));
        String tag = s(d.get("tag"));
        String title = s(d.get("title"));
        String body = s(d.get("body"));
        String who = s(d.get("who"));
        boolean force = "1".equals(d.get("force"));

        switch (kind) {
            case "ring":
                if (Nd.foreground) return; // l'app est ouverte : elle sonne elle-même
                if (Nd.ignored(this) || Nd.isRinging(this)) return; // déjà en train de sonner (relances aux 5 s)
                Nd.showRinging(this, who, "1".equals(d.get("video")), body);
                return;
            case "callend":
                Nd.stopRinging(this);
                return;
            case "missed":
                Nd.stopRinging(this);
                Nd.showSimple(this, Nd.CH_MISSED, Nd.ID_MISSED, title, body);
                return;
            default:
                break;
        }

        if (!force && Nd.foreground) return; // tu regardes déjà l'app

        String channel = Nd.CH_MESSAGES;
        int id = Nd.ID_MESSAGE;
        if ("lists".equals(tag)) { channel = Nd.CH_LISTS; id = Nd.ID_LISTS; }
        else if ("agenda".equals(tag)) { channel = Nd.CH_AGENDA; id = Nd.ID_AGENDA; }
        else if ("test".equals(tag)) { id = Nd.ID_TEST; }
        Nd.showSimple(this, channel, id, title, body.isEmpty() ? "Nouveau message 💌" : body);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // l'app web récupère le jeton à chaque ouverture et le met à jour dans Firebase
        Nd.prefs(this).edit().putString("token", token).apply();
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }
}
