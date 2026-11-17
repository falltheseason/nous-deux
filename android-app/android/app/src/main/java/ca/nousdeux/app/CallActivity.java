package ca.nousdeux.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Écran d'appel entrant, plein écran, même sur l'écran verrouillé.
 * La sonnerie vient de la notification d'appel (elle tourne en boucle) ;
 * cet écran l'arrête quand on répond ou qu'on ignore.
 */
public class CallActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver endReceiver;
    private boolean done = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        showOverLockScreen();

        Intent in = getIntent();
        String who = in.getStringExtra("who");
        boolean video = in.getBooleanExtra("video", false);
        if (who == null || who.isEmpty()) who = "Ton amour";

        setContentView(buildUi(who, video));

        endReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) { if (!done) finishCall(); }
        };
        IntentFilter f = new IntentFilter(Nd.ACTION_CALL_END);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(endReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(endReceiver, f);

        handler.postDelayed(this::finishCall, 50_000); // l'appelant abandonne après 45 s

        if (Nd.ACTION_ANSWER.equals(in.getAction())) answer();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Nd.ACTION_ANSWER.equals(intent.getAction())) answer();
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private View buildUi(String who, boolean video) {
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { Color.parseColor("#3A2530"), Color.parseColor("#7A2E44"), Color.parseColor("#D9506A") });
        root.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = (int) (110 * dp);
        root.addView(top, tp);

        TextView app = text("💞 Nous deux", 16, Color.parseColor("#F3D7DE"), false);
        top.addView(app);

        TextView avatar = text(who.substring(0, 1).toUpperCase(), 52, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#55FFFFFF"));
        avatar.setBackground(circle);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams((int) (128 * dp), (int) (128 * dp));
        ap.topMargin = (int) (36 * dp);
        top.addView(avatar, ap);

        TextView name = text(who, 34, Color.WHITE, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = (int) (22 * dp);
        top.addView(name, np);

        TextView sub = text(video ? "📹 Appel vidéo entrant…" : "📞 Appel entrant…", 19, Color.parseColor("#F3D7DE"), false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = (int) (8 * dp);
        top.addView(sub, sp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.bottomMargin = (int) (90 * dp);
        root.addView(bottom, bp);

        bottom.addView(roundButton("✕", "Ignorer", Color.parseColor("#E5394B"), v -> ignore(), dp));
        View gap = new View(this);
        bottom.addView(gap, new LinearLayout.LayoutParams((int) (90 * dp), 1));
        bottom.addView(roundButton(video ? "📹" : "📞", "Répondre", Color.parseColor("#2FB86B"), v -> answer(), dp));
        return root;
    }

    private LinearLayout roundButton(String icon, String label, int color, View.OnClickListener click, float dp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView b = text(icon, 30, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(color);
        b.setBackground(c);
        b.setOnClickListener(click);
        b.setContentDescription(label);
        col.addView(b, new LinearLayout.LayoutParams((int) (78 * dp), (int) (78 * dp)));
        TextView l = text(label, 16, Color.WHITE, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (10 * dp);
        col.addView(l, lp);
        return col;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void answer() {
        if (done) return;
        done = true;
        Nd.stopRinging(this);
        Nd.setPendingAnswer(this);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            // demande de déverrouiller le téléphone (empreinte), puis ouvre l'app
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() { openApp(); }

                @Override
                public void onDismissCancelled() { openApp(); }

                @Override
                public void onDismissError() { openApp(); }
            });
        } else {
            openApp();
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
            .setAction(Nd.ACTION_ANSWER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private void ignore() {
        if (done) return;
        done = true;
        Nd.ignoreFor(this, 60_000);
        Nd.stopRinging(this);
        finish();
    }

    private void finishCall() {
        if (!isFinishing()) finish();
    }

    @Override
    public void onBackPressed() {
        ignore();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(endReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
