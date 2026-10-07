/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

/**
 * Shared container-level counting engine behind {@link Threshold} (for {@link RoaringBitmap}) and
 * {@link org.roaringbitmap.buffer.BufferThreshold} (for {@link
 * org.roaringbitmap.buffer.ImmutableRoaringBitmap}).
 *
 * <p>The engine receives, for one 16-bit high key at a time, one {@link ContainerBatchIterator} per
 * input container holding that key, and counts how many of these containers contain each 16-bit
 * value. Values whose count reaches the threshold {@code k} are emitted as a fresh {@link
 * Container}. Counting is done with a fixed-size per-key tally table indexed by the 16-bit value:
 * integers are never expanded into a hash map, and the work per key is proportional to the number
 * of values actually present plus the number of distinct values touched, so both sparse and dense
 * containers are handled without scanning all 2^16 possible values.
 *
 * <p>Instances are not thread-safe, but a single instance may be reused for any number of keys and
 * any number of threshold computations; all internal state is reset between calls.
 */
public final class ContainerThreshold {

  private static final int CONTAINER_CAPACITY = 1 << 16;
  private static final int BATCH_SIZE = 8192;

  // Tallies for the current key; counts[value] is the number of containers seen so far that
  // contain value. Entries are reset to zero before each call returns.
  private final int[] counts = new int[CONTAINER_CAPACITY];
  // Values whose tally was incremented at least once for the current key, in first-touch order.
  private final int[] touched = new int[CONTAINER_CAPACITY];
  private final int[] batch = new int[BATCH_SIZE];

  /**
   * Computes the values that appear in at least {@code k} of the provided containers.
   *
   * @param iterators one batch iterator per input container (for a single shared high key), only
   *     the first {@code count} entries are used; each iterator is consumed and released
   * @param count the number of iterators to use (the number of containers at this key)
   * @param k the threshold, must satisfy {@code 1 <= k <= count}
   * @return a fresh container holding the qualifying values, or {@code null} if no value qualifies;
   *     the returned container is never shared with any input
   */
  public Container threshold(ContainerBatchIterator[] iterators, int count, int k) {
    if (k < 1 || k > count) {
      throw new IllegalArgumentException("k must be within [1, " + count + "] but was " + k);
    }
    final int[] counts = this.counts;
    final int[] touched = this.touched;
    final int[] batch = this.batch;
    int touchedCount = 0;
    for (int i = 0; i < count; ++i) {
      ContainerBatchIterator iterator = iterators[i];
      while (iterator.hasNext()) {
        int used = iterator.next(0, batch);
        for (int j = 0; j < used; ++j) {
          int value = batch[j] & 0xFFFF;
          if (counts[value]++ == 0) {
            touched[touchedCount++] = value;
          }
        }
      }
      iterator.releaseContainer();
    }
    long[] bits = null;
    int cardinality = 0;
    for (int t = 0; t < touchedCount; ++t) {
      int value = touched[t];
      int tally = counts[value];
      counts[value] = 0; // reset the tally table for the next key
      if (tally >= k) {
        if (bits == null) {
          bits = new long[CONTAINER_CAPACITY / 64];
        }
        bits[value >>> 6] |= 1L << value;
        ++cardinality;
      }
    }
    if (cardinality == 0) {
      return null;
    }
    Container container = new BitmapContainer(bits, cardinality);
    if (cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
      container = ((BitmapContainer) container).toArrayContainer();
    }
    return container;
  }
}
