package org.roaringbitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Pre-deserialization inspection of a serialized Roaring bitmap carried by a {@link ByteBuffer}
 * slice.
 *
 * <p>Unlike {@link RoaringBitmap#deserialize(ByteBuffer)}, an inspection never allocates the
 * bitmap's containers: it walks the serialized header and container stream in place and checks
 * that the bytes fully conform to the portable format (see <a
 * href="https://github.com/RoaringBitmap/RoaringFormatSpec">RoaringFormatSpec</a>). It verifies
 * the format cookie, the number of containers, strictly increasing container keys, the declared
 * cardinality of every container, the container offset table, and the bounds and content of
 * array, bitmap and run containers (sorted elements, sorted and non-overlapping runs, and
 * cardinality matching the container content). All offset and size arithmetic is performed with
 * overflow-safe 64 bit math.
 *
 * <p>The buffer is inspected through {@link ByteBuffer#slice()}; its position, mark, limit and
 * byte order are never modified and read-only buffers are supported. Every reported byte offset
 * is relative to the origin (position) of the inspected slice, so the same offsets are reported
 * regardless of where the bitmap lives inside a larger buffer.
 *
 * <p>Because a preflight reads every container payload, a successful inspection guarantees that
 * deserializing the same slice will not run into malformed structural data. It does not replace
 * cryptographic integrity checks when an adversary could tamper with the bytes.
 */
public final class SerializationPreflight {

  /** Serialization cookie used when at least one container is run encoded. */
  static final int SERIAL_COOKIE = 12347;

  /** Serialization cookie used when the bitmap never contained any run container. */
  static final int SERIAL_COOKIE_NO_RUNCONTAINER = 12346;

  /** Bitmaps with fewer containers omit the offset table when run compression is used. */
  static final int NO_OFFSET_THRESHOLD = 4;

  /** Number of values in the 16 bit space represented by one container. */
  private static final int VALUES_PER_CONTAINER = 1 << 16;

  /** Number of 64 bit words making up a bitmap container payload. */
  private static final int WORDS_PER_BITMAP_CONTAINER = 1 << 10;

  /** Number of bytes used by a bitmap container payload. */
  private static final int BYTES_PER_BITMAP_CONTAINER =
      WORDS_PER_BITMAP_CONTAINER * Long.BYTES;

  /** Array containers carry at most 4096 values; larger containers use the bitmap encoding. */
  private static final int MAX_ARRAY_CARDINALITY = ArrayContainer.DEFAULT_MAX_SIZE;

  private SerializationPreflight() {}

  /** Kinds of problems that can be reported by a preflight inspection. */
  public enum Problem {
    /** The first four bytes are neither of the two supported serialization cookies. */
    INVALID_COOKIE,
    /** The cookie (or size field) encodes an illegal number of containers. */
    INVALID_CONTAINER_COUNT,
    /** The slice ends before the header, offset table or a container payload. */
    TRUNCATED,
    /** Container keys are not strictly increasing unsigned 16 bit values. */
    KEY_NOT_INCREASING,
    /** An entry of the offset table is missing or inconsistent with the payload. */
    INVALID_OFFSET,
    /** An array container is not made of strictly increasing, distinct values. */
    ARRAY_NOT_SORTED,
    /** A run container does not contain strictly increasing, non-overlapping runs. */
    RUN_OVERLAP,
    /** A run reaches past the 16 bit value space of its container. */
    RUN_OUT_OF_RANGE,
    /** The declared cardinality does not match the container content. */
    CARDINALITY_MISMATCH,
    /** More than the allowed number of bytes would have to be inspected. */
    SCAN_LIMIT_EXCEEDED
  }

  /** Outcome of a preflight inspection. */
  public static final class Report {
    private static final Report VALID = new Report(null, -1L, -1);

    private final Problem problem;
    private final long byteOffset;
    private final int containerIndex;

    private Report(Problem problem, long byteOffset, int containerIndex) {
      this.problem = problem;
      this.byteOffset = byteOffset;
      this.containerIndex = containerIndex;
    }

    static Report of(Problem problem, long byteOffset, int containerIndex) {
      return new Report(problem, byteOffset, containerIndex);
    }

    /** @return true if the inspected slice is a well-formed serialized Roaring bitmap */
    public boolean isValid() {
      return problem == null;
    }

    /** @return the first detected problem, or null if the slice is valid */
    public Problem getProblem() {
      return problem;
    }

    /**
     * Byte offset of the first detected problem, relative to the origin (position) of the
     * inspected slice. Header level problems use the offset of the field that could not be read;
     * problems tied to a container payload point into that payload. The value is -1 for a
     * successful inspection.
     *
     * @return the relative byte offset of the first problem, or -1 if the slice is valid
     */
    public long getByteOffset() {
      return byteOffset;
    }

    /**
     * Index of the container involved in the first detected problem. Header level problems with
     * no associated container use -1.
     *
     * @return the zero based container index, or -1 when no container is involved
     */
    public int getContainerIndex() {
      return containerIndex;
    }

    @Override
    public String toString() {
      if (problem == null) {
        return "SerializationPreflight.Report{valid}";
      }
      return "SerializationPreflight.Report{"
          + problem
          + ", byteOffset="
          + byteOffset
          + ", containerIndex="
          + containerIndex
          + '}';
    }
  }

  /**
   * Inspect a serialized bitmap carried by a buffer without building the bitmap. The inspection
   * starts at {@code buffer.position()} and covers exactly {@code buffer.remaining()} bytes;
   * reported offsets are relative to that starting position. The buffer position, mark, limit and
   * byte order are left unchanged and read-only buffers are accepted.
   *
   * @param buffer the buffer slice carrying the serialized bitmap
   * @return a valid report if the slice is a well-formed serialized bitmap, otherwise the first
   *     problem found
   */
  public static Report inspect(ByteBuffer buffer) {
    return inspect(buffer, Long.MAX_VALUE);
  }

  /**
   * Inspect a serialized bitmap carried by a buffer without building the bitmap, limiting how
   * many bytes may be scanned. The inspection starts at {@code buffer.position()} and reported
   * offsets are relative to that starting position. If the serialized data would require reading
   * beyond {@code maxBytes} bytes from the slice origin, a {@link Problem#SCAN_LIMIT_EXCEEDED}
   * report is returned whose byte offset is {@code maxBytes}. The buffer position, mark, limit
   * and byte order are left unchanged and read-only buffers are accepted.
   *
   * @param buffer the buffer slice carrying the serialized bitmap
   * @param maxBytes maximum number of bytes (relative to the slice origin) that may be inspected
   * @return a valid report if the slice is a well-formed serialized bitmap fitting in the scan
   *     limit, otherwise the first problem found
   */
  public static Report inspect(ByteBuffer buffer, long maxBytes) {
    Objects.requireNonNull(buffer, "buffer");
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must be non-negative");
    }
    return new Inspector(buffer.slice(), maxBytes).inspect();
  }

  private static long toUnsignedLong(int value) {
    return value & 0xFFFFFFFFL;
  }

  private static final class Inspector {
    private final ByteBuffer buffer;
    private final long length;
    private final long limit;

    private Inspector(ByteBuffer slice, long maxBytes) {
      this.buffer = slice.order(ByteOrder.LITTLE_ENDIAN);
      this.length = slice.remaining();
      this.limit = Math.min(maxBytes, this.length);
    }

    private Report report(Problem problem, long byteOffset, int containerIndex) {
      return Report.of(problem, byteOffset, containerIndex);
    }

    /**
     * Ensure the half-open region {@code [start, start + size)} is fully inside the slice and
     * the scan limit. Returns null when the region is readable, otherwise the first problem hit.
     */
    private Report requireRegion(long start, long size, int containerIndex) {
      long end = start + size;
      if (start < 0 || end < start || end > length) {
        long offset = Math.min(Math.max(end, 0L), length);
        return report(Problem.TRUNCATED, offset, containerIndex);
      }
      if (end > limit) {
        return report(Problem.SCAN_LIMIT_EXCEEDED, limit, containerIndex);
      }
      return null;
    }

    private int getInt(long position) {
      return buffer.getInt((int) position);
    }

    private int getChar(long position) {
      return buffer.getChar((int) position) & 0xFFFF;
    }

    private boolean isRunContainer(int containerIndex, byte[] runMarkers) {
      return runMarkers != null
          && (runMarkers[containerIndex / 8] & (1 << (containerIndex % 8))) != 0;
    }

    private Report inspect() {
      Report cookieRegionProblem = requireRegion(0, Integer.BYTES, -1);
      if (cookieRegionProblem != null) {
        return cookieRegionProblem;
      }
      final int cookie = getInt(0);
      final boolean runCapable = (cookie & 0xFFFF) == SERIAL_COOKIE;
      if (!runCapable && cookie != SERIAL_COOKIE_NO_RUNCONTAINER) {
        return report(Problem.INVALID_COOKIE, 0, -1);
      }

      final int containerCount;
      if (runCapable) {
        // The high 16 bits of the cookie hold (containerCount - 1).
        int count = (cookie >>> 16) + 1;
        if (count < 1 || count > VALUES_PER_CONTAINER) {
          return report(Problem.INVALID_CONTAINER_COUNT, 0, -1);
        }
        containerCount = count;
      } else {
        Report sizeRegionProblem = requireRegion(Integer.BYTES, Integer.BYTES, -1);
        if (sizeRegionProblem != null) {
          return sizeRegionProblem;
        }
        long count = toUnsignedLong(getInt(Integer.BYTES));
        if (count > VALUES_PER_CONTAINER) {
          return report(Problem.INVALID_CONTAINER_COUNT, Integer.BYTES, -1);
        }
        containerCount = (int) count;
      }

      final int runMarkerBytes = runCapable ? (containerCount + 7) / 8 : 0;
      final boolean hasOffsets =
          !runCapable || containerCount >= NO_OFFSET_THRESHOLD;
      final long keyTableStart =
          (runCapable ? Integer.BYTES : 2L * Integer.BYTES) + runMarkerBytes;
      final long offsetTableStart = keyTableStart + 4L * containerCount;
      final long headerSize =
          offsetTableStart + (hasOffsets ? 4L * containerCount : 0);
      if (headerSize < 0 || headerSize > Integer.MAX_VALUE) {
        return report(Problem.INVALID_CONTAINER_COUNT, 0, -1);
      }
      Report headerProblem = requireRegion(0, headerSize, -1);
      if (headerProblem != null) {
        return headerProblem;
      }

      final byte[] runMarkers;
      if (runCapable) {
        runMarkers = new byte[runMarkerBytes];
        for (int i = 0; i < runMarkerBytes; ++i) {
          runMarkers[i] = buffer.get(Integer.BYTES + i);
        }
      } else {
        runMarkers = null;
      }

      final int[] cardinalities = new int[containerCount];
      int previousKey = -1;
      for (int k = 0; k < containerCount; ++k) {
        long keyPosition = keyTableStart + 4L * k;
        int key = getChar(keyPosition);
        if (key <= previousKey) {
          return report(Problem.KEY_NOT_INCREASING, keyPosition, k);
        }
        previousKey = key;
        cardinalities[k] = getChar(keyPosition + Character.BYTES) + 1;
      }

      long position = headerSize;
      for (int k = 0; k < containerCount; ++k) {
        if (hasOffsets) {
          long offsetEntryPosition = offsetTableStart + 4L * k;
          long declaredOffset = toUnsignedLong(getInt(offsetEntryPosition));
          if (declaredOffset != position) {
            return report(Problem.INVALID_OFFSET, offsetEntryPosition, k);
          }
        }

        final long containerStart = position;
        if (isRunContainer(k, runMarkers)) {
          Report countRegionProblem = requireRegion(containerStart, Character.BYTES, k);
          if (countRegionProblem != null) {
            return countRegionProblem;
          }
          long numRuns = getChar(containerStart);
          long payloadSize = Character.BYTES + 4L * numRuns;
          Report payloadProblem = requireRegion(containerStart, payloadSize, k);
          if (payloadProblem != null) {
            return payloadProblem;
          }

          long runsStart = containerStart + Character.BYTES;
          long runCardinality = 0L;
          int previousRunEnd = -1;
          for (int r = 0; r < numRuns; ++r) {
            long runPairPosition = runsStart + 4L * r;
            int runStart = getChar(runPairPosition);
            int runLengthMinusOne = getChar(runPairPosition + Character.BYTES);
            long runEnd = (long) runStart + runLengthMinusOne;
            if (runEnd >= VALUES_PER_CONTAINER) {
              return report(Problem.RUN_OUT_OF_RANGE, runPairPosition, k);
            }
            if (runStart <= previousRunEnd) {
              return report(Problem.RUN_OVERLAP, runPairPosition, k);
            }
            runCardinality += runLengthMinusOne + 1L;
            if (runCardinality > VALUES_PER_CONTAINER) {
              return report(Problem.CARDINALITY_MISMATCH, runPairPosition, k);
            }
            previousRunEnd = (int) runEnd;
          }
          if (runCardinality != cardinalities[k]) {
            return report(Problem.CARDINALITY_MISMATCH, containerStart, k);
          }
          position += payloadSize;
        } else if (cardinalities[k] > MAX_ARRAY_CARDINALITY) {
          Report bitmapProblem =
              requireRegion(containerStart, BYTES_PER_BITMAP_CONTAINER, k);
          if (bitmapProblem != null) {
            return bitmapProblem;
          }
          long popcount = 0L;
          for (int w = 0; w < WORDS_PER_BITMAP_CONTAINER; ++w) {
            long word = buffer.getLong((int) (containerStart + (long) w * Long.BYTES));
            popcount += Integer.bitCount((int) word);
            popcount += Integer.bitCount((int) (word >>> 32));
          }
          if (popcount != cardinalities[k]) {
            return report(Problem.CARDINALITY_MISMATCH, containerStart, k);
          }
          position += BYTES_PER_BITMAP_CONTAINER;
        } else {
          long payloadSize = (long) cardinalities[k] * Character.BYTES;
          Report arrayProblem = requireRegion(containerStart, payloadSize, k);
          if (arrayProblem != null) {
            return arrayProblem;
          }
          int previousValue = -1;
          for (int i = 0; i < cardinalities[k]; ++i) {
            long valuePosition = containerStart + 2L * i;
            int value = getChar(valuePosition);
            if (value <= previousValue) {
              return report(Problem.ARRAY_NOT_SORTED, valuePosition, k);
            }
            previousValue = value;
          }
          position += payloadSize;
        }
      }

      return Report.VALID;
    }
  }
}
