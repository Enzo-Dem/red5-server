package org.red5.io.obu;

import static org.red5.io.obu.OBPConstants.OBU_FRAME_TYPE_BITSHIFT;
import static org.red5.io.obu.OBPConstants.OBU_FRAME_TYPE_MASK;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * OBU info
 *
 * @author mondain
 */
public class OBUInfo {

    // OBU type
    private OBUType obuType;

    // general OBU info
    private int size;
    private int temporalId;
    private int spatialId;

    // OBU header info
    private byte[] prefix = new byte[7];

    // OBU data
    private ByteBuffer data;

    /**
     * Default constructor.
     * Allows creating an empty OBUInfo object to be filled later via setters.
     */
    public OBUInfo() {
    }

    /**
     * Fully parameterized constructor for OBUInfo.
     */
    public OBUInfo(OBUType obuType, int size, int temporalId, int spatialId, byte[] prefix, ByteBuffer data) {
        this.obuType = obuType;
        this.size = size;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
        this.prefix = prefix;
        this.data = data;
    }

    /**
     * Constructor for OBUInfo without data buffer.
     */
    public OBUInfo(OBUType obuType, int size, int temporalId, int spatialId, byte[] prefix) {
        this.obuType = obuType;
        this.size = size;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
        this.prefix = prefix;
    }

    /**
     * Constructor for basic OBUInfo without prefix and data.
     */
    public OBUInfo(OBUType obuType, int size, int temporalId, int spatialId) {
        this.obuType = obuType;
        this.size = size;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
    }

    /**
     * Constructor for OBUInfo containing only type and data.
     */
    public OBUInfo(OBUType obuType, ByteBuffer data) {
        this.obuType = obuType;
        this.data = data;
    }

    /**
     * Static factory method to build an OBUInfo directly from a raw byte array.
     *
     * @param data   The raw byte array containing the OBU.
     * @param offset The starting offset in the array.
     * @param length The length of the data to wrap.
     * @return A newly constructed OBUInfo instance.
     */
    public static OBUInfo build(byte[] data, int offset, int length) {
        OBUType obuType = OBUType.fromValue((data[0] & OBU_FRAME_TYPE_MASK) >>> OBU_FRAME_TYPE_BITSHIFT);
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        return new OBUInfo(obuType, buffer);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        if (getData() != null) {
            return "OBUInfo [obuType=" + getObuType() + ", size=" + getSize() + ", temporalId=" + getTemporalId() + ", spatialId=" + getSpatialId() + ", prefix=" + Arrays.toString(getPrefix()) + ", data=" + getData() + "]";
        }
        return "OBUInfo [obuType=" + getObuType() + ", size=" + getSize() + ", temporalId=" + getTemporalId() + ", spatialId=" + getSpatialId() + ", prefix=" + Arrays.toString(getPrefix()) + "]";
    }

    public OBUType getObuType() {
        return obuType;
    }

    public void setObuType(OBUType obuType) {
        this.obuType = obuType;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getTemporalId() {
        return temporalId;
    }

    public void setTemporalId(int temporalId) {
        this.temporalId = temporalId;
    }

    public int getSpatialId() {
        return spatialId;
    }

    public void setSpatialId(int spatialId) {
        this.spatialId = spatialId;
    }

    public byte[] getPrefix() {
        return prefix;
    }

    public void setPrefix(byte[] prefix) {
        this.prefix = prefix;
    }

    public ByteBuffer getData() {
        return data;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }
}
