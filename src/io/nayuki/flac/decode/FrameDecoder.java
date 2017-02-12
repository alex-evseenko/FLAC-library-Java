/* 
 * FLAC library (Java)
 * Copyright (c) Project Nayuki. All rights reserved.
 * https://www.nayuki.io/
 */

package io.nayuki.flac.decode;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;


// Decodes a FLAC frame from an input stream into raw audio samples. Note that these objects are
// stateful and not thread-safe, because of the bit input stream field, private temporary arrays, etc.
public final class FrameDecoder {
	
	/*---- Fields ----*/
	
	// Can be changed when there is no active call of readFrame(). Must be not null when readFrame() is called.
	public BitInputStream in;
	
	// Temporary arrays to hold two decoded audio channels. The maximum possible block size is either
	// 65536 from the frame header logic, or 65535 from a strict reading of the FLAC specification.
	// Two buffers are needed due to stereo techniques like mid-side processing, but not more than
	// two buffers because all other multi-channel audio is processed independently per channel.
	private long[] temp0;
	private long[] temp1;
	
	// The number of samples (per channel) in the current block/frame being processed.
	// This value is only valid while the method readFrame() is on the call stack.
	// When readFrame() is active, this value is in the range [1, 65536].
	private int currentBlockSize;
	
	
	
	/*---- Constructors ----*/
	
	// Constructs a frame decoder that initially uses the given stream.
	public FrameDecoder(BitInputStream in) {
		this.in = in;
		temp0 = new long[65536];
		temp1 = new long[65536];
		currentBlockSize = -1;
	}
	
	
	
	/*---- Frame header decoding methods ----*/
	
	// Reads the next frame of FLAC data from the current bit input stream, decodes it,
	// and stores output samples into the given array, and returns a new metadata object.
	// The bit input stream must be initially aligned at a byte boundary. If EOF is encountered before
	// any actual bytes were read, then this returns null. Otherwise this function either successfully
	// decodes a frame and returns a new metadata object, or throws an appropriate exception. A frame
	// may have up to 8 channels and 65536 samples, so the output arrays need to be sized appropriately.
	public FrameMetadata readFrame(int[][] outSamples, int outOffset) throws IOException {
		// Some argument checks (plus more later)
		Objects.requireNonNull(outSamples);
		if (outOffset < 0 || outOffset > outSamples[0].length)
			throw new IndexOutOfBoundsException();
		
		// Preliminaries
		long startByte = in.getByteCount();
		in.resetCrcs();
		int temp = in.readByte();
		if (temp == -1)
			return null;
		FrameMetadata result = new FrameMetadata();
		
		// Read sync bits
		int sync = temp << 6 | in.readUint(6);  // Uint14
		if (sync != 0x3FFE)
			throw new DataFormatException("Sync code expected");
		
		// Read various simple fields
		if (in.readUint(1) != 0)
			throw new DataFormatException("Reserved bit");
		int blockStrategy     = in.readUint(1);
		int blockSizeCode     = in.readUint(4);
		int sampleRateCode    = in.readUint(4);
		int channelAssignment = in.readUint(4);
		if (channelAssignment < 8)
			result.numChannels = channelAssignment + 1;
		else if (8 <= channelAssignment && channelAssignment <= 10)
			result.numChannels = 2;
		else
			throw new DataFormatException("Reserved channel assignment");
		if (outSamples.length < result.numChannels)
			throw new IllegalArgumentException("Output array too small for number of channels");
		result.sampleDepth = decodeSampleDepth(in.readUint(3));
		if (in.readUint(1) != 0)
			throw new DataFormatException("Reserved bit");
		
		// Read and check the frame/sample position field
		long position = readUtf8Integer();  // Reads 1 to 7 bytes
		if (blockStrategy == 0) {
			if ((position >>> 31) != 0)
				throw new DataFormatException("Frame index too large");
			result.frameIndex = (int)position;
			result.sampleOffset = -1;
		} else if (blockStrategy == 1) {
			result.sampleOffset = position;
			result.frameIndex = -1;
		} else
			throw new AssertionError();
		
		// Read variable-length data for some fields
		currentBlockSize = decodeBlockSize(blockSizeCode);  // Reads 0 to 2 bytes
		if (outOffset > outSamples[0].length - currentBlockSize)
			throw new IndexOutOfBoundsException();
		result.blockSize = currentBlockSize;
		result.sampleRate = decodeSampleRate(sampleRateCode);  // Reads 0 to 2 bytes
		int computedCrc8 = in.getCrc8();
		if (in.readUint(8) != computedCrc8)
			throw new DataFormatException("CRC-8 mismatch");
		
		// Do the hard work
		decodeSubframes(result.sampleDepth, channelAssignment, outSamples, outOffset);
		
		// Read padding and footer
		while (in.getBitPosition() != 0) {
			if (in.readUint(1) != 0)
				throw new DataFormatException("Invalid padding bit");
		}
		int computedCrc16 = in.getCrc16();
		if (in.readUint(16) != computedCrc16)
			throw new DataFormatException("CRC-16 mismatch");
		long frameSize = in.getByteCount() - startByte;
		if (frameSize < 10)
			throw new AssertionError();
		if ((int)frameSize != frameSize)
			throw new DataFormatException("Frame size too large");
		result.frameSize = (int)frameSize;
		currentBlockSize = -1;
		return result;
	}
	
	
	// Reads 1 to 7 bytes from the input stream. Return value is a uint36.
	// See: https://hydrogenaud.io/index.php/topic,112831.msg929128.html#msg929128
	private long readUtf8Integer() throws IOException {
		int head = in.readUint(8);
		int n = Integer.numberOfLeadingZeros(~(head << 24));  // Number of leading 1s in the byte
		assert 0 <= n && n <= 8;
		if (n == 0)
			return head;
		else if (n == 1 || n == 8)
			throw new DataFormatException("Invalid UTF-8 coded number");
		else {
			long result = head & (0x7F >>> n);
			for (int i = 0; i < n - 1; i++) {
				int temp = in.readUint(8);
				if ((temp & 0xC0) != 0x80)
					throw new DataFormatException("Invalid UTF-8 coded number");
				result = (result << 6) | (temp & 0x3F);
			}
			if ((result >>> 36) != 0)
				throw new AssertionError();
			return result;
		}
	}
	
	
	// Argument is a uint4 value. Reads 0 to 2 bytes from the input stream.
	// Return value is in the range [1, 65536].
	private int decodeBlockSize(int code) throws IOException {
		if ((code >>> 4) != 0)
			throw new IllegalArgumentException();
		else if (code == 0)
			throw new DataFormatException("Reserved block size");
		else if (code == 1)
			return 192;
		else if (2 <= code && code <= 5)
			return 576 << (code - 2);
		else if (code == 6)
			return in.readUint(8) + 1;
		else if (code == 7)
			return in.readUint(16) + 1;
		else if (8 <= code && code <= 15)
			return 256 << (code - 8);
		else
			throw new AssertionError();
	}
	
	
	// Argument is a uint4 value. Reads 0 to 2 bytes from the input stream.
	// Return value is in the range [-1, 655350].
	private int decodeSampleRate(int code) throws IOException {
		if ((code >>> 4) != 0)
			throw new IllegalArgumentException();
		switch (code) {
			case  0:  return -1;  // Caller should obtain value from stream info metadata block
			case 12:  return in.readUint(8);
			case 13:  return in.readUint(16);
			case 14:  return in.readUint(16) * 10;
			case 15:  throw new DataFormatException("Invalid sample rate");
			default:  return SAMPLE_RATES[code];  // 1 <= code <= 11
		}
	}
	
	private static final int[] SAMPLE_RATES = {-1, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};
	
	
	// Argument is a uint3 value. Pure function and performs no I/O. Return value is in the range [-1, 24].
	private static int decodeSampleDepth(int code) {
		if ((code >>> 3) != 0)
			throw new IllegalArgumentException();
		else if (code == 0)
			return -1;  // Caller should obtain value from stream info metadata block
		else if (SAMPLE_DEPTHS[code] < 0)
			throw new DataFormatException("Reserved bit depth");
		else
			return SAMPLE_DEPTHS[code];
	}
	
	private static final int[] SAMPLE_DEPTHS = {-1, 8, 12, -2, 16, 20, 24, -2};
	
	
	
	/*---- Sub-frame audio data decoding methods ----*/
	
	// Based on the current bit input stream and the two given arguments, this method reads and decodes
	// each subframe, performs stereo decoding if applicable, and writes the final uncompressed audio data
	// to the array range outSamples[0 : numChannels][outOffset : outOffset + currentBlockSize].
	// Note that this method uses the private temporary arrays and passes them into sub-method calls.
	private void decodeSubframes(int sampleDepth, int chanAsgn, int[][] outSamples, int outOffset) throws IOException {
		// Check arguments
		if (sampleDepth < 1 || sampleDepth > 32)
			throw new IllegalArgumentException();
		if ((chanAsgn >>> 4) != 0)
			throw new IllegalArgumentException();
		
		if (0 <= chanAsgn && chanAsgn <= 7) {
			// Handle 1 to 8 independently coded channels
			int numChannels = chanAsgn + 1;
			for (int ch = 0; ch < numChannels; ch++) {
				decodeSubframe(sampleDepth, temp0);
				int[] outChan = outSamples[ch];
				for (int i = 0; i < currentBlockSize; i++)
					outChan[outOffset + i] = checkBitDepth(temp0[i], sampleDepth);
			}
			
		} else if (8 <= chanAsgn && chanAsgn <= 10) {
			// Handle one of the side-coded stereo methods
			decodeSubframe(sampleDepth + (chanAsgn == 9 ? 1 : 0), temp0);
			decodeSubframe(sampleDepth + (chanAsgn == 9 ? 0 : 1), temp1);
			
			if (chanAsgn == 8) {  // Left-side stereo
				for (int i = 0; i < currentBlockSize; i++)
					temp1[i] = temp0[i] - temp1[i];
			} else if (chanAsgn == 9) {  // Side-right stereo
				for (int i = 0; i < currentBlockSize; i++)
					temp0[i] += temp1[i];
			} else if (chanAsgn == 10) {  // Mid-side stereo
				for (int i = 0; i < currentBlockSize; i++) {
					long s = temp1[i];
					long m = (temp0[i] << 1) | (s & 1);
					temp0[i] = (m + s) >> 1;
					temp1[i] = (m - s) >> 1;
				}
			} else
				throw new AssertionError();
			
			// Copy data from temporary to output arrays, and convert from long to int
			int[] outLeft  = outSamples[0];
			int[] outRight = outSamples[1];
			for (int i = 0; i < currentBlockSize; i++) {
				outLeft [outOffset + i] = checkBitDepth(temp0[i], sampleDepth);
				outRight[outOffset + i] = checkBitDepth(temp1[i], sampleDepth);
			}
		} else  // 11 <= channelAssignment <= 15
			throw new DataFormatException("Reserved channel assignment");
	}
	
	
	// Checks that 'val' is a signed 'depth'-bit integer, and either returns the
	// value downcasted to an int or throws an exception if it's out of range.
	// Note that depth must be in the range [1, 32] because the return value is an int.
	// For example when depth = 16, the range of valid values is [-32768, 32767].
	private static int checkBitDepth(long val, int depth) {
		assert 1 <= depth && depth <= 32;
		if ((-(val >> (depth - 1)) | 1) != 1)  // Or equivalently: (val >> (depth - 1)) == 0 || (val >> (depth - 1)) == -1
			throw new IllegalArgumentException(val + " is not a signed " + depth + "-bit value");
		else
			return (int)val;
	}
	
	
	// Reads one subframe from the bit input stream, decodes it, and writes to result[0 : currentBlockSize].
	private void decodeSubframe(int sampleDepth, long[] result) throws IOException {
		if (sampleDepth < 1 || sampleDepth > 33)
			throw new IllegalArgumentException();
		if (in.readUint(1) != 0)
			throw new DataFormatException("Invalid padding bit");
		int type = in.readUint(6);
		int shift = in.readUint(1);  // Also known as "wasted bits-per-sample"
		if (shift == 1) {
			while (in.readUint(1) == 0) {  // Unary coding
				if (shift >= sampleDepth)
					throw new DataFormatException("Waste-bits-per-sample exceeds sample depth");
				shift++;
			}
		}
		if (!(0 <= shift && shift <= sampleDepth))
			throw new AssertionError();
		sampleDepth -= shift;
		
		if (type == 0)  // Constant coding
			Arrays.fill(result, 0, currentBlockSize, in.readSignedInt(sampleDepth));
		else if (type == 1) {  // Verbatim coding
			for (int i = 0; i < currentBlockSize; i++)
				result[i] = in.readSignedInt(sampleDepth);
		} else if (2 <= type && type <= 7)
			throw new DataFormatException("Reserved subframe type");
		else if (8 <= type && type <= 12)
			decodeFixedPredictionSubframe(type - 8, sampleDepth, result);
		else if (13 <= type && type <= 31)
			throw new DataFormatException("Reserved subframe type");
		else if (32 <= type && type <= 63)
			decodeLinearPredictiveCodingSubframe(type - 31, sampleDepth, result);
		else
			throw new AssertionError();
		
		// Add back the trailing zeros to each sample
		if (shift > 0) {
			for (int i = 0; i < currentBlockSize; i++)
				result[i] <<= shift;
		}
	}
	
	
	// Reads from the input stream, performs computation, and writes to result[0 : currentBlockSize].
	private void decodeFixedPredictionSubframe(int predOrder, int sampleDepth, long[] result) throws IOException {
		// Check arguments
		if (sampleDepth < 1 || sampleDepth > 33)
			throw new IllegalArgumentException();
		if (predOrder < 0 || predOrder > 4)
			throw new IllegalArgumentException();
		
		// Read and compute various values
		for (int i = 0; i < predOrder; i++)  // Unpredicted warm-up samples
			result[i] = in.readSignedInt(sampleDepth);
		
		readResiduals(predOrder, result);
		restoreLpc(result, FIXED_PREDICTION_COEFFICIENTS[predOrder], 0);
	}
	
	private static final int[][] FIXED_PREDICTION_COEFFICIENTS = {
		{},
		{1},
		{2, -1},
		{3, -3, 1},
		{4, -6, 4, -1},
	};
	
	
	// Reads from the input stream, performs computation, and writes to result[0 : currentBlockSize].
	private void decodeLinearPredictiveCodingSubframe(int lpcOrder, int sampleDepth, long[] result) throws IOException {
		// Check arguments
		if (sampleDepth < 1 || sampleDepth > 33)
			throw new IllegalArgumentException();
		if (lpcOrder < 1 || lpcOrder > 32)
			throw new IllegalArgumentException();
		
		for (int i = 0; i < lpcOrder; i++)  // Unpredicted warm-up samples
			result[i] = in.readSignedInt(sampleDepth);
		
		// Read parameters for the LPC coefficients
		int precision = in.readUint(4) + 1;
		if (precision == 16)
			throw new DataFormatException("Invalid LPC precision");
		int shift = in.readSignedInt(5);
		if (shift < 0)
			throw new DataFormatException("Invalid LPC shift");
		
		// Read the coefficients themselves
		int[] coefs = new int[lpcOrder];
		for (int i = 0; i < coefs.length; i++)
			coefs[i] = in.readSignedInt(precision);
		
		// Perform the main LPC decoding
		readResiduals(lpcOrder, result);
		restoreLpc(result, coefs, shift);
	}
	
	
	// Updates the values of block[coefs.length : currentBlockSize] according to linear predictive coding.
	// This method reads all the arguments and the field currentBlockSize, only writes to result, and has no other side effects.
	private void restoreLpc(long[] result, int[] coefs, int shift) {
		// Check and handle arguments
		for (int i = coefs.length; i < currentBlockSize; i++) {
			long sum = 0;
			for (int j = 0; j < coefs.length; j++)
				sum += result[i - 1 - j] * coefs[j];
			result[i] += sum >> shift;
		}
	}
	
	
	// Reads metadata and Rice-coded numbers from the input stream, storing them in result[warmup : currentBlockSize].
	private void readResiduals(int warmup, long[] result) throws IOException {
		int method = in.readUint(2);
		if (method >= 2)
			throw new DataFormatException("Reserved residual coding method");
		assert method == 0 || method == 1;
		int paramBits = method == 0 ? 4 : 5;
		int escapeParam = method == 0 ? 0xF : 0x1F;
		
		int partitionOrder = in.readUint(4);
		int numPartitions = 1 << partitionOrder;
		if (currentBlockSize % numPartitions != 0)
			throw new DataFormatException("Block size not divisible by number of Rice partitions");
		for (int inc = currentBlockSize >>> partitionOrder, partEnd = inc, resultIndex = warmup;
				partEnd <= currentBlockSize; partEnd += inc) {
			
			int param = in.readUint(paramBits);
			if (param == escapeParam) {
				int numBits = in.readUint(5);
				for (; resultIndex < partEnd; resultIndex++)
					result[resultIndex] = in.readSignedInt(numBits);
			} else {
				in.readRiceSignedInts(param, result, resultIndex, partEnd);
				resultIndex = partEnd;
			}
		}
	}
	
}
