package org.red5.io.obu;

import org.red5.io.utils.LEB128;

import java.util.Arrays;

/**
 * Strategy implementation for parsing OBU Metadata.
 */
public class OBUMetadataParser implements OBUParserStrategy<OBPMetadata> {

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
    public OBPMetadata parse(byte[] buf, int bufSize) throws OBUParseException {
        OBPMetadata metadata = new OBPMetadata();
        int consumed;
        // int will only be 4 bytes long max
        LEB128.LEB128Result result = LEB128.decode(buf);
        int leb = result.value;
        consumed = result.bytesRead;
        metadata.metadataType = OBPMetadataType.fromValue(leb);
        BitReader br = new BitReader(buf, consumed, bufSize - consumed);
        switch (metadata.metadataType) {
            case HDR_CLL:
                metadata.metadataHdrCll = new OBPMetadata.MetadataHdrCll();
                metadata.metadataHdrCll.maxCll = (short) br.readBits(16);
                metadata.metadataHdrCll.maxFall = (short) br.readBits(16);
                break;
            case HDR_MDCV:
                metadata.metadataHdrMdcv = new OBPMetadata.MetadataHdrMdcv();
                for (int i = 0; i < 3; i++) {
                    metadata.metadataHdrMdcv.primaryChromaticityX[i] = (short) br.readBits(16);
                    metadata.metadataHdrMdcv.primaryChromaticityY[i] = (short) br.readBits(16);
                }
                metadata.metadataHdrMdcv.whitePointChromaticityX = (short) br.readBits(16);
                metadata.metadataHdrMdcv.whitePointChromaticityY = (short) br.readBits(16);
                metadata.metadataHdrMdcv.luminanceMax = br.readBits(32);
                metadata.metadataHdrMdcv.luminanceMin = br.readBits(32);
                break;
            case SCALABILITY:
                metadata.metadataScalability = new OBPMetadata.MetadataScalability();
                metadata.metadataScalability.scalabilityModeIdc = (byte) br.readBits(8);
                if (metadata.metadataScalability.scalabilityModeIdc != 0) {
                    metadata.metadataScalability.scalabilityStructure = new OBPMetadata.MetadataScalability.ScalabilityStructure();
                    parseScalabilityStructure(br, metadata.metadataScalability.scalabilityStructure);
                }
                break;
            case ITUT_T35:
                metadata.metadataItutT35 = new OBPMetadata.MetadataItutT35();
                metadata.metadataItutT35.ituTT35CountryCode = (byte) br.readBits(8);
                long offset = 1;
                if (metadata.metadataItutT35.ituTT35CountryCode == 0xFF) {
                    metadata.metadataItutT35.ituTT35CountryCodeExtensionByte = (byte) br.readBits(8);
                    offset++;
                }
                metadata.metadataItutT35.ituTT35PayloadBytes = Arrays.copyOfRange(buf, (int) (consumed + offset), buf.length);
                metadata.metadataItutT35.ituTT35PayloadBytesSize = findItuT35PayloadSize(metadata.metadataItutT35.ituTT35PayloadBytes);
                break;
            case TIMECODE:
                metadata.metadataTimecode = new OBPMetadata.MetadataTimecode();
                metadata.metadataTimecode.countingType = (byte) br.readBits(5);
                metadata.metadataTimecode.fullTimestampFlag = br.readBits(1) != 0;
                metadata.metadataTimecode.discontinuityFlag = br.readBits(1) != 0;
                metadata.metadataTimecode.cntDroppedFlag = br.readBits(1) != 0;
                metadata.metadataTimecode.nFrames = (short) br.readBits(9);
                if (metadata.metadataTimecode.fullTimestampFlag) {
                    metadata.metadataTimecode.secondsValue = (byte) br.readBits(6);
                    metadata.metadataTimecode.minutesValue = (byte) br.readBits(6);
                    metadata.metadataTimecode.hoursValue = (byte) br.readBits(5);
                } else {
                    metadata.metadataTimecode.secondsFlag = br.readBits(1) != 0;
                    if (metadata.metadataTimecode.secondsFlag) {
                        metadata.metadataTimecode.secondsValue = (byte) br.readBits(6);
                        metadata.metadataTimecode.minutesFlag = br.readBits(1) != 0;
                        if (metadata.metadataTimecode.minutesFlag) {
                            metadata.metadataTimecode.minutesValue = (byte) br.readBits(6);
                            metadata.metadataTimecode.hoursFlag = br.readBits(1) != 0;
                            if (metadata.metadataTimecode.hoursFlag) {
                                metadata.metadataTimecode.hoursValue = (byte) br.readBits(5);
                            }
                        }
                    }
                }
                metadata.metadataTimecode.timeOffsetLength = (byte) br.readBits(5);
                if (metadata.metadataTimecode.timeOffsetLength > 0) {
                    metadata.metadataTimecode.timeOffsetValue = br.readBits(metadata.metadataTimecode.timeOffsetLength);
                }
                break;
            default:
                if (metadata.metadataType.getValue() >= 6 && metadata.metadataType.getValue() <= 31) {
                    metadata.unregistered = new OBPMetadata.Unregistered();
                    metadata.unregistered.buf = Arrays.copyOfRange(buf, (int) consumed, buf.length);
                    metadata.unregistered.bufSize = bufSize - consumed;
                } else {
                    throw new OBUParseException("Invalid metadata type: " + metadata.metadataType.getValue());
                }
        }
        return metadata;
    }

    private static long findItuT35PayloadSize(byte[] payload) {
        int nonZeroBytesCount = 0;
        for (int i = payload.length - 1; i >= 0; i--) {
            if (payload[i] != 0) {
                nonZeroBytesCount++;
                if (nonZeroBytesCount == 2) {
                    return i + 1;
                }
            }
        }
        return payload.length;
    }

    private static void parseScalabilityStructure(BitReader br, OBPMetadata.MetadataScalability.ScalabilityStructure structure) throws OBUParseException {
        structure.spatialLayersCntMinus1 = (byte) br.readBits(2);
        structure.spatialLayerDimensionsPresentFlag = br.readBits(1) != 0;
        structure.spatialLayerDescriptionPresentFlag = br.readBits(1) != 0;
        structure.temporalGroupDescriptionPresentFlag = br.readBits(1) != 0;
        structure.scalabilityStructureReserved3bits = (byte) br.readBits(3);
        if (structure.spatialLayerDimensionsPresentFlag) {
            for (int i = 0; i <= structure.spatialLayersCntMinus1; i++) {
                structure.spatialLayerMaxWidth[i] = (short) br.readBits(16);
                structure.spatialLayerMaxHeight[i] = (short) br.readBits(16);
            }
        }
        if (structure.spatialLayerDescriptionPresentFlag) {
            for (int i = 0; i <= structure.spatialLayersCntMinus1; i++) {
                structure.spatialLayerRefId[i] = (byte) br.readBits(8);
            }
        }
        if (structure.temporalGroupDescriptionPresentFlag) {
            structure.temporalGroupSize = (byte) br.readBits(8);
            for (int i = 0; i < structure.temporalGroupSize; i++) {
                structure.temporalGroupTemporalId[i] = (byte) br.readBits(3);
                structure.temporalGroupTemporalSwitchingUpPointFlag[i] = br.readBits(1) != 0;
                structure.temporalGroupSpatialSwitchingUpPointFlag[i] = br.readBits(1) != 0;
                structure.temporalGroupRefCnt[i] = (byte) br.readBits(3);
                for (int j = 0; j < structure.temporalGroupRefCnt[i]; j++) {
                    structure.temporalGroupRefPicDiff[i][j] = (byte) br.readBits(8);
                }
            }
        }
    }
}