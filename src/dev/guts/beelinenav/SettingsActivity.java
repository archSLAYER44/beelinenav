package dev.guts.beelinenav;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings: which map service to use and its keys, the theme, and a word about the build. */
public final class SettingsActivity extends CardActivity {
    private EditText olaField, googleField;   // kept so a key typed but not yet saved survives a rebuild

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Router.load(this);
        Theme.load(this);
        build();
    }

    /** Builds the whole screen from the saved state; called again after every change. */
    private void build() {
        String olaTyped = olaField == null ? "" : olaField.getText().toString();
        String googleTyped = googleField == null ? "" : googleField.getText().toString();
        NavService s = NavService.instance;
        palette(Theme.isLight(s == null ? null : s.last));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(8), 0, dp(14));
        TextView back = round("←", chip, fg, 36, 18);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = text("Settings", 22, fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        root.addView(mapsCard(), stack(12));
        root.addView(keyCard(Router.OLA_MAPS, "Ola Maps key",
                "From maps.olakrutrim.com, under API keys. Also draws the side roads on the display.",
                olaTyped, googleTyped), stack(12));
        root.addView(keyCard(Router.GOOGLE, "Google Maps key",
                "From console.cloud.google.com, with Routes API, Places API (New), Maps Static API and "
                        + "Maps JavaScript API enabled. Set the key's application restrictions to None "
                        + "(restricting it to those four APIs is fine). Google asks for a billing account "
                        + "even for its free monthly allowance.",
                olaTyped, googleTyped), stack(12));
        root.addView(themeCard(), stack(12));
        root.addView(aboutCard(), stack(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(page);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** Which service routes and searches, with a tick on the one in use. */
    private LinearLayout mapsCard() {
        LinearLayout c = card(card);
        c.addView(label("Maps"));
        String using = Router.provider();
        String[][] rows = {
                {Router.OSM, "OpenStreetMap", "Free, no key. Routes and search only, no map pictures."},
                {Router.OLA_MAPS, "Ola Maps", "Needs a key. Adds the map picture, the zoomable map and side roads on the display."},
                {Router.GOOGLE, "Google Maps", "Needs a key. Adds the map picture and the zoomable map."}};
        for (int i = 0; i < rows.length; i++) {
            String id = rows[i][0];
            if (i > 0) c.addView(hline(), new LinearLayout.LayoutParams(-1, dp(1)));
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(13), 0, dp(13));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(rows[i][1], 15, fg);
            t.setTypeface(MEDIUM);
            col.addView(t);
            TextView d = text(rows[i][2], 12, grey);
            d.setPadding(0, dp(2), 0, 0);
            col.addView(d);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            boolean on = id.equals(using);
            r.addView(round(on ? "✓" : "", on ? fg : chip, bg, 26, 13), beside(12));
            r.setOnClickListener(v -> {
                Router.saveProvider(this, id);
                build();
            });
            c.addView(r);
        }
        String chosen = Router.chosen();
        String note = chosen.isEmpty() ? "Automatic: the first service with a key, else OpenStreetMap."
                : chosen.equals(using) ? ""
                : Router.name(chosen) + " has no key yet, so " + Router.name(using) + " is used until one is pasted below.";
        if (!note.isEmpty()) {
            TextView n = text(note, 12, grey);
            n.setPadding(0, dp(12), 0, 0);
            c.addView(n);
        }
        return c;
    }

    /** Paste field with Save and, for a pasted key, Remove. A key compiled in at build time cannot be removed here. */
    private LinearLayout keyCard(String id, String title, String where, String olaTyped, String googleTyped) {
        LinearLayout c = card(card);
        c.addView(label(title));
        String src = Router.keySource(id);
        TextView state = text(src.isEmpty() ? "No key" : src.equals("pasted") ? "Key saved" : "Built-in key", 15, fg);
        state.setTypeface(MEDIUM);
        state.setPadding(0, dp(4), 0, 0);
        c.addView(state);
        TextView w = text(where, 12, grey);
        w.setPadding(0, dp(2), 0, 0);
        c.addView(w);

        EditText f = new EditText(this);
        f.setHint("Paste the key here");
        f.setHintTextColor(grey);
        f.setTextColor(fg);
        f.setTextSize(14);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        f.setBackground(rounded(chip, 14));
        f.setPadding(dp(14), dp(12), dp(14), dp(12));
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setText(id.equals(Router.GOOGLE) ? googleTyped : olaTyped);
        if (id.equals(Router.GOOGLE)) googleField = f; else olaField = f;
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(12);
        c.addView(f, flp);

        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(10), 0, 0);
        TextView save = pill("Save", fg, bg);
        Runnable doSave = () -> {
            String k = f.getText().toString().trim();
            if (k.isEmpty()) return;
            hideKeyboard(f);
            f.setText("");
            Router.saveKey(this, id, k);
            build();
        };
        save.setOnClickListener(v -> doSave.run());
        f.setOnEditorActionListener((v, action, event) -> {     // Done on the keyboard saves too
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            doSave.run();
            return true;
        });
        btns.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        if (src.equals("pasted")) {
            TextView remove = pill("Remove", chip, fg);
            remove.setOnClickListener(v -> {
                Router.saveKey(this, id, "");
                build();
            });
            btns.addView(remove, beside(10));
        }
        c.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    /** Auto / Light / Dark as a row of pills, the current one filled. */
    private LinearLayout themeCard() {
        LinearLayout c = card(card);
        c.addView(label("Theme"));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(0, dp(10), 0, 0);
        int[] modes = {Theme.AUTO, Theme.LIGHT, Theme.DARK};
        String[] names = {"Auto", "Light", "Dark"};
        for (int i = 0; i < modes.length; i++) {
            int m = modes[i];
            boolean on = Theme.mode() == m;
            TextView t = pill(names[i], on ? fg : chip, on ? bg : fg);
            t.setOnClickListener(v -> {
                Theme.setMode(this, m);
                NavService s = NavService.instance;
                if (s != null) s.refreshTheme();
                build();
            });
            seg.addView(t, i == 0 ? new LinearLayout.LayoutParams(-2, -2) : beside(8));
        }
        c.addView(seg);
        TextView h = text("Auto is light by day and dark by night, from sunrise and sunset where you are.", 12, grey);
        h.setPadding(0, dp(10), 0, 0);
        c.addView(h);
        return c;
    }

    private LinearLayout aboutCard() {
        LinearLayout c = card(card);
        c.addView(label("About"));
        TextView b = text("BeeNav build " + Secrets.BUILD, 15, fg);
        b.setTypeface(MEDIUM);
        b.setPadding(0, dp(4), 0, 0);
        c.addView(b);
        TextView d = text("Routes and search: " + Router.name(Router.provider())
                + ". Keys are stored on this phone only. The display gets its directions over Bluetooth.", 12, grey);
        d.setPadding(0, dp(2), 0, 0);
        c.addView(d);
        return c;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }
}
