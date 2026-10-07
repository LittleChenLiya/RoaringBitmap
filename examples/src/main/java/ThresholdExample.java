/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.Threshold;
import org.roaringbitmap.buffer.BufferThreshold;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

/**
 * Reproducible demonstration of the threshold aggregation (integers appearing in at least k of many
 * bitmaps), together with a performance comparison against naive per-integer counting.
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
    RoaringBitmap b1 = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b2 = RoaringBitmap.bitmapOf(2, 3, 4);
    RoaringBitmap b3 = RoaringBitmap.bitmapOf(3, 4, 5);
    RoaringBitmap result = Threshold.threshold(2, b1, b2, b3);
    System.out.println("== acceptance demo ==");
    System.out.println("inputs: {1,2,3}, {2,3,4}, {3,4,5}, k = 2");
    for (int value = 1; value <= 5; ++value) {
      int count =
          (b1.contains(value) ? 1 : 0)
              + (b2.contains(value) ? 1 : 0)
              + (b3.contains(value) ? 1 : 0);
      System.out.println(
          "  value "
              + value
              + " occurs in "
              + count
              + " bitmap(s) -> "
              + (count >= 2 ? "kept" : "dropped"));
    }
    System.out.println("result: " + result + " (expected {2,3,4})");
    if (!result.equals(RoaringBitmap.bitmapOf(2, 3, 4))) {
      throw new AssertionError("unexpected result " + result);
    }
    // mutating the result leaves the inputs untouched
    result.add(1000);
    result.remove(2);
    System.out.println(
        "after mutating the result, inputs are unchanged: "
            + b1.equals(RoaringBitmap.bitmapOf(1, 2, 3))
            + ", "
            + b2.equals(RoaringBitmap.bitmapOf(2, 3, 4))
            + ", "
            + b3.equals(RoaringBitmap.bitmapOf(3, 4, 5)));
    System.out.println();
  }

  /** The buffer-based counterpart shares the same counting engine. */
  static void bufferDemo() {
    MutableRoaringBitmap b1 = new MutableRoaringBitmap();
    b1.add(0L, 100000L);
    MutableRoaringBitmap b2 = new MutableRoaringBitmap();
    b2.add(50000L, 150000L);
    b2.runOptimize();
    MutableRoaringBitmap result = BufferThreshold.threshold(2, b1, b2);
    System.out.println("== buffer demo ==");
    System.out.println(
        "threshold(2, [0,100000), [50000,150000)) = ["
            + result.first()
            + ","
            + result.last()
            + "] with cardinality "
            + result.getCardinality()
            + " (expected [50000,99999] with cardinality 50000)");
    if (result.getCardinality() != 50000 || result.first() != 50000 || result.last() != 99999) {
      throw new AssertionError("unexpected buffer result");
    }
    System.out.println();
  }

  /**
   * Performance verification: the threshold operator works container-wise and never expands
   * integers into a hash map; the naive baseline does. Fixed seed, so the numbers are reproducible
   * up to machine speed.
   */
  static void performanceDemo() {
    System.out.println("== performance demo (fixed seed 42) ==");
    int n = 8;
    int k = 4;
    RoaringBitmap[] bitmaps = buildBitmaps(42L, n);
    long totalValues = 0;
    for (RoaringBitmap bitmap : bitmaps) {
      totalValues += bitmap.getLongCardinality();
    }
    System.out.println(
        "inputs: "
            + n
            + " bitmaps, "
            + totalValues
            + " stored values in total (mixed array/bitmap/run containers), k = "
            + k);

    // warm up
    for (int i = 0; i < 3; ++i) {
      Threshold.threshold(k, bitmaps).getCardinality();
      naiveThreshold(k, bitmaps).getCardinality();
    }
    int rounds = 5;
    long bestThreshold = Long.MAX_VALUE;
    long bestNaive = Long.MAX_VALUE;
    RoaringBitmap result = null;
    for (int i = 0; i < rounds; ++i) {
      long start = System.nanoTime();
      result = Threshold.threshold(k, bitmaps);
      bestThreshold = Math.min(bestThreshold, System.nanoTime() - start);
      start = System.nanoTime();
      RoaringBitmap reference = naiveThreshold(k, bitmaps);
      bestNaive = Math.min(bestNaive, System.nanoTime() - start);
      if (!reference.equals(result)) {
        throw new AssertionError("threshold result does not match naive counting");
      }
    }
    System.out.println("result cardinality: " + result.getLongCardinality());
    System.out.println(
        "threshold (container-level, no integer expansion): "
            + bestThreshold / 1_000_000.0
            + " ms");
    System.out.println(
        "naive hash-map counting (expands every integer):   " + bestNaive / 1_000_000.0 + " ms");
    System.out.println("speedup: " + (bestNaive / (double) bestThreshold) + "x");

    // buffer-based variant on the same data
    ImmutableRoaringBitmap[] bufferBitmaps = new ImmutableRoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      bufferBitmaps[i] = bitmaps[i].toMutableRoaringBitmap();
    }
    long start = System.nanoTime();
    MutableRoaringBitmap bufferResult = BufferThreshold.threshold(k, bufferBitmaps);
    long elapsed = System.nanoTime() - start;
    System.out.println(
        "buffer threshold: "
            + elapsed / 1_000_000.0
            + " ms, cardinality "
            + bufferResult.getLongCardinality());
    if (bufferResult.getLongCardinality() != result.getLongCardinality()) {
      throw new AssertionError("buffer result differs");
    }
  }

  /** Builds n bitmaps with a mix of sparse, dense and run-compressed containers. */
  static RoaringBitmap[] buildBitmaps(long seed, int n) {
    Random random = new Random(seed);
    RoaringBitmap[] bitmaps = new RoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      RoaringBitmap bitmap = new RoaringBitmap();
      // dense bands shared by many bitmaps (bitmap containers)
      for (int band = 0; band < 4; ++band) {
        long start = random.nextInt(1 << 24);
        bitmap.add(start, start + 300_000 + random.nextInt(200_000));
      }
      // runs (run containers after runOptimize)
      for (int run = 0; run < 8; ++run) {
        long start = random.nextInt(1 << 22);
        bitmap.add(start, start + random.nextInt(20_000));
      }
      // sparse noise (array containers)
      for (int v = 0; v < 50_000; ++v) {
        bitmap.add(random.nextInt(1 << 26));
      }
      bitmap.runOptimize();
      bitmaps[i] = bitmap;
    }
    return bitmaps;
  }

  /** Naive reference: expands every integer into a hash map and counts occurrences. */
  static RoaringBitmap naiveThreshold(int k, RoaringBitmap... bitmaps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (RoaringBitmap bitmap : bitmaps) {
      for (int value : bitmap) {
        counts.merge(value, 1, Integer::sum);
      }
    }
    RoaringBitmap answer = new RoaringBitmap();
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      if (entry.getValue() >= k) {
        answer.add(entry.getKey());
      }
    }
    return answer;
  }
}
