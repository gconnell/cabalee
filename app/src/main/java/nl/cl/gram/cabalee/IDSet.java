package nl.cl.gram.cabalee;

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
