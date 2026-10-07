/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

/**
 * Threshold aggregation over many bitmaps: select the integers that appear in at least {@code k} of
 * the input bitmaps.
 *
 * <p>For example, given the bitmaps {1,2,3}, {2,3,4} and {3,4,5} with {@code k = 2}, the result is
 * {2,3,4} because 2, 3 and 4 each appear in at least two of the three inputs (while 1 and 5 appear
 * only once).
 *
 * <p>The computation is performed container by container: inputs are never expanded into a hash map
 * of integers, and the inputs are never modified. The result does not share any mutable container
 * with the inputs, so it can be freely mutated afterwards. The same bitmap may be passed several
 * times; each occurrence counts as a separate input. A buffer-based counterpart operating on {@link
 * org.roaringbitmap.buffer.ImmutableRoaringBitmap} is available in {@link
 * org.roaringbitmap.buffer.BufferThreshold}; both share the same counting engine ({@link
 * ContainerThreshold}).
 */
public final class Threshold {

  private Threshold() {}

  /**
   * Computes the integers that appear in at least {@code k} of the input bitmaps.
   *
   * <p>Special cases: {@code k = 1} is equivalent to a bitwise OR of all inputs, and {@code k = n}
   * (where {@code n} is the number of inputs) is equivalent to a bitwise AND.
   *
   * @param k the threshold, must satisfy {@code 1 <= k <= bitmaps.length}
   * @param bitmaps the input bitmaps; none of them is modified, and repeated occurrences of the
   *     same bitmap each count as a separate input. An empty array yields an empty bitmap.
   * @return a new bitmap containing exactly the integers present in at least {@code k} inputs
   * @throws IllegalArgumentException if {@code k} is out of the range {@code [1, bitmaps.length]}
   * @throws NullPointerException if {@code bitmaps} or any of its elements is null
   */
  public static RoaringBitmap threshold(int k, RoaringBitmap... bitmaps) {
    if (bitmaps == null) {
      throw new NullPointerException("bitmaps must not be null");
    }
    int n = bitmaps.length;
    if (n == 0) {
      // Convention: the threshold aggregate of zero inputs is the empty bitmap.
      return new RoaringBitmap();
    }
    if (k < 1 || k > n) {
      throw new IllegalArgumentException("k must be within [1, " + n + "] but was " + k);
    }
    RoaringArray[] arrays = new RoaringArray[n];
    for (int i = 0; i < n; ++i) {
      if (bitmaps[i] == null) {
        throw new NullPointerException("bitmaps[" + i + "] must not be null");
      }
      arrays[i] = bitmaps[i].highLowContainer;
    }
    RoaringBitmap answer = new RoaringBitmap();
    int[] positions = new int[n];
    Container[] containers = new Container[n];
    ContainerBatchIterator[] iterators = new ContainerBatchIterator[n];
    ContainerThreshold engine = null; // allocated lazily, only if the counting path is needed
    while (true) {
      // Find the smallest high key still available among the inputs (k-way merge by key).
      char key = 0;
      boolean found = false;
      for (int i = 0; i < n; ++i) {
        RoaringArray array = arrays[i];
        if (positions[i] < array.size()) {
          char candidate = array.getKeyAtIndex(positions[i]);
          if (!found || candidate < key) {
            key = candidate;
            found = true;
          }
        }
      }
      if (!found) {
        break;
      }
      // Gather the containers held at this key; a bitmap without this key contributes nothing.
      int m = 0;
      for (int i = 0; i < n; ++i) {
        RoaringArray array = arrays[i];
        if (positions[i] < array.size() && array.getKeyAtIndex(positions[i]) == key) {
          containers[m++] = array.getContainerAtIndex(positions[i]);
          positions[i]++;
        }
      }
      if (m < k) {
        continue; // not enough containers at this key to ever reach the threshold
      }
      Container result;
      if (k == 1) {
        result = orAll(containers, m);
      } else if (k == m) {
        result = andAll(containers, m);
      } else {
        for (int i = 0; i < m; ++i) {
          iterators[i] = containers[i].getBatchIterator();
        }
        if (engine == null) {
          engine = new ContainerThreshold();
        }
        result = engine.threshold(iterators, m, k);
      }
      if (result != null && !result.isEmpty()) {
        answer.highLowContainer.append(key, result);
      }
    }
    return answer;
  }

  // Union of the containers; only fresh containers are produced, inputs are left untouched.
  private static Container orAll(Container[] containers, int m) {
    if (m == 1) {
      return containers[0].clone();
    }
    Container acc = containers[0].lazyOR(containers[1]);
    for (int i = 2; i < m; ++i) {
      acc = acc.lazyIOR(containers[i]);
    }
    return acc.repairAfterLazy();
  }

  // Intersection of the containers; Container.and never modifies its operands.
  private static Container andAll(Container[] containers, int m) {
    Container acc = containers[0];
    for (int i = 1; i < m; ++i) {
      acc = acc.and(containers[i]);
      if (acc.isEmpty()) {
        break;
      }
    }
    return acc;
  }
}
