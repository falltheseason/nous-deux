package ca.nousdeux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bouton « Ignorer » de la notification d'appel. */
public class CallActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Nd.ACTION_DECLINE.equals(i.getAction())) {
            Nd.ignoreFor(c, 60_000);
            Nd.stopRinging(c);
        }
    }
}
