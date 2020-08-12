// Copyright 2020 The Cabalī Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package nl.co.gram.cabalee;

import android.os.SystemClock;

import com.google.android.gms.common.internal.Preconditions;
import com.google.protobuf.ByteString;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class IDSet {
    public IDSet(long rotateAfterNanos, int gcAfterRotations) {
        this.rotateAfterNanos = rotateAfterNanos;
        this.gcAfterRotations = gcAfterRotations;
        Preconditions.checkArgument(gcAfterRotations >= 1);
        Preconditions.checkArgument(rotateAfterNanos >= 0);
    }

    private static class IDs {
        final Set<ByteString> s = new HashSet<>();
        final long elapsedNanos;


        private IDs(long elapsedNanos) {
            this.elapsedNanos = elapsedNanos;
        }
    }

    private LinkedList<IDs> recentUniqueMessages = new LinkedList<>();

    private final long rotateAfterNanos;
    private final int gcAfterRotations;

    public synchronized boolean checkAndAdd(ByteString bs) {
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        if (recentUniqueMessages.size() == 0) {
            recentUniqueMessages.add(new IDs(elapsedNanos));
        } else if (rotateAfterNanos != 0) {
            if (recentUniqueMessages.getLast().elapsedNanos + rotateAfterNanos < elapsedNanos) {
                recentUniqueMessages.add(new IDs(elapsedNanos));
                if (recentUniqueMessages.size() > gcAfterRotations) {
                    recentUniqueMessages.removeFirst();
                }
            }
        }
        boolean found = false;
        for (IDs i : recentUniqueMessages) {
            if (i.s.contains(bs)) {
                found = true;
                break;
            }
        }
        recentUniqueMessages.getLast().s.add(bs);
        return found;
    }

    public synchronized void trimMemory() {
        while (recentUniqueMessages.size() > 1) {
            recentUniqueMessages.removeFirst();
        }
    }
}
