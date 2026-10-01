/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

import java.nio.ByteBuffer;

/**
 * Preflight checker for serialized RoaringBitmap data (the portable format described at
 * https://github.com/RoaringBitmap/RoaringFormatSpec).
 *
 * <p>Unlike {@link RoaringBitmap#deserialize(ByteBuffer)} followed by {@link
 * RoaringBitmap#validate()}, this checker never materializes the bitmap: it scans the serialized
 * bytes directly and verifies the structural integrity of the payload. It checks the cookie, the
 * container count, that keys are strictly increasing, that declared cardinalities are consistent
 * with the container contents, that the offset table (when present) matches the actual container
 * layout without overlaps, and that array/bitmap/run containers stay within their bounds. Array
 * containers are checked for sortedness, run containers for non-overlapping runs, and all offset
 * arithmetic is performed with overflow-safe (64-bit) operations.
 *
 * <p>When a problem is found, the result reports the byte offset of the first problem relative to
 * the start of the provided slice, together with the index of the container involved (or -1 when
 * the problem is in the header).
 *
 * <p>The provided {@link ByteBuffer} is not modified: neither its position, limit, mark nor byte
 * order. Read-only, direct and array-backed buffers are supported. The data is expected to start
 * at the buffer's current position; all reported offsets are relative to that position.
 */
public final class RoaringBitmapPreflight {

  private static final int SERIAL_COOKIE_NO_RUNCONTAINER = 12346;
  private static final int SERIAL_COOKIE = 12347;
  private static final int NO_OFFSET_THRESHOLD = 4;
  private static final int MAX_CONTAINER_COUNT = 1 << 16;
  private static final int BITMAP_CONTAINER_BYTES = BitmapContainer.MAX_CAPACITY / 8;
  private static final int ARRAY_CONTAINER_MAX_CARDINALITY = ArrayContainer.DEFAULT_MAX_SIZE;

  private RoaringBitmapPreflight() {}

  /** Outcome of a preflight check. */
  public static final class Result {
    private final boolean valid;
    private final long errorOffset;
    private final int containerIndex;
    private final String message;
    private final long serializedSizeInBytes;

    private Result(
        boolean valid,
        long errorOffset,
        int containerIndex,
        String message,
        long serializedSizeInBytes) {
      this.valid = valid;
      this.errorOffset = errorOffset;
      this.containerIndex = containerIndex;
      this.message = message;
      this.serializedSizeInBytes = serializedSizeInBytes;
    }

    /** @return true if the serialized payload passed all checks. */
    public boolean isValid() {
      return valid;
    }

    /**
     * @return the byte offset, relative to the start of the checked slice, of the first problem
     *     found, or -1 when the payload is valid.
     */
    public long errorOffset() {
      return errorOffset;
    }

    /**
     * @return the index of the container involved in the first problem, or -1 when the problem is
     *     in the header (or when the payload is valid).
     */
    public int containerIndex() {
      return containerIndex;
    }

    /** @return a human-readable description of the first problem ("ok" when valid). */
    public String message() {
      return message;
    }

    /**
     * @return the size in bytes of the serialized bitmap, or -1 when the payload is invalid. Note
     *     that the checked buffer may legitimately hold more bytes than this (trailing data).
     */
    public long serializedSizeInBytes() {
      return serializedSizeInBytes;
    }

    @Override
    public String toString() {
      if (valid) {
        return "RoaringBitmapPreflight.Result{valid, serializedSizeInBytes="
            + serializedSizeInBytes
            + "}";
      }
      return "RoaringBitmapPreflight.Result{invalid: "
          + message
          + " at offset "
          + errorOffset
          + " (container "
          + containerIndex
          + ")}";
    }
  }

  /**
   * Check the serialized bitmap starting at the current position of the given buffer.
   *
   * @param buffer the serialized data (not modified by this call)
   * @return the check result
   */
  public static Result check(ByteBuffer buffer) {
    return check(buffer, MAX_CONTAINER_COUNT);
  }

  /**
   * Check the serialized bitmap starting at the current position of the given buffer, rejecting
   * payloads declaring more containers than the given scan limit.
   *
   * @param buffer the serialized data (not modified by this call)
   * @param maxContainers the maximum number of containers to scan (scan limit)
   * @return the check result
   */
  public static Result check(ByteBuffer buffer, int maxContainers) {
    if (buffer == null) {
      throw new NullPointerException("buffer");
    }
    if (maxContainers < 0) {
      throw new IllegalArgumentException("maxContainers must be >= 0");
    }
    // Slice so that all offsets are relative to the caller's current position and so that the
    // caller's buffer state (position/limit/order) is left untouched. Works with read-only
    // buffers since we only perform absolute gets.
    ByteBuffer buf = buffer.slice().order(LITTLE_ENDIAN);
    final long limit = buf.remaining();

    if (limit < 4) {
      return error(0, -1, "truncated: cannot read the cookie");
    }
    final int cookie = buf.getInt(0);
    final boolean hasRunContainers = (cookie & 0xFFFF) == SERIAL_COOKIE;
    if (!hasRunContainers && cookie != SERIAL_COOKIE_NO_RUNCONTAINER) {
      return error(0, -1, "bad cookie: " + cookie);
    }
    final int size;
    final long pos;
    if (hasRunContainers) {
      size = (cookie >>> 16) + 1;
      pos = 4;
    } else {
      if (limit < 8) {
        return error(4, -1, "truncated: cannot read the container count");
      }
      size = buf.getInt(4);
      if (size < 0) {
        return error(4, -1, "negative container count: " + size);
      }
      pos = 8;
    }
    if (size > MAX_CONTAINER_COUNT) {
      return error(hasRunContainers ? 0 : 4, -1, "container count too large: " + size);
    }
    if (size > maxContainers) {
      return error(
          hasRunContainers ? 0 : 4,
          -1,
          "container count " + size + " exceeds the scan limit of " + maxContainers);
    }

    // Bitmap of run containers.
    long cursor = pos;
    final int runBitmapBytes = hasRunContainers ? (size + 7) / 8 : 0;
    if (cursor + runBitmapBytes > limit) {
      return error(cursor, -1, "truncated: cannot read the run-container bitmap");
    }
    final long runBitmapPos = cursor;
    cursor += runBitmapBytes;

    // Keys and cardinalities.
    if (cursor + 4L * size > limit) {
      return error(cursor, -1, "truncated: cannot read the keys and cardinalities");
    }
    final char[] keys = new char[size];
    final int[] cardinalities = new int[size];
    final boolean[] isRun = new boolean[size];
    for (int k = 0; k < size; ++k) {
      final long entryPos = cursor + 4L * k;
      keys[k] = buf.getChar((int) entryPos);
      cardinalities[k] = 1 + (0xFFFF & buf.getChar((int) (entryPos + 2)));
      if (k > 0 && keys[k] <= keys[k - 1]) {
        return error(entryPos, k, "keys are not strictly increasing");
      }
      if (hasRunContainers) {
        isRun[k] = (buf.get((int) (runBitmapPos + k / 8)) & (1 << (k % 8))) != 0;
      }
    }
    cursor += 4L * size;

    // Offset table (omitted for small bitmaps that carry a run-container bitmap).
    final boolean hasOffsets = !hasRunContainers || size >= NO_OFFSET_THRESHOLD;
    final long offsetsPos = cursor;
    if (hasOffsets) {
      if (cursor + 4L * size > limit) {
        return error(cursor, -1, "truncated: cannot read the offset table");
      }
      cursor += 4L * size;
    }

    // Containers. All cumulative sizes are tracked with 64-bit arithmetic to avoid overflow.
    long expected = cursor;
    for (int k = 0; k < size; ++k) {
      final int cardinality = cardinalities[k];
      final boolean bitmap = !isRun[k] && cardinality > ARRAY_CONTAINER_MAX_CARDINALITY;
      final long containerSize;
      if (isRun[k]) {
        if (expected + 2 > limit) {
          return error(expected, k, "truncated: cannot read the run count");
        }
        final int runCount = 0xFFFF & buf.getChar((int) expected);
        containerSize = 2 + 4L * runCount;
      } else if (bitmap) {
        containerSize = BITMAP_CONTAINER_BYTES;
      } else {
        containerSize = 2L * cardinality;
      }
      if (expected + containerSize > limit) {
        return error(expected, k, "truncated: container data is missing");
      }
      if (hasOffsets) {
        final long offsetEntryPos = offsetsPos + 4L * k;
        final long storedOffset = buf.getInt((int) offsetEntryPos);
        if (storedOffset < 0) {
          return error(offsetEntryPos, k, "negative container offset: " + storedOffset);
        }
        if (storedOffset != expected) {
          return error(
              offsetEntryPos,
              k,
              "container offset mismatch: expected " + expected + " but found " + storedOffset);
        }
      }
      if (isRun[k]) {
        Result res = checkRunContainer(buf, expected, cardinality, k);
        if (res != null) {
          return res;
        }
      } else if (bitmap) {
        Result res = checkBitmapContainer(buf, expected, cardinality, k);
        if (res != null) {
          return res;
        }
      } else {
        Result res = checkArrayContainer(buf, expected, cardinality, k);
        if (res != null) {
          return res;
        }
      }
      expected += containerSize;
    }
    return new Result(true, -1L, -1, "ok", expected);
  }

  private static Result checkArrayContainer(
      ByteBuffer buf, long containerPos, int cardinality, int k) {
    int previous = -1;
    for (int j = 0; j < cardinality; ++j) {
      final long valuePos = containerPos + 2L * j;
      final int value = 0xFFFF & buf.getChar((int) valuePos);
      if (value <= previous) {
        return error(valuePos, k, "array container values are not strictly increasing");
      }
      previous = value;
    }
    return null;
  }

  private static Result checkBitmapContainer(
      ByteBuffer buf, long containerPos, int cardinality, int k) {
    long count = 0;
    for (int word = 0; word < BITMAP_CONTAINER_BYTES / 8; ++word) {
      count += Long.bitCount(buf.getLong((int) (containerPos + 8L * word)));
    }
    if (count != cardinality) {
      return error(
          containerPos,
          k,
          "bitmap container cardinality mismatch: declared "
              + cardinality
              + " but found "
              + count);
    }
    return null;
  }

  private static Result checkRunContainer(
      ByteBuffer buf, long containerPos, int cardinality, int k) {
    final int runCount = 0xFFFF & buf.getChar((int) containerPos);
    long previousEnd = -1;
    long count = 0;
    for (int r = 0; r < runCount; ++r) {
      final long runPos = containerPos + 2 + 4L * r;
      final int value = 0xFFFF & buf.getChar((int) runPos);
      final int length = 0xFFFF & buf.getChar((int) (runPos + 2));
      final long end = (long) value + length;
      if (end > 0xFFFF) {
        return error(runPos, k, "run overflows the container range");
      }
      if (value <= previousEnd) {
        return error(runPos, k, "overlapping or unsorted runs");
      }
      previousEnd = end;
      count += length + 1L;
    }
    if (count != cardinality) {
      return error(
          containerPos,
          k,
          "run container cardinality mismatch: declared " + cardinality + " but found " + count);
    }
    return null;
  }

  private static Result error(long offset, int containerIndex, String message) {
    return new Result(false, offset, containerIndex, message, -1L);
  }
}
