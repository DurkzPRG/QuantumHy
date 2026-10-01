package com.durkz.quantumhy.view;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Per-viewer selection memory and reusable bounded heap. Accessed by one ECS worker at a time. */
final class StableEntitySelection<T> {
    private Set<T> previous = identitySet();
    private Set<T> next = identitySet();
    private Set<T> verticalExcluded = identitySet();
    private Set<T> nextVerticalExcluded = identitySet();
    private final Set<T> heapSelected = identitySet();
    private Object world;
    private Object settings;
    private boolean sampled;
    private T previousTarget;
    private T mount;
    private T target;
    private double targetDistance;
    private long entered;
    private long exited;
    private Object[] refs = new Object[0];
    private double[] distances = new double[0];
    private int size;
    private int capacity;
    private boolean keepNearest;
    private Object[] candidateRefs = new Object[0];
    private double[] candidateDistances = new double[0];
    private int candidateCount;
    private int protectedCandidates;
    private final Set<T> capDrops = identitySet();

    void begin(Object world, Object settings) {
        if (!Objects.equals(this.world, world) || this.settings != settings) {
            previous.clear();
            verticalExcluded.clear();
            previousTarget = null;
            sampled = false;
            this.world = world;
            this.settings = settings;
        }
        next.clear();
        nextVerticalExcluded.clear();
        clearHeap();
        clearCandidates();
        mount = null;
        target = null;
        targetDistance = Double.POSITIVE_INFINITY;
    }

    void protectMount(T mount) {
        this.mount = mount;
    }

    void considerInteraction(T ref, double distanceSquared) {
        if (distanceSquared < targetDistance
                || (distanceSquared == targetDistance && ref == previousTarget)) {
            target = ref;
            targetDistance = distanceSquared;
        }
    }

    boolean protectedEntity(T ref) {
        return ref == mount || ref == target;
    }

    boolean excludeVertically(T ref, double dy, int limit) {
        if (limit <= 0 || protectedEntity(ref)) {
            return false;
        }
        double threshold = verticalExcluded.contains(ref) ? Math.max(0, limit - 2) : limit;
        if (Math.abs(dy) > threshold) {
            nextVerticalExcluded.add(ref);
            return true;
        }
        return false;
    }

    double score(T ref, double distanceSquared) {
        return distanceSquared * (previous.contains(ref) ? 0.81D : 1.0D);
    }

    /**
     * Records a non-player entity that survived the vertical cull, with its squared distance, so the
     * cap pass needs no second component lookup. Protected refs are only counted.
     */
    void stashCandidate(T ref, double distanceSquared) {
        if (protectedEntity(ref)) {
            protectedCandidates++;
            return;
        }
        if (candidateCount == candidateRefs.length) {
            int length = candidateRefs.length * 2 + 16;
            candidateRefs = java.util.Arrays.copyOf(candidateRefs, length);
            candidateDistances = java.util.Arrays.copyOf(candidateDistances, length);
        }
        candidateRefs[candidateCount] = ref;
        candidateDistances[candidateCount] = distanceSquared;
        candidateCount++;
    }

    /**
     * Picks which stashed candidates to drop so at most {@code cap} non-player entities stay,
     * protected ones included. Returns the drop set (empty when under the cap), valid until the
     * next {@link #begin} or {@link #finish}.
     */
    @SuppressWarnings("unchecked")
    Set<T> selectCapDrops(int cap) {
        capDrops.clear();
        int budget = Math.max(0, cap - protectedCandidates);
        if (candidateCount <= budget) {
            return capDrops;
        }
        prepareHeap(candidateCount, budget);
        for (int i = 0; i < candidateCount; i++) {
            offer((T) candidateRefs[i], candidateDistances[i]);
        }
        selectHeap();
        for (int i = 0; i < candidateCount; i++) {
            T ref = (T) candidateRefs[i];
            if (dropForCap(ref)) {
                capDrops.add(ref);
            }
        }
        return capDrops;
    }

    void prepareHeap(int eligible, int budget) {
        clearHeap();
        int keep = Math.max(0, budget);
        int overflow = Math.max(0, eligible - keep);
        keepNearest = keep <= overflow;
        capacity = Math.min(keep, overflow);
        if (refs.length < capacity) {
            int length = Math.max(capacity, refs.length * 2 + 8);
            refs = new Object[length];
            distances = new double[length];
        }
    }

    void offer(T ref, double distanceSquared) {
        if (capacity == 0) {
            return;
        }
        double distance = score(ref, distanceSquared);
        if (size < capacity) {
            int index = size++;
            refs[index] = ref;
            distances[index] = distance;
            siftUp(index);
        } else if ((keepNearest && distance < distances[0])
                || (!keepNearest && distance > distances[0])) {
            refs[0] = ref;
            distances[0] = distance;
            siftDown(0);
        }
    }

    @SuppressWarnings("unchecked")
    void selectHeap() {
        for (int i = 0; i < size; i++) {
            heapSelected.add((T) refs[i]);
        }
    }

    boolean dropForCap(T ref) {
        return !protectedEntity(ref) && (keepNearest != heapSelected.contains(ref));
    }

    void finish(Collection<T> visible) {
        entered = 0;
        for (T ref : visible) {
            next.add(ref);
            if (sampled && !previous.remove(ref)) {
                entered++;
            }
        }
        exited = sampled ? previous.size() : 0;
        previous.clear();
        Set<T> swap = previous;
        previous = next;
        next = swap;
        verticalExcluded.clear();
        swap = verticalExcluded;
        verticalExcluded = nextVerticalExcluded;
        nextVerticalExcluded = swap;
        previousTarget = target;
        mount = null;
        target = null;
        clearHeap();
        clearCandidates();
        sampled = true;
    }

    long entered() {
        return entered;
    }

    long exited() {
        return exited;
    }

    private void clearHeap() {
        for (int i = 0; i < size; i++) {
            refs[i] = null;
        }
        size = 0;
        heapSelected.clear();
    }

    private void clearCandidates() {
        for (int i = 0; i < candidateCount; i++) {
            candidateRefs[i] = null;
        }
        candidateCount = 0;
        protectedCandidates = 0;
        capDrops.clear();
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (ordered(distances[parent], distances[index])) {
                return;
            }
            swap(parent, index);
            index = parent;
        }
    }

    private void siftDown(int index) {
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < size && preferred(distances[right], distances[child])) {
                child = right;
            }
            if (ordered(distances[index], distances[child])) {
                return;
            }
            swap(index, child);
            index = child;
        }
    }

    private boolean ordered(double parent, double child) {
        return keepNearest ? parent >= child : parent <= child;
    }

    private boolean preferred(double candidate, double current) {
        return keepNearest ? candidate > current : candidate < current;
    }

    private void swap(int a, int b) {
        Object ref = refs[a];
        refs[a] = refs[b];
        refs[b] = ref;
        double distance = distances[a];
        distances[a] = distances[b];
        distances[b] = distance;
    }
}
