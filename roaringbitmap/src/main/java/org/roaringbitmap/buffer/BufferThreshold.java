/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap.buffer;

import org.roaringbitmap.ContainerBatchIterator;
import org.roaringbitmap.Threshold;

import java.nio.CharBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Buffer-based counterpart of {@link org.roaringbitmap.Threshold}: given {@code n} input
 * bitmaps and a threshold {@code k} (with {@code 1 <= k <= n}), compute the set of integers
 * that appear in <b>at least k</b> of the input bitmaps.
 *
 * <p>As with {@link org.roaringbitmap.Threshold}, {@code k == 1} is the union of the inputs
 * and {@code k == n} is their intersection. Inputs are consumed as a multiset: passing the
 * same bitmap twice counts it twice.
 *
 * <p>The computation is performed container by container, in bulk, without expanding the
 * contained integers into a hash map, and shares its counting kernel with
 * {@link org.roaringbitmap.Threshold}. The inputs are never modified and the result does
 * not share any mutable container with the inputs.
 */
public final class BufferThreshold {

  private BufferThreshold() {}

  /**
   * Computes the integers present in at least {@code k} of the given bitmaps.
   *
   * @param k the threshold, must satisfy {@code 1 <= k <= bitmaps.length}
   * @param bitmaps the input bitmaps (a multiset: repeated arguments count repeatedly);
   *     must contain at least one non-null bitmap
   * @return a new mutable bitmap containing exactly the integers that appear in at least
   *     {@code k} of the inputs; the inputs are left unmodified
   * @throws IllegalArgumentException if {@code bitmaps} is empty or {@code k} is out of
   *     range {@code [1, bitmaps.length]}
   * @throws NullPointerException if {@code bitmaps} or any of its elements is null
   */
  public static MutableRoaringBitmap threshold(int k, ImmutableRoaringBitmap... bitmaps) {
    Objects.requireNonNull(bitmaps, "bitmaps");
    final int n = bitmaps.length;
    if (n == 0) {
      throw new IllegalArgumentException("at least one input bitmap is required");
    }
    if (k < 1 || k > n) {
      throw new IllegalArgumentException("k must be in [1, " + n + "] but was " + k);
    }
    for (ImmutableRoaringBitmap bitmap : bitmaps) {
      Objects.requireNonNull(bitmap, "input bitmaps must be non-null");
    }
    MutableRoaringBitmap answer = new MutableRoaringBitmap();
    PriorityQueue<MappeableContainerPointer> pq = new PriorityQueue<>(n);
    for (ImmutableRoaringBitmap bitmap : bitmaps) {
      MappeableContainerPointer pointer = bitmap.highLowContainer.getContainerPointer();
      if (pointer.getContainer() != null) {
        pq.add(pointer);
      }
    }
    Threshold.Workspace workspace = null;
    List<MappeableContainerPointer> advanced = new ArrayList<>(n);
    List<MappeableContainer> containers = new ArrayList<>(n);
    while (!pq.isEmpty()) {
      char key = pq.peek().key();
      containers.clear();
      advanced.clear();
      while (!pq.isEmpty() && pq.peek().key() == key) {
        MappeableContainerPointer pointer = pq.poll();
        containers.add(pointer.getContainer());
        pointer.advance();
        advanced.add(pointer);
      }
      if (k != 1 && k != n && workspace == null) {
        workspace = new Threshold.Workspace();
      }
      MappeableContainer result = thresholdContainer(k, n, containers, workspace);
      if (result != null && !result.isEmpty()) {
        answer.getMappeableRoaringArray().append(key, result);
      }
      for (MappeableContainerPointer pointer : advanced) {
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
  private static MappeableContainer thresholdContainer(
      int k, int n, List<MappeableContainer> containers, Threshold.Workspace workspace) {
    int m = containers.size();
    if (k == 1) { // union of the containers present at this key
      if (m == 1) {
        return containers.get(0).clone();
      }
      MappeableContainer acc = containers.get(0).lazyOR(containers.get(1));
      for (int i = 2; i < m; ++i) {
        acc = acc.lazyIOR(containers.get(i));
      }
      return acc.repairAfterLazy();
    }
    if (k == n) { // intersection: every input must have a container at this key
      if (m < n) {
        return null;
      }
      MappeableContainer acc = containers.get(0).and(containers.get(1));
      for (int i = 2; i < m && !acc.isEmpty(); ++i) {
        acc = acc.and(containers.get(i));
      }
      return acc.isEmpty() ? null : acc;
    }
    // 1 < k < n: exact per-value counting over the containers at this key,
    // using the same kernel as the RoaringBitmap variant
    ContainerBatchIterator[] iterators = new ContainerBatchIterator[m];
    for (int i = 0; i < m; ++i) {
      iterators[i] = containers.get(i).getBatchIterator();
    }
    int cardinality = Threshold.countValuesAtLeast(iterators, k, workspace);
    if (cardinality == 0) {
      return null;
    }
    return toContainer(workspace.bits, cardinality);
  }

  /**
   * Builds a fresh container from the selected bits. The returned container never shares
   * state with the input bits array.
   */
  private static MappeableContainer toContainer(long[] bits, int cardinality) {
    if (cardinality <= MappeableArrayContainer.DEFAULT_MAX_SIZE) {
      char[] values = new char[cardinality];
      int pos = 0;
      for (int w = 0; w < bits.length; ++w) {
        long word = bits[w];
        while (word != 0) {
          values[pos++] = (char) ((w << 6) + Long.numberOfTrailingZeros(word));
          word &= (word - 1);
        }
      }
      return new MappeableArrayContainer(CharBuffer.wrap(values), cardinality);
    }
    return new MappeableBitmapContainer(
        LongBuffer.wrap(Arrays.copyOf(bits, bits.length)), cardinality);
  }
}
