/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Execution(ExecutionMode.CONCURRENT)
public class ThresholdTest {

  /** Naive reference implementation: expands every integer into a hash map. */
  private static RoaringBitmap naiveThreshold(int k, RoaringBitmap... bitmaps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (RoaringBitmap bitmap : bitmaps) {
      bitmap.forEach((int value) -> counts.merge(value, 1, Integer::sum));
    }
    RoaringBitmap answer = new RoaringBitmap();
    counts.forEach(
        (value, count) -> {
          if (count >= k) {
            answer.add(value);
          }
        });
    return answer;
  }

  @Test
  public void acceptanceExample() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b = RoaringBitmap.bitmapOf(2, 3, 4);
    RoaringBitmap c = RoaringBitmap.bitmapOf(3, 4, 5);
    RoaringBitmap result = Threshold.threshold(2, a, b, c);
    assertEquals(RoaringBitmap.bitmapOf(2, 3, 4), result);
    // occurrence counts must be visible: 1x1, 2x2, 3x3, 4x2, 5x1
    assertEquals(naiveThreshold(2, a, b, c), result);
    assertEquals(RoaringBitmap.bitmapOf(3), Threshold.threshold(3, a, b, c));
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, 5), Threshold.threshold(1, a, b, c));
  }

  @Test
  public void thresholdOfOneIsUnion() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3, 1 << 16);
    RoaringBitmap b = RoaringBitmap.bitmapOf(3, 4, 5, 2 << 16);
    RoaringBitmap c = RoaringBitmap.bitmapOf(5, 6, 7);
    assertEquals(FastAggregation.or(a, b, c), Threshold.threshold(1, a, b, c));
    assertEquals(RoaringBitmap.or(a, b), Threshold.threshold(1, a, b));
  }

  @Test
  public void thresholdOfNIsIntersection() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3, 4);
    RoaringBitmap b = RoaringBitmap.bitmapOf(2, 3, 4, 5);
    RoaringBitmap c = RoaringBitmap.bitmapOf(3, 4, 5, 6);
    assertEquals(FastAggregation.and(a, b, c), Threshold.threshold(3, a, b, c));
    assertEquals(RoaringBitmap.and(a, b), Threshold.threshold(2, a, b));
  }

  @Test
  public void singleInputReturnsACopy() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap result = Threshold.threshold(1, a);
    assertEquals(a, result);
    // mutating the result must not affect the input
    result.add(1000);
    result.remove(1);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3), a);
  }

  @Test
  public void duplicateInputsCountSeparately() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b = RoaringBitmap.bitmapOf(3, 4, 5);
    // the same bitmap passed twice counts as two inputs
    assertEquals(a, Threshold.threshold(2, a, a));
    assertEquals(RoaringBitmap.bitmapOf(3), Threshold.threshold(3, a, a, b));
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3), Threshold.threshold(2, a, a, b));
    assertEquals(RoaringBitmap.bitmapOf(3, 4, 5), Threshold.threshold(2, a, b, b));
    // k greater than the number of inputs is rejected, not silently empty
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(4, a, a, b));
    // a valid threshold can still yield an empty result
    assertEquals(new RoaringBitmap(), Threshold.threshold(2, a, RoaringBitmap.bitmapOf(4, 5, 6)));
  }

  @Test
  public void crossesContainerBoundaries() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(65535, 65536, 65537, 131072, -1);
    RoaringBitmap b = RoaringBitmap.bitmapOf(65535, 65536, 200000, -1);
    RoaringBitmap c = RoaringBitmap.bitmapOf(65536, 65537, 200000);
    RoaringBitmap result = Threshold.threshold(2, a, b, c);
    assertEquals(RoaringBitmap.bitmapOf(65535, 65536, 65537, 200000, -1), result);
    assertEquals(naiveThreshold(2, a, b, c), result);
    assertEquals(RoaringBitmap.bitmapOf(65536), Threshold.threshold(3, a, b, c));
  }

  @Test
  public void runContainers() {
    RoaringBitmap a = new RoaringBitmap();
    a.add(0L, 100000L);
    a.runOptimize();
    RoaringBitmap b = new RoaringBitmap();
    b.add(50000L, 150000L);
    b.runOptimize();
    RoaringBitmap c = new RoaringBitmap();
    c.add(90000L, 110000L);
    c.runOptimize();
    RoaringBitmap expected = new RoaringBitmap();
    expected.add(50000L, 100000L);
    expected.add(100000L, 110000L); // present in b and c
    expected.runOptimize();
    RoaringBitmap result = Threshold.threshold(2, a, b, c);
    assertEquals(expected, result);
    assertEquals(naiveThreshold(2, a, b, c), result);
    // intersection of the three runs
    RoaringBitmap expectedAll = new RoaringBitmap();
    expectedAll.add(90000L, 100000L);
    assertEquals(expectedAll, Threshold.threshold(3, a, b, c));
  }

  @Test
  public void unsignedHighValues() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(-1, Integer.MIN_VALUE, -65536, 0);
    RoaringBitmap b = RoaringBitmap.bitmapOf(-1, Integer.MIN_VALUE, 1);
    RoaringBitmap c = RoaringBitmap.bitmapOf(-1, 2);
    RoaringBitmap result = Threshold.threshold(2, a, b, c);
    assertEquals(RoaringBitmap.bitmapOf(-1, Integer.MIN_VALUE), result);
    assertEquals(naiveThreshold(2, a, b, c), result);
    assertEquals(RoaringBitmap.bitmapOf(-1), Threshold.threshold(3, a, b, c));
    // -1 is the largest unsigned 32-bit value; it must sort last
    assertEquals(-1, result.last());
    assertEquals(Integer.MIN_VALUE, result.first());
  }

  @Test
  public void invalidArguments() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(1));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(0, a));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(-1, a));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(2, a));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(3, a, a));
    assertThrows(NullPointerException.class, () -> Threshold.threshold(1, a, null));
    assertThrows(NullPointerException.class, () -> Threshold.threshold(1, (RoaringBitmap[]) null));
  }

  @Test
  public void emptyBitmapsAsInputs() {
    RoaringBitmap empty = new RoaringBitmap();
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3);
    assertEquals(new RoaringBitmap(), Threshold.threshold(2, a, empty));
    assertEquals(a, Threshold.threshold(1, a, empty));
    assertEquals(a, Threshold.threshold(2, a, a, empty));
    assertEquals(new RoaringBitmap(), Threshold.threshold(1, empty, empty));
  }

  @Test
  public void inputsAreNotModifiedAndResultDoesNotShareContainers() {
    RoaringBitmap a = RoaringBitmap.bitmapOf(1, 2, 3, 70000);
    RoaringBitmap b = new RoaringBitmap();
    b.add(2L, 80000L);
    b.runOptimize();
    RoaringBitmap c = RoaringBitmap.bitmapOf(2, 3, 4, 70000, 80000);
    RoaringBitmap aCopy = a.clone();
    RoaringBitmap bCopy = b.clone();
    RoaringBitmap cCopy = c.clone();
    RoaringBitmap result = Threshold.threshold(2, a, b, c);
    // inputs unchanged by the computation
    assertEquals(aCopy, a);
    assertEquals(bCopy, b);
    assertEquals(cCopy, c);
    // mutating the result must not leak into the inputs
    result.add(5);
    result.remove(2);
    result.andNot(RoaringBitmap.bitmapOf(3));
    assertEquals(aCopy, a);
    assertEquals(bCopy, b);
    assertEquals(cCopy, c);
    // mutating the inputs must not leak into the result
    RoaringBitmap resultCopy = result.clone();
    a.clear();
    b.clear();
    c.clear();
    assertEquals(resultCopy, result);
  }

  @Test
  public void randomizedAgainstNaiveCounting() {
    Random random = new Random(0x5EED5EEDL);
    for (int round = 0; round < 300; ++round) {
      int n = 1 + random.nextInt(6);
      RoaringBitmap[] bitmaps = new RoaringBitmap[n];
      for (int i = 0; i < n; ++i) {
        bitmaps[i] = randomBitmap(random);
      }
      int k = 1 + random.nextInt(n);
      RoaringBitmap expected = naiveThreshold(k, bitmaps);
      RoaringBitmap actual = Threshold.threshold(k, bitmaps);
      assertEquals(expected, actual, "round=" + round + " k=" + k + " n=" + n);
    }
  }

  private static RoaringBitmap randomBitmap(Random random) {
    RoaringBitmap bitmap = new RoaringBitmap();
    int containerCount = random.nextInt(4);
    for (int c = 0; c < containerCount; ++c) {
      // small key space to force overlaps, plus occasional very high (unsigned) keys
      int key = random.nextBoolean() ? random.nextInt(8) : 0xFFF0 + random.nextInt(16);
      long base = (key & 0xFFFFL) << 16;
      switch (random.nextInt(3)) {
        case 0: // sparse: array containers
          int sparse = 1 + random.nextInt(200);
          for (int j = 0; j < sparse; ++j) {
            bitmap.add((int) (base + random.nextInt(1 << 16)));
          }
          break;
        case 1: // dense: bitmap containers
          int dense = 5000 + random.nextInt(20000);
          for (int j = 0; j < dense; ++j) {
            bitmap.add((int) (base + random.nextInt(1 << 16)));
          }
          break;
        default: // runs
          int runs = 1 + random.nextInt(5);
          for (int r = 0; r < runs; ++r) {
            int start = random.nextInt(1 << 16);
            int length = random.nextInt(20000);
            bitmap.add(base + start, base + Math.min(1 << 16, start + length));
          }
          bitmap.runOptimize();
          break;
      }
    }
    return bitmap;
  }

  @Test
  public void denseInputsPerformanceSmokeTest() {
    // Dense inputs (bitmap containers): the threshold computation must process containers
    // in bulk and stay far ahead of expanding every integer into a hash map.
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
    RoaringBitmap expected = naiveThreshold(4, bitmaps);
    long naiveMillis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(expected, result);
    System.out.printf(
        "dense threshold: %d ms, naive hash-map counting: %d ms, result cardinality %d%n",
        thresholdMillis, naiveMillis, result.getCardinality());
    assertTrue(thresholdMillis < 60_000, "threshold computation took too long");
  }

  @Test
  public void sparseWideInputsPerformanceSmokeTest() {
    // Sparse inputs spread over many containers (a 2^22-wide universe): exercises the
    // per-container workspace reuse.
    Random random = new Random(1337L);
    int n = 8;
    RoaringBitmap[] bitmaps = new RoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      RoaringBitmap bitmap = new RoaringBitmap();
      for (int j = 0; j < 300_000; ++j) {
        bitmap.add(random.nextInt(1 << 22));
      }
      bitmaps[i] = bitmap;
    }
    long start = System.nanoTime();
    RoaringBitmap result = Threshold.threshold(5, bitmaps);
    long thresholdMillis = (System.nanoTime() - start) / 1_000_000;
    start = System.nanoTime();
    RoaringBitmap expected = naiveThreshold(5, bitmaps);
    long naiveMillis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(expected, result);
    System.out.printf(
        "sparse threshold: %d ms, naive hash-map counting: %d ms, result cardinality %d%n",
        thresholdMillis, naiveMillis, result.getCardinality());
    assertTrue(thresholdMillis < 60_000, "threshold computation took too long");
  }
}
