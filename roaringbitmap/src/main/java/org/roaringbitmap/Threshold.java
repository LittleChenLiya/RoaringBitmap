/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Threshold aggregation over many bitmaps: given {@code n} input bitmaps and a threshold
 * {@code k} (with {@code 1 <= k <= n}), compute the set of integers that appear in
 * <b>at least k</b> of the input bitmaps.
 *
 * <p>This is a generalization of the classical set aggregates:
 *
 * <ul>
 *   <li>{@code k == 1} is the union (OR) of the inputs;
 *   <li>{@code k == n} is the intersection (AND) of the inputs;
 *   <li>{@code 1 < k < n} selects the values whose frequency across the inputs reaches k.
 * </ul>
 *
 * <p>Inputs are consumed as a multiset: if the same bitmap instance (or an equal bitmap) is
 * passed several times, each occurrence counts as a separate input. For example
 * {@code threshold(2, x, x)} returns a copy of {@code x}.
 *
 * <p>The computation is performed container by container (in bulk, on 16-bit container
 * chunks) and never expands the contained integers into a hash map, so both sparse and dense
 * inputs are handled efficiently. The inputs are never modified, and the result does not
 * share any mutable container with the inputs.
 *
 * <p>The buffer-based counterpart of this class is
 * {@link org.roaringbitmap.buffer.BufferThreshold}; both share the same counting kernel
 * ({@link #countValuesAtLeast(ContainerBatchIterator[], int, Workspace)}).
 */
public final class Threshold {

  /** Number of distinct values inside one container (2^16). */
  static final int CONTAINER_CAPACITY = 1 << 16;

  private Threshold() {}

  /**
   * Computes the integers present in at least {@code k} of the given bitmaps.
   *
   * @param k the threshold, must satisfy {@code 1 <= k <= bitmaps.length}
   * @param bitmaps the input bitmaps (a multiset: repeated arguments count repeatedly);
   *     must contain at least one non-null bitmap
   * @return a new bitmap containing exactly the integers that appear in at least {@code k}
   *     of the inputs; the inputs are left unmodified
   * @throws IllegalArgumentException if {@code bitmaps} is empty or {@code k} is out of
   *     range {@code [1, bitmaps.length]}
   * @throws NullPointerException if {@code bitmaps} or any of its elements is null
   */
  public static RoaringBitmap threshold(int k, RoaringBitmap... bitmaps) {
    Objects.requireNonNull(bitmaps, "bitmaps");
    final int n = checkArguments(k, bitmaps.length);
    for (RoaringBitmap bitmap : bitmaps) {
      Objects.requireNonNull(bitmap, "input bitmaps must be non-null");
    }
    RoaringBitmap answer = new RoaringBitmap();
    PriorityQueue<ContainerPointer> pq = new PriorityQueue<>(n);
    for (RoaringBitmap bitmap : bitmaps) {
      ContainerPointer pointer = bitmap.highLowContainer.getContainerPointer();
      if (pointer.getContainer() != null) {
        pq.add(pointer);
      }
    }
    Workspace workspace = null;
    List<ContainerPointer> advanced = new ArrayList<>(n);
    List<Container> containers = new ArrayList<>(n);
    while (!pq.isEmpty()) {
      char key = pq.peek().key();
      containers.clear();
      advanced.clear();
      while (!pq.isEmpty() && pq.peek().key() == key) {
        ContainerPointer pointer = pq.poll();
        containers.add(pointer.getContainer());
        pointer.advance();
        advanced.add(pointer);
      }
      if (k != 1 && k != n && workspace == null) {
        workspace = new Workspace();
      }
      Container result = thresholdContainer(k, n, containers, workspace);
      if (result != null && !result.isEmpty()) {
        answer.highLowContainer.append(key, result);
      }
      for (ContainerPointer pointer : advanced) {
        if (pointer.getContainer() != null) {
          pq.add(pointer);
        }
      }
    }
    return answer;
  }

  /**
   * Computes the thresholded container for one key, given all the input containers at that
   * key. Returns null when no value qualifies.
   */
  private static Container thresholdContainer(
      int k, int n, List<Container> containers, Workspace workspace) {
    int m = containers.size();
    if (k == 1) { // union of the containers present at this key
      if (m == 1) {
        return containers.get(0).clone();
      }
      Container acc = containers.get(0).lazyOR(containers.get(1));
      for (int i = 2; i < m; ++i) {
        acc = acc.lazyIOR(containers.get(i));
      }
      return acc.repairAfterLazy();
    }
    if (k == n) { // intersection: every input must have a container at this key
      if (m < n) {
        return null;
      }
      Container acc = containers.get(0).and(containers.get(1));
      for (int i = 2; i < m && !acc.isEmpty(); ++i) {
        acc = acc.and(containers.get(i));
      }
      return acc.isEmpty() ? null : acc;
    }
    // 1 < k < n: exact per-value counting over the containers at this key
    ContainerBatchIterator[] iterators = new ContainerBatchIterator[m];
    for (int i = 0; i < m; ++i) {
      iterators[i] = containers.get(i).getBatchIterator();
    }
    int cardinality = countValuesAtLeast(iterators, k, workspace);
    if (cardinality == 0) {
      return null;
    }
    return toContainer(workspace.bits, cardinality);
  }

  /**
   * Shared counting kernel used by both {@link Threshold} and
   * {@link org.roaringbitmap.buffer.BufferThreshold}.
   *
   * <p>Counts, for every 16-bit value, in how many of the given container iterators it
   * appears, and sets the bits of the values whose count is at least {@code k} in
   * {@code workspace.bits}. The containers are consumed in bulk (batch by batch) and no
   * hash map is involved. The workspace is left clean (all counters back to zero) so it can
   * be reused for the next container.
   *
   * @param iterators one batch iterator per input container (all covering the same key);
   *     the iterators are consumed entirely
   * @param k the threshold, must satisfy {@code 1 <= k <= iterators.length}
   * @param workspace a reusable workspace; on return, {@code workspace.bits} holds the
   *     selected values and remains valid until the next call with the same workspace
   * @return the number of selected values (the cardinality of {@code workspace.bits})
   */
  public static int countValuesAtLeast(
      ContainerBatchIterator[] iterators, int k, Workspace workspace) {
    int[] counts = workspace.counts;
    int[] touched = workspace.touched;
    long[] bits = workspace.bits;
    Arrays.fill(bits, 0L);
    int touchedCount = 0;
    int[] batch = new int[2048];
    for (ContainerBatchIterator iterator : iterators) {
      while (iterator.hasNext()) {
        int consumed = iterator.next(0, batch);
        for (int i = 0; i < consumed; ++i) {
          int value = batch[i] & 0xFFFF;
          if (counts[value] == 0) {
            touched[touchedCount++] = value;
          }
          ++counts[value];
        }
      }
    }
    int cardinality = 0;
    for (int i = 0; i < touchedCount; ++i) {
      int value = touched[i];
      if (counts[value] >= k) {
        bits[value >>> 6] |= (1L << value);
        ++cardinality;
      }
      counts[value] = 0; // reset for the next use of the workspace
    }
    return cardinality;
  }

  /**
   * Builds a fresh container from the selected bits. The returned container never shares
   * state with the input bits array.
   */
  private static Container toContainer(long[] bits, int cardinality) {
    if (cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
      char[] values = new char[cardinality];
      int pos = 0;
      for (int w = 0; w < bits.length; ++w) {
        long word = bits[w];
        while (word != 0) {
          values[pos++] = (char) ((w << 6) + Long.numberOfTrailingZeros(word));
          word &= (word - 1);
        }
      }
      return new ArrayContainer(values);
    }
    return new BitmapContainer(Arrays.copyOf(bits, bits.length), cardinality);
  }

  private static int checkArguments(int k, int n) {
    if (n == 0) {
      throw new IllegalArgumentException("at least one input bitmap is required");
    }
    if (k < 1 || k > n) {
      throw new IllegalArgumentException("k must be in [1, " + n + "] but was " + k);
    }
    return n;
  }

  /**
   * Reusable scratch space for {@link #countValuesAtLeast(ContainerBatchIterator[], int,
   * Workspace)}. Not thread-safe: use one workspace per thread.
   */
  public static final class Workspace {
    private final int[] counts = new int[CONTAINER_CAPACITY];
    private final int[] touched = new int[CONTAINER_CAPACITY];
    /**
     * The 1024-word bitset of selected values, valid between a call to
     * {@link Threshold#countValuesAtLeast} and the next call using this workspace.
     */
    public final long[] bits = new long[CONTAINER_CAPACITY / 64];

    /** Creates a new, clean workspace. */
    public Workspace() {}
  }
}
