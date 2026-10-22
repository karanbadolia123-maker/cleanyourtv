package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Liquid-glass home screen, built in code so it can be iterated on without touching MainActivity.
 * A translucent top bar, a large title for the focused app, a row of app cards and a floating glass
 * dock. No real blur (the TVs have about 1 GB of RAM): glass is translucency, soft gradients, a light
 * top edge and a luminous focus ring.
 */
public class GlassHomeActivity extends Activity {

    private static final int ACCENT = 0xFF6EA0FF;

    private static final class Entry {
        String pkg;
        CharSequence label;
        Drawable banner;
        Drawable icon;
        Intent intent;
        int color = ACCENT;
    }

    private float density;
    private BgView bg;
    private TextView heroTitle;
    private int glow = ACCENT;
    private ValueAnimator glowAnim;
    private final List<Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        loadApps();

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        bg = new BgView(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        buildTopBar(root);
        buildHero(root);
        View firstFocus = buildAppRow(root);
        buildDock(root);
        setContentView(root);
        if (firstFocus != null) firstFocus.post(firstFocus::requestFocus);
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.label = ri.loadLabel(pm);
                Drawable banner = null;
                try { banner = ri.activityInfo.loadBanner(pm); } catch (RuntimeException ignored) { }
                e.banner = banner;
                try { e.icon = ri.loadIcon(pm); } catch (RuntimeException ignored) { }
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                e.intent = launch;
                Drawable src = e.banner != null ? e.banner : e.icon;
                e.color = src == null ? ACCENT : glowColor(src);
                entries.add(e);
            }
        }
        Collections.sort(entries, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    private static int glowColor(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 1, 1);
            d.draw(c);
            float[] hsv = new float[3];
            Color.colorToHSV(b.getPixel(0, 0), hsv);
            b.recycle();
            if (hsv[1] < 0.25f) return ACCENT;
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return ACCENT;
        }
    }

    // ---------------------------------------------------------------- top bar

    private void buildTopBar(FrameLayout root) {
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFF4F6FB);
        clock.setTextSize(34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextColor(0xB3F4F6FB);
        date.setTextSize(14);
        time.addView(clock);
        time.addView(date);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(48);
        tp.topMargin = dp(24);
        root.addView(time, tp);

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.addView(pill("Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settings = pill("Settings", new Intent(Settings.ACTION_SETTINGS));
        ((LinearLayout.LayoutParams) settings.getLayoutParams()).leftMargin = dp(12);
        pills.addView(settings);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pp.rightMargin = dp(48);
        pp.topMargin = dp(30);
        root.addView(pills, pp);
    }

    private TextView pill(String text, Intent intent) {
        final TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(0xFFF4F6FB);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(22), 0, dp(22), 0);
        v.setFocusable(true);
        v.setBackgroundResource(R.drawable.pill_bg);
        v.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(38)));
        v.setOnFocusChangeListener((view, has) -> {
            v.setTextColor(has ? 0xFF0A0D14 : 0xFFF4F6FB);
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        });
        v.setOnClickListener(view -> {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        });
        return v;
    }

    // ---------------------------------------------------------------- hero

    private void buildHero(FrameLayout root) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = new TextView(this);
        eyebrow.setText("SELECTED");
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.25f);
        eyebrow.setTextColor(ACCENT);
        heroTitle = new TextView(this);
        heroTitle.setTextSize(52);
        heroTitle.setSingleLine(true);
        heroTitle.setTextColor(0xFFF4F6FB);
        heroTitle.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextView hint = new TextView(this);
        hint.setText("Press OK to open");
        hint.setTextSize(15);
        hint.setTextColor(0xB3F4F6FB);
        hero.addView(eyebrow);
        hero.addView(heroTitle);
        hero.addView(hint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(640), -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(48);
        lp.topMargin = dp(120);
        root.addView(hero, lp);
        if (!entries.isEmpty()) showEntry(entries.get(0));
    }

    private void showEntry(Entry e) {
        heroTitle.setText(e.label);
        if (e.color == glow) return;
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofArgb(glow, e.color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> {
            glow = (Integer) a.getAnimatedValue();
            bg.setGlow(glow);
        });
        glowAnim.start();
    }

    // ---------------------------------------------------------------- app row

    private View buildAppRow(FrameLayout root) {
        TextView label = new TextView(this);
        label.setText("Apps");
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(0xE6F4F6FB);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(48);
        lp.topMargin = dp(250);
        root.addView(label, lp);

        RecyclerView rv = new RecyclerView(this);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(48), dp(14), dp(48), dp(14));
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final List<Entry> row = entries;
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                FrameLayout card = makeCard(false);
                card.setLayoutParams(new RecyclerView.LayoutParams(dp(196), dp(110)));
                ((RecyclerView.LayoutParams) card.getLayoutParams()).rightMargin = dp(16);
                return new RecyclerView.ViewHolder(card) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                bindCard((FrameLayout) holder.itemView, row.get(position), false);
            }

            @Override
            public int getItemCount() {
                return row.size();
            }
        });
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, dp(150), Gravity.TOP | Gravity.START);
        rp.topMargin = dp(272);
        root.addView(rv, rp);
        return rv;
    }

    // ---------------------------------------------------------------- dock

    private void buildDock(FrameLayout root) {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable band = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x14DCE6FF});
        band.setCornerRadius(dp(34));
        band.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        dock.setBackground(band);
        int n = Math.min(5, entries.size());
        for (int i = 0; i < n; i++) {
            FrameLayout tile = makeCard(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
            if (i > 0) lp.leftMargin = dp(16);
            tile.setLayoutParams(lp);
            bindCard(tile, entries.get(i), true);
            dock.addView(tile);
        }
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLp.bottomMargin = dp(22);
        root.addView(dock, dockLp);
    }

    // ---------------------------------------------------------------- cards

    private FrameLayout makeCard(boolean square) {
        final FrameLayout card = new FrameLayout(this);
        final int radius = dp(square ? 16 : 14);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackgroundColor(0xFF161A24);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.max(2, dp(2)), 0xE6FFFFFF);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            ((FrameLayout) v).setForeground(has ? ring : null);
            v.setElevation(has ? dp(8) : 0);
            Object tag = v.getTag();
            if (has && tag instanceof Entry) showEntry((Entry) tag);
        });
        return card;
    }

    private void bindCard(FrameLayout card, final Entry e, boolean square) {
        card.setTag(e);
        ImageView img = (ImageView) card.getChildAt(0);
        Drawable art = square ? e.icon : (e.banner != null ? e.banner : e.icon);
        boolean banner = !square && e.banner != null;
        img.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int pad = banner ? 0 : dp(square ? 8 : 20);
        img.setPadding(pad, pad, pad, pad);
        img.setImageDrawable(art);
        card.setOnClickListener(v -> {
            try {
                startActivity(e.intent);
            } catch (RuntimeException ignored) { }
        });
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- background

    /** Midnight base with soft colour glows; the main glow follows the focused app's colour. */
    private static final class BgView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int glow = ACCENT;

        BgView(Context c) {
            super(c);
        }

        void setGlow(int color) {
            glow = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.drawColor(0xFF05070D);
            blob(canvas, w * 0.78f, h * 0.12f, w * 0.62f, (glow & 0x00FFFFFF) | 0x66000000);
            blob(canvas, w * 0.10f, h * 0.95f, w * 0.50f, 0x33788CA0);
            blob(canvas, w * 0.55f, h * 0.55f, w * 0.40f, 0x2A5A3CC8);
            paint.setShader(null);
        }

        private void blob(Canvas canvas, float cx, float cy, float r, int color) {
            paint.setShader(new RadialGradient(cx, cy, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
        }
    }
}
