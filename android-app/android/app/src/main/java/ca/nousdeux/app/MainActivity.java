package ca.nousdeux.app;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NousDeuxPlugin.class);
        super.onCreate(savedInstanceState);
        Nd.ensureChannels(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        Nd.foreground = true;
        Nd.clearAll(this);
    }

    @Override
    public void onPause() {
        Nd.foreground = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // « Répondre » touché alors que l'app était déjà ouverte en arrière-plan
        if (intent != null && Nd.ACTION_ANSWER.equals(intent.getAction()) && bridge != null) {
            PluginHandle h = bridge.getPlugin("NousDeux");
            if (h != null && h.getInstance() instanceof NousDeuxPlugin) ((NousDeuxPlugin) h.getInstance()).notifyAnswer();
        }
    }
}
