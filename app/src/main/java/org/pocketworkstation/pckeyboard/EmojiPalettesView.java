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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The emoji palette shown in place of the keyboard: one continuously
 * scrolling grid of all categories (recently used first) with category tabs
 * that follow the scroll position, a skin tone picker on long press, a
 * handle on top to make the palette taller or smaller, and a bottom bar to
 * go back to the keyboard, search, type a space or delete.
 */
@SuppressLint("ViewConstructor")
public class EmojiPalettesView extends FrameLayout
        implements EmojiCellView.Listener, EmojiVariantPicker.Listener {

    public interface Listener {
        void onEmojiPicked(String emoji);
        /** A key on the palette's bottom bar, e.g. space or delete. */
        void onPaletteKey(int primaryCode);
        /** Haptic / audio feedback for a touch on the palette. */
        void onPaletteKeyFeedback(int primaryCode);
        void onSwitchToKeyboard();
        void onSearchRequested();
        /** The resize handle was grabbed. */
        void onPaletteResizeStart();
        /** The resize handle moved by dy pixels since it was grabbed (up is negative). */
        void onPaletteResize(float dy);
        /** The resize handle was released. */
        void onPaletteResizeEnd();
    }

    /** Category ids from emoji.txt, in tab order after "recents". */
    private static final String[] CATEGORY_IDS = {
        "smileys", "people", "animals", "food", "travel",
        "activities", "objects", "symbols", "flags",
    };
    private static final int[] TAB_ICONS = {
        R.drawable.ic_emoji_recent, R.drawable.ic_emoji_smileys, R.drawable.ic_emoji_people,
        R.drawable.ic_emoji_animals, R.drawable.ic_emoji_food, R.drawable.ic_emoji_travel,
        R.drawable.ic_emoji_activities, R.drawable.ic_emoji_objects,
        R.drawable.ic_emoji_symbols, R.drawable.ic_emoji_flags,
    };
    private static final int[] TAB_LABELS = {
        R.string.emoji_category_recents, R.string.emoji_category_smileys,
        R.string.emoji_category_people, R.string.emoji_category_animals,
        R.string.emoji_category_food, R.string.emoji_category_travel,
        R.string.emoji_category_activities, R.string.emoji_category_objects,
        R.string.emoji_category_symbols, R.string.emoji_category_flags,
    };
    private static final int SECTION_COUNT = TAB_ICONS.length;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_EMOJI = 1;
    private static final int TYPE_HINT = 2;

    private static final float CELL_TARGET_DP = 45;
    private static final float WIDE_LAYOUT_DP = 600;
    private static final int DELETE_REPEAT_START_MS = 400;
    private static final int DELETE_REPEAT_MS = 50;

    private final EmojiTheme mTheme;
    private final Listener mListener;
    private final EmojiHistory mHistory;
    private EmojiData mData;

    private final LinearLayout mTopBar;
    private final LinearLayout mBottomBar;
    private final LinearLayout mTabStrip;
    private final View mSpaceKey;
    private final ImageView[] mTabs = new ImageView[SECTION_COUNT];
    private final RecyclerView mGrid;
    private final GridLayoutManager mLayoutManager;
    private final Adapter mAdapter = new Adapter();
    private final EmojiVariantPicker mPicker;

    private final List<Item> mItems = new ArrayList<Item>();
    private final int[] mSectionStart = new int[SECTION_COUNT];
    private int mSelectedTab = -1;
    private int mColumns = 9;
    private int mCellHeight;
    private float mEmojiTextSize;
    private boolean mWide;

    public EmojiPalettesView(Context context, EmojiTheme theme, EmojiHistory history,
            Listener listener) {
        super(context);
        mTheme = theme;
        mHistory = history;
        mListener = listener;
        setBackground(theme.newBackground());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        content.addView(new ResizeHandle(context), new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, theme.dp(16)));

        mTabStrip = new LinearLayout(context);
        mTabStrip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SECTION_COUNT; ++i) {
            final int section = i;
            ImageView tab = new ImageView(context);
            tab.setImageResource(TAB_ICONS[i]);
            tab.setScaleType(ImageView.ScaleType.CENTER);
            tab.setContentDescription(context.getString(TAB_LABELS[i]));
            tab.setOnTouchListener(new FeedbackTouchListener(0));
            tab.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    scrollToSection(section);
                }
            });
            mTabs[i] = tab;
            mTabStrip.addView(tab, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }

        mTopBar = new LinearLayout(context);
        mTopBar.setPadding(theme.dp(4), theme.dp(2), theme.dp(4), 0);
        content.addView(mTopBar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, theme.dp(40)));

        mGrid = new RecyclerView(context);
        mLayoutManager = new GridLayoutManager(context, mColumns);
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mItems.get(position).type == TYPE_EMOJI ? 1 : mColumns;
            }
        });
        mGrid.setLayoutManager(mLayoutManager);
        mGrid.setAdapter(mAdapter);
        mGrid.setItemAnimator(null);
        mGrid.setVerticalScrollBarEnabled(true);
        mGrid.setClipToPadding(false);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateSelectedTabFromScroll();
            }
        });
        content.addView(mGrid, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        mBottomBar = new LinearLayout(context);
        mBottomBar.setOrientation(LinearLayout.HORIZONTAL);
        mBottomBar.setGravity(Gravity.CENTER_VERTICAL);
        mBottomBar.setPadding(theme.dp(4), 0, theme.dp(4), theme.dp(2));
        content.addView(mBottomBar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, theme.dp(44)));

        TextView abc = new TextView(context);
        abc.setText(R.string.emoji_switch_to_keyboard);
        abc.setTextColor(theme.textColor);
        abc.setTypeface(Typeface.DEFAULT_BOLD);
        abc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        abc.setGravity(Gravity.CENTER);
        abc.setBackground(theme.newPill(0x1C));
        abc.setOnTouchListener(new FeedbackTouchListener(Keyboard.KEYCODE_MODE_CHANGE));
        abc.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mListener.onSwitchToKeyboard();
            }
        });
        LinearLayout.LayoutParams abcParams = new LinearLayout.LayoutParams(
                theme.dp(56), theme.dp(34));
        abcParams.rightMargin = theme.dp(4);
        mBottomBar.addView(abc, abcParams);

        ImageView search = newBarIcon(R.drawable.ic_emoji_search, R.string.emoji_search_hint);
        search.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mListener.onSearchRequested();
            }
        });
        mBottomBar.addView(search, new LinearLayout.LayoutParams(theme.dp(44), LayoutParams.MATCH_PARENT));

        mSpaceKey = new View(context);
        mSpaceKey.setBackground(theme.newPill(0x1C));
        mSpaceKey.setContentDescription(context.getString(R.string.emoji_space));
        mSpaceKey.setOnTouchListener(new FeedbackTouchListener(LatinIME.ASCII_SPACE));
        mSpaceKey.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mListener.onPaletteKey(LatinIME.ASCII_SPACE);
            }
        });
        mBottomBar.addView(mSpaceKey);

        ImageView delete = newBarIcon(R.drawable.ic_emoji_backspace, R.string.emoji_delete);
        delete.setOnTouchListener(new RepeatingDeleteListener());
        mBottomBar.addView(delete, new LinearLayout.LayoutParams(theme.dp(56), LayoutParams.MATCH_PARENT));

        mPicker = new EmojiVariantPicker(context, theme, this);
        addView(mPicker, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        applyWideLayout(false);
        rebuildItems();
    }

    private ImageView newBarIcon(int icon, int description) {
        ImageView view = new ImageView(getContext());
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(getContext().getString(description));
        mTheme.tint(view, mTheme.textColor);
        view.setOnTouchListener(new FeedbackTouchListener(0));
        return view;
    }

    public void setData(EmojiData data) {
        mData = data;
        mPicker.dismiss(); // its emoji and anchor are about to be rebound
        rebuildItems();
    }

    /** Called each time the palette is opened: refresh recents and start at the top. */
    public void onShown() {
        mPicker.dismiss();
        rebuildItems();
        mLayoutManager.scrollToPositionWithOffset(0, 0);
        selectTab(0);
    }

    public void onHidden() {
        mPicker.dismiss();
    }

    /** @return true if the back key was consumed. */
    public boolean handleBack() {
        if (mPicker.isShowing()) {
            mPicker.dismiss();
            return true;
        }
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == oldw || w == 0) return;
        final float density = getResources().getDisplayMetrics().density;
        final int columns = Math.max(6, Math.round(w / (CELL_TARGET_DP * density)));
        final boolean wide = w / density >= WIDE_LAYOUT_DP;
        // Changing children from inside a layout pass must be deferred.
        post(new Runnable() {
            public void run() {
                applyWideLayout(wide);
                setColumns(columns);
            }
        });
    }

    private void setColumns(int columns) {
        int width = mGrid.getWidth() > 0 ? mGrid.getWidth() : getWidth();
        mColumns = columns;
        mPicker.dismiss();
        float cellWidth = width / (float) columns;
        mCellHeight = Math.round(Math.max(mTheme.dp(36), Math.min(cellWidth * 0.9f, mTheme.dp(54))));
        mEmojiTextSize = Math.min(cellWidth, mCellHeight) * 0.62f;
        mLayoutManager.setSpanCount(columns);
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Narrow screens get the category tabs in their own row at the top; wide
     * (landscape / tablet) screens fit them into the bottom bar to leave more
     * room for the grid.
     */
    private void applyWideLayout(boolean wide) {
        if (mTabStrip.getParent() != null && wide == mWide) return;
        mWide = wide;
        if (mTabStrip.getParent() != null) ((ViewGroup) mTabStrip.getParent()).removeView(mTabStrip);
        if (wide) {
            mTopBar.setVisibility(GONE);
            mBottomBar.addView(mTabStrip, 2, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 3));
            mSpaceKey.setLayoutParams(spaceParams(1));
        } else {
            mTopBar.setVisibility(VISIBLE);
            mTopBar.addView(mTabStrip, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mSpaceKey.setLayoutParams(spaceParams(1));
        }
    }

    private LinearLayout.LayoutParams spaceParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, mTheme.dp(34), weight);
        p.leftMargin = mTheme.dp(4);
        p.rightMargin = mTheme.dp(4);
        return p;
    }

    private void scrollToSection(int section) {
        mPicker.dismiss();
        mLayoutManager.scrollToPositionWithOffset(mSectionStart[section], 0);
        selectTab(section);
    }

    private void updateSelectedTabFromScroll() {
        int first = mLayoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first >= mItems.size()) return;
        // At the very end the last sections can't reach the top; show the last one.
        if (!mGrid.canScrollVertically(1)) {
            selectTab(mItems.get(mItems.size() - 1).section);
            return;
        }
        selectTab(mItems.get(first).section);
    }

    private void selectTab(int section) {
        if (section == mSelectedTab) return;
        mSelectedTab = section;
        for (int i = 0; i < SECTION_COUNT; ++i) {
            boolean selected = i == section;
            mTheme.tint(mTabs[i], selected ? mTheme.textColor : mTheme.withAlpha(mTheme.textColor, 0x80));
            mTabs[i].setBackground(selected ? mTheme.newPill(0x22) : null);
            mTabs[i].setSelected(selected);
        }
    }

    private void rebuildItems() {
        mItems.clear();
        mSectionStart[0] = 0;
        mItems.add(Item.header(0, getContext().getString(TAB_LABELS[0])));
        if (mData == null) {
            mItems.add(Item.hint(0, getContext().getString(R.string.emoji_loading)));
            for (int i = 1; i < SECTION_COUNT; ++i) mSectionStart[i] = 0;
            mAdapter.notifyDataSetChanged();
            return;
        }
        int recents = 0;
        for (String form : mHistory.getRecents()) {
            EmojiData.Emoji emoji = mData.find(form);
            if (emoji == null) continue; // not renderable on this device any more
            mItems.add(Item.emoji(0, emoji, form));
            ++recents;
        }
        if (recents == 0) {
            mItems.add(Item.hint(0, getContext().getString(R.string.emoji_recents_empty)));
        }
        for (int section = 1; section < SECTION_COUNT; ++section) {
            mSectionStart[section] = mItems.size();
            EmojiData.Category category = findCategory(CATEGORY_IDS[section - 1]);
            if (category == null) continue;
            mItems.add(Item.header(section, getContext().getString(TAB_LABELS[section])));
            for (EmojiData.Emoji emoji : category.emoji) {
                mItems.add(Item.emoji(section, emoji, mHistory.getPreferredForm(emoji)));
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private EmojiData.Category findCategory(String id) {
        for (EmojiData.Category c : mData.getCategories()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    // EmojiCellView.Listener

    public void onEmojiClicked(EmojiCellView cell) {
        mListener.onPaletteKeyFeedback(0);
        mListener.onEmojiPicked(cell.getForm());
    }

    public boolean onEmojiLongPressed(EmojiCellView cell) {
        EmojiData.Emoji emoji = cell.getEmoji();
        if (emoji == null || !emoji.hasVariants()) return false;
        mListener.onPaletteKeyFeedback(0);
        mPicker.show(cell, emoji, cell.getForm(), Math.max(cell.getWidth(), cell.getHeight()));
        return true;
    }

    public void onEmojiPickerGesture(EmojiCellView cell, MotionEvent event) {
        mPicker.onGesture(event);
    }

    // EmojiVariantPicker.Listener

    public void onVariantPicked(EmojiData.Emoji emoji, String form) {
        mHistory.setVariant(emoji, form);
        for (int i = 0; i < mItems.size(); ++i) {
            Item item = mItems.get(i);
            // Recents keep the exact form that was used.
            if (item.emoji == emoji && item.section != 0) {
                item.form = form;
                mAdapter.notifyItemChanged(i);
            }
        }
        mListener.onPaletteKeyFeedback(0);
        mListener.onEmojiPicked(form);
    }

    private static class Item {
        final int type;
        final int section;
        final EmojiData.Emoji emoji;
        String form;
        final String text;

        private Item(int type, int section, EmojiData.Emoji emoji, String form, String text) {
            this.type = type;
            this.section = section;
            this.emoji = emoji;
            this.form = form;
            this.text = text;
        }

        static Item header(int section, String label) {
            return new Item(TYPE_HEADER, section, null, null, label);
        }

        static Item hint(int section, String text) {
            return new Item(TYPE_HINT, section, null, null, text);
        }

        static Item emoji(int section, EmojiData.Emoji emoji, String form) {
            return new Item(TYPE_EMOJI, section, emoji, form, null);
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mItems.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == TYPE_EMOJI) {
                view = new EmojiCellView(context, mTheme);
            } else {
                TextView text = new TextView(context);
                text.setTextColor(mTheme.secondaryTextColor);
                if (viewType == TYPE_HEADER) {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    text.setTypeface(Typeface.DEFAULT_BOLD);
                    text.setAllCaps(true);
                    text.setPadding(mTheme.dp(12), mTheme.dp(8), mTheme.dp(12), mTheme.dp(4));
                } else {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    text.setGravity(Gravity.CENTER);
                    text.setPadding(mTheme.dp(12), mTheme.dp(12), mTheme.dp(12), mTheme.dp(12));
                }
                view = text;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Item item = mItems.get(position);
            if (item.type == TYPE_EMOJI) {
                EmojiCellView cell = (EmojiCellView) holder.itemView;
                ViewGroup.LayoutParams lp = cell.getLayoutParams();
                if (lp.height != mCellHeight) {
                    lp.height = mCellHeight;
                    cell.setLayoutParams(lp);
                }
                cell.bind(item.emoji, item.form, mEmojiTextSize, EmojiPalettesView.this);
            } else {
                ((TextView) holder.itemView).setText(item.text);
            }
        }
    }

    /**
     * A grip bar on top of the palette: dragging it up makes the palette
     * taller to see more emoji at once, dragging it down makes it smaller.
     */
    private class ResizeHandle extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mGrip = new RectF();
        private float mDownY;
        private boolean mDragging;

        ResizeHandle(Context context) {
            super(context);
            mPaint.setColor(mTheme.withAlpha(mTheme.textColor, 0x60));
            setContentDescription(context.getString(R.string.emoji_resize));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = mTheme.dp(mDragging ? 48 : 36);
            float height = mTheme.dp(4);
            float x = (getWidth() - width) / 2f;
            float y = (getHeight() - height) / 2f;
            mGrip.set(x, y, x + width, y + height);
            canvas.drawRoundRect(mGrip, height / 2f, height / 2f, mPaint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPicker.dismiss();
                mDownY = event.getRawY();
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                mListener.onPaletteKeyFeedback(0);
                mListener.onPaletteResizeStart();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) mListener.onPaletteResize(event.getRawY() - mDownY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    mListener.onPaletteResizeEnd();
                    invalidate();
                }
                return true;
            }
            return true;
        }
    }

    /** Gives key-press feedback on touch down, like the keyboard's own keys. */
    private class FeedbackTouchListener implements OnTouchListener {
        private final int mCode;

        FeedbackTouchListener(int code) {
            mCode = code;
        }

        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mListener.onPaletteKeyFeedback(mCode);
            }
            return false;
        }
    }

    /** Delete key: deletes on touch down and keeps deleting while held. */
    private class RepeatingDeleteListener implements OnTouchListener, Runnable {
        private View mView;

        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mView = v;
                v.setPressed(true);
                v.setBackground(mTheme.newPill(0x28));
                mListener.onPaletteKeyFeedback(Keyboard.KEYCODE_DELETE);
                mListener.onPaletteKey(Keyboard.KEYCODE_DELETE);
                v.postDelayed(this, DELETE_REPEAT_START_MS);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(this);
                v.setPressed(false);
                v.setBackground(null);
                mView = null;
                return true;
            }
            return true;
        }

        public void run() {
            if (mView == null) return;
            mListener.onPaletteKey(Keyboard.KEYCODE_DELETE);
            mView.postDelayed(this, DELETE_REPEAT_MS);
        }
    }
}
