package org.red5.io.obu;

import static org.red5.io.obu.OBPConstants.OBU_FRAME_TYPE_BITSHIFT;
import static org.red5.io.obu.OBPConstants.OBU_FRAME_TYPE_MASK;
import static org.red5.io.obu.OBUType.FRAME;
import static org.red5.io.obu.OBUType.FRAME_HEADER;
import static org.red5.io.obu.OBUType.METADATA;
import static org.red5.io.obu.OBUType.PADDING;
import static org.red5.io.obu.OBUType.REDUNDANT_FRAME_HEADER;
import static org.red5.io.obu.OBUType.SEQUENCE_HEADER;
import static org.red5.io.obu.OBUType.TEMPORAL_DELIMITER;
import static org.red5.io.obu.OBUType.TILE_GROUP;
import static org.red5.io.obu.OBUType.TILE_LIST;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.red5.io.utils.HexDump;
import org.red5.io.utils.LEB128;
import org.red5.io.utils.LEB128.LEB128Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

/**
 * Parsers OBU providing headers and extract relevant data. Logic is derived from the C code in the obuparser project.
 *
 * @author Paul Gregoire
 */
public class OBUParser {

    private static final Logger log = LoggerFactory.getLogger(OBUParser.class);

    /** Constant <code>OBU_START_FRAGMENT_BIT=(byte) 0b1000_0000</code> */
    public static final byte OBU_START_FRAGMENT_BIT = (byte) 0b1000_0000; // 0b1'0000'000

    /** Constant <code>OBU_END_FRAGMENT_BIT=0b0100_0000</code> */
    public static final byte OBU_END_FRAGMENT_BIT = 0b0100_0000;

    /** Constant <code>OBU_START_SEQUENCE_BIT=0b0000_1000</code> */
    public static final byte OBU_START_SEQUENCE_BIT = 0b0000_1000;

    /** Constant <code>OBU_COUNT_MASK=0b0011_0000</code> */
    public static final byte OBU_COUNT_MASK = 0b0011_0000;

    /** Constant <code>OBU_TYPE_MASK=0b0111_1000</code> */
    public static final byte OBU_TYPE_MASK = 0b0111_1000;

    /** Constant <code>OBU_SIZE_PRESENT_BIT=0b0000_0010</code> */
    public static final byte OBU_SIZE_PRESENT_BIT = 0b0000_0010; // 0b0'0000'010

    /** Constant <code>OBU_EXT_BIT=0b0000_0100</code> */
    public static final byte OBU_EXT_BIT = 0b0000_0100; // 0b0'0000'100

    /** Constant <code>OBU_EXT_S1T1_BIT=0b0010_1000</code> */
    public static final byte OBU_EXT_S1T1_BIT = 0b0010_1000; // 0b001'01'000

    /** Constant <code>OBU_TYPE_SHIFT=3</code> */
    public static final byte OBU_TYPE_SHIFT = 3;

    private static final Set<Integer> VALID_OBU_TYPES = Set.of(SEQUENCE_HEADER.getValue(), TEMPORAL_DELIMITER.getValue(), FRAME_HEADER.getValue(), TILE_GROUP.getValue(), METADATA.getValue(), FRAME.getValue(), REDUNDANT_FRAME_HEADER.getValue(), TILE_LIST.getValue(), PADDING.getValue());


    /**
     * Parses the next OBU header in a packet containing a set of one or more OBUs
     * (e.g. an IVF or ISOBMFF packet) and returns its location in the buffer, as well as all
     * relevant data from the header.
     *
     * @param buf     Input packet buffer.
     * @param offset  The offset into the buffer where this OBU starts.
     * @param bufSize Size of the input packet buffer.
     * @return An {@link OBUInfo} object containing the OBU type, offset, size, and parsed data.
     * @throws OBUParseException If the buffer is too small, the OBU type is invalid, or parsing fails.
     */
    public static OBUInfo getNextObu(byte[] buf, int offset, int bufSize) throws OBUParseException {
        log.trace("getNextObu - buffer length: {} size: {} offset: {}", buf.length, bufSize, offset);

        // Validation
        validateBuffer(buf, offset, bufSize);

        OBUInfo info = new OBUInfo();
        int pos = offset;

        // Type Header
        parseObuType(buf, pos, info);
        boolean hasExtension = obuHasExtension(buf[pos]);
        boolean hasSizeField = obuHasSize(buf[pos]);
        log.trace("OBU type: {} extension? {} size field? {}", info.getObuType(), hasExtension, hasSizeField);
        pos++; // move past the OBU header

        // Extension Header
        if (hasExtension) {
            parseExtensionHeader(buf, bufSize, pos, info);
            pos++; // move past the OBU extension header
        }

        // Sizing
        if (hasSizeField) {
            LEB128Result result = readObuSizeValue(buf, pos);
            info.setSize(result.value);
            pos += result.bytesRead;
            log.trace("OBU had size field: {}", info.getSize());
        } else {
            info.setSize(bufSize - pos);
        }
        log.trace("OBU size: {}", info.getSize());

        // Data
        finalizeObuData(buf, bufSize, pos, info);

        return info;
    }


    /**
     * Parses a sequence header OBU and extracts the relevant data.
     *
     * @param buf     Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize Size of the input OBU buffer.
     * @return An {@link OBPSequenceHeader} object filled with the parsed data.
     * @throws OBUParseException If the sequence header cannot be parsed or the buffer is invalid.
     */
    public static OBPSequenceHeader parseSequenceHeader(byte[] buf, int bufSize) throws OBUParseException {
        OBUParserStrategy<OBPSequenceHeader> strategy = new OBUSequenceHeaderParser();
        return strategy.parse(buf, bufSize);
    }


    /**
     * Parses a frame OBU and fills out the fields in the user-provided {@link OBPFrameHeader}
     * and {@link OBPTileGroup} structures.
     *
     * @param buf             Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize         Size of the input OBU buffer.
     * @param seq             The sequence header associated with this frame.
     * @param state           An opaque state structure. Must be zeroed by the user on first use.
     * @param temporalId      A temporal ID previously obtained from the sequence header.
     * @param spatialId       A spatial ID previously obtained from the sequence header.
     * @param fh              The {@link OBPFrameHeader} structure to be filled with the parsed data.
     * @param tileGroup       The {@link OBPTileGroup} structure to be filled with the parsed data.
     * @param seenFrameHeader Tracking variable as per AV1 spec indicating if a frame header has been seen.
     * @throws OBUParseException If the frame header or tile group fails to parse.
     */
    public static void parseFrame(byte[] buf, int bufSize, OBPSequenceHeader seq, OBPState state, int temporalId, int spatialId, OBPFrameHeader fh, OBPTileGroup tileGroup, AtomicBoolean seenFrameHeader) throws OBUParseException {
        OBUFrameParser strategy = new OBUFrameParser();
        strategy.parseFrame(buf, bufSize, seq, state, temporalId, spatialId, fh, tileGroup, seenFrameHeader);
    }


    /**
     * Parses a metadata OBU and extracts the relevant fields.
     * This OBU's returned payload is *NOT* safe to use once the input 'buf' has
     * been freed, since it may contain references to offsets in that data.
     *
     * @param buf     Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize Size of the input OBU buffer.
     * @return An {@link OBPMetadata} object containing the parsed metadata.
     * @throws OBUParseException If the metadata type is invalid or parsing fails.
     */
    public static OBPMetadata parseMetadata(byte[] buf, int bufSize) throws OBUParseException {
        OBUParserStrategy<OBPMetadata> strategy = new OBUMetadataParser();
        return strategy.parse(buf, bufSize);
    }


    /**
     * Parses a tile list OBU and extracts the relevant fields.
     * This OBU's returned payload is *NOT* safe to use once the input 'buf' has
     * been freed, since it may contain references to offsets in that data.
     *
     * @param buf     Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize Size of the input OBU buffer.
     * @return An {@link OBPTileList} object containing the parsed tile list data.
     * @throws OBUParseException If the tile list OBU is malformed or too small.
     */
    public static OBPTileList parseTileList(byte[] buf, long bufSize) throws OBUParseException {
        OBUTileListParser strategy = new OBUTileListParser();
        return strategy.parseTileList(buf, bufSize);
    }

    /**
     * Returns true if the given OBU type value is valid.
     *
     * @param type the OBU type value
     * @return true if the given OBU type value is valid
     */
    public static boolean isValidObu(int type) {
        return VALID_OBU_TYPES.contains(type);
    }

    /**
     * Returns true if the given OBU type is valid.
     *
     * @param type the OBU type
     * @return true if the given OBU type is valid
     */
    public static boolean isValidObu(OBUType type) {
        switch (type) {
            case SEQUENCE_HEADER:
            case FRAME_HEADER:
            case TILE_GROUP:
            case METADATA:
            case FRAME:
            case REDUNDANT_FRAME_HEADER:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns true if the given byte starts a fragment. This is denoted as Z in the spec: MUST be set to 1 if the
     * first OBU element is an OBU fragment that is a continuation of an OBU fragment from the previous packet, and
     * MUST be set to 0 otherwise.
     *
     * @param aggregationHeader a byte
     * @return true if the given byte starts a fragment
     */
    public static boolean startsWithFragment(byte aggregationHeader) {
        return (aggregationHeader & OBU_START_FRAGMENT_BIT) != 0;
    }

    /**
     * Returns true if the given byte ends a fragment. This is denoted as Y in the spec: MUST be set to 1 if the last
     * OBU element is an OBU fragment that will continue in the next packet, and MUST be set to 0 otherwise.
     *
     * @param aggregationHeader a byte
     * @return true if the given byte ends a fragment
     */
    public static boolean endsWithFragment(byte aggregationHeader) {
        return (aggregationHeader & OBU_END_FRAGMENT_BIT) != 0;
    }

    /**
     * Returns true if the given byte is the starts a new sequence. This denoted as N in the spec: MUST be set to 1 if
     * the packet is the first packet of a coded video sequence, and MUST be set to 0 otherwise.
     *
     * @param aggregationHeader a byte
     * @return true if the given byte starts a new sequence
     */
    public static boolean startsNewCodedVideoSequence(byte aggregationHeader) {
        return (aggregationHeader & OBU_START_SEQUENCE_BIT) != 0;
    }

    /**
     * Returns expected number of OBU's.
     *
     * @param aggregationHeader a byte
     * @return expected number of OBU's
     */
    public static int obuCount(byte aggregationHeader) {
        return (aggregationHeader & OBU_COUNT_MASK) >> 4;
    }

    /**
     * Returns the OBU type from the given byte.
     *
     * @param obuHeader a byte
     * @return the OBU type
     */
    public static int obuType(byte obuHeader) {
        return (obuHeader & OBU_TYPE_MASK) >>> 3;
    }

    /**
     * Returns whether or not the OBU has an extension.
     *
     * @param obuHeader a byte
     * @return true if the OBU has an extension
     */
    public static boolean obuHasExtension(byte obuHeader) {
        return (obuHeader & OBU_EXT_BIT) != 0;
    }

    /**
     * Returns whether or not the OBU has a size.
     *
     * @param obuHeader a byte
     * @return true if the OBU has a size
     */
    public static boolean obuHasSize(byte obuHeader) {
        return (obuHeader & OBU_SIZE_PRESENT_BIT) != 0;
    }


    private static void finalizeObuData(byte[] buf, int bufSize, int pos, OBUInfo info) throws OBUParseException {
        int remainingInBuffer = bufSize - pos;
        if (info.getSize() > remainingInBuffer) {
            throw new OBUParseException("Invalid OBU size: larger than remaining buffer");
        }
        byte[] dataArray = Arrays.copyOfRange(buf, pos, (pos + info.getSize()));
        info.setData(ByteBuffer.wrap(dataArray));
    }

    @NonNull
    private static LEB128Result readObuSizeValue(byte[] buf, int pos) {
        byte[] lengthBytes = new byte[buf[pos] == 127 ? 2 : 1];
        System.arraycopy(buf, pos, lengthBytes, 0, lengthBytes.length);
        LEB128Result result = LEB128.decode(lengthBytes);
        return result;
    }

    private static void parseExtensionHeader(byte[] buf, int bufSize, int pos, OBUInfo info) throws OBUParseException {
        if (bufSize < pos + 1) {
            throw new OBUParseException("Buffer is too small to contain an OBU extension header");
        }
        info.setTemporalId((buf[pos] & 0xE0) >> 5);
        info.setSpatialId((buf[pos] & 0x18) >> 3);
        log.trace("Temporal id: {} spatial id: {}", info.getTemporalId(), info.getSpatialId());
    }

    private static void parseObuType(byte[] buf, int pos, OBUInfo info) throws OBUParseException {
        int obuType = (buf[pos] & OBU_FRAME_TYPE_MASK) >>> OBU_FRAME_TYPE_BITSHIFT;
        if (!isValidObu(obuType)) {
            log.warn("OBU header contains invalid OBU type: {} data: {}", obuType, HexDump.byteArrayToHexString(buf));
            throw new OBUParseException("OBU header contains invalid OBU type: " + obuType);
        }
        info.setObuType(OBUType.fromValue(obuType));
    }

    private static void validateBuffer(byte[] buf, int offset, int bufSize) throws OBUParseException {
        if (bufSize < 1) {
            throw new OBUParseException("Buffer is too small to contain an OBU");
        }
        if (buf.length < (offset + 1)) {
            throw new OBUParseException("Buffer is too small for given offset");
        }
    }

}
