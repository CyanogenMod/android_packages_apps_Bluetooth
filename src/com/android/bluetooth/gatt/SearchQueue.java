/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.gatt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Helper class to store characteristics and descriptors that will be
 * queued up for future exploration.
 * @hide
 */
/*package*/ class SearchQueue {
    class Entry {
        public int connId;
        public int srvcType;
        public int srvcInstId;
        public long srvcUuidLsb;
        public long srvcUuidMsb;
        public int charInstId;
        public long charUuidLsb;
        public long charUuidMsb;
    }

    private List<Entry> mEntries = new ArrayList<Entry>();

    void add(int connId, int srvcType,
            int srvcInstId, long srvcUuidLsb, long srvcUuidMsb) {
        Entry entry = new Entry();
        entry.connId = connId;
        entry.srvcType = srvcType;
        entry.srvcInstId = srvcInstId;
        entry.srvcUuidLsb = srvcUuidLsb;
        entry.srvcUuidMsb = srvcUuidMsb;
        entry.charUuidLsb = 0;
        mEntries.add(entry);
    }

    void add(int connId, int srvcType,
        int srvcInstId, long srvcUuidLsb, long srvcUuidMsb,
        int charInstId, long charUuidLsb, long charUuidMsb)
    {
        Entry entry = new Entry();
        entry.connId = connId;
        entry.srvcType = srvcType;
        entry.srvcInstId = srvcInstId;
        entry.srvcUuidLsb = srvcUuidLsb;
        entry.srvcUuidMsb = srvcUuidMsb;
        entry.charInstId = charInstId;
        entry.charUuidLsb = charUuidLsb;
        entry.charUuidMsb = charUuidMsb;
        mEntries.add(entry);
    }

    Entry pop() {
        Entry entry = mEntries.get(0);
        mEntries.remove(0);
        return entry;
    }

    void removeConnId(int connId) {
        for (Iterator<Entry> it = mEntries.iterator(); it.hasNext();) {
            Entry entry = it.next();
            if (entry.connId == connId) {
                it.remove();
            }
        }
    }

    boolean isEmpty() {
        return mEntries.isEmpty();
    }

    void clear() {
        mEntries.clear();
    }
}
