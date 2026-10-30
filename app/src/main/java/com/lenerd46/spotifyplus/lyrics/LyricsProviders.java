package com.lenerd46.spotifyplus.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.LineSyncedLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.LineVocal;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.ProviderLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.StaticSyncedLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableMetadata;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableSyncedLyrics;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableVocal;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.SyllableVocalSet;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.TextMetadata;

import de.robv.android.xposed.XposedBridge;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

/**
 * Lyrics providers ported (simplified, Java + OkHttp) from BitChord's
 * LyricsSource/LyricsRepository. Order is BitChord's LyricsSource.offered
 * order. Hidden BitChord sources (SIMP_MUSIC, MEGALOBIZ) and the YouTube
 * sources (no videoId inside Spotify) are excluded on purpose.
 *
 * Cascade rule: Spotify's own lyrics are tried first by the caller. Only
 * when Spotify has none, providers are tried in this order, first hit wins.
 * With syllableOnly=true (Now Playing gradient) a line-only hit is skipped
 * and the search continues for a syllable hit.
 *
 * No API keys are hardcoded. PaxSenix variants are skipped when the user
 * has not typed a key in the Lyrics Providers screen. Musixmatch needs a
 * rotating signing key that BitChord scrapes; without it the public API
 * call is attempted as-is and usually returns nothing — it is kept in the
 * list for parity, not silently dropped.
 */
public final class LyricsProviders {
    private LyricsProviders() {}

    public enum Provider {
        LRC_RED("lrc.red", "Apple timings by recording, syllable by syllable", true, false),
        BINI_LYRICS("BiniLyrics", "The same Apple timings, matched on the recording itself", true, false),
        BETTER_LYRICS("BetterLyrics", "Apple Music timings, word by word", true, false),
        BETTER_LYRICS_PORTATO("BetterLyrics Portato", "QQ Music karaoke timings through BetterLyrics", true, false),
        PAXSENIX("PaxSenix", "Apple Music timings through PaxSenix; API key required", true, true),
        PAXSENIX_SPOTIFY("PaxSenix: Spotify", "Spotify lyrics via PaxSenix; API key required", false, true),
        PAXSENIX_MUSIXMATCH("PaxSenix: Musixmatch", "Musixmatch timings via PaxSenix; API key required", true, true),
        LYRICS_PLUS("LyricsPlus", "Syllable by syllable, on community mirrors", true, false),
        UNISON("Unison", "Contributed by listeners, so it has what nobody licensed", true, false),
        KUGOU("KuGou", "Whole lines, strong outside the English catalogue", false, false),
        LRCLIB("LRCLIB", "Whole lines only, and always up", false, false),
        MUSIXMATCH("Musixmatch", "Whole lines, from the biggest lyrics database there is", false, false),
        GENIUS("Genius", "Plain text fallback, massive web catalogue", false, false);

        public final String label;
        public final String detail;
        public final boolean wordSynced;
        public final boolean requiresKey;

        Provider(String label, String detail, boolean wordSynced, boolean requiresKey) {
            this.label = label;
            this.detail = detail;
            this.wordSynced = wordSynced;
            this.requiresKey = requiresKey;
        }

        public String prefKey() { return "lyrics_provider_" + name(); }
    }

    public static final class TimedWord {
        public final String text; public final double start; public final double end; public final boolean partOfWord;
        public TimedWord(String text, double start, double end, boolean partOfWord) {
            this.text = text; this.start = start; this.end = end; this.partOfWord = partOfWord;
        }
    }

    public static final class TimedLine {
        public final double start; public final double end; public final String text;
        public final List<TimedWord> words; public final boolean syllable;
        /** True when the line came from a source with no timing at all (e.g. TTML whose
         *  <p> elements carry no begin/end). Such lines render as static text only. */
        public final boolean untimed;
        public TimedLine(double start, double end, String text, List<TimedWord> words, boolean syllable) {
            this(start, end, text, words, syllable, false);
        }
        public TimedLine(double start, double end, String text, List<TimedWord> words, boolean syllable, boolean untimed) {
            this.start = start; this.end = end; this.text = text; this.words = words; this.syllable = syllable; this.untimed = untimed;
        }
    }

    public static final class Result {
        public final Provider source; public final List<TimedLine> lines; public final boolean syllable;
        public Result(Provider source, List<TimedLine> lines, boolean syllable) {
            this.source = source; this.lines = lines; this.syllable = syllable;
        }
        public boolean isSyllable() { return syllable && lines.stream().anyMatch(l -> !l.words.isEmpty()); }
        /** True when every line is untimed: usable as static text, never as synced lyrics. */
        public boolean isUntimed() { return !lines.isEmpty() && lines.stream().allMatch(l -> l.untimed); }
        public ProviderLyrics toProviderLyrics() {
            ProviderLyrics out = new ProviderLyrics();
            if (isSyllable()) {
                SyllableSyncedLyrics s = new SyllableSyncedLyrics();
                s.startTime = lines.get(0).start; s.endTime = lines.get(lines.size() - 1).end;
                List<Object> content = new ArrayList<>();
                for (TimedLine line : lines) {
                    SyllableVocalSet set = new SyllableVocalSet();
                    SyllableVocal lead = new SyllableVocal();
                    lead.startTime = line.start; lead.endTime = line.end;
                    List<SyllableMetadata> syl = new ArrayList<>();
                    for (TimedWord w : line.words) {
                        SyllableMetadata m = new SyllableMetadata();
                        m.text = w.text; m.startTime = w.start; m.endTime = w.end; m.isPartOfWord = w.partOfWord;
                        syl.add(m);
                    }
                    lead.syllables = syl; set.lead = lead; set.background = new ArrayList<>();
                    content.add(set);
                }
                s.content = content; out.syllableLyrics = s;
            } else if (lines.stream().anyMatch(l -> l.start > 0 || l.end > 0)) {
                LineSyncedLyrics l = new LineSyncedLyrics();
                l.startTime = lines.get(0).start; l.endTime = lines.get(lines.size() - 1).end;
                List<Object> content = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    TimedLine line = lines.get(i);
                    // Same rule as parseLrc/NPV displayEnd: a line with no
                    // usable end lasts until the next line starts, else 3s.
                    double end = line.end > line.start ? line.end
                            : (i + 1 < lines.size() ? lines.get(i + 1).start : line.start + 3.0);
                    // KuGou LRC can repeat a stamp, so next-start may equal
                    // this start: a zero-length line never stays ACTIVE in
                    // the big screen and never triggers its scroll.
                    if (end <= line.start) end = (i + 1 < lines.size() && lines.get(i + 1).start > line.start)
                            ? lines.get(i + 1).start : line.start + 3.0;
                    if (end <= line.start) end = line.start + 3.0;
                    LineVocal v = new LineVocal();
                    v.text = line.text; v.startTime = line.start; v.endTime = end;
                    content.add(v);
                }
                l.content = content; out.lineLyrics = l;
            } else {
                StaticSyncedLyrics s = new StaticSyncedLyrics();
                List<TextMetadata> ls = new ArrayList<>();
                for (TimedLine line : lines) { TextMetadata t = new TextMetadata(); t.text = line.text; ls.add(t); }
                s.lines = ls; out.staticLyrics = s;
            }
            return out;
        }
    }

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 SpotifyPlus/0.7.4";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().addInterceptor(chain -> { Request orig = chain.request(); Request.Builder req = orig.newBuilder(); if (orig.header("User-Agent") == null) req.header("User-Agent", DEFAULT_USER_AGENT); return chain.proceed(req.build()); })
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();
    private static final Gson GSON = new Gson();

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, Provider p) {
        return prefs(ctx).getBoolean(p.prefKey(), true);
    }

    public static void setEnabled(Context ctx, Provider p, boolean enabled) {
        prefs(ctx).edit().putBoolean(p.prefKey(), enabled).apply();
    }

    /** "spotify" = native Spotify lyrics untouched; anything else = modded (default). */
    public static boolean isSpotifyStyle(Context ctx) {
        if (ctx == null) return false;
        return "spotify".equals(prefs(ctx).getString("lyrics_style_mode", "modded"));
    }

    public static String paxSenixKey(Context ctx) {
        String k = prefs(ctx).getString("lyrics_paxsenix_api_key", "");
        if (k == null) return "";
        if (k.toLowerCase().startsWith("bearer ")) k = k.substring(7).trim();
        return k.trim();
    }

    /** First enabled provider with a usable hit, in BitChord order. Null = none. */
    public static Result fetch(Context ctx, String title, String artist, String album, long durationMs, boolean syllableOnly) {
        return fetch(ctx, title, artist, album, durationMs, syllableOnly, false);
    }

    /**
     * Same as {@link #fetch(Context, String, String, String, long, boolean)}, but with
     * skipUntimed=true the Now Playing view skips untimed hits (TTML without begin/end,
     * plain text) and keeps searching: it cannot sync them, so an untimed hit must not
     * end the search before a timed provider (e.g. KuGou) is tried. The fullscreen
     * overlay calls with skipUntimed=false and renders untimed hits as static text.
     */
    /** Normalized title for matching when no duration is available: case/space/punctuation ignored. */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    public static Result fetch(Context ctx, String title, String artist, String album, long durationMs, boolean syllableOnly, boolean skipUntimed) {
        if (title == null || title.isBlank()) return null;
        if (artist == null) artist = "";
        // Entry log: without this, a device log that shows no provider line
        // cannot tell "providers were never called" from "providers were
        // called and all missed". This line proves the call itself.
        XposedBridge.log("[SpotifyPlus][LyricsProviders] fetch title=" + title + " artist=" + artist
                + " durationMs=" + durationMs + " syllableOnly=" + syllableOnly + " skipUntimed=" + skipUntimed);
        if (durationMs <= 0) XposedBridge.log("[SpotifyPlus][LyricsProviders] Track duration unknown (0): providers will not filter by duration");
        List<Provider> enabled = new ArrayList<>();
        for (Provider p : Provider.values()) {
            if (!isEnabled(ctx, p)) continue;
            if (p.requiresKey && paxSenixKey(ctx).isEmpty()) {
                XposedBridge.log("[SpotifyPlus][LyricsProviders] Skipping " + p.name() + ": no PaxSenix API key");
                continue;
            }
            enabled.add(p);
        }
        if (enabled.isEmpty()) return null;
        // All enabled providers at once, in list order. The winner is the
        // first hit in that order, not the first to finish: results are
        // consumed strictly top to bottom, so a slower lower provider can
        // never beat a higher one, and as soon as the winner is known the
        // rest are cancelled/ignored instead of being waited for.
        ExecutorService exec = Executors.newFixedThreadPool(enabled.size(), r -> {
            Thread th = new Thread(r);
            th.setDaemon(true);
            th.setName("LyricsProviders");
            return th;
        });
        final String queryTitle = title;
        final String queryArtist = artist;
        final String queryAlbum = album;
        final long queryDurationMs = durationMs;
        List<Future<Result>> futures = new ArrayList<>();
        for (Provider p : enabled) {
            final Provider provider = p;
            futures.add(exec.submit(() -> {
                try {
                    return fetchOne(ctx, provider, queryTitle, queryArtist, queryAlbum, queryDurationMs);
                } catch (Throwable th) {
                    XposedBridge.log("[SpotifyPlus][LyricsProviders] " + provider.name() + " failed: " + th);
                    return null;
                }
            }));
        }
        try {
            for (int i = 0; i < enabled.size(); i++) {
                Provider p = enabled.get(i);
                Result r;
                try {
                    r = futures.get(i).get();
                } catch (Throwable th) {
                    XposedBridge.log("[SpotifyPlus][LyricsProviders] " + p.name() + " failed: " + th);
                    continue;
                }
                if (r == null || r.lines.isEmpty()) {
                    XposedBridge.log("[SpotifyPlus][LyricsProviders] " + p.name() + " miss (no lines)");
                    continue;
                }
                if (skipUntimed && (r.isUntimed() || isStaticResult(r))) {
                    XposedBridge.log("[SpotifyPlus][LyricsProviders] " + p.name() + " returned " + r.lines.size()
                            + " untimed lines, skipped for NPV");
                    continue;
                }
                if (syllableOnly && !r.isSyllable()) {
                    XposedBridge.log("[SpotifyPlus][LyricsProviders] " + p.name() + " returned " + r.lines.size()
                            + " line-only lines, continuing for syllable");
                    continue;
                }
                XposedBridge.log("[SpotifyPlus][LyricsProviders] " + p.name() + " hit: " + r.lines.size() + " lines"
                        + (r.isSyllable() ? " (syllable) " : r.isUntimed() ? " (static, untimed) " : " (line) "));
                return r;
            }
        } finally {
            for (Future<Result> f : futures) f.cancel(true);
            exec.shutdownNow();
        }
        return null;
    }

    /** A hit with no timed line at all: toProviderLyrics() renders it as static text, which can never sync. Timed means syllable lyrics or line lyrics (any line with start/end > 0). */
    private static boolean isStaticResult(Result r) {
        try {
            ProviderLyrics p = r.toProviderLyrics();
            return p.syllableLyrics == null && p.lineLyrics == null;
        } catch (Throwable ignored) { return false; }
    }

    private static Result fetchOne(Context ctx, Provider p, String title, String artist, String album, long durationMs) throws Exception {
        switch (p) {
            case LRC_RED: return lrcRed(title, artist, durationMs);
            case BINI_LYRICS: return bini(title, artist, album, durationMs);
            case BETTER_LYRICS: return better(title, artist, album, durationMs, "https://lyrics-api.boidu.dev/getLyrics");
            case BETTER_LYRICS_PORTATO: return better(title, artist, album, durationMs, "https://lyrics-api.boidu.dev/qq/getLyrics");
            case PAXSENIX: case PAXSENIX_SPOTIFY: case PAXSENIX_MUSIXMATCH: return paxSenix(ctx, title, artist);
            case LYRICS_PLUS: return lyricsPlus(title, artist, album, durationMs);
            case UNISON: return unison(title, artist, album, durationMs);
            case KUGOU: return kugou(title, artist, album, durationMs);
            case LRCLIB: return lrclib(title, artist, durationMs);
            case MUSIXMATCH: return musixmatch(title, artist, durationMs);
            case GENIUS: return genius(title, artist);
            default: return null;
        }
    }

    // ---------- HTTP helpers ----------
    private static String get(String url) throws Exception {
        return get(url, null, null);
    }
    private static String get(String url, String header, String value) throws Exception {
        Request.Builder b = new Request.Builder().url(url);
        if (header != null) b.header(header, value);
        try (Response r = CLIENT.newCall(b.build()).execute()) {
            if (!r.isSuccessful()) { XposedBridge.log("[SpotifyPlus][LyricsProviders] HTTP " + r.code() + " for " + url); return null; }
            if (r.body() == null) return null;
            return r.body().string();
        }
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }
    private static String str(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        }
        return null;
    }
    private static double num(JsonObject o, String key, double def) {
        try { JsonElement e = o.get(key); return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def; } catch (Exception e) { return def; }
    }
    private static boolean hasText(List<TimedLine> lines) {
        return lines != null && !lines.isEmpty() && lines.stream().anyMatch(l -> l.text != null && !l.text.isBlank());
    }

    // ---------- LRC ----------
    private static final Pattern LRC_STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{2,3}))?\\]");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:\\.(\\d{2,3}))?>");

    public static List<TimedLine> parseLrc(String lrc) {
        List<TimedLine> out = new ArrayList<>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[") && !LRC_STAMP.matcher(line).find()) continue;
            Matcher m = LRC_STAMP.matcher(line);
            List<double[]> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                double t = Integer.parseInt(m.group(1)) * 60.0 + Integer.parseInt(m.group(2))
                        + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)) / (m.group(3).length() == 2 ? 100.0 : 1000.0));
                stamps.add(new double[]{t}); lastEnd = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(lastEnd).trim();
            // Enhanced LRC: word stamps inside the body.
            Matcher wm = WORD_STAMP.matcher(body);
            List<TimedWord> words = new ArrayList<>();
            List<String> wordTexts = new ArrayList<>(); List<Double> wordTimes = new ArrayList<>();
            while (wm.find()) {
                double t = Integer.parseInt(wm.group(1)) * 60.0 + Integer.parseInt(wm.group(2))
                        + (wm.group(3) == null ? 0 : Integer.parseInt(wm.group(3)) / (wm.group(3).length() == 2 ? 100.0 : 1000.0));
                wordTimes.add(t);
            }
            if (!wordTimes.isEmpty()) {
                String[] parts = WORD_STAMP.split(body);
                // parts[0] is empty (body starts with a stamp); following parts are words.
                for (int i = 1; i < parts.length && i - 1 < wordTimes.size(); i++) {
                    String w = parts[i].trim();
                    if (w.isEmpty()) continue;
                    double s = wordTimes.get(i - 1);
                    double e = i < wordTimes.size() ? wordTimes.get(i) : s + 0.5;
                    words.add(new TimedWord(w, s, e, false));
                }
                if (!words.isEmpty()) {
                    out.add(new TimedLine(words.get(0).start, words.get(words.size() - 1).end,
                            words.stream().map(w -> w.text).reduce((a, b) -> a + " " + b).orElse(""), words, true));
                    continue;
                }
            }
            String text = body.replaceAll("<[^>]*>", "").trim();
            for (double[] s : stamps) out.add(new TimedLine(s[0], s[0], text, new ArrayList<>(), false));
        }
        out.sort((a, b) -> Double.compare(a.start, b.start));
        for (int i = 0; i < out.size(); i++) {
            TimedLine l = out.get(i);
            double end = i + 1 < out.size() ? out.get(i + 1).start : l.start + 3.0;
            if (!l.syllable) out.set(i, new TimedLine(l.start, end, l.text, l.words, false));
        }
        return out;
    }

    // ---------- TTML ----------
    public static List<TimedLine> parseTtml(String ttml) {
        List<TimedLine> out = new ArrayList<>();
        if (ttml == null || !ttml.contains("<p")) return out;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(ttml)));
            NodeList ps = doc.getElementsByTagName("p");
            for (int i = 0; i < ps.getLength(); i++) {
                Element p = (Element) ps.item(i);
                String role = p.getAttribute("ttm:agent");
                if (role.contains("translation") || role.contains("roman")) continue;
                double pStart = parseTime(p.getAttribute("begin"), -1);
                double pEnd = parseTime(p.getAttribute("end"), -1);
                List<TimedWord> words = new ArrayList<>();
                StringBuilder lineText = new StringBuilder();
                NodeList kids = p.getChildNodes();
                for (int k = 0; k < kids.getLength(); k++) {
                    Node kid = kids.item(k);
                    if (kid.getNodeType() == Node.ELEMENT_NODE && "span".equals(kid.getNodeName())) {
                        Element span = (Element) kid;
                        String txt = span.getTextContent();
                        if (txt == null || txt.isEmpty()) continue;
                        double s = parseTime(span.getAttribute("begin"), pStart);
                        double e = parseTime(span.getAttribute("end"), s + 0.3);
                        boolean nextIsSpan = k + 1 < kids.getLength() && kids.item(k + 1).getNodeType() == Node.ELEMENT_NODE;
                        words.add(new TimedWord(txt, s, e, nextIsSpan));
                        lineText.append(txt);
                    } else if (kid.getNodeType() == Node.TEXT_NODE) {
                        lineText.append(kid.getNodeValue());
                    }
                }
                String text = lineText.toString().trim();
                if (text.isEmpty()) continue;
                if (pStart < 0) {
                    // TTML with no timing at all (e.g. lrc.red/Bini with lrc:timing="None"):
                    // keep the lines as untimed text instead of dropping the whole hit.
                    // toProviderLyrics() renders them as static lyrics; NPV callers skip
                    // them via fetch(..., skipUntimed=true) and keep searching.
                    out.add(new TimedLine(0, 0, text, new ArrayList<>(), false, true));
                    continue;
                }
                boolean syllable = !words.isEmpty();
                out.add(new TimedLine(pStart, pEnd > pStart ? pEnd : pStart + 1.0, text, words, syllable));
            }
        } catch (Throwable ignored) {}
        out.sort((a, b) -> Double.compare(a.start, b.start));
        return out;
    }

    private static double parseTime(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try {
            s = s.trim();
            if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
            if (s.contains(":")) {
                String[] parts = s.split(":");
                double v = 0;
                for (String part : parts) v = v * 60 + Double.parseDouble(part);
                return v;
            }
            return Double.parseDouble(s);
        } catch (Exception e) { return def; }
    }

    /** TTML first, then LRC, then plain text (static). */
    public static List<TimedLine> parseGeneric(String raw) {
        if (raw == null) return new ArrayList<>();
        String body = raw.trim();
        // JSON envelope: pull a lyrics/ttml/lrc/content string out.
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                JsonElement el = GSON.fromJson(body, JsonElement.class);
                String extracted = extractLyricsString(el);
                if (extracted != null && !extracted.isBlank()) body = extracted.trim();
            } catch (Exception ignored) {}
        }
        body = body.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
        List<TimedLine> ttml = parseTtml(body);
        if (hasText(ttml)) return ttml;
        List<TimedLine> lrc = parseLrc(body);
        if (hasText(lrc)) return lrc;
        List<TimedLine> plain = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.matches("\\[.*")) plain.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
        }
        return plain;
    }

    private static String extractLyricsString(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return el.getAsString();
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String s = extractLyricsString(e);
                if (s != null && s.length() > 20) return s;
            }
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String k : new String[]{"ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "syncedLyrics", "plainLyrics"}) {
                JsonElement e = o.get(k);
                if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && !e.getAsString().isBlank()) return e.getAsString();
            }
            for (String k : new String[]{"data", "result", "response", "results"}) {
                String s = extractLyricsString(o.get(k));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static Result wrap(Provider p, List<TimedLine> lines) {
        if (!hasText(lines)) return null;
        boolean syl = lines.stream().anyMatch(l -> l.syllable && !l.words.isEmpty());
        return new Result(p, lines, syl);
    }

    // ---------- Providers ----------
    private static Result lrclib(String title, String artist, long durationMs) throws Exception {
        int sec = (int) (durationMs / 1000);
        String synced = null;
        // With no duration, the exact get (duration=0) never matches: skip it
        // and go straight to search, where title match beats duration order.
        if (sec > 0) {
            String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + "&duration=" + sec;
            String body = get(url, "User-Agent", "SpotifyPlus");
            if (body != null) {
                JsonObject o = GSON.fromJson(body, JsonObject.class);
                synced = str(o, "syncedLyrics");
            }
        }
        if (synced == null || synced.isBlank()) {
            String search = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist), "User-Agent", "SpotifyPlus");
            if (search != null) {
                JsonArray arr = GSON.fromJson(search, JsonArray.class);
                String want = norm(title);
                String firstSynced = null;
                double best = Double.MAX_VALUE;
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    String s = str(o, "syncedLyrics");
                    if (s == null || s.isBlank()) continue;
                    if (firstSynced == null) firstSynced = s;
                    if (sec <= 0) {
                        // No duration to rank by: first hit whose title matches wins.
                        if (want.equals(norm(str(o, "trackName")))) { synced = s; break; }
                    } else {
                        double d = Math.abs(num(o, "duration", 0) - sec);
                        if (d < best) { best = d; synced = s; }
                    }
                }
                if (synced == null) synced = firstSynced;
            }
        }
        return synced == null ? null : wrap(Provider.LRCLIB, parseLrc(synced));
    }

    private static Result bini(String title, String artist, String album, long durationMs) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse("https://lyrics-api.binimum.org/").newBuilder()
                .addQueryParameter("track", title).addQueryParameter("artist", artist);
        if (album != null && !album.isBlank()) b.addQueryParameter("album", album);
        if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
        String body = get(b.build().toString());
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray results = o.has("results") && o.get("results").isJsonArray() ? o.getAsJsonArray("results") : null;
        if (results == null || results.size() == 0) return null;
        // First result is not always the right track (e.g. an EP version of
        // the same title). Prefer a result whose title matches, else the first.
        String want = norm(title);
        JsonObject best = results.get(0).getAsJsonObject();
        for (JsonElement e : results) {
            JsonObject r = e.getAsJsonObject();
            if (want.equals(norm(str(r, "trackName")))) { best = r; break; }
        }
        String url = str(best, "lyricsUrl");
        if (url == null) return null;
        String ttml = get(url);
        return wrap(Provider.BINI_LYRICS, parseTtml(ttml));
    }

    /** First artist only: Spotify metadata joins artists with ", " or " & ",
     *  and lrc.red's text search misses on the joined string (proved live:
     *  "Nostalgia Slow GM" returns no hits while Bini finds the same track). */
    private static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",")[0].split("&")[0].split(" feat\\.")[0].split(" ft\\.")[0];
        return a.trim();
    }

    private static Result lrcRed(String title, String artist, long durationMs) throws Exception {
        // Query variants, in order: full artist, first artist only, title
        // only. The first variant that yields a timed TTML hit wins; a
        // variant that only yields unrelated titles is skipped by the
        // title-match check below (no-duration case) or the ±3s check.
        String[] queries = {title + " " + artist, title + " " + firstArtist(artist), title};
        String seen = "";
        for (String q : queries) {
            if (q == null || q.isBlank() || seen.contains("|" + q + "|")) continue;
            seen += "|" + q + "|";
            Result r = lrcRedQuery(q, title, durationMs);
            if (r != null) return r;
        }
        return null;
    }

    private static Result lrcRedQuery(String query, String title, long durationMs) throws Exception {
        String body = get("https://lrc.red/search.json?q=" + enc(query));
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray hits = o.has("hits") && o.get("hits").isJsonArray() ? o.getAsJsonArray("hits") : null;
        if (hits == null) return null;
        double sec = durationMs / 1000.0; String bestIsrc = null; double bestDist = Double.MAX_VALUE;
        String want = norm(title);
        String firstIsrc = null;
        for (JsonElement e : hits) {
            JsonObject h = e.getAsJsonObject();
            String isrc = str(h, "isrc");
            if (isrc == null) continue;
            String clean = isrc.replace("-", "").toUpperCase();
            if (firstIsrc == null) firstIsrc = clean;
            if (durationMs <= 0) {
                // Track duration is always 0 from the player state, so the
                // ±3s filter rejected every hit. Without a duration, the
                // first hit whose title matches wins, else the first hit.
                if (want.equals(norm(str(h, "title")))) { bestIsrc = clean; break; }
                continue;
            }
            double d = Math.abs(num(h, "duration", sec) - sec);
            if (d <= 6.0 && d < bestDist) { bestDist = d; bestIsrc = clean; }
        }
        // No-duration title-only queries must not accept an unrelated first
        // hit (e.g. "Nostalgia" alone returns other artists' tracks).
        if (bestIsrc == null) bestIsrc = durationMs <= 0 && query.trim().equals(title.trim()) ? null : (durationMs <= 0 ? firstIsrc : null);
        if (bestIsrc == null) return null;
        String ttml = get("https://lrc.red/s/" + bestIsrc + ".ttml");
        Result r = wrap(Provider.LRC_RED, parseTtml(ttml));
        // A hit whose TTML parses to nothing is a miss, not a hit: keep
        // trying the next query variant instead of ending the cascade here.
        return r;
    }

    private static Result better(String title, String artist, String album, long durationMs, String endpoint) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(endpoint).newBuilder()
                .addQueryParameter("s", title).addQueryParameter("a", artist);
        if (durationMs > 0) b.addQueryParameter("d", String.valueOf(durationMs / 1000));
        if (album != null && !album.isBlank()) b.addQueryParameter("al", album);
        String body = get(b.build().toString());
        if (body == null) return null;
        Provider p = endpoint.contains("/qq/") ? Provider.BETTER_LYRICS_PORTATO : Provider.BETTER_LYRICS;
        return wrap(p, parseGeneric(body));
    }

    private static Result paxSenix(Context ctx, String title, String artist) throws Exception {
        String key = paxSenixKey(ctx);
        String body = get("https://api.paxsenix.org/lyrics/lrcget?q=" + enc(title + " " + artist),
                "Authorization", "Bearer " + key);
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray docs = o.has("lyrics") && o.get("lyrics").isJsonArray() ? o.getAsJsonArray("lyrics") : null;
        if (docs != null) {
            for (JsonElement d : docs) {
                List<TimedLine> lines = parseGeneric(d.toString());
                if (hasText(lines)) return wrap(Provider.PAXSENIX, lines);
            }
        }
        return wrap(Provider.PAXSENIX, parseGeneric(body));
    }

    private static Result lyricsPlus(String title, String artist, String album, long durationMs) throws Exception {
        // binimum first: it answers live, prjktla.my.id returns 502 and the
        // vercel mirrors are deployment-disabled, so they only waste time.
        String[] mirrors = {"https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id",
                "https://lyricsplus.atomix.one", "https://lyricsplus.prjktla.workers.dev",
                "https://lyricsplus-seven.vercel.app", "https://lyrics-plus-backend.vercel.app"};
        for (String host : mirrors) {
            try {
                HttpUrl.Builder b = HttpUrl.parse(host + "/v2/lyrics/get").newBuilder()
                        .addQueryParameter("title", title).addQueryParameter("artist", artist);
                if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
                if (album != null && !album.isBlank()) b.addQueryParameter("album", album);
                String body = get(b.build().toString());
                if (body == null) continue;
                JsonObject o = GSON.fromJson(body, JsonObject.class);
                JsonArray arr = o.has("lyrics") && o.get("lyrics").isJsonArray() ? o.getAsJsonArray("lyrics") : null;
                if (arr == null) continue;
                List<TimedLine> lines = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject l = e.getAsJsonObject();
                    double start = num(l, "time", 0) / 1000.0;
                    double dur = num(l, "duration", 0) / 1000.0;
                    String text = str(l, "text");
                    List<TimedWord> words = new ArrayList<>();
                    if (l.has("syllabus") && l.get("syllabus").isJsonArray()) {
                        for (JsonElement s : l.getAsJsonArray("syllabus")) {
                            JsonObject so = s.getAsJsonObject();
                            String wt = str(so, "text");
                            if (wt == null) continue;
                            double ws = num(so, "time", 0) / 1000.0;
                            double wd = num(so, "duration", 0) / 1000.0;
                            words.add(new TimedWord(wt, ws, ws + wd, false));
                        }
                    }
                    if (text == null && !words.isEmpty()) text = words.stream().map(w -> w.text).reduce((x, y) -> x + " " + y).orElse("");
                    if (text == null || text.isBlank()) continue;
                    lines.add(new TimedLine(start, start + dur, text, words, !words.isEmpty()));
                }
                if (hasText(lines)) return wrap(Provider.LYRICS_PLUS, lines);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Result unison(String title, String artist, String album, long durationMs) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse("https://unison.boidu.dev/lyrics").newBuilder()
                .addQueryParameter("song", title).addQueryParameter("artist", artist);
        if (album != null && !album.isBlank()) b.addQueryParameter("album", album);
        if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
        String body = get(b.build().toString());
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        if (o.has("success") && !o.get("success").getAsBoolean()) return null;
        JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
        if (data == null) return null;
        String lyrics = str(data, "lyrics");
        if (lyrics == null) return null;
        String format = str(data, "format");
        List<TimedLine> lines = "ttml".equalsIgnoreCase(format) ? parseTtml(lyrics) : parseGeneric(lyrics);
        return wrap(Provider.UNISON, lines);
    }

    private static String stripParenthetical(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[(\\uFF08][^)\\uFF09]*[\\)\\uFF09]", "").trim();
        return out.isEmpty() ? s.trim() : out;
    }

    /** KuGou keyword, aligned with BitChord: title/artist without parentheticals, plus album. */
    private static String kugouKeyword(String title, String artist, String album) {
        String kw = stripParenthetical(title) + " - " + stripParenthetical(artist);
        if (album != null && !album.isBlank()) kw += " " + album.trim();
        return kw;
    }

    private static Result kugouDownload(String id, String accessKey) throws Exception {
        String dl = get("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=" + enc(id)
                + "&accesskey=" + enc(accessKey == null ? "" : accessKey));
        if (dl == null) return null;
        JsonObject o = GSON.fromJson(dl, JsonObject.class);
        String content = str(o, "content");
        if (content == null) return null;
        String lrc = new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        if (isInstrumentalNotice(lrc)) return null;
        List<TimedLine> lines = parseLrc(lrc);
        return hasText(lines) ? wrap(Provider.KUGOU, lines) : null;
    }

    private static boolean isInstrumentalNotice(String lrc) {
        if (lrc == null) return false;
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\\n")) {
            String t = raw.replaceAll("\\[\\d{1,2}:\\d{2}(?:\\.\\d{2,3})?\\]", "").trim();
            if (!t.isEmpty()) texts.add(t);
        }
        return !texts.isEmpty() && texts.stream().allMatch(t -> t.contains("纯音乐") && t.contains("欣赏"));
    }

    private static List<String[]> kugouCandidates(String url) throws Exception {
        List<String[]> out = new ArrayList<>();
        String body = get(url);
        if (body == null) return out;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray cands = o.has("candidates") && o.get("candidates").isJsonArray() ? o.getAsJsonArray("candidates") : null;
        if (cands == null) return out;
        for (JsonElement e : cands) {
            JsonObject c = e.getAsJsonObject();
            String id = str(c, "id");
            if (id != null) out.add(new String[]{id, str(c, "accesskey")});
        }
        return out;
    }

    private static Result kugou(String title, String artist, String album, long durationMs) throws Exception {
        String keyword = kugouKeyword(title, artist, album);
        int seconds = (int) (durationMs / 1000);
        // Song hashes within ±8s of the track, closest first (BitChord rule).
        // Trying only the first hash/first candidate missed valid downloads.
        String search = get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=" + enc(keyword));
        List<String[]> hashOrder = new ArrayList<>();
        if (search != null) {
            JsonObject o = GSON.fromJson(search, JsonObject.class);
            JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            if (data != null && data.has("info") && data.get("info").isJsonArray()) {
                List<JsonObject> infos = new ArrayList<>();
                for (JsonElement e : data.getAsJsonArray("info")) infos.add(e.getAsJsonObject());
                // With no duration, sorting by closeness to 0 puts the
                // shortest tracks first and the right track last, so the
                // first valid download can be a wrong version. Keep the
                // search (relevance) order instead; only sort when a real
                // duration is known.
                if (seconds > 0) infos.sort((a, b) -> Double.compare(
                        Math.abs(num(a, "duration", seconds) - seconds),
                        Math.abs(num(b, "duration", seconds) - seconds)));
                for (JsonObject info : infos) {
                    String h = str(info, "hash");
                    double d = num(info, "duration", seconds);
                    if (h == null) continue;
                    if (seconds > 0 && d >= 0 && Math.abs(d - seconds) > 8) continue;
                    hashOrder.add(new String[]{h});
                }
            }
        }
        for (String[] hash : hashOrder) {
            for (String[] cand : kugouCandidates("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + enc(hash[0]))) {
                Result r = kugouDownload(cand[0], cand[1]);
                if (r != null) return r;
            }
        }
        // Keyword fallback, duration in ms, every candidate until a valid download.
        for (String[] cand : kugouCandidates("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                + "&duration=" + (durationMs > 0 ? durationMs : ""))) {
            Result r = kugouDownload(cand[0], cand[1]);
            if (r != null) return r;
        }
        return null;
    }

    private static Result musixmatch(String title, String artist, long durationMs) throws Exception {
        // BitChord signs this API with a rotating key scraped from the web
        // page; without that key the public endpoint usually answers 401.
        // Attempted as-is (app_id only) so a future keyless change works,
        // but expect a miss — do not copy any signing secret here.
        String url = "https://apic.musixmatch.com/ws/1.1/track.search?format=json&q_track=" + enc(title)
                + "&q_artist=" + enc(artist) + "&app_id=mobile-app-v1.0&page_size=1";
        String body = get(url);
        if (body == null) return null;
        XposedBridge.log("[SpotifyPlus][LyricsProviders] Musixmatch track.search answered but rich/subtitle fetch is not implemented without a signing key");
        return null;
    }

    private static Result genius(String title, String artist) throws Exception {
        String search = get("https://genius.com/api/search/multi?q=" + enc(title + " " + artist),
                "User-Agent", "SpotifyPlus");
        if (search == null) return null;
        JsonObject o = GSON.fromJson(search, JsonObject.class);
        String path = findGeniusPath(o);
        if (path == null) return null;
        String page = get("https://genius.com" + path, "User-Agent", "SpotifyPlus");
        if (page == null) return null;
        Pattern container = Pattern.compile("data-lyrics-container[^>]*>(.*?)</div>", Pattern.DOTALL);
        Matcher m = container.matcher(page);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String html = m.group(1).replaceAll("(?i)<br[^>]*>", "\n").replaceAll("<[^>]+>", "");
            html = html.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
            sb.append(html).append("\n");
        }
        List<TimedLine> lines = new ArrayList<>();
        for (String line : sb.toString().split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("[")) lines.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
            else if (t.startsWith("[")) lines.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
        }
        return wrap(Provider.GENIUS, lines);
    }

    private static String findGeniusPath(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("path") && o.has("title")) {
                String p = str(o, "path");
                if (p != null && p.contains("lyrics")) return p;
            }
            for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
                String p = findGeniusPath(e.getValue());
                if (p != null) return p;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String p = findGeniusPath(e);
                if (p != null) return p;
            }
        }
        return null;
    }
}
