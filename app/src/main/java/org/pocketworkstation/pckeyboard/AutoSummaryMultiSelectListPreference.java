/*
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
import android.preference.MultiSelectListPreference;
import android.util.AttributeSet;

import java.util.Set;

/** A list of checkboxes whose summary shows the checked entries. */
public class AutoSummaryMultiSelectListPreference extends MultiSelectListPreference {

    public AutoSummaryMultiSelectListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setValues(Set<String> values) {
        super.setValues(values);
        updateSummary();
    }

    private void updateSummary() {
        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        if (entries == null || entryValues == null) return;
        Set<String> values = getValues();
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < entryValues.length && i < entries.length; ++i) {
            if (!values.contains(entryValues[i].toString())) continue;
            if (summary.length() > 0) summary.append(", ");
            summary.append(entries[i]);
        }
        setSummary(summary.length() > 0 ? summary
                : getContext().getString(R.string.summary_switchable_layouts_none));
    }
}
