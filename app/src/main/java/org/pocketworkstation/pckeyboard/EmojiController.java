/*
 * Copyright (C) 2025 Hacker's Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glue between LatinIME and the emoji UI: owns the emoji data, the palette and
 * the search bar, and routes key presses to the search query while searching.
 */
public class EmojiController implements EmojiPalettesView.Listener, EmojiSearchView.Listener {
    private static final String TAG = "PCKeyboard/Emoji";

    static final String PREF_EMOJI_SUGGESTIONS = "pref_emoji_suggestions";
    private static final int MAX_EMOJI_SUGGESTIONS = 2;
    /** Emoji the pre-Marshmallow system font can be relied on to have. */
    private static final float MAX_VERSION_BEFORE_M = 0.7f;
    private static final float MIN_PALETTE_HEIGHT_DP = 180;
    /** The palette can be resized up to this fraction of the screen height. */
    private static final float MAX_PALETTE_HEIGHT_FRACTION = 0.75f;
    /** Height the palette is resized to beyond the keyboard height, in dp, per orientation. */
    private static final String PREF_PALETTE_EXTRA_HEIGHT_PORTRAIT = "emoji_palette_extra_height_portrait";
    private static final String PREF_PALETTE_EXTRA_HEIGHT_LANDSCAPE = "emoji_palette_extra_height_landscape";

    private final LatinIME mIme;
    private final EmojiHistory mHistory;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Glyph support per emoji; survives data reloads for other languages. */
    private static final Map<String, Boolean> sGlyphCache = new ConcurrentHashMap<String, Boolean>();

    private EmojiData mData;
    private List<String> mLoadedLanguages;
    private List<String> mLoadingLanguages;

    private LatinKeyboardView mKeyboardView;
    private KeyboardFrame mFrame;
    private EmojiPalettesView mPalette;
    private EmojiSearchView mSearch;
    private boolean mPaletteShown;
    private boolean mSearching;
    private final Set<String> mSuggestedEmoji = new HashSet<String>();
    /** Palette height when the resize handle was grabbed. */
    private int mResizeStartHeight;

    public EmojiController(LatinIME ime) {
        mIme = ime;
        mHistory = new EmojiHistory(ime);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ime);
    }

    // ---------------------------------------------------------------- views

    /**
     * Wraps the keyboard view into the IME's input view: the search bar above
     * the keyboard, and the palette overlaying the keyboard at the same height.
     */
    public View createInputView(LatinKeyboardView keyboardView) {
        if (isActive()) {
            // The view is recreated (theme, height, ...) while emoji were shown:
            // we come back on the keyboard, so let the IME restore its strip.
            mHandler.post(new Runnable() {
                public void run() {
                    mIme.onEmojiModeChanged();
                }
            });
        }
        mPaletteShown = false;
        mSearching = false;
        mKeyboardView = keyboardView;
        Context context = keyboardView.getContext();
        EmojiTheme theme = new EmojiTheme(keyboardView);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        mSearch = new EmojiSearchView(context, theme, mHistory, this);
        mSearch.setVisibility(View.GONE);
        container.addView(mSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        KeyboardFrame frame = new KeyboardFrame(context, theme.dp(MIN_PALETTE_HEIGHT_DP));
        frame.setExtraHeight(theme.dp(mPrefs.getFloat(extraHeightPref(), 0)));
        mFrame = frame;
        container.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ViewGroup oldParent = (ViewGroup) keyboardView.getParent();
        if (oldParent != null) oldParent.removeView(keyboardView);
        frame.addView(keyboardView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mPalette = new EmojiPalettesView(context, theme, mHistory, this);
        mPalette.setVisibility(View.GONE);
        frame.addView(mPalette, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (mData != null) {
            mPalette.setData(mData);
            mSearch.setData(mData);
        }
        return container;
    }

    private String extraHeightPref() {
        return mIme.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? PREF_PALETTE_EXTRA_HEIGHT_LANDSCAPE : PREF_PALETTE_EXTRA_HEIGHT_PORTRAIT;
    }

    /**
     * Sizes the palette to cover the keyboard it replaces, plus the extra
     * height the palette was resized to.
     */
    private static class KeyboardFrame extends FrameLayout {
        private final int mMinPaletteHeight;
        /** Palette height beyond the keyboard height; negative if smaller. */
        private int mExtraHeight;

        KeyboardFrame(Context context, int minPaletteHeight) {
            super(context);
            mMinPaletteHeight = minPaletteHeight;
        }

        int getKeyboardHeight() {
            return getChildAt(0).getMeasuredHeight();
        }

        int getExtraHeight() {
            return mExtraHeight;
        }

        void setExtraHeight(int extraHeight) {
            if (extraHeight == mExtraHeight) return;
            mExtraHeight = extraHeight;
            requestLayout();
        }

        int paletteHeight(int keyboardHeight) {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels
                    * MAX_PALETTE_HEIGHT_FRACTION);
            int min = Math.min(mMinPaletteHeight, max);
            return Math.max(min, Math.min(keyboardHeight + mExtraHeight, Math.max(max, keyboardHeight)));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            View keyboard = getChildAt(0);
            View palette = getChildAt(1);
            measureChild(keyboard, widthMeasureSpec, heightMeasureSpec);
            int width = keyboard.getMeasuredWidth();
            int height = keyboard.getMeasuredHeight();
            if (palette.getVisibility() != GONE) {
                height = paletteHeight(height);
                palette.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(resolveSize(width, widthMeasureSpec), height);
        }
    }

    public boolean isPaletteShown() {
        return mPaletteShown;
    }

    public boolean isSearching() {
        return mSearching;
    }

    /** True while the palette or the search bar replace normal typing. */
    public boolean isActive() {
        return mPaletteShown || mSearching;
    }

    public void showPalette() {
        if (mPalette == null) return;
        ensureLoaded();
        mSearching = false;
        mSearch.setVisibility(View.GONE);
        mPaletteShown = true;
        mKeyboardView.setVisibility(View.INVISIBLE);
        mPalette.setVisibility(View.VISIBLE);
        mPalette.onShown();
        mIme.onEmojiModeChanged();
    }

    /** Leaves the palette and search, back to the keyboard. */
    public void hide() {
        if (!isActive()) return;
        hideViews();
        mIme.onEmojiModeChanged();
    }

    /** Resets to the keyboard without notifying the IME, e.g. when input finishes. */
    public void reset() {
        if (isActive()) hideViews();
    }

    private void hideViews() {
        mPaletteShown = false;
        mSearching = false;
        if (mPalette != null) {
            mPalette.onHidden();
            mPalette.setVisibility(View.GONE);
            mSearch.setVisibility(View.GONE);
            mKeyboardView.setVisibility(View.VISIBLE);
        }
    }

    private void startSearch() {
        mPaletteShown = false;
        mPalette.onHidden();
        mPalette.setVisibility(View.GONE);
        mKeyboardView.setVisibility(View.VISIBLE);
        mSearching = true;
        mSearch.clear();
        mSearch.setVisibility(View.VISIBLE);
        mIme.onEmojiModeChanged();
    }

    /** @return true if the back key was consumed. */
    public boolean handleBack() {
        if (mPaletteShown && mPalette.handleBack()) return true;
        if (mSearching) {
            showPalette();
            return true;
        }
        return false;
    }

    // --------------------------------------------------------- search input

    /**
     * Keys typed while searching edit the query instead of the text field.
     * @return true if the key was consumed.
     */
    public boolean handleSearchKey(int primaryCode) {
        if (!mSearching) return false;
        switch (primaryCode) {
        case Keyboard.KEYCODE_DELETE:
            mSearch.deleteLast();
            return true;
        case LatinIME.ASCII_ENTER: {
            String first = mSearch.getFirstResult();
            if (first != null) onSearchEmojiPicked(first);
            return true;
        }
        case LatinKeyboardView.KEYCODE_ESCAPE:
            showPalette();
            return true;
        // Keys that change the keyboard itself keep working.
        case Keyboard.KEYCODE_SHIFT:
        case Keyboard.KEYCODE_MODE_CHANGE:
        case Keyboard.KEYCODE_CANCEL:
        case Keyboard.KEYCODE_EMOJI:
        case LatinKeyboardView.KEYCODE_OPTIONS:
        case LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS:
        case LatinKeyboardView.KEYCODE_NEXT_LANGUAGE:
        case LatinKeyboardView.KEYCODE_PREV_LANGUAGE:
        case LatinKeyboardView.KEYCODE_NEXT_LAYOUT:
        case LatinKeyboardView.KEYCODE_FN:
            return false;
        }
        if (primaryCode >= LatinIME.ASCII_SPACE) {
            mSearch.append(new String(Character.toChars(primaryCode)));
        }
        // Everything else (arrows, function keys, tab, ...) would act on the
        // text field behind the search bar, so swallow it.
        return true;
    }

    /** Multi-character keys (e.g. ".com") typed while searching. */
    public boolean handleSearchText(CharSequence text) {
        if (!mSearching) return false;
        mSearch.append(text);
        return true;
    }

    // ------------------------------------------------ palette/search events

    public void onEmojiPicked(String emoji) {
        mIme.commitEmoji(emoji);
    }

    public void onSearchEmojiPicked(String emoji) {
        mIme.commitEmoji(emoji);
    }

    public void onEmojiCommitted(String emoji) {
        mHistory.addRecent(emoji);
    }

    public void onPaletteKey(int primaryCode) {
        mIme.onKey(primaryCode, new int[] { primaryCode },
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE,
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE);
    }

    public void onPaletteKeyFeedback(int primaryCode) {
        mIme.emojiKeyFeedback(primaryCode);
    }

    public void onSwitchToKeyboard() {
        hide();
    }

    public void onSearchRequested() {
        ensureLoaded();
        startSearch();
    }

    public void onSearchClosed() {
        showPalette();
    }

    public void onPaletteResizeStart() {
        mResizeStartHeight = mPalette.getHeight();
    }

    public void onPaletteResize(float dy) {
        // Dragging up (negative dy) makes the palette taller.
        int height = mResizeStartHeight - Math.round(dy);
        mFrame.setExtraHeight(height - mFrame.getKeyboardHeight());
    }

    public void onPaletteResizeEnd() {
        // Store what the frame can actually apply, not where the finger went.
        int keyboardHeight = mFrame.getKeyboardHeight();
        int extra = mFrame.paletteHeight(keyboardHeight) - keyboardHeight;
        mFrame.setExtraHeight(extra);
        float density = mIme.getResources().getDisplayMetrics().density;
        mPrefs.edit().putFloat(extraHeightPref(), extra / density).apply();
    }

    // ---------------------------------------------------------- suggestions

    /**
     * Adds emoji matching the typed word to the suggestion list. They go after
     * the typed word and the auto-correction so those keep their positions.
     */
    public List<CharSequence> addSuggestions(List<CharSequence> suggestions, CharSequence typedWord) {
        mSuggestedEmoji.clear();
        if (mData == null || typedWord == null || typedWord.length() < 2
                || !mPrefs.getBoolean(PREF_EMOJI_SUGGESTIONS, true)) {
            return suggestions;
        }
        List<EmojiData.Emoji> emoji = mData.suggest(typedWord.toString(), MAX_EMOJI_SUGGESTIONS,
                mHistory.getRecentEmoji(mData));
        if (emoji.isEmpty()) return suggestions;
        List<CharSequence> result = new ArrayList<CharSequence>(suggestions);
        int pos = Math.min(2, result.size());
        for (EmojiData.Emoji e : emoji) {
            String form = mHistory.getPreferredForm(e);
            mSuggestedEmoji.add(form);
            result.add(pos++, form);
        }
        return result;
    }

    public boolean isSuggestedEmoji(CharSequence suggestion) {
        return suggestion != null && mSuggestedEmoji.contains(suggestion.toString());
    }

    // ---------------------------------------------------------------- data

    /** Loads the data early when it is needed for suggestions while typing. */
    public void preload() {
        if (mPrefs.getBoolean(PREF_EMOJI_SUGGESTIONS, true)) ensureLoaded();
    }

    /** Loads the emoji data in the background, if not loaded for these languages yet. */
    public void ensureLoaded() {
        final List<String> languages = keywordLanguages();
        if (languages.equals(mLoadedLanguages)) {
            mLoadingLanguages = null; // a pending load for other languages is stale now
            return;
        }
        if (languages.equals(mLoadingLanguages)) return;
        mLoadingLanguages = languages;
        final Context context = mIme.getApplicationContext();
        new Thread("EmojiDataLoader") {
            @Override
            public void run() {
                EmojiData loaded = null;
                try {
                    loaded = load(context, languages);
                } catch (Throwable t) { // e.g. OutOfMemoryError; still clear the pending state
                    Log.e(TAG, "cannot load emoji data", t);
                }
                final EmojiData data = loaded;
                mHandler.post(new Runnable() {
                    public void run() {
                        if (languages != mLoadingLanguages) return; // superseded
                        mLoadingLanguages = null;
                        if (data == null) return;
                        mData = data;
                        mLoadedLanguages = languages;
                        if (mPalette != null) {
                            mPalette.setData(data);
                            mSearch.setData(data);
                        }
                    }
                });
            }
        }.start();
    }

    /** Keyword languages: the input language, the system language and English. */
    private List<String> keywordLanguages() {
        Set<String> languages = new LinkedHashSet<String>();
        Locale input = mIme.getEmojiInputLocale();
        if (input != null) languages.add(keywordLanguage(input));
        languages.add(keywordLanguage(Locale.getDefault()));
        languages.add("en");
        return new ArrayList<String>(languages);
    }

    /** Maps a locale to the suffix of its keywords_*.txt asset (CLDR naming). */
    static String keywordLanguage(Locale locale) {
        String lang = locale.getLanguage();
        if (lang.equals("in")) return "id";
        if (lang.equals("iw")) return "he";
        if (lang.equals("nb") || lang.equals("nn")) return "no";
        if (lang.equals("tl")) return "fil";
        if (lang.equals("zh")) {
            String country = locale.getCountry();
            if (country.equals("TW") || country.equals("HK") || country.equals("MO")) {
                return "zh_Hant";
            }
        }
        return lang;
    }

    private static EmojiData load(Context context, List<String> languages) {
        long start = SystemClock.uptimeMillis();
        EmojiData data;
        try {
            data = EmojiData.parse(openAsset(context, "emoji/emoji.txt"));
        } catch (IOException e) {
            Log.e(TAG, "cannot load emoji data", e);
            return null;
        }
        final Paint paint = new Paint();
        final boolean hasGlyphApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        final EmojiData versions = data;
        data.filter(new EmojiData.GlyphChecker() {
            public boolean canRender(String emoji) {
                if (!hasGlyphApi) {
                    EmojiData.Emoji e = versions.find(emoji);
                    return e != null && e.version <= MAX_VERSION_BEFORE_M;
                }
                Boolean cached = sGlyphCache.get(emoji);
                if (cached == null) {
                    cached = paint.hasGlyph(emoji);
                    sGlyphCache.put(emoji, cached);
                }
                return cached;
            }
        }, hasGlyphApi /* skin tones need Android 6 */);
        long filtered = SystemClock.uptimeMillis();
        for (String lang : languages) {
            try {
                data.addKeywords(openAsset(context, "emoji/keywords_" + lang + ".txt"));
            } catch (FileNotFoundException e) {
                // No keywords for this language.
            } catch (IOException e) {
                Log.w(TAG, "cannot load emoji keywords for " + lang, e);
            }
        }
        Log.i(TAG, "loaded " + data.getAll().size() + " emoji in "
                + (filtered - start) + "ms, keywords " + languages + " in "
                + (SystemClock.uptimeMillis() - filtered) + "ms");
        return data;
    }

    private static Reader openAsset(Context context, String name) throws IOException {
        return new InputStreamReader(context.getAssets().open(name), "UTF-8");
    }
}
