/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.Threshold;
import org.roaringbitmap.buffer.BufferThreshold;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Reproducible demonstration of the threshold aggregation
 * ({@link Threshold} / {@link BufferThreshold}): keep the integers that appear in at least
 * k of the input bitmaps.
 *
 * <p>Run with: ./gradlew :examples:runExampleThresholdExample
 */
public class ThresholdExample {

  public static void main(String[] args) {
    acceptanceDemo();
    bufferDemo();
    performanceDemo();
  }

  /** The acceptance scenario: {1,2,3}, {2,3,4}, {3,4,5} with k = 2 gives {2,3,4}. */
  static void acceptanceDemo() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b = RoaringBitmap.bitmapOf(2, 3, 4);
    RoaringBitmap c = RoaringBitmap.bitmapOf(3, 4, 5);

    // show the occurrence counts behind the result
    Map<Integer, Integer> counts = new TreeMap<>();
    for (RoaringBitmap bitmap : new RoaringBitmap[] {a, b, c}) {
      bitmap.forEach((int value) -> counts.merge(value, 1, Integer::sum));
    }
    System.out.println("inputs: " + a + " " + b + " " + c);
    System.out.println("occurrence counts: " + counts);
    System.out.println("threshold(k=2): " + Threshold.threshold(2, a, b, c));
    System.out.println("threshold(k=1) = union: " + Threshold.threshold(1, a, b, c));
    System.out.println("threshold(k=3) = intersection: " + Threshold.threshold(3, a, b, c));
    // duplicate inputs count as separate inputs
    System.out.println("threshold(k=2, a, a): " + Threshold.threshold(2, a, a));
  }

  /** The buffer-based variant exposes the same operation. */
  static void bufferDemo() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    MutableRoaringBitmap b = MutableRoaringBitmap.bitmapOf(2, 3, 4);
    MutableRoaringBitmap c = MutableRoaringBitmap.bitmapOf(3, 4, 5);
    System.out.println("buffer threshold(k=2): " + BufferThreshold.threshold(2, a, b, c));
  }

  /**
   * Deterministic (fixed-seed) comparison between the bulk, container-wise threshold and
   * naive counting that expands every integer into a hash map.
   */
  static void performanceDemo() {
    Random random = new Random(42L);
    int n = 8;
    int universe = 1_500_000;
    RoaringBitmap[] bitmaps = new RoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      RoaringBitmap bitmap = new RoaringBitmap();
      for (int j = 0; j < 1_000_000; ++j) {
        bitmap.add(random.nextInt(universe));
      }
      bitmaps[i] = bitmap;
    }
    long start = System.nanoTime();
    RoaringBitmap result = Threshold.threshold(4, bitmaps);
    long thresholdMillis = (System.nanoTime() - start) / 1_000_000;

    start = System.nanoTime();
    Map<Integer, Integer> counts = new HashMap<>();
    for (RoaringBitmap bitmap : bitmaps) {
      bitmap.forEach((int value) -> counts.merge(value, 1, Integer::sum));
    }
    final long[] cardinality = {0};
    counts.forEach((value, count) -> cardinality[0] += count >= 4 ? 1 : 0);
    long naiveMillis = (System.nanoTime() - start) / 1_000_000;

    System.out.println("dense inputs (8 bitmaps x 1M values):");
    System.out.println(
        "  bulk threshold:        " + thresholdMillis + " ms, cardinality "
            + result.getCardinality());
    System.out.println(
        "  naive hash-map count:  " + naiveMillis + " ms, cardinality " + cardinality[0]);
    if (result.getCardinality() != cardinality[0]) {
      throw new IllegalStateException("mismatch between bulk and naive computation");
    }
  }
}
