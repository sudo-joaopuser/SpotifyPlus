package com.lenerd46.spotifyplus.hooks;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lenerd46.spotifyplus.References;
import com.lenerd46.spotifyplus.SpotifyTrack;
import com.lenerd46.spotifyplus.beautifullyrics.entities.SyllableVocals;
import com.lenerd46.spotifyplus.beautifullyrics.entities.GradientTextView;
import com.lenerd46.spotifyplus.lyrics.LyricsProviders;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableMetadata;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableSyncedLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.LineSyncedLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.LineVocal;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableVocalSet;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NowPlayingLyricsGradientHook extends SpotifyHook {
    private static final String NOW_PLAYING_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final OkHttpClient lyricsClient = new OkHttpClient();
    private final Gson gson = new Gson();
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private volatile List<TimedLine> timedLines = Collections.emptyList();
    private volatile String requestedTrackUri;
    private volatile String requestedTrackTitle;
    private volatile String requestedTrackArtist = "";
    private volatile String requestedTrackAlbum = "";
    private volatile long requestedTrackDurationMs;
    private volatile String loadedTrackUri;
    private volatile String mediaTrackUri;
    private volatile String mediaTrackTitle;
    private volatile boolean nowPlayingLyricsVisible;
    private volatile boolean nowPlayingActivityResumed;
    private volatile WeakReference<Activity> nowPlayingActivity = new WeakReference<>(null);
    private volatile boolean nativeLyricsFallback;
    private volatile boolean npvLyricsLoading;
    private TextView npvLoadingView;
    private TextView npvGapView;
    private Call activeCall;
    private Field[] lineColorFields;
    private Class<?> lineModelClass;
    private final ThreadLocal<Integer> npvLineBuildDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> npvLineRenderDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> npvRendererDepth = ThreadLocal.withInitial(() -> 0);
    private final List<WeakReference<Object>> npvLineModels = Collections.synchronizedList(new ArrayList<>());
    private final List<NpvLineColors> npvHiddenLineModels = Collections.synchronizedList(new ArrayList<>());
    private final List<WeakReference<Object>> npvRecomposeScopes = Collections.synchronizedList(new ArrayList<>());
    private final List<WeakReference<Object>> npvColorStates = Collections.synchronizedList(new ArrayList<>());
    private final Set<Class<?>> hookedColorStateClasses = Collections.synchronizedSet(new HashSet<>());
    private Method npvScopeInvalidator;
    private String colorStateGetterName;
    private Field npvMutableColorStateField;
    private Method npvMutableColorStateGetter;
    private Method npvMutableColorStateSetter;
    private Constructor<?> transparentColorConstructor;
    private FlexboxLayout lyricContainer;
    private boolean videoBackgroundCached;
    private long videoBackgroundCheckedAt;
    private ViewGroup videoBackgroundHost;
    private Rect videoButtonBoundsCached;
    private long videoButtonBoundsCheckedAt;
    private View lyricClickCatcher;
    private SyllableVocals activeVocals;
    private TimedLine displayedLine;
    private ViewGroup overlayHost;
    private long lastTrackCheck;
    private long lastNpvRenderAt;
    private long lastAnimationFrameNanos;
    private boolean framePosted;
    private volatile boolean trackLookupRunning;
    private volatile String retriedTrackUri;
    private volatile long playbackPositionMs = -1;
    private volatile long playbackUpdateElapsed;
    private volatile float playbackSpeed = 1f;
    private volatile boolean playbackAdvancing;
    private volatile boolean playbackStateSeen;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            try {
                updateFrame(frameTimeNanos);
                // Keep polling while the NPV is visible, even with no lines
                // or no anchor yet: the first frame often runs before Compose
                // has laid out the title, and a dead loop here is exactly why
                // the lyric only appeared after closing and reopening.
                if(isNowPlayingViewVisible()) postFrame(overlayHost != null && overlayHost.isAttachedToWindow() ? 0 : 100);
            } catch(Throwable t) {
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Animated lyric frame failed");
                XposedBridge.log(t);
                nowPlayingLyricsVisible = false;
                removeOverlay();
            }
        }
    };

    @Override
    protected void hook() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if(!isNowPlayingActivity(activity)) return;
                    nowPlayingActivity = new WeakReference<>(activity);
                    nowPlayingActivityResumed = true;
                    if(References.currentActivity != null && LyricsProviders.isSpotifyStyle(References.currentActivity)) return;
                    if(hasLoadedCustomLyrics()) {
                        hideRememberedNpvLineModels();
                        notifyNpvColorStatesChanged();
                        invalidateNpvRecomposeScopes();
                    }
                    ensureTrackAndLyrics();
                    postFrame();
                    // Compose may not have laid out the title on the first
                    // resume yet; one bounded retry covers that window.
                    mainHandler.postDelayed(() -> {
                        if(isNowPlayingViewVisible()) {
                            ensureTrackAndLyrics();
                            postFrame();
                        }
                    }, 500);
                }
            });
            XC_MethodHook hideWhenNowPlayingCloses = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if(activity != nowPlayingActivity.get()) return;
                    nowPlayingActivityResumed = false;
                    retriedTrackUri = null;
                    removeOverlay();
                    if(hasLoadedCustomLyrics()) {
                        restoreNpvLineModels();
                        notifyNpvColorStatesChanged();
                        invalidateNpvRecomposeScopes();
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", hideWhenNowPlayingCloses);
            XposedHelpers.findAndHookMethod(Activity.class, "onStop", hideWhenNowPlayingCloses);
            List<MethodData> npvRendererCandidates = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().returnType(Object.class).paramCount(5).usingStrings("lyrics_line")));
            XposedBridge.log("[SpotifyPlus][NPV DexKit] link=npvRenderer candidates=" + npvRendererCandidates.size());
            if (npvRendererCandidates.isEmpty()) throw new IllegalStateException("[NowPlayingLyricsGradientHook/DexKit] link=npvRenderer: no candidates");
            Method npvRenderer = npvRendererCandidates.get(0).getMethodInstance(lpparm.classLoader);
            List<MethodData> lineRendererCandidates = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL).returnType(void.class).usingStrings("fontScale", "lyrics line color")));
            XposedBridge.log("[SpotifyPlus][NPV DexKit] link=lineRenderer candidates=" + lineRendererCandidates.size());
            if(lineRendererCandidates.size() != 1) throw new IllegalStateException("[NowPlayingLyricsGradientHook/DexKit] link=lineRenderer: expected 1, found " + lineRendererCandidates.size());
            MethodData lineRendererData = lineRendererCandidates.get(0);
            Method lineRenderer = lineRendererData.getMethodInstance(lpparm.classLoader);
            Class<?> resolvedLineModelClass = lineRenderer.getParameterTypes()[0];
            MethodData colorAnimationData = null;
            MethodData colorStateGetterData = null;
            for(MethodData invoked : lineRendererData.getInvokes()) {
                List<String> params = invoked.getParamTypeNames();
                if(!invoked.isMethod() || params.size() != 6 || !"long".equals(params.get(0)) || !String.class.getName().equals(params.get(2)) || !"int".equals(params.get(4)) || !"int".equals(params.get(5))) continue;
                List<MethodData> getters = invoked.getReturnType().getMethods().stream().filter(method -> method.isMethod() && !Modifier.isStatic(method.getModifiers()) && method.getParamCount() == 0 && Object.class.getName().equals(method.getReturnTypeName())).collect(java.util.stream.Collectors.toList());
                if(getters.size() != 1) continue;
                if(colorAnimationData != null && !colorAnimationData.getDescriptor().equals(invoked.getDescriptor())) { XposedBridge.log("[SpotifyPlus][NPV DexKit] link=colorState ambiguous, skipped"); colorAnimationData = null; colorStateGetterData = null; break; }
                colorAnimationData = invoked;
                colorStateGetterData = getters.get(0);
            }
            // Non-fatal link: losing only the colour-state hook must not
            // kill the renderer/overlay hooks below.
            XposedBridge.log("[SpotifyPlus][NPV DexKit] link=colorState found=" + (colorAnimationData != null && colorStateGetterData != null));
            if (colorAnimationData != null && colorStateGetterData != null) {
            colorStateGetterName = colorStateGetterData.getMethodName();
            XposedBridge.hookMethod(colorAnimationData.getMethodInstance(lpparm.classLoader), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(npvLineRenderDepth.get() != 0 && param.args.length > 2 && "lyrics line color".equals(param.args[2]) && param.getResult() != null) rememberNpvColorState(param.getResult());
                }
            });
            } else {
                XposedBridge.log("[SpotifyPlus][NPV DexKit] link=colorState skipped, colour-state hiding disabled");
            }
            MethodData scopeAccessorData = null;
            MethodData scopeInvalidatorData = null;
            boolean scopeAmbiguous = false;
            for(MethodData invoked : lineRendererData.getInvokes()) {
                if(scopeAmbiguous) break;
                if(!invoked.isMethod() || invoked.getParamCount() != 0 || "void".equals(invoked.getReturnTypeName())) continue;
                for(MethodData candidate : invoked.getReturnType().getMethods()) {
                    if(!candidate.isMethod() || Modifier.isStatic(candidate.getModifiers()) || candidate.getParamCount() != 0 || !"void".equals(candidate.getReturnTypeName())) continue;
                    boolean invalidatesScope = candidate.getInvokes().stream().anyMatch(call -> call.getParamTypeNames().equals(List.of(invoked.getReturnTypeName(), Object.class.getName())));
                    if(!invalidatesScope) continue;
                    if(scopeAccessorData != null && !scopeAccessorData.getDescriptor().equals(invoked.getDescriptor())) { XposedBridge.log("[SpotifyPlus][NPV DexKit] link=recomposeScope ambiguous, skipped"); scopeAccessorData = null; scopeInvalidatorData = null; scopeAmbiguous = true; break; }
                    scopeAccessorData = invoked;
                    scopeInvalidatorData = candidate;
                }
            }
            // Non-fatal link, same rule as colour state above.
            XposedBridge.log("[SpotifyPlus][NPV DexKit] link=recomposeScope found=" + (scopeAccessorData != null && scopeInvalidatorData != null));
            if (scopeAccessorData != null && scopeInvalidatorData != null) {
            npvScopeInvalidator = scopeInvalidatorData.getMethodInstance(lpparm.classLoader);
            npvScopeInvalidator.setAccessible(true);
            XposedBridge.hookMethod(scopeAccessorData.getMethodInstance(lpparm.classLoader), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(npvLineRenderDepth.get() != 0 && param.getResult() != null) rememberNpvRecomposeScope(param.getResult());
                }
            });
            } else {
                XposedBridge.log("[SpotifyPlus][NPV DexKit] link=recomposeScope skipped, scope invalidation disabled");
            }
            var lineModelBuilderCandidates = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().returnType(Object.class).paramCount(5).addInvoke(MethodMatcher.create().name("<init>").declaredClass(resolvedLineModelClass))));
            XposedBridge.log("[SpotifyPlus][NPV DexKit] link=lineModelBuilder candidates=" + lineModelBuilderCandidates.size());
            if(lineModelBuilderCandidates.isEmpty()) throw new IllegalStateException("[NowPlayingLyricsGradientHook/DexKit] link=lineModelBuilder: no candidates");
            for(var lineModelBuilderData : lineModelBuilderCandidates) XposedBridge.hookMethod(lineModelBuilderData.getMethodInstance(lpparm.classLoader), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if(!hasNpvPresentation(param.args)) return;
                    param.setObjectExtra("spotifyplus_npv_line_build", Boolean.TRUE);
                    int depth = npvLineBuildDepth.get();
                    npvLineBuildDepth.set(depth + 1);
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(!Boolean.TRUE.equals(param.getObjectExtra("spotifyplus_npv_line_build"))) return;
                    int depth = npvLineBuildDepth.get() - 1;
                    if(depth > 0) npvLineBuildDepth.set(depth);
                    else npvLineBuildDepth.remove();
                }
            });
            XposedBridge.hookAllConstructors(resolvedLineModelClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(npvLineBuildDepth.get() == 0) return;
                    rememberNpvLineModel(param.thisObject);
                    if(isNowPlayingViewVisible() && hasLoadedCustomLyrics()) hideNpvLineModel(param.thisObject);
                }
            });
            XposedBridge.hookMethod(npvRenderer, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object state = param.args.length > 1 ? param.args[1] : null;
                    if(!isNowPlayingLyricsState(state)) return;
                    param.setObjectExtra("spotifyplus_npv_renderer", Boolean.TRUE);
                    npvRendererDepth.set(npvRendererDepth.get() + 1);
                    lastNpvRenderAt = System.currentTimeMillis();
                    nowPlayingLyricsVisible = hasVisibleLyrics(state);
                    if(isNowPlayingViewVisible()) {
                        ensureTrackAndLyrics();
                        postFrame();
                    } else {
                        mainHandler.post(NowPlayingLyricsGradientHook.this::removeOverlay);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(Boolean.TRUE.equals(param.getObjectExtra("spotifyplus_npv_renderer"))) {
                        int depth = npvRendererDepth.get() - 1;
                        if(depth > 0) npvRendererDepth.set(depth);
                        else npvRendererDepth.remove();
                    }
                }
            });
            XposedBridge.hookMethod(lineRenderer, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if(!isNowPlayingViewVisible() || nativeLyricsFallback || param.args.length < 2 || param.args[0] == null) return;
                        // Scope the hide to the NPV small line only. The previous
                        // version remembered and hid every model rendered while
                        // the NPV was visible, which also hid the lyrics preview
                        // card in the NPV (same activity, same renderer) and left
                        // its "Letra" card empty. Preview models are never NPV
                        // models and never render inside the NPV renderer, so
                        // they are left alone here and keep their colours.
                        boolean isNpvRender = isNpvLineModel(param.args[0]) || npvRendererDepth.get() > 0;
                        if(!isNpvRender) return;
                        if(!isNpvLineModel(param.args[0])) rememberNpvLineModel(param.args[0]);
                        param.setObjectExtra("spotifyplus_npv_line_render", Boolean.TRUE);
                        npvLineRenderDepth.set(npvLineRenderDepth.get() + 1);
                        lastNpvRenderAt = System.currentTimeMillis();
                        String text = getPrimaryLineText(param.args[0]);
                        if(Boolean.TRUE.equals(param.args[1]) && !TextUtils.isEmpty(text)) {
                            postFrame();
                        }
                        if(!hasLoadedCustomLyrics()) return;
                        hideNpvLineModel(param.args[0]);
                    } catch(Throwable t) {
                        XposedBridge.log(t);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(Boolean.TRUE.equals(param.getObjectExtra("spotifyplus_npv_line_render"))) {
                        int depth = npvLineRenderDepth.get() - 1;
                        if(depth > 0) npvLineRenderDepth.set(depth);
                        else npvLineRenderDepth.remove();
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if(param.thisObject == nowPlayingActivity.get()) {
                        nowPlayingActivityResumed = false;
                        nowPlayingActivity = new WeakReference<>(null);
                    }
                    if(overlayHost != null && overlayHost.getContext() == param.thisObject) removeOverlay();
                }
            });
            XposedHelpers.findAndHookMethod("android.media.session.MediaSession", lpparm.classLoader, "setPlaybackState", PlaybackState.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    PlaybackState state = (PlaybackState) param.args[0];
                    if(state == null) return;
                    playbackPositionMs = state.getPosition();
                    playbackUpdateElapsed = state.getLastPositionUpdateTime() > 0 ? state.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
                    playbackSpeed = state.getPlaybackSpeed();
                    playbackAdvancing = state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_FAST_FORWARDING || state.getState() == PlaybackState.STATE_REWINDING;
                    playbackStateSeen = true;
                }
            });
            XposedHelpers.findAndHookMethod("android.media.session.MediaSession", lpparm.classLoader, "setMetadata", MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    MediaMetadata metadata = (MediaMetadata) param.args[0];
                    if(metadata == null) return;
                    String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                    if(TextUtils.isEmpty(uri) || !uri.startsWith("spotify:track:")) return;
                    mediaTrackUri = uri;
                    CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                    mediaTrackTitle = title == null ? "" : title.toString();
                    lastTrackCheck = 0;
                    if(isNowPlayingViewVisible()) ensureTrackAndLyrics();
                }
            });
            References.registerPlayerStateListener(state -> {
                if(nowPlayingActivityResumed) {
                    lastTrackCheck = 0;
                    ensureTrackAndLyrics();
                    postFrame();
                }
            });
            XposedBridge.log("[SpotifyPlus] Now-playing gradient lyrics hook initialized");
        } catch(Throwable t) {
            XposedBridge.log("[SpotifyPlus] Failed to initialize now-playing gradient lyrics hook");
            XposedBridge.log(t);
        }
    }

    private boolean isNowPlayingLyricsState(Object state) {
        if(state == null) return false;
        int fields = 0;
        int strings = 0;
        for(Field field : state.getClass().getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers())) continue;
            fields++;
            if(field.getType() == String.class) strings++;
        }
        return fields == 3 && strings == 2;
    }

    private boolean hasVisibleLyrics(Object state) {
        try {
            for(Field field : state.getClass().getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType() == String.class) continue;
                field.setAccessible(true);
                Object displayState = field.get(state);
                if(displayState == null) return false;
                for(Field displayField : displayState.getClass().getDeclaredFields()) if(!Modifier.isStatic(displayField.getModifiers())) return true;
                return false;
            }
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
        return false;
    }

    private boolean hasNpvPresentation(Object[] args) {
        for(Object arg : args) {
            if(arg == null) continue;
            try {
                for(Field field : arg.getClass().getDeclaredFields()) {
                    if(Modifier.isStatic(field.getModifiers()) || !field.getType().isEnum()) continue;
                    field.setAccessible(true);
                    Object presentation = field.get(arg);
                    if(presentation == null) continue;
                    if("NPV".equalsIgnoreCase(String.valueOf(presentation))) return true;
                    for(Field presentationField : presentation.getClass().getDeclaredFields()) {
                        if(Modifier.isStatic(presentationField.getModifiers()) || presentationField.getType() != String.class) continue;
                        presentationField.setAccessible(true);
                        if("npv".equalsIgnoreCase((String) presentationField.get(presentation))) return true;
                    }
                }
            } catch(Throwable t) {
                XposedBridge.log(t);
            }
        }
        return false;
    }

    private void rememberNpvLineModel(Object model) {
        synchronized(npvLineModels) {
            for(int i = npvLineModels.size() - 1; i >= 0; i--) if(npvLineModels.get(i).get() == null) npvLineModels.remove(i);
            npvLineModels.add(new WeakReference<>(model));
        }
    }

    private boolean isNpvLineModel(Object model) {
        synchronized(npvLineModels) {
            for(int i = npvLineModels.size() - 1; i >= 0; i--) {
                Object candidate = npvLineModels.get(i).get();
                if(candidate == null) npvLineModels.remove(i);
                else if(candidate == model) return true;
            }
        }
        return false;
    }

    private void hideNpvLineModel(Object model) {
        synchronized(npvHiddenLineModels) {
            for(int i = npvHiddenLineModels.size() - 1; i >= 0; i--) {
                Object candidate = npvHiddenLineModels.get(i).model.get();
                if(candidate == null) npvHiddenLineModels.remove(i);
                else if(candidate == model) return;
            }
            try {
                Field[] colors = getLineColorFields(model.getClass());
                long[] original = new long[colors.length];
                for(int i = 0; i < colors.length; i++) original[i] = colors[i].getLong(model);
                npvHiddenLineModels.add(new NpvLineColors(model, original));
                for(Field color : colors) color.setLong(model, 0L);
            } catch(Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    private void hideRememberedNpvLineModels() {
        synchronized(npvLineModels) {
            for(int i = npvLineModels.size() - 1; i >= 0; i--) {
                Object model = npvLineModels.get(i).get();
                if(model == null) npvLineModels.remove(i);
                else hideNpvLineModel(model);
            }
        }
    }

    private void restoreNpvLineModels() {
        synchronized(npvHiddenLineModels) {
            for(int i = npvHiddenLineModels.size() - 1; i >= 0; i--) {
                NpvLineColors hidden = npvHiddenLineModels.get(i);
                Object model = hidden.model.get();
                if(model == null) continue;
                try {
                    Field[] colors = getLineColorFields(model.getClass());
                    for(int j = 0; j < colors.length && j < hidden.colors.length; j++) colors[j].setLong(model, hidden.colors[j]);
                } catch(Throwable t) {
                    XposedBridge.log(t);
                }
            }
            npvHiddenLineModels.clear();
        }
    }

    private void rememberNpvRecomposeScope(Object scope) {
        synchronized(npvRecomposeScopes) {
            for(int i = npvRecomposeScopes.size() - 1; i >= 0; i--) {
                Object candidate = npvRecomposeScopes.get(i).get();
                if(candidate == null) npvRecomposeScopes.remove(i);
                else if(candidate == scope) return;
            }
            npvRecomposeScopes.add(new WeakReference<>(scope));
        }
    }

    private void invalidateNpvRecomposeScopes() {
        if (npvScopeInvalidator == null) return;
        synchronized(npvRecomposeScopes) {
            for(int i = npvRecomposeScopes.size() - 1; i >= 0; i--) {
                Object scope = npvRecomposeScopes.get(i).get();
                if(scope == null) npvRecomposeScopes.remove(i);
                else {
                    try {
                        npvScopeInvalidator.invoke(scope);
                    } catch(Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }
        }
    }

    private void rememberNpvColorState(Object state) {
        if (colorStateGetterName == null) return;
        synchronized(npvColorStates) {
            for(int i = npvColorStates.size() - 1; i >= 0; i--) {
                Object candidate = npvColorStates.get(i).get();
                if(candidate == null) npvColorStates.remove(i);
                else if(candidate == state) return;
            }
            npvColorStates.add(new WeakReference<>(state));
        }
        resolveMutableColorState(state);
        Class<?> stateClass = state.getClass();
        if(!hookedColorStateClasses.add(stateClass)) return;
        try {
            Method getter = stateClass.getMethod(colorStateGetterName);
            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if(!isNowPlayingViewVisible() || !hasLoadedCustomLyrics() || !isNpvColorState(param.thisObject) || param.getResult() == null) return;
                    Object value = param.getResult();
                    for(Constructor<?> constructor : value.getClass().getDeclaredConstructors()) {
                        if(constructor.getParameterCount() != 1 || constructor.getParameterTypes()[0] != long.class) continue;
                        try {
                            constructor.setAccessible(true);
                            param.setResult(constructor.newInstance(0L));
                        } catch(Throwable t) {
                            XposedBridge.log(t);
                        }
                        return;
                    }
                }
            });
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void resolveMutableColorState(Object state) {
        if(npvMutableColorStateField != null && npvMutableColorStateGetter != null && npvMutableColorStateSetter != null && transparentColorConstructor != null) return;
        try {
            Method outerGetter = state.getClass().getMethod(colorStateGetterName);
            Object color = outerGetter.invoke(state);
            if(color == null) return;
            Constructor<?> colorConstructor = null;
            for(Constructor<?> constructor : color.getClass().getDeclaredConstructors()) {
                if(constructor.getParameterCount() != 1 || constructor.getParameterTypes()[0] != long.class) continue;
                constructor.setAccessible(true);
                colorConstructor = constructor;
                break;
            }
            if(colorConstructor == null) return;
            for(Field field : state.getClass().getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object candidate = field.get(state);
                if(candidate == null) continue;
                Method getter;
                try {
                    getter = candidate.getClass().getMethod(colorStateGetterName);
                } catch(NoSuchMethodException ignored) {
                    continue;
                }
                if(getter.getParameterCount() != 0 || getter.getReturnType() != Object.class) continue;
                Method setter = null;
                for(Method method : candidate.getClass().getMethods()) {
                    if(Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class || method.getParameterCount() != 1 || method.getParameterTypes()[0] != Object.class) continue;
                    if(setter != null) {
                        setter = null;
                        break;
                    }
                    setter = method;
                }
                if(setter == null) continue;
                npvMutableColorStateField = field;
                npvMutableColorStateGetter = getter;
                npvMutableColorStateSetter = setter;
                transparentColorConstructor = colorConstructor;
                return;
            }
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void notifyNpvColorStatesChanged() {
        if(npvMutableColorStateField == null || npvMutableColorStateGetter == null || npvMutableColorStateSetter == null || transparentColorConstructor == null) {
            XposedBridge.log("[SpotifyPlus][NPV Lyrics] Could not notify the mutable lyrics-color state");
            return;
        }
        synchronized(npvColorStates) {
            for(int i = npvColorStates.size() - 1; i >= 0; i--) {
                Object state = npvColorStates.get(i).get();
                if(state == null) {
                    npvColorStates.remove(i);
                    continue;
                }
                try {
                    Object mutableState = npvMutableColorStateField.get(state);
                    Object original = npvMutableColorStateGetter.invoke(mutableState);
                    npvMutableColorStateSetter.invoke(mutableState, transparentColorConstructor.newInstance(0L));
                    npvMutableColorStateSetter.invoke(mutableState, original);
                } catch(Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }
    }

    private boolean isNpvColorState(Object state) {
        synchronized(npvColorStates) {
            for(int i = npvColorStates.size() - 1; i >= 0; i--) {
                Object candidate = npvColorStates.get(i).get();
                if(candidate == null) npvColorStates.remove(i);
                else if(candidate == state) return true;
            }
        }
        return false;
    }

    private String getPrimaryLineText(Object model) {
        try {
            for(Field field : model.getClass().getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                field.setAccessible(true);
                String value = (String) field.get(model);
                if(!TextUtils.isEmpty(value)) return value;
            }
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
        return "";
    }

    private void ensureTrackAndLyrics() {
        long now = System.currentTimeMillis();
        // A new track is a fresh attempt: drop a stale native fallback now,
        // on the main thread, or it gates every later check (see below).
        if(!TextUtils.isEmpty(mediaTrackUri) && !mediaTrackUri.equals(requestedTrackUri)) nativeLyricsFallback = false;
        if(now - lastTrackCheck < 500 || trackLookupRunning) return;
        lastTrackCheck = now;
        trackLookupRunning = true;
        resolverExecutor.execute(() -> {
            try {
                String uri = mediaTrackUri;
                String title = mediaTrackTitle;
                SpotifyTrack track = null;
                if(TextUtils.isEmpty(uri)) {
                    track = References.getTrackTitle(lpparm, bridge);
                    if(track == null) return;
                    uri = track.uri;
                    title = track.title;
                }
                if(TextUtils.isEmpty(uri) || !isNowPlayingViewVisible()) return;
                // Same track, already loaded: nothing to refetch. Same track
                // with NO loaded lyrics (aborted first load, or a previous
                // fallback): retry, or the provider is never attempted again
                // and only the lyrics-button path (Beautiful Lyrics) works.
                if(uri.equals(requestedTrackUri) && hasLoadedCustomLyrics()) return;
                // One same-track retry per open session: without this guard
                // the frame/renderer loop would refetch a genuinely
                // lyric-less track every 500 ms while the NPV stays open.
                // The guard clears on close, so reopening retries once.
                if(uri.equals(requestedTrackUri) && uri.equals(retriedTrackUri)) return;
                if(uri.equals(requestedTrackUri)) { nativeLyricsFallback = false; retriedTrackUri = uri; }
                String resolvedUri = uri;
                String resolvedTitle = title;
                if(track == null) { try { SpotifyTrack t2 = References.getTrackTitle(lpparm, bridge); if(t2 != null && resolvedUri.equals(t2.uri)) track = t2; } catch(Throwable ignored) {} }
                if(track != null) { requestedTrackArtist = track.artist == null ? "" : track.artist; requestedTrackAlbum = track.album == null ? "" : track.album; requestedTrackDurationMs = track.duration; }
                else { requestedTrackArtist = ""; requestedTrackAlbum = ""; requestedTrackDurationMs = 0; }
                requestedTrackUri = resolvedUri;
                requestedTrackTitle = resolvedTitle;
                loadedTrackUri = null;
                timedLines = Collections.emptyList();
                nativeLyricsFallback = false;
                mainHandler.post(() -> {
                    restoreNpvLineModels();
                    if(lyricContainer != null) lyricContainer.setVisibility(View.INVISIBLE);
                    if(lyricContainer != null) lyricContainer.removeAllViews();
                    activeVocals = null;
                    displayedLine = null;
                });
                if(activeCall != null) activeCall.cancel();
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Requesting syllable lyrics for " + resolvedUri);
                if(!playbackStateSeen && track != null) {
                    playbackPositionMs = track.position;
                    playbackUpdateElapsed = SystemClock.elapsedRealtime();
                    playbackSpeed = 1f;
                    playbackAdvancing = true;
                }
                npvLyricsLoading = true;
                requestLyrics(resolvedUri);
            } catch(Throwable t) {
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Failed to resolve the current track");
                XposedBridge.log(t);
            } finally {
                trackLookupRunning = false;
            }
        });
    }

    private void requestLyrics(String trackUri) {
        try {
            String[] uriParts = trackUri.split(":");
            if(uriParts.length < 3) return;
            String id = uriParts[2];
            Request request = new Request.Builder().url("https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/" + id).build();
            activeCall = lyricsClient.newCall(request);
            activeCall.enqueue(new Callback() {
                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    if(call.isCanceled() || !trackUri.equals(requestedTrackUri)) {
                        response.close();
                        return;
                    }
                    if(!response.isSuccessful() || response.body() == null) {
                        int responseCode = response.code();
                        response.close();
                        tryProviderSyllable(trackUri, "HTTP " + responseCode);
                        return;
                    }
                    String content;
                    try {
                        content = response.body().string();
                    } catch(IOException exception) {
                        tryProviderSyllable(trackUri, "failed to read response");
                        return;
                    } finally {
                        response.close();
                    }
                    if(content.isBlank()) {
                        tryProviderSyllable(trackUri, "empty response");
                        return;
                    }
                    try {
                        JsonObject data = JsonParser.parseString(content).getAsJsonObject();
                        if(data != null && data.has("Type") && "Line".equals(data.get("Type").getAsString())) {
                            // Styled-lyrics fix (proven by the 10:06 log): Devon returns
                            // Line for these tracks, so the old code fell back to native
                            // lyrics and the styled gradient never appeared. Render the
                            // Line lyrics with the same gradient overlay instead.
                            List<TimedLine> lineParsed = parseLineTimedLines(gson.fromJson(data, LineSyncedLyrics.class));
                            if(!lineParsed.isEmpty() && trackUri.equals(requestedTrackUri)) {
                                timedLines = Collections.unmodifiableList(lineParsed);
                                loadedTrackUri = trackUri;
                                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Loaded " + lineParsed.size() + " line-timed lines (styled)");
                                mainHandler.post(() -> {
                                    postFrame(); // Restart loop even if not visible yet (updateFrame drops it if not visible)
                                    if(!isNowPlayingViewVisible()) return;
                                    hideRememberedNpvLineModels();
                                    notifyNpvColorStatesChanged();
                                    invalidateNpvRecomposeScopes();
                                    postFrame();
                                    if(overlayHost != null) overlayHost.invalidate();
                                });
                                return;
                            }
                            tryProviderSyllable(trackUri, "lyrics type Line");
                            return;
                        }
                        if(data == null || !data.has("Type") || !"Syllable".equals(data.get("Type").getAsString())) {
                            tryProviderSyllable(trackUri, data != null && data.has("Type") ? "lyrics type " + data.get("Type").getAsString() : "missing lyrics data");
                            return;
                        }
                        SyllableSyncedLyrics lyrics = gson.fromJson(data, SyllableSyncedLyrics.class);
                        List<TimedLine> parsed = parseTimedLines(lyrics);
                        if(parsed.isEmpty()) {
                            tryProviderSyllable(trackUri, "no timed lines");
                            return;
                        }
                        if(!trackUri.equals(requestedTrackUri)) return;
                        timedLines = Collections.unmodifiableList(parsed);
                        loadedTrackUri = trackUri;
                        XposedBridge.log("[SpotifyPlus][NPV Lyrics] Loaded " + parsed.size() + " syllable-timed lines");
                        mainHandler.post(() -> {
                            // Restart the frame loop even if the view was
                            // not visible at this instant; updateFrame itself
                            // drops the overlay when it is not visible, and
                            // without this a load that finishes early leaves
                            // loaded lines with no loop until a reopen.
                            postFrame();
                            if(!isNowPlayingViewVisible()) return;
                            hideRememberedNpvLineModels();
                            notifyNpvColorStatesChanged();
                            invalidateNpvRecomposeScopes();
                            if(overlayHost != null) overlayHost.invalidate();
                        });
                    } catch(Throwable t) {
                        XposedBridge.log("[SpotifyPlus] Failed to parse SpotifyPlus lyrics API NPV response");
                        XposedBridge.log(t);
                        tryProviderSyllable(trackUri, "invalid lyrics response");
                    }
                }

                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    if(!call.isCanceled()) tryProviderSyllable(trackUri, e.getMessage());
                }
            });
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void tryProviderSyllable(String trackUri, String reason) {
        // Devon first, providers only on a miss or a line-only answer.
        // Syllable only here: a line-only provider hit keeps searching
        // inside LyricsProviders, and a miss falls back to native lyrics.
        new Thread(() -> {
            try {
                android.app.Activity activity = References.currentActivity;
                if(activity == null) activity = nowPlayingActivity.get();
                if(activity == null) { mainHandler.post(() -> enableNativeFallback(trackUri, reason)); return; }
                LyricsProviders.Result found = LyricsProviders.fetch(activity, requestedTrackTitle, requestedTrackArtist, requestedTrackAlbum, requestedTrackDurationMs, true);
                if(found == null || found.toProviderLyrics().syllableLyrics == null) {
                    // No syllable anywhere: use a line hit styled, before native fallback.
                    tryProviderLine(trackUri, reason);
                    return;
                }
                List<TimedLine> parsed = parseTimedLines(found.toProviderLyrics().syllableLyrics);
                if(parsed.isEmpty()) { tryProviderLine(trackUri, reason); return; }
                if(!trackUri.equals(requestedTrackUri)) return;
                timedLines = Collections.unmodifiableList(parsed);
                loadedTrackUri = trackUri;
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Loaded " + parsed.size() + " syllable-timed lines from " + found.source.name());
                mainHandler.post(() -> {
                    postFrame(); // Restart loop even if not visible yet (updateFrame drops it if not visible)
                    if(!isNowPlayingViewVisible()) return;
                    hideRememberedNpvLineModels();
                    notifyNpvColorStatesChanged();
                    invalidateNpvRecomposeScopes();
                    postFrame();
                    if(overlayHost != null) overlayHost.invalidate();
                });
            } catch(Throwable t) {
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Provider syllable fetch failed: " + t);
                mainHandler.post(() -> enableNativeFallback(trackUri, reason));
            }
        }).start();
    }

    private void tryProviderLine(String trackUri, String reason) {
        new Thread(() -> {
            try {
                android.app.Activity activity = References.currentActivity;
                if(activity == null) activity = nowPlayingActivity.get();
                if(activity == null) { mainHandler.post(() -> enableNativeFallback(trackUri, reason)); return; }
                LyricsProviders.Result found = LyricsProviders.fetch(activity, requestedTrackTitle, requestedTrackArtist, requestedTrackAlbum, requestedTrackDurationMs, false, true);
                if(found == null) { mainHandler.post(() -> enableNativeFallback(trackUri, reason)); return; }
                List<TimedLine> parsed;
                if(found.toProviderLyrics().syllableLyrics != null) parsed = parseTimedLines(found.toProviderLyrics().syllableLyrics);
                else if(found.toProviderLyrics().lineLyrics != null) parsed = parseLineTimedLines(found.toProviderLyrics().lineLyrics);
                else { mainHandler.post(() -> enableNativeFallback(trackUri, reason)); return; }
                if(parsed.isEmpty() || !trackUri.equals(requestedTrackUri)) { mainHandler.post(() -> enableNativeFallback(trackUri, reason)); return; }
                timedLines = Collections.unmodifiableList(parsed);
                loadedTrackUri = trackUri;
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Loaded " + parsed.size() + " styled lines from " + found.source.name());
                mainHandler.post(() -> {
                    postFrame(); // Restart loop even if not visible yet (updateFrame drops it if not visible)
                    if(!isNowPlayingViewVisible()) return;
                    hideRememberedNpvLineModels();
                    notifyNpvColorStatesChanged();
                    invalidateNpvRecomposeScopes();
                    postFrame();
                    if(overlayHost != null) overlayHost.invalidate();
                });
            } catch(Throwable t) {
                XposedBridge.log("[SpotifyPlus][NPV Lyrics] Provider line fetch failed: " + t);
                mainHandler.post(() -> enableNativeFallback(trackUri, reason));
            }
        }).start();
    }

    private List<TimedLine> parseLineTimedLines(LineSyncedLyrics lyrics) {
        List<TimedLine> result = new ArrayList<>();
        if(lyrics == null || lyrics.content == null) return result;
        for(Object content : lyrics.content) {
            try {
                LineVocal vocal = gson.fromJson(gson.toJsonTree(content), LineVocal.class);
                if(vocal == null || TextUtils.isEmpty(vocal.text)) continue;
                // One syllable spanning the whole line: the gradient sweeps the
                // line as a unit, which is the styled effect for line-only data.
                SyllableMetadata syl = new SyllableMetadata();
                syl.text = vocal.text; syl.startTime = vocal.startTime; syl.endTime = vocal.endTime; syl.isPartOfWord = false;
                TimedLine line = TimedLine.create(List.of(syl), false);
                if(line != null) result.add(line);
            } catch(Throwable t) { XposedBridge.log(t); }
        }
        result.sort((left, right) -> Double.compare(left.startTime, right.startTime));
        result = dedupConsecutive(result);
        for(int i = 0; i < result.size(); i++) result.get(i).displayEndTime = i + 1 < result.size() ? result.get(i + 1).startTime : result.get(i).endTime + 0.5d;
        return result;
    }

    /** Drops consecutive lines with the same normalized text (case/space
     *  ignored), whatever their start times: a provider payload repeating
     *  a line, or the native line re-emitted as the next line, must never
     *  render twice in a row. Non-consecutive repeats are kept, and display
     *  ends are recomputed by the callers after this. */
    private static List<TimedLine> dedupConsecutive(List<TimedLine> lines) {
        List<TimedLine> out = new ArrayList<>();
        for (TimedLine line : lines) {
            if (!out.isEmpty() && normalizeLineText(out.get(out.size() - 1)).equals(normalizeLineText(line))) continue;
            out.add(line);
        }
        return out;
    }

    private static String normalizeLineText(TimedLine line) {
        return lineText(line).replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String lineText(TimedLine line) {
        StringBuilder sb = new StringBuilder();
        if (line.syllables != null) for (SyllableMetadata s : line.syllables) sb.append(s.text);
        return sb.toString();
    }

    private void enableNativeFallback(String trackUri, String reason) {
        if(!trackUri.equals(requestedTrackUri)) return;
        npvLyricsLoading = false;
        nativeLyricsFallback = true;
        XposedBridge.log("[SpotifyPlus][NPV Lyrics] Using Spotify lyrics fallback: " + reason);
        mainHandler.post(() -> {
            if(lyricContainer != null && npvLoadingView != null) { lyricContainer.removeView(npvLoadingView); npvLoadingView = null; }
            restoreNpvLineModels();
            refreshNativeLyrics();
        });
    }

    private Field[] getLineColorFields(Class<?> modelClass) {
        if(lineModelClass == modelClass && lineColorFields != null) return lineColorFields;
        List<Field> colors = new ArrayList<>();
        for(Field field : modelClass.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
            field.setAccessible(true);
            colors.add(field);
        }
        lineModelClass = modelClass;
        lineColorFields = colors.toArray(new Field[0]);
        return lineColorFields;
    }

    private void refreshNativeLyrics() {
        Activity activity = References.currentActivity;
        invalidateNpvRecomposeScopes();
        if(activity != null) activity.getWindow().getDecorView().invalidate();
    }

    private List<TimedLine> parseTimedLines(SyllableSyncedLyrics lyrics) {
        List<TimedLine> result = new ArrayList<>();
        if(lyrics == null || lyrics.content == null) return result;
        for(Object content : lyrics.content) {
            try {
                SyllableVocalSet set = gson.fromJson(gson.toJsonTree(content), SyllableVocalSet.class);
                if(set == null || set.lead == null || set.lead.syllables == null || set.lead.syllables.isEmpty()) continue;
                TimedLine line = TimedLine.create(set.lead.syllables, set.oppositeAligned);
                if(line != null) result.add(line);
            } catch(Throwable t) {
                XposedBridge.log(t);
            }
        }
        result.sort((left, right) -> Double.compare(left.startTime, right.startTime));
        result = dedupConsecutive(result);
        for(int i = 0; i < result.size(); i++) result.get(i).displayEndTime = i + 1 < result.size() ? result.get(i + 1).startTime : result.get(i).endTime + 0.5d;
        return result;
    }

    private void updateFrame(long frameTimeNanos) {
        if(!isNowPlayingViewVisible()) {
            removeOverlay();
            return;
        }
        Activity activity = References.currentActivity;
        if(BeautifulLyricsHook.isOverlayAttached(activity) || BeautifulLyricsHook.isEmbeddedShowing()) {
            if(lyricContainer != null) lyricContainer.setVisibility(View.INVISIBLE);
            lastAnimationFrameNanos = 0;
            return;
        }
        ensureTrackAndLyrics();
        if(hasLoadedCustomLyrics()) npvLyricsLoading = false;
        if(timedLines.isEmpty() || !loadedTrackUriEqualsRequested()) {
            if(npvLyricsLoading && !nativeLyricsFallback) {
                if(positionOverlay() && lyricContainer != null) {
                    if(npvLoadingView == null || npvLoadingView.getParent() != lyricContainer) {
                        lyricContainer.removeAllViews();
                        npvLoadingView = new TextView(lyricContainer.getContext());
                        npvLoadingView.setText("Loading Lyrics");
                        npvLoadingView.setTextSize(16f);
                        npvLoadingView.setTextColor(Color.WHITE);
                        npvLoadingView.setClickable(false);
                        lyricContainer.addView(npvLoadingView);
                    }
                    lyricContainer.setVisibility(View.VISIBLE);
                }
            } else if(lyricContainer != null && npvLoadingView != null) {
                lyricContainer.removeView(npvLoadingView); npvLoadingView = null;
            }
            return;
        }
        long position = getPlaybackPosition();
        if(position < 0) return;
        TimedLine line = findLine(position / 1000d);
        if(line == null) {
            // Lyric loaded but no speech right now (instrumental / gap):
            // show "..." instead of an empty strip. This is not loading:
            // loading only shows while npvLyricsLoading is true (above).
            boolean positioned = positionOverlay();
            if(positioned && lyricContainer != null) {
                if(npvGapView == null || npvGapView.getParent() != lyricContainer) {
                    lyricContainer.removeAllViews();
                    npvLoadingView = null;
                    GradientTextView gap = new GradientTextView(lyricContainer.getContext());
                    boolean gapThemed = ThemeHook.isThemeEnabled();
                    int gapBase = gapThemed ? ThemeHook.getTextColor() : Color.WHITE;
                    int gapR = Color.red(gapBase), gapG = Color.green(gapBase), gapB = Color.blue(gapBase);
                    int gapCol = Color.argb(255, gapR, gapG, gapB);
                    gap.setText("...");
                    gap.setTextSize(16f);
                    gap.setTypeface(References.beautifulFont.get());
                    gap.setTextColor(gapCol);
                    gap.setGradientColors(new int[]{gapCol, Color.argb(120, gapR, gapG, gapB)});
                    gap.setLineState(true);
                    gap.setClickable(false);
                    npvGapView = gap;
                    lyricContainer.addView(npvGapView);
                }
                lyricContainer.setVisibility(View.VISIBLE);
                displayedLine = null; activeVocals = null;
            } else if(lyricContainer != null) {
                lyricContainer.setVisibility(View.INVISIBLE);
            }
            lastAnimationFrameNanos = 0;
            return;
        }
        if(npvGapView != null) npvGapView = null;
        boolean positioned = positionOverlay();
        if(lyricContainer == null || overlayHost == null || !positioned) return;
        if(displayedLine != null && normalizeLineText(displayedLine).equals(normalizeLineText(line))) line = displayedLine;
        boolean lineChanged = displayedLine != line;
        if(lineChanged) {
            npvGapView = null;
            npvLoadingView = null; npvLyricsLoading = false;
            lyricContainer.removeAllViews();
            lyricContainer.setJustifyContent(line.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
            activeVocals = new SyllableVocals(lyricContainer, line.syllables, false, false, line.oppositeAligned, References.currentActivity, 16);
            displayedLine = line;
            lastAnimationFrameNanos = 0;
            // The frame's first positionOverlay() ran while the container
            // still held the "Loading Lyrics" view, so the lyric views added
            // here were never laid out with the real lyric geometry: on a
            // static cover the lyric kept the Loading frame's position
            // instead of the title-anchored one. Re-position now, with the
            // real children in place, so the first lyric frame is already
            // at the correct static position. Re-applying the same geometry
            // changes nothing for video (same left/top/width, only padding
            // differs, and that is re-applied identically too).
            positionOverlay();
        }
        double deltaTime = lastAnimationFrameNanos == 0 ? 0d : Math.min((frameTimeNanos - lastAnimationFrameNanos) / 1000000000d, 0.1d);
        lastAnimationFrameNanos = frameTimeNanos;
        if(activeVocals != null) activeVocals.animate(position / 1000d, deltaTime, lineChanged);
        lyricContainer.setVisibility(View.VISIBLE);
    }

    private boolean loadedTrackUriEqualsRequested() {
        return loadedTrackUri != null && loadedTrackUri.equals(requestedTrackUri);
    }

    private TimedLine findLine(double timestamp) {
        List<TimedLine> lines = timedLines;
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while(low <= high) {
            int middle = (low + high) >>> 1;
            if(lines.get(middle).startTime <= timestamp) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if(found < 0) return null;
        TimedLine line = lines.get(found);
        return timestamp <= line.displayEndTime ? line : null;
    }

    private long getPlaybackPosition() {
        long position = playbackPositionMs;
        if(position < 0) return -1;
        if(playbackAdvancing) position += (long) ((SystemClock.elapsedRealtime() - playbackUpdateElapsed) * playbackSpeed);
        return Math.max(0, position);
    }

    private boolean hasLoadedCustomLyrics() {
        return !timedLines.isEmpty() && loadedTrackUriEqualsRequested();
    }

    private boolean isNowPlayingActivity(Activity activity) {
        return activity != null && NOW_PLAYING_ACTIVITY.equals(activity.getClass().getName());
    }

    private boolean isNowPlayingViewVisible() {
        // Spotify Style: native lyrics untouched, no gradient overlay, no hiding native lines.
        if(References.currentActivity != null && LyricsProviders.isSpotifyStyle(References.currentActivity)) return false;
        if(!nowPlayingActivityResumed) return false;
        Activity activity = nowPlayingActivity.get();
        if(!isNowPlayingActivity(activity) || activity.isFinishing() || activity.isDestroyed()) return false;
        View decor = activity.getWindow().getDecorView();
        if(!decor.isAttachedToWindow() || !decor.isShown() || decor.getWindowVisibility() != View.VISIBLE) return false;
        // A native fallback must not gate resolution: with no native lyrics
        // the renderer never runs, nowPlayingLyricsVisible stays false, and
        // ensureTrack then returns before ever resetting the fallback —
        // a deadlock broken only by the lyrics button, which uses its own
        // flow. Rendering is still safe: updateFrame shows nothing when
        // timedLines is empty, and native lines are only hidden once custom
        // lyrics are actually loaded.
        return true;
    }

    private boolean positionOverlay() {
        Activity activity = References.currentActivity;
        if(activity == null) return false;
        Anchor anchor = findTitleAnchor(activity);
        if(anchor == null) {
            if(lyricContainer != null) lyricContainer.setVisibility(View.INVISIBLE);
            if(lyricClickCatcher != null) lyricClickCatcher.setVisibility(View.GONE);
            return false;
        }
        ensureOverlay(anchor.host);
        int[] hostLocation = new int[2];
        anchor.host.getLocationOnScreen(hostLocation);
        int left = Math.max(0, anchor.bounds.left - hostLocation[0]);
        int top = Math.max(0, anchor.bounds.top - hostLocation[1]);
        int right = Math.min(anchor.host.getWidth(), anchor.bounds.right - hostLocation[0]);
        int bottom = Math.min(anchor.host.getHeight(), anchor.bounds.bottom - hostLocation[1]);
        // Video NPV: the title anchor is narrow/centred, so the lyric strip
        // used to be narrow too and a normal line wrapped in two. With a
        // video background, stretch the strip to almost the full host width
        // (the container's own 16dp side padding stays), so a normal line
        // fits on one line. Static-cover NPV keeps the anchor width, which
        // already works there. Top/height are unchanged, so controls stay clear.
        boolean videoBg = isVideoBackground(activity, anchor.host);
        if (videoBg) {
            left = 0;
            right = anchor.host.getWidth();
        }
        // Left-align the lyric text with the track title: the strip already
        // starts at the title's left edge, but the container's 16dp left
        // padding used to push the text ~16dp to the right of the title
        // (visible on "Meu Sorriso", where the lyric sat right of the title
        // and over the cover's bottom edge). Non-video: 8dp left padding, so the line sits a little right of
        // the title's left edge (user request, NPV only). Video: the strip
        // starts at the screen edge, so it used to sit at 16dp while the
        // static lyric sits at the title margin (~24dp) + 8dp. Video only:
        // 32dp left padding puts the video lyric at the same screen x as
        // the static lyric. Static is untouched; the video strip itself
        // still spans the full host width, only its text starts further right.
        if (lyricContainer != null) {
            int side = Math.round(16f * activity.getResources().getDisplayMetrics().density);
            int leftPad = Math.round(8f * activity.getResources().getDisplayMetrics().density);
            int videoLeftPad = Math.round(32f * activity.getResources().getDisplayMetrics().density);
            lyricContainer.setPadding(videoBg ? videoLeftPad : leftPad, 0, side, 0);
        }
        if(right <= left || bottom <= top) return false;
        lyricContainer.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        lyricContainer.layout(left, top, right, bottom);
        updateLyricClickCatcher(anchor.host, left, top, right, bottom);
        return true;
    }

    // The lyric line itself lives in the host's ViewOverlay, which never
    // receives touches, so a tap on it did nothing. This real child view,
    // only over the lyric strip, opens the custom Beautiful Lyrics screen.
    private void updateLyricClickCatcher(ViewGroup host, int left, int top, int right, int bottom) {
        if(!(host instanceof FrameLayout)) return;
        if(lyricClickCatcher == null) {
            lyricClickCatcher = new View(host.getContext());
            lyricClickCatcher.setClickable(true);
            lyricClickCatcher.setFocusable(true);
            lyricClickCatcher.setOnClickListener(v -> {
                Activity act = nowPlayingActivity.get();
                if(act == null) act = References.currentActivity;
                if(act != null) BeautifulLyricsHook.showOverlay(act, true);
            });
            host.addView(lyricClickCatcher);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top);
        params.leftMargin = left;
        params.topMargin = top;
        lyricClickCatcher.setLayoutParams(params);
        lyricClickCatcher.setVisibility(View.VISIBLE);
    }

    private Anchor findTitleAnchor(Activity activity) {
        String title = normalize(requestedTrackTitle);
        if(title.isEmpty()) return null;
        View decor = activity.getWindow().getDecorView();
        if(!(decor instanceof ViewGroup)) return null;
        Rect titleBounds = findTitleTextViewBounds(decor, title);
        Anchor anchor = titleBounds == null ? findAnchor(decor, title) : new Anchor((ViewGroup) decor, titleBounds);
        if(anchor == null) return null;
        float density = activity.getResources().getDisplayMetrics().density;
        int spacing = Math.round(12f * density);
        int height = Math.round(40f * density);
        int[] hostLocation = new int[2];
        anchor.host.getLocationOnScreen(hostLocation);
        int horizontalInset = Math.max(0, anchor.bounds.left - hostLocation[0]);
        anchor.bounds.right = hostLocation[0] + anchor.host.getWidth() - horizontalInset;
        if(activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            anchor.bounds.top = anchor.bounds.bottom + Math.round(24f * density);
            anchor.bounds.bottom = anchor.bounds.top + height;
            return anchor;
        }

        Rect videoButtonBounds = getCachedSwitchToVideoButtonBounds(decor, title, anchor.bounds, density);
        if(videoButtonBounds != null) {
            int videoSpacing = Math.round(8f * density);
            int candidateBottom = videoButtonBounds.top - videoSpacing;
            int candidateTop = candidateBottom - height;
            if(candidateTop >= hostLocation[1]) {
                anchor.bounds.bottom = candidateBottom;
                anchor.bounds.top = candidateTop;
            } else {
                anchor.bounds.bottom = anchor.bounds.top - spacing;
                anchor.bounds.top = anchor.bounds.bottom - height;
            }
        } else {
            anchor.bounds.bottom = anchor.bounds.top - spacing;
            anchor.bounds.top = anchor.bounds.bottom - height;
        }
        return anchor;
    }

    private Rect getCachedSwitchToVideoButtonBounds(View decor, String title, Rect titleBounds, float density) {
        long now = SystemClock.elapsedRealtime();
        if(now - videoButtonBoundsCheckedAt < 1000) {
            return videoButtonBoundsCached;
        }
        videoButtonBoundsCheckedAt = now;
        videoButtonBoundsCached = findSwitchToVideoButtonBounds(decor, title, titleBounds, density);
        return videoButtonBoundsCached;
    }

    private boolean isSwitchToVideoText(CharSequence cs) {
        if(cs == null) return false;
        String s = normalize(cs.toString()).trim().toLowerCase(java.util.Locale.ROOT);
        if(s.isEmpty()) return false;
        return s.equals("mudar para video")
                || s.equals("mudar para vídeo")
                || s.equals("switch to video")
                || s.equals("watch video")
                || s.equals("assistir ao vídeo")
                || s.equals("assistir ao video")
                || s.equals("cambiar a video")
                || s.equals("cambiar a vídeo");
    }

    private boolean isValidVideoButtonBounds(Rect bounds, Rect titleBounds, float density) {
        if(bounds == null || titleBounds == null) return false;
        int minH = Math.round(18f * density);
        int maxH = Math.round(55f * density);
        int minW = Math.round(40f * density);
        if(bounds.height() < minH || bounds.height() > maxH || bounds.width() < minW) return false;
        int maxLookUp = Math.round(80f * density);
        int bottomTol = Math.round(8f * density);
        return bounds.bottom <= (titleBounds.top + bottomTol) && bounds.top >= (titleBounds.top - maxLookUp);
    }

    private Rect findSwitchToVideoButtonBounds(View view, String title, Rect titleBounds, float density) {
        if(view == null || view.getVisibility() != View.VISIBLE) return null;
        if(view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText() != null ? tv.getText().toString() : "";
            if(!text.isEmpty() && !title.equalsIgnoreCase(normalize(text))) {
                if(isSwitchToVideoText(text) || isSwitchToVideoText(tv.getContentDescription())) {
                    int[] loc = new int[2];
                    tv.getLocationOnScreen(loc);
                    Rect r = new Rect(loc[0], loc[1], loc[0] + tv.getWidth(), loc[1] + tv.getHeight());
                    if(isValidVideoButtonBounds(r, titleBounds, density)) return r;
                }
            }
        }
        CharSequence cd = view.getContentDescription();
        if(cd != null && !title.equalsIgnoreCase(normalize(cd.toString())) && isSwitchToVideoText(cd)) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
            if(isValidVideoButtonBounds(r, titleBounds, density)) return r;
        }
        try {
            if(view.getId() != View.NO_ID && view.getResources() != null) {
                String resName = view.getResources().getResourceEntryName(view.getId()).toLowerCase(java.util.Locale.ROOT);
                if(resName.contains("video_switch") || resName.contains("switch_to_video") || resName.contains("canvas_switch")) {
                    int[] loc = new int[2];
                    view.getLocationOnScreen(loc);
                    Rect r = new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
                    if(isValidVideoButtonBounds(r, titleBounds, density)) return r;
                }
            }
        } catch(Throwable ignored) {}

        if(view instanceof ViewGroup) {
            if(view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")) {
                Rect composeBounds = findSwitchToVideoComposeBounds(view, title, titleBounds, density);
                if(composeBounds != null) return composeBounds;
            }
            ViewGroup group = (ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++) {
                Rect bounds = findSwitchToVideoButtonBounds(group.getChildAt(i), title, titleBounds, density);
                if(bounds != null) return bounds;
            }
        }
        return null;
    }

    private Rect findSwitchToVideoComposeBounds(View composeView, String title, Rect titleBounds, float density) {
        try {
            AccessibilityNodeProvider provider = composeView.getAccessibilityNodeProvider();
            if(provider != null) {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
                if(root != null) {
                    try {
                        return findSwitchToVideoNodeBounds(provider, root, title, titleBounds, density, new int[]{0});
                    } finally {
                        root.recycle();
                    }
                }
            }
        } catch(Throwable ignored) {}
        return null;
    }

    private Rect findSwitchToVideoNodeBounds(AccessibilityNodeProvider provider, AccessibilityNodeInfo node, String title, Rect titleBounds, float density, int[] visited) {
        if(visited[0]++ > 512) return null;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if(text != null && !title.equalsIgnoreCase(normalize(text.toString())) && isSwitchToVideoText(text)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if(isValidVideoButtonBounds(bounds, titleBounds, density)) return bounds;
        }
        if(desc != null && !title.equalsIgnoreCase(normalize(desc.toString())) && isSwitchToVideoText(desc)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if(isValidVideoButtonBounds(bounds, titleBounds, density)) return bounds;
        }
        for(int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if(child == null) {
                    long childId = ((Number) XposedHelpers.callMethod(node, "getChildId", i)).longValue();
                    child = provider.createAccessibilityNodeInfo((int) (childId >> 32));
                }
                if(child == null) continue;
                Rect bounds = findSwitchToVideoNodeBounds(provider, child, title, titleBounds, density, visited);
                if(bounds != null) return bounds;
            } catch(Throwable ignored) {
            } finally {
                if(child != null) child.recycle();
            }
        }
        return null;
    }

    private Rect findTitleTextViewBounds(View view, String title) {
        Rect[] lowest = new Rect[1];
        findTitleTextViewBounds(view, title, view.getResources().getDisplayMetrics().widthPixels, view.getResources().getDisplayMetrics().heightPixels, lowest);
        return lowest[0];
    }

    private void findTitleTextViewBounds(View view, String title, int screenWidth, int screenHeight, Rect[] lowest) {
        if(view.getVisibility() != View.VISIBLE) return;
        if(view instanceof TextView && normalize(((TextView) view).getText().toString()).equals(title)) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            Rect bounds = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
            if(bounds.right > 0 && bounds.left < screenWidth && bounds.bottom > 0 && bounds.top < screenHeight && (lowest[0] == null || bounds.top > lowest[0].top)) lowest[0] = bounds;
        }
        if(view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++) findTitleTextViewBounds(group.getChildAt(i), title, screenWidth, screenHeight, lowest);
        }
    }

    private boolean isVideoBackground(Activity activity, ViewGroup host) {
        long now = SystemClock.elapsedRealtime();
        if (host == videoBackgroundHost && now - videoBackgroundCheckedAt < 1000) return videoBackgroundCached;
        videoBackgroundHost = host;
        videoBackgroundCheckedAt = now;
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        videoBackgroundCached = findLargeVideoView(host, sw, sh);
        return videoBackgroundCached;
    }

    private boolean findLargeVideoView(View v, int sw, int sh) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        if ((v instanceof SurfaceView || v instanceof TextureView) && v.getWidth() >= sw / 2 && v.getHeight() >= sh / 2) return true;
        String cn = v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (cn.contains("video") && v.getWidth() >= sw / 2 && v.getHeight() >= sh / 2) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (findLargeVideoView(g.getChildAt(i), sw, sh)) return true;
        }
        return false;
    }

    private void ensureOverlay(ViewGroup host) {
        if(overlayHost == host && lyricContainer != null) return;
        removeOverlay();
        overlayHost = host;
        lyricContainer = new FlexboxLayout(host.getContext().getApplicationContext());
        lyricContainer.setClickable(false);
        lyricContainer.setFocusable(false);
        lyricContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lyricContainer.setClipChildren(false);
        lyricContainer.setClipToPadding(false);
        int sidePadding = Math.round(16f * host.getResources().getDisplayMetrics().density);
        lyricContainer.setPadding(sidePadding, 0, sidePadding, 0);
        lyricContainer.setFlexWrap(FlexWrap.WRAP);
        lyricContainer.setJustifyContent(JustifyContent.FLEX_START);
        host.getOverlay().add(lyricContainer);
    }

    private Anchor findAnchor(View view, String target) {
        Anchor best = null;
        if(view instanceof ViewGroup && view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")) {
            Rect bounds = findTextBounds(view, target);
            if(bounds != null) best = new Anchor((ViewGroup) view, bounds);
        }
        if(view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++) {
                Anchor anchor = findAnchor(group.getChildAt(i), target);
                if(anchor != null && (best == null || anchor.bounds.top > best.bounds.top)) best = anchor;
            }
        }
        return best;
    }

    private Rect findTextBounds(View composeView, String target) {
        try {
            AccessibilityNodeProvider provider = composeView.getAccessibilityNodeProvider();
            if(provider != null) {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
                if(root != null) {
                    try {
                        Rect bounds = findTextBounds(provider, root, target, new int[] { 0 });
                        if(bounds != null) return bounds;
                    } finally {
                        root.recycle();
                    }
                }
            }
        } catch(Throwable ignored) {
        }
        return null;
    }

    private Rect findTextBounds(AccessibilityNodeProvider provider, AccessibilityNodeInfo node, String target, int[] visited) {
        if(visited[0]++ > 512) return null;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if(!target.isEmpty() && (text != null && normalize(text.toString()).equals(target) || description != null && normalize(description.toString()).equals(target))) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds;
        }
        for(int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if(child == null) {
                    long childId = ((Number) XposedHelpers.callMethod(node, "getChildId", i)).longValue();
                    child = provider.createAccessibilityNodeInfo((int) (childId >> 32));
                }
                if(child == null) continue;
                Rect bounds = findTextBounds(provider, child, target, visited);
                if(bounds != null) return bounds;
            } catch(Throwable ignored) {
            } finally {
                if(child != null) child.recycle();
            }
        }
        return null;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private void postFrame() {
        postFrame(0);
    }

    private void postFrame(long delay) {
        if(framePosted) return;
        framePosted = true;
        choreographer.postFrameCallbackDelayed(frameCallback, delay);
    }

    private void removeOverlay() {
        framePosted = false;
        choreographer.removeFrameCallback(frameCallback);
        if(overlayHost != null && lyricContainer != null) overlayHost.getOverlay().remove(lyricContainer);
        if(lyricClickCatcher != null) {
            ViewGroup parent = (ViewGroup) lyricClickCatcher.getParent();
            if(parent != null) parent.removeView(lyricClickCatcher);
            lyricClickCatcher = null;
        }
        lyricContainer = null;
        activeVocals = null;
        displayedLine = null;
        overlayHost = null;
        lastAnimationFrameNanos = 0;
    }

    private static final class NpvLineColors {
        final WeakReference<Object> model;
        final long[] colors;

        NpvLineColors(Object model, long[] colors) {
            this.model = new WeakReference<>(model);
            this.colors = colors;
        }
    }

    private static final class Anchor {
        final ViewGroup host;
        final Rect bounds;

        Anchor(ViewGroup host, Rect bounds) {
            this.host = host;
            this.bounds = bounds;
        }
    }

    private static final class TimedLine {
        final List<SyllableMetadata> syllables;
        final double startTime;
        final double endTime;
        final boolean oppositeAligned;
        double displayEndTime;

        TimedLine(List<SyllableMetadata> syllables, double startTime, double endTime, boolean oppositeAligned) {
            this.syllables = syllables;
            this.startTime = startTime;
            this.endTime = endTime;
            this.oppositeAligned = oppositeAligned;
            this.displayEndTime = endTime;
        }

        static TimedLine create(List<SyllableMetadata> source, boolean oppositeAligned) {
            List<SyllableMetadata> syllables = new ArrayList<>();
            for(SyllableMetadata syllable : source) {
                if(syllable == null || TextUtils.isEmpty(syllable.text)) continue;
                syllables.add(syllable);
            }
            if(syllables.isEmpty()) return null;
            SyllableMetadata first = syllables.get(0);
            SyllableMetadata last = syllables.get(syllables.size() - 1);
            return new TimedLine(Collections.unmodifiableList(syllables), first.startTime, last.endTime, oppositeAligned);
        }
    }
}
