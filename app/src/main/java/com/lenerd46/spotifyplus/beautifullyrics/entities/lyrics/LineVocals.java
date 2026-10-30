package com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Pair;
import com.google.android.flexbox.FlexboxLayout;
import com.lenerd46.spotifyplus.References;
import com.lenerd46.spotifyplus.beautifullyrics.entities.*;
import de.robv.android.xposed.XposedBridge;

import java.util.List;
import java.util.stream.Collectors;

public class LineVocals implements SyncableVocals {
    public final ActivityChangedSource activityChanged;

    public FlexboxLayout container;
    public GradientTextView lyricText;

    public final double startTime;
    public final double duration;

    private boolean active;
    private LyricState state;
    private boolean isSleeping = true;

    private Spline glowSpline;
    private final Spring glowSpring;
    private final SharedPreferences prefs;
    private final boolean lineGradient;
    private final float baseFontSize;
    private boolean secondary;

    private final List<Pair<Double, Double>> glowRange = List.of(
            Pair.create(0d, 0d),
            Pair.create(0.5d, 1d),
            Pair.create(0.925d, 1d),
            Pair.create(1d, 0d)
    );

    private Spline getSpline(List<Pair<Double, Double>> range) {
        return new Spline(range.stream().map(e -> e.first).collect(Collectors.toList()), range.stream().map(e -> e.second).collect(Collectors.toList()));
    }

    public LineVocals(FlexboxLayout container, LineVocal vocal, boolean isRomanized, Activity activity) {
        this(container, vocal, isRomanized, activity, 30f, false);
    }

    public LineVocals(FlexboxLayout container, LineVocal vocal, boolean isRomanized, Activity activity,
            float fontSize, boolean secondary) {
        this.container = container;
        activityChanged = new ActivityChangedSource();
        prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        this.baseFontSize = fontSize;
        this.secondary = secondary;

        this.startTime = vocal.startTime;
        double lineDuration = vocal.endTime - vocal.startTime;
        this.duration = lineDuration > 0 ? lineDuration : 1.0;

        glowSpline = getSpline(glowRange);
        glowSpring = new Spring(0.0, 0.5, 1.0);

        lyricText = new GradientTextView(activity);
        lyricText.setText(isRomanized ? vocal.romanizedText : vocal.text);
        lyricText.setLineState(true);

        lyricText.setTextColor(Color.WHITE);
        lyricText.setTextSize(secondary ? Math.max(18f, fontSize * 0.7f) : fontSize);
        lyricText.setPadding(0, 0, 1, 0);
        lyricText.setTypeface(References.beautifulFont.get());

        if (vocal.oppositeAligned) {
            lyricText.setTextAlignment(GradientTextView.TEXT_ALIGNMENT_TEXT_END);
        }

        lineGradient = prefs.getBoolean("lyric_enable_line_gradient", true);

        container.addView(lyricText);
        setToGeneralState(false);
    }

    public void setSecondary(boolean secondary) {
        if (this.secondary == secondary) {
            return;
        }
        this.secondary = secondary;
        lyricText.setTextSize(secondary ? Math.max(18f, baseFontSize * 0.7f) : baseFontSize);
        evaluateClassState();
    }

    private void setToGeneralState(boolean state) {
        int timeScale = state ? 1 : 0;

        updateLiveTextState(timeScale, true);
        updateLiveTextVisuals(timeScale, 0);

        this.state = state ? LyricState.SUNG : LyricState.IDLE;
        evaluateClassState();
    }

    private void updateLiveTextState(double timeScale, boolean forceTo) {
        double glowAlpha = glowSpline.at(timeScale);

        if (forceTo) {
            glowSpring.set(glowAlpha);
        } else {
            glowSpring.finalPosition = glowAlpha;
        }
    }

    private boolean updateLiveTextVisuals(double timeScale, double deltaTime) {
        float glowAlpha = (float) glowSpring.update(deltaTime);

        if (lineGradient) {
            lyricText.updateShadow(glowAlpha * (secondary ? 0.25f : 0.5f),
                    (secondary ? 2 : 4) + ((secondary ? 4 : 8) * glowAlpha));
            lyricText.setProgress((float) (120 * timeScale));
        }

        return glowSpring.sleeping;
    }

    private void evaluateClassState() {
        boolean themed = com.lenerd46.spotifyplus.hooks.ThemeHook.isThemeEnabled();
        int baseText = themed ? com.lenerd46.spotifyplus.hooks.ThemeHook.getTextColor() : Color.WHITE;
        int subdued = themed ? com.lenerd46.spotifyplus.hooks.ThemeHook.getSubduedColor() : 0xFFB3B3B3;
        int r = Color.red(baseText), g = Color.green(baseText), b = Color.blue(baseText);
        int sr = Color.red(subdued), sg = Color.green(subdued), sb = Color.blue(subdued);
        int activeCol = Color.argb(secondary ? 217 : 255, r, g, b);
        int activeGrad2 = Color.argb(secondary ? 80 : 120, r, g, b);
        if (state == LyricState.ACTIVE) {
            lyricText.setTextColor(activeCol);
            lyricText.setGradientColors(lineGradient
                    ? new int[]{activeCol, activeGrad2}
                    : new int[]{activeCol, activeCol});
            return;
        } else if (state == LyricState.SUNG) {
            lyricText.setTextColor(Color.argb(secondary ? 72 : 100, sr, sg, sb));
            lyricText.updateShadow(0f, 0f);
            return;
        } else {
            int idleCol = Color.argb(secondary ? 190 : 255, sr, sg, sb);
            lyricText.setTextColor(idleCol);
            lyricText.setGradientColors(new int[]{idleCol, Color.argb(secondary ? 48 : 60, sr, sg, sb)});
            return;
        }
    }

    @Override
    public void animate(double songTimestamp, double deltaTime, boolean isImmediate) {
        double relativeTime = songTimestamp - this.startTime;
        double timeScale = Math.max(0, Math.min((double) relativeTime / (double) this.duration, 1.0));

        boolean pastStart = relativeTime >= 0;
        boolean beforeEnd = relativeTime <= this.duration;
        boolean isActive = pastStart && beforeEnd;

        LyricState stateNow = isActive ? LyricState.ACTIVE : pastStart ? LyricState.SUNG : LyricState.IDLE;

        boolean stateChanged = stateNow != this.state;
        boolean shouldUpdateVisualState = stateChanged || isActive || isImmediate;

        if (stateChanged) {
            this.state = stateNow;
            evaluateClassState();

            if (this.state == LyricState.ACTIVE) {
                activityChanged.invoke(new ScrollInformation(container, isImmediate));
            }
        } else if (this.state == LyricState.ACTIVE && isImmediate) {
            activityChanged.invoke(new ScrollInformation(container, true));
        }

        if (shouldUpdateVisualState) {
            this.isSleeping = false;

            updateLiveTextState(timeScale, (isImmediate || (relativeTime < 0)));
        }

        if (!this.isSleeping) {
            boolean isSleeping = updateLiveTextVisuals(timeScale, deltaTime);

            if (isSleeping) {
                this.isSleeping = true;

                if (!this.active) {
                    evaluateClassState();
                }
            }
        }
    }

    @Override
    public boolean isActive() {
        return this.active;
    }
}
