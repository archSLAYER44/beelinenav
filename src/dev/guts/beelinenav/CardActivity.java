package dev.guts.beelinenav;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** What the app's screens share: the colours of the current theme and the rounded-card building blocks. */
abstract class CardActivity extends Activity {
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final int CARD_RADIUS = 22;   // dp

    boolean light;
    int page, card, ink, fg, bg, grey, line, chip, inkGrey, inkChip;   // colours of the current theme

    /** Picks the colours for day or night and paints the system bars to match. */
    void palette(boolean light) {
        this.light = light;
        page = light ? 0xFFEDEDED : 0xFF000000;      // the grey (or black) behind the cards
        card = light ? 0xFFFFFFFF : 0xFF161616;      // ordinary cards
        bg = light ? 0xFFFFFFFF : 0xFF000000;        // text on a filled (fg) button
        ink = light ? 0xFF141414 : 0xFF222222;       // the dark hero card
        fg = light ? 0xFF111111 : 0xFFFFFFFF;
        grey = light ? 0xFF8A8A8A : 0xFF8E8E8E;
        line = light ? 0xFFEDEDED : 0xFF2A2A2A;
        chip = light ? 0xFFF1F1F1 : 0xFF262626;      // pills, icon squares, secondary buttons
        inkGrey = 0xFF9C9C9C;                        // small text on the hero card
        inkChip = light ? 0xFF2C2C2C : 0xFF383838;   // the unfilled part of the progress bar
        // no accent colours: filled buttons are fg on bg, status squares are fg (good), grey (busy) or chip (off)
        getWindow().setStatusBarColor(page);
        getWindow().setNavigationBarColor(page);
        getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.US);
    }

    // ---------- building blocks ----------

    /** Rounded card with the standard padding; children are clipped to the rounded corners. */
    LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(color, CARD_RADIUS));
        c.setClipToOutline(true);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        return c;
    }

    /** A tappable list row: the name (and an optional grey detail line under it) with a small arrow at the right. */
    LinearLayout row(String name, String detail) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(13), 0, dp(13));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(name, 15, fg);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (detail != null && !detail.isEmpty()) {
            TextView d = text(detail, 12, grey);
            d.setMaxLines(1);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, dp(2), 0, 0);
            col.addView(d);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("↗", 15, grey);
        arrow.setPadding(dp(12), 0, 0, 0);
        r.addView(arrow);
        return r;
    }

    /** A small label over a value, like "Duration / 08:21" on the sample widgets. */
    LinearLayout stat(String labelText, TextView valueView, int labelColor, int gravity) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(gravity);
        TextView l = text(labelText, 12, labelColor);
        l.setGravity(gravity);              // children of a vertical LinearLayout are full width, so align the text itself
        col.addView(l);
        valueView.setGravity(gravity);
        valueView.setPadding(0, dp(3), 0, 0);
        col.addView(valueView);
        return col;
    }

    TextView label(String s) {
        return text(s, 12, grey);
    }

    TextView value() {
        TextView t = text("", 15, fg);
        t.setTypeface(MEDIUM);
        return t;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Full-size action button: a filled pill. */
    TextView pill(String labelText, int bgColor, int textColor) {
        TextView b = text(labelText, 14, textColor);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(13), dp(18), dp(13));
        b.setBackground(rounded(bgColor, 999));
        return b;
    }

    /** Small pill for the title row and badges. */
    TextView tag(String labelText, int textColor) {
        TextView b = text(labelText, 12, textColor);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        b.setBackground(rounded(chip, 999));
        return b;
    }

    /** Round button with one glyph in it. */
    TextView round(String glyph, int bgColor, int textColor, int sizeDp, int sp) {
        TextView b = text(glyph, sp, textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setWidth(dp(sizeDp));
        b.setHeight(dp(sizeDp));
        b.setBackground(rounded(bgColor, 999));
        return b;
    }

    /** Rounded coloured square, the status light of the status cards. */
    View square(int color, int sizeDp, int radiusDp) {
        View v = new View(this);
        v.setBackground(rounded(color, radiusDp));
        v.setMinimumWidth(dp(sizeDp));
        v.setMinimumHeight(dp(sizeDp));
        return v;
    }

    View hline() {
        View v = new View(this);
        v.setBackgroundColor(line);
        return v;
    }

    GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Full-width layout params with a gap below, for stacking cards. */
    LinearLayout.LayoutParams stack(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /** Wrap-content params with a gap to the left, for things placed in a row. */
    LinearLayout.LayoutParams beside(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
