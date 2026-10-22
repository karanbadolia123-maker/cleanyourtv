package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Liquid-glass home screen with a night-drive wallpaper, built in code so it can be iterated on without touching
 * MainActivity.
 *
 * Glass is a frosted copy of the wallpaper (a tiny blurred bitmap, made once) drawn behind each panel with a tint,
 * a light top edge and a hairline border: it looks like blur but costs nothing per frame. Motion is spring-like
 * focus scaling, a light sweep across the focused card, a slowly drifting wallpaper, a pulsing headlight and
 * light streaks on the road. All motion pauses while another app is in front.
 *
 * Power and Restart open the system power menu through PowerDialogService, which has to be enabled once:
 *   adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.PowerDialogService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class GlassHomeActivity extends Activity {

    private static final int ACCENT = 0xFF6EA0FF;
    private static final String[] FAVOURITES = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "in.startv.hotstar.dplus", "com.jio.media.ondemand",
    };

    private static final class Entry {
        String pkg;
        CharSequence label;
        Drawable banner;
        Drawable icon;
        Intent intent;
        int color = ACCENT;
    }

    private float density;
    private int screenW;
    private int screenH;
    private Bitmap frost;
    private FrameLayout root;
    private FrameLayout bgHolder;
    private ImageView bgImage;
    private View headlight;
    private TextView heroTitle;
    private TextView quickHint;
    private View glowView;
    private int glowColor = ACCENT;
    private ValueAnimator glowAnim;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Animator> ambient = new ArrayList<>();
    private final List<View> streaks = new ArrayList<>();
    private final List<View> intro = new ArrayList<>();
    private boolean introPlayed;
    private View inputsPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        frost = makeFrost();
        loadApps();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070D);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        buildBackground();
        View topBar = buildTopBar();
        View hero = buildHero();
        View rowLabel = buildRowLabel();
        RecyclerView row = buildAppRow();
        View dock = buildDock();
        intro.add(topBar);
        intro.add(hero);
        intro.add(rowLabel);
        intro.add(row);
        intro.add(dock);
        setContentView(root);

        for (View v : intro) {
            v.setAlpha(0f);
            v.setTranslationY(dp(22));
        }
        row.post(() -> {
            View first = row.getChildAt(0);
            if (first != null) first.requestFocus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        playIntro();
        startAmbient();
    }

    @Override
    protected void onPause() {
        stopAmbient();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // This is Home: Back only closes the inputs list, otherwise it goes nowhere.
        closeInputs();
    }

    // ------------------------------------------------------------------ data

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Entry e = new Entry();
                e.pkg = pkg;
                e.label = ri.loadLabel(pm);
                e.intent = launch;
                try { e.banner = ri.activityInfo.loadBanner(pm); } catch (RuntimeException ignored) { }
                try { e.icon = ri.loadIcon(pm); } catch (RuntimeException ignored) { }
                Drawable src = e.banner != null ? e.banner : e.icon;
                if (src != null) e.color = colourOf(src);
                entries.add(e);
            }
        }
        Collections.sort(entries, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    /** The favourites that are installed, in the order listed, then the first other apps up to five. */
    private List<Entry> dockEntries() {
        List<Entry> out = new ArrayList<>();
        for (String pkg : FAVOURITES) {
            for (Entry e : entries) {
                if (e.pkg.equals(pkg) && !out.contains(e)) out.add(e);
            }
        }
        for (Entry e : entries) {
            if (out.size() >= 5) break;
            if (!out.contains(e)) out.add(e);
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    private static int colourOf(Drawable d) {
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

    /** A tiny, smooth copy of the wallpaper; scaled up with filtering it reads as a heavy blur. */
    private Bitmap makeFrost() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap small = BitmapFactory.decodeResource(getResources(), R.drawable.car_bg, o);
            if (small == null) return null;
            Bitmap tiny = Bitmap.createScaledBitmap(small, 48, 27, true);
            Bitmap tiniest = Bitmap.createScaledBitmap(tiny, 24, 14, true);
            if (tiny != small) small.recycle();
            tiny.recycle();
            return tiniest;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ background

    private void buildBackground() {
        bgHolder = new FrameLayout(this);
        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage.setImageResource(R.drawable.car_bg);
        bgImage.setScaleX(1.07f);
        bgImage.setScaleY(1.07f);
        bgHolder.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bgHolder, new FrameLayout.LayoutParams(-1, -1));

        // Colour of the focused app, a soft glow high on the right.
        glowView = new View(this);
        glowView.setBackground(radial(0x00000000, 0x00000000));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(620), dp(620), Gravity.TOP | Gravity.END);
        gp.topMargin = -dp(220);
        gp.rightMargin = -dp(160);
        root.addView(glowView, gp);
        setGlow(ACCENT);

        // Scrims keep the text readable over the picture.
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3040610, 0x00040610}));
        root.addView(left, new FrameLayout.LayoutParams(dp(560), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6040610, 0x00040610}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, dp(330), Gravity.BOTTOM));

        // Pulsing headlight where the car's lamp is in the picture.
        headlight = new View(this);
        headlight.setBackground(radial(0xB3CFE6FF, 0x00CFE6FF));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(150), dp(150));
        hp.leftMargin = Math.round(screenW * 0.452f) - dp(75);
        hp.topMargin = Math.round(screenH * 0.507f) - dp(75);
        root.addView(headlight, hp);

        // Light streaks sliding over the road.
        float[] ys = {0.70f, 0.77f, 0.85f};
        for (float y : ys) {
            View s = new View(this);
            s.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x00A0C8FF, 0x66A0C8FF, 0x00A0C8FF}));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(300), Math.max(1, dp(1.5f)));
            sp.topMargin = Math.round(screenH * y);
            root.addView(s, sp);
            streaks.add(s);
        }
    }

    private GradientDrawable radial(int inner, int outer) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(300));
        g.setColors(new int[]{inner, outer});
        return g;
    }

    private GradientDrawable radialSized(int inner, int outer, int radiusPx) {
        GradientDrawable g = radial(inner, outer);
        g.setGradientRadius(radiusPx);
        return g;
    }

    private void setGlow(int colour) {
        glowColor = colour;
        glowView.setBackground(radialSized((colour & 0x00FFFFFF) | 0x55000000, colour & 0x00FFFFFF, dp(310)));
    }

    private void startAmbient() {
        stopAmbient();
        // Slow drift and breathing of the wallpaper.
        ObjectAnimator drift = ObjectAnimator.ofFloat(bgImage, View.TRANSLATION_X, -dp(10), dp(10));
        drift.setDuration(22000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        ObjectAnimator breathe = ObjectAnimator.ofFloat(bgImage, View.SCALE_X, 1.07f, 1.095f);
        breathe.setDuration(18000);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        ObjectAnimator breatheY = ObjectAnimator.ofFloat(bgImage, View.SCALE_Y, 1.07f, 1.095f);
        breatheY.setDuration(18000);
        breatheY.setRepeatMode(ValueAnimator.REVERSE);
        breatheY.setRepeatCount(ValueAnimator.INFINITE);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(headlight, View.ALPHA, 0.55f, 1f);
        pulse.setDuration(2600);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        ambient.add(drift);
        ambient.add(breathe);
        ambient.add(breatheY);
        ambient.add(pulse);
        long[] durations = {7000, 10000, 13000};
        for (int i = 0; i < streaks.size(); i++) {
            ObjectAnimator a = ObjectAnimator.ofFloat(streaks.get(i), View.TRANSLATION_X, -dp(320), screenW);
            a.setDuration(durations[i % durations.length]);
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            ambient.add(a);
        }
        for (Animator a : ambient) a.start();
        for (int i = 0; i < streaks.size(); i++) {
            ((ValueAnimator) ambient.get(4 + i)).setCurrentPlayTime(i * 2300L);
        }
    }

    private void stopAmbient() {
        for (Animator a : ambient) a.cancel();
        ambient.clear();
    }

    private void playIntro() {
        if (introPlayed) return;
        introPlayed = true;
        long delay = 60;
        for (View v : intro) {
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(520)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            delay += 90;
        }
    }

    // ------------------------------------------------------------------ top bar

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setClipChildren(false);

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFF4F6FB);
        clock.setTextSize(40);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xB3F4F6FB);
        date.setTextSize(14);
        time.addView(clock);
        time.addView(date);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(48);
        bar.addView(time, tp);

        GlassLayout quick = new GlassLayout(this, frost, screenW, screenH, dp(26));
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER_VERTICAL);
        quick.setPadding(dp(10), dp(8), dp(10), dp(8));
        addQuick(quick, R.drawable.ic_wifi, "Wi-Fi", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addQuick(quick, R.drawable.ic_bluetooth, "Bluetooth", () -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        addQuick(quick, R.drawable.ic_input, "Inputs", this::openInputs);
        addQuick(quick, R.drawable.ic_settings, "Settings", () -> open(new Intent(Settings.ACTION_SETTINGS)));
        addQuick(quick, R.drawable.ic_restart, "Restart", this::powerMenu);
        addQuick(quick, R.drawable.ic_power, "Power", this::powerMenu);
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        qp.rightMargin = dp(48);
        qp.topMargin = dp(2);
        bar.addView(quick, qp);

        quickHint = new TextView(this);
        quickHint.setTextSize(13);
        quickHint.setTextColor(0xCCF4F6FB);
        quickHint.setAlpha(0f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hp.rightMargin = dp(56);
        hp.topMargin = dp(60);
        bar.addView(quickHint, hp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(90), Gravity.TOP);
        bp.topMargin = dp(26);
        root.addView(bar, bp);
        return bar;
    }

    private void addQuick(LinearLayout bar, int icon, final String label, final Runnable action) {
        final ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(0xFFF4F6FB);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(10);
        b.setPadding(pad, pad, pad, pad);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(label);
        b.setBackground(quickBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        if (bar.getChildCount() > 0) lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, has) -> {
            b.setColorFilter(has ? 0xFF0A0D14 : 0xFFF4F6FB);
            springScale(v, has ? 1.14f : 1f);
            if (has) {
                quickHint.setText(label);
                quickHint.animate().cancel();
                quickHint.setAlpha(0f);
                quickHint.animate().alpha(1f).setDuration(200).start();
            }
        });
        b.setOnClickListener(v -> action.run());
        bar.addView(b);
    }

    private StateListDrawable quickBackground() {
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, 0xF0DCE6FF});
        on.setShape(GradientDrawable.OVAL);
        on.setStroke(Math.max(2, dp(3)), 0x8CA9C3FF);
        GradientDrawable off = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x0DFFFFFF});
        off.setShape(GradientDrawable.OVAL);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open that on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void closeInputs() {
        if (inputsPanel == null) return;
        final View panel = inputsPanel;
        inputsPanel = null;
        panel.animate().alpha(0f).translationY(-dp(8)).setDuration(160)
                .withEndAction(() -> root.removeView(panel)).start();
    }

    /** A glass list of the TV's HDMI and AV inputs, switched through the TV input framework. */
    private void openInputs() {
        if (inputsPanel != null) {
            closeInputs();
            return;
        }
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> found = new ArrayList<>();
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.isPassthroughInput() && !info.isHidden(this)) found.add(info);
                }
            } catch (RuntimeException ignored) { }
        }
        if (found.isEmpty()) {
            Toast.makeText(this, "No inputs found on this TV", Toast.LENGTH_SHORT).show();
            return;
        }
        final GlassLayout panel = new GlassLayout(this, frost, screenW, screenH, dp(22));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        View firstRow = null;
        for (final TvInputInfo info : found) {
            CharSequence name = null;
            try { name = info.loadCustomLabel(this); } catch (RuntimeException ignored) { }
            if (name == null || name.length() == 0) {
                try { name = info.loadLabel(this); } catch (RuntimeException ignored) { }
            }
            if (name == null || name.length() == 0) name = info.getId();
            TextView row = new TextView(this);
            row.setText(name);
            row.setTextSize(16);
            row.setTextColor(0xFFF4F6FB);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), 0, dp(40), 0);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.input_row_bg);
            row.setLayoutParams(new LinearLayout.LayoutParams(dp(220), dp(44)));
            row.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? 0xFF0A0D14 : 0xFFF4F6FB));
            row.setOnClickListener(v -> {
                closeInputs();
                try {
                    open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())));
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Can't open that input", Toast.LENGTH_SHORT).show();
                }
            });
            panel.addView(row);
            if (firstRow == null) firstRow = row;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(48);
        lp.topMargin = dp(96);
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(8));
        root.addView(panel, lp);
        inputsPanel = panel;
        panel.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        final View focusTarget = firstRow;
        panel.post(focusTarget::requestFocus);
    }

    private void powerMenu() {
        if (!PowerDialogService.showPowerDialog()) {
            Toast.makeText(this, "Power menu needs one-time setup: enable the power helper over adb",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ hero

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = new TextView(this);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        eyebrow.setText(h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.25f);
        eyebrow.setTextColor(ACCENT);
        heroTitle = new TextView(this);
        heroTitle.setTextSize(54);
        heroTitle.setSingleLine(true);
        heroTitle.setTextColor(0xFFF4F6FB);
        heroTitle.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heroTitle.setShadowLayer(dp(8), 0, dp(2), 0x99000000);
        TextView hint = new TextView(this);
        hint.setText("Press OK to open  ·  Hold OK for app info");
        hint.setTextSize(14);
        hint.setTextColor(0xB3F4F6FB);
        hero.addView(eyebrow);
        hero.addView(heroTitle);
        hero.addView(hint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(620), -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(48);
        lp.topMargin = dp(124);
        root.addView(hero, lp);
        if (!entries.isEmpty()) heroTitle.setText(entries.get(0).label);
        return hero;
    }

    private void showEntry(Entry e) {
        heroTitle.setText(e.label);
        if (e.color == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofArgb(glowColor, e.color);
        glowAnim.setDuration(500);
        glowAnim.addUpdateListener(a -> setGlow((Integer) a.getAnimatedValue()));
        glowAnim.start();
    }

    // ------------------------------------------------------------------ app row

    private View buildRowLabel() {
        TextView label = new TextView(this);
        label.setText("Apps");
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(0xE6F4F6FB);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(48);
        lp.topMargin = dp(262);
        root.addView(label, lp);
        return label;
    }

    private RecyclerView buildAppRow() {
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
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(196), dp(110));
                lp.rightMargin = dp(16);
                card.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(card) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                bindCard((FrameLayout) holder.itemView, row.get(position), false, position);
            }

            @Override
            public int getItemCount() {
                return row.size();
            }
        });
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, dp(150), Gravity.TOP | Gravity.START);
        rp.topMargin = dp(284);
        root.addView(rv, rp);
        return rv;
    }

    // ------------------------------------------------------------------ dock

    private View buildDock() {
        GlassLayout dock = new GlassLayout(this, frost, screenW, screenH, dp(34));
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(18), dp(12), dp(18), dp(12));
        List<Entry> favs = dockEntries();
        for (int i = 0; i < favs.size(); i++) {
            FrameLayout tile = makeCard(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58));
            if (i > 0) lp.leftMargin = dp(16);
            tile.setLayoutParams(lp);
            bindCard(tile, favs.get(i), true, -1);
            dock.addView(tile);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(22);
        root.addView(dock, lp);
        return dock;
    }

    // ------------------------------------------------------------------ cards

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
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));

        // Light that sweeps across the card when it gains focus.
        final View sweep = new View(this);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF}));
        sweep.setAlpha(0f);
        card.addView(sweep, new FrameLayout.LayoutParams(dp(square ? 28 : 70), -1));

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.max(2, dp(2)), 0xE6FFFFFF);

        card.setOnFocusChangeListener((v, has) -> {
            springScale(v, has ? 1.08f : 1f);
            card.setForeground(has ? ring : null);
            v.setElevation(has ? dp(10) : 0);
            if (has) {
                Object tag = v.getTag();
                if (tag instanceof Entry) showEntry((Entry) tag);
                sweep.animate().cancel();
                sweep.setAlpha(1f);
                sweep.setTranslationX(-dp(square ? 28 : 70));
                sweep.animate().translationX(card.getWidth()).setDuration(650)
                        .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> sweep.setAlpha(0f)).start();
                Object pos = v.getTag(R.id.glass);
                if (pos instanceof Integer) parallax((Integer) pos);
            } else {
                sweep.animate().cancel();
                sweep.setAlpha(0f);
            }
        });
        return card;
    }

    private void bindCard(FrameLayout card, final Entry e, boolean square, int position) {
        card.setTag(e);
        card.setTag(R.id.glass, position >= 0 ? Integer.valueOf(position) : null);
        ImageView img = (ImageView) card.getChildAt(0);
        boolean banner = !square && e.banner != null;
        Drawable art = square ? e.icon : (banner ? e.banner : e.icon);
        img.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int pad = banner ? 0 : dp(square ? 8 : 20);
        img.setPadding(pad, pad, pad, pad);
        img.setImageDrawable(art);
        card.setOnClickListener(v -> open(new Intent(e.intent)));
        card.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)));
            return true;
        });
    }

    /** The wallpaper holder leans a few dp with the focused card, so the scene feels like it has depth. */
    private void parallax(int position) {
        float x = -Math.min(position, 12) * dp(1.6f);
        bgHolder.animate().translationX(x).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void springScale(View v, float to) {
        v.animate().cancel();
        v.animate().scaleX(to).scaleY(to).setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ glass panel

    /**
     * A panel that draws its own frosted backdrop: the part of the blurred wallpaper that lies behind it, a
     * white tint that is brighter at the top, a hairline border and a light top edge.
     */
    private static final class GlassLayout extends LinearLayout {
        private final Bitmap frost;
        private final int screenW;
        private final int screenH;
        private final float radius;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final Rect src = new Rect();
        private final int[] loc = new int[2];

        GlassLayout(Context c, Bitmap frost, int screenW, int screenH, float radius) {
            super(c);
            this.frost = frost;
            this.screenW = Math.max(1, screenW);
            this.screenH = Math.max(1, screenH);
            this.radius = radius;
            setWillNotDraw(false);
            setClipChildren(false);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density));
            borderPaint.setColor(0x59FFFFFF);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density));
            edgePaint.setColor(0x66FFFFFF);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            tintPaint.setShader(new LinearGradient(0, 0, 0, h, 0x40FFFFFF, 0x14DCE6FF, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.save();
            canvas.clipPath(clip);
            if (frost != null) {
                getLocationOnScreen(loc);
                float sx = frost.getWidth() / (float) screenW;
                float sy = frost.getHeight() / (float) screenH;
                src.set(Math.max(0, Math.round(loc[0] * sx)), Math.max(0, Math.round(loc[1] * sy)),
                        Math.min(frost.getWidth(), Math.max(1, Math.round((loc[0] + w) * sx))),
                        Math.min(frost.getHeight(), Math.max(1, Math.round((loc[1] + h) * sy))));
                if (src.width() < 1) src.right = Math.min(frost.getWidth(), src.left + 1);
                if (src.height() < 1) src.bottom = Math.min(frost.getHeight(), src.top + 1);
                canvas.drawBitmap(frost, src, rect, bitmapPaint);
                canvas.drawColor(0x66060814);
            } else {
                canvas.drawColor(0x99101626);
            }
            canvas.drawRect(rect, tintPaint);
            canvas.restore();
            canvas.drawRoundRect(rect, radius, radius, borderPaint);
            float inset = radius * 0.7f;
            canvas.drawLine(inset, 1f, w - inset, 1f, edgePaint);
        }
    }
}
