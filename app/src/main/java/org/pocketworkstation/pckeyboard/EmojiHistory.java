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
import android.preference.PreferenceManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recently used emoji and skin tone choices, persisted across sessions.
 */
public class EmojiHistory {
    static final String PREF_DEFAULT_SKIN_TONE = "pref_emoji_skin_tone";

    private static final String PREFS_NAME = "emoji_history";
    private static final String KEY_RECENTS = "recents";
    private static final String KEY_VARIANTS = "variants";
    private static final int MAX_RECENTS = 36;

    private final SharedPreferences mHistoryPrefs;
    private final SharedPreferences mSettings;
    private final ArrayList<String> mRecents = new ArrayList<String>();
    /** Base emoji -> the skin tone variant last picked for it. */
    private final Map<String, String> mVariants = new HashMap<String, String>();

    public EmojiHistory(Context context) {
        mHistoryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mSettings = PreferenceManager.getDefaultSharedPreferences(context);
        String recents = mHistoryPrefs.getString(KEY_RECENTS, "");
        for (String e : TextUtils.split(recents, " ")) {
            if (!e.isEmpty()) mRecents.add(e);
        }
        String variants = mHistoryPrefs.getString(KEY_VARIANTS, "");
        for (String pair : TextUtils.split(variants, " ")) {
            int eq = pair.indexOf('=');
            if (eq > 0) mVariants.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    /** Most recent first. */
    public List<String> getRecents() {
        return new ArrayList<String>(mRecents);
    }

    /** The emoji behind the recently used forms, to rank them higher in search. */
    public Set<EmojiData.Emoji> getRecentEmoji(EmojiData data) {
        Set<EmojiData.Emoji> result = new HashSet<EmojiData.Emoji>();
        for (String form : mRecents) {
            EmojiData.Emoji e = data.find(form);
            if (e != null) result.add(e);
        }
        return result;
    }

    public void addRecent(String emoji) {
        mRecents.remove(emoji);
        mRecents.add(0, emoji);
        while (mRecents.size() > MAX_RECENTS) mRecents.remove(mRecents.size() - 1);
        mHistoryPrefs.edit().putString(KEY_RECENTS, TextUtils.join(" ", mRecents)).apply();
    }

    public void setVariant(EmojiData.Emoji emoji, String variant) {
        if (variant.equals(emoji.base)) {
            mVariants.remove(emoji.base);
        } else {
            mVariants.put(emoji.base, variant);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : mVariants.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        mHistoryPrefs.edit().putString(KEY_VARIANTS, sb.toString()).apply();
    }

    /**
     * The form of an emoji to show and insert: the variant last picked for it,
     * otherwise the default skin tone from the settings, otherwise the base.
     */
    public String getPreferredForm(EmojiData.Emoji emoji) {
        if (!emoji.hasVariants()) return emoji.base;
        String picked = mVariants.get(emoji.base);
        if (picked != null) {
            for (String v : emoji.variants) {
                if (v.equals(picked)) return picked;
            }
        }
        int tone = getDefaultSkinTone();
        if (tone < 0) return emoji.base;
        // 5 variants: one per tone. 25: 5x5 grid, the diagonal is "both the same tone".
        return emoji.variants.length == 25 ? emoji.variants[tone * 6] : emoji.variants[tone];
    }

    /** 0..4 for light..dark, or -1 for the default yellow. */
    private int getDefaultSkinTone() {
        String value = mSettings.getString(PREF_DEFAULT_SKIN_TONE, "");
        try {
            int tone = value.isEmpty() ? -1 : Integer.parseInt(value);
            return tone >= 0 && tone < 5 ? tone : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
