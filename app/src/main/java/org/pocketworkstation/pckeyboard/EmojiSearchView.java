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

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Emoji search bar shown above the keyboard: a query field typed into with the
 * regular keyboard, and a strip of matching emoji.
 */
@SuppressLint("ViewConstructor")
public class EmojiSearchView extends LinearLayout implements EmojiCellView.Listener {

    public interface Listener {
        void onSearchEmojiPicked(String emoji);
        void onSearchClosed();
        void onPaletteKeyFeedback(int primaryCode);
    }

    private static final int MAX_RESULTS = 80;

    private final EmojiTheme mTheme;
    private final EmojiHistory mHistory;
    private final Listener mListener;
    private final TextView mQueryView;
    private final TextView mEmptyView;
    private final RecyclerView mResultsView;
    private final ResultsAdapter mAdapter = new ResultsAdapter();
    private final List<EmojiData.Emoji> mResults = new ArrayList<EmojiData.Emoji>();
    private final List<String> mResultForms = new ArrayList<String>();
    private final StringBuilder mQuery = new StringBuilder();
    private final int mCellSize;
    private EmojiData mData;

    public EmojiSearchView(Context context, EmojiTheme theme, EmojiHistory history,
            Listener listener) {
        super(context);
        mTheme = theme;
        mHistory = history;
        mListener = listener;
        mCellSize = theme.dp(46);
        setOrientation(VERTICAL);
        setBackground(theme.newBackground());

        LinearLayout queryRow = new LinearLayout(context);
        queryRow.setOrientation(HORIZONTAL);
        queryRow.setGravity(Gravity.CENTER_VERTICAL);
        queryRow.setPadding(theme.dp(4), theme.dp(4), theme.dp(8), 0);
        addView(queryRow, new LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(44)));

        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_emoji_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(context.getString(R.string.emoji_search_close));
        theme.tint(back, theme.textColor);
        back.setOnTouchListener(new OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mListener.onPaletteKeyFeedback(0);
                }
                return false;
            }
        });
        back.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mListener.onSearchClosed();
            }
        });
        queryRow.addView(back, new LayoutParams(theme.dp(44), LayoutParams.MATCH_PARENT));

        LinearLayout field = new LinearLayout(context);
        field.setOrientation(HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(theme.newPill(0x1C));
        field.setPadding(theme.dp(12), 0, theme.dp(12), 0);
        queryRow.addView(field, new LayoutParams(0, theme.dp(36), 1));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_emoji_search);
        theme.tint(icon, theme.secondaryTextColor);
        field.addView(icon, new LayoutParams(theme.dp(20), theme.dp(20)));

        mQueryView = new TextView(context);
        mQueryView.setTextColor(theme.textColor);
        mQueryView.setHintTextColor(theme.secondaryTextColor);
        mQueryView.setHint(R.string.emoji_search_hint);
        mQueryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mQueryView.setSingleLine(true);
        mQueryView.setEllipsize(TextUtils.TruncateAt.START);
        mQueryView.setPadding(theme.dp(8), 0, 0, 0);
        field.addView(mQueryView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        FrameLayout resultsFrame = new FrameLayout(context);
        addView(resultsFrame, new LayoutParams(LayoutParams.MATCH_PARENT, mCellSize + theme.dp(4)));

        mResultsView = new RecyclerView(context);
        mResultsView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        mResultsView.setAdapter(mAdapter);
        mResultsView.setItemAnimator(null);
        mResultsView.setPadding(theme.dp(4), 0, theme.dp(4), 0);
        mResultsView.setClipToPadding(false);
        resultsFrame.addView(mResultsView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mEmptyView = new TextView(context);
        mEmptyView.setText(R.string.emoji_search_no_results);
        mEmptyView.setTextColor(theme.secondaryTextColor);
        mEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setVisibility(GONE);
        resultsFrame.addView(mEmptyView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setData(EmojiData data) {
        mData = data;
        updateResults();
    }

    public void clear() {
        mQuery.setLength(0);
        updateResults();
    }

    public void append(CharSequence text) {
        mQuery.append(text);
        updateResults();
    }

    public void deleteLast() {
        int len = mQuery.length();
        if (len == 0) return;
        mQuery.delete(Character.offsetByCodePoints(mQuery, len, -1), len);
        updateResults();
    }

    /** The preferred form of the best match, or null if there is none. */
    public String getFirstResult() {
        return mResultForms.isEmpty() ? null : mResultForms.get(0);
    }

    private void updateResults() {
        mQueryView.setText(mQuery);
        mResults.clear();
        mResultForms.clear();
        String query = mQuery.toString().trim();
        if (mData != null) {
            if (query.isEmpty()) {
                // Nothing typed yet: offer the recently used emoji.
                for (String form : mHistory.getRecents()) {
                    EmojiData.Emoji emoji = mData.find(form);
                    if (emoji == null) continue;
                    mResults.add(emoji);
                    mResultForms.add(form);
                }
            } else {
                for (EmojiData.Emoji emoji : mData.search(query, MAX_RESULTS, mHistory.getRecentEmoji(mData))) {
                    mResults.add(emoji);
                    mResultForms.add(mHistory.getPreferredForm(emoji));
                }
            }
        }
        mEmptyView.setVisibility(mResults.isEmpty() && !query.isEmpty() ? VISIBLE : GONE);
        mAdapter.notifyDataSetChanged();
        mResultsView.scrollToPosition(0);
    }

    public void onEmojiClicked(EmojiCellView cell) {
        mListener.onPaletteKeyFeedback(0);
        mListener.onSearchEmojiPicked(cell.getForm());
    }

    public boolean onEmojiLongPressed(EmojiCellView cell) {
        return false;
    }

    public void onEmojiPickerGesture(EmojiCellView cell, MotionEvent event) {
    }

    private class ResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return mResults.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            EmojiCellView cell = new EmojiCellView(parent.getContext(), mTheme);
            cell.setShowVariantMark(false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(mCellSize, mCellSize));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((EmojiCellView) holder.itemView).bind(mResults.get(position),
                    mResultForms.get(position), mCellSize * 0.62f, EmojiSearchView.this);
        }
    }
}
