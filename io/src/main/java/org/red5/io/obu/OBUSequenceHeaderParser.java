package org.red5.io.obu;

public class OBUSequenceHeaderParser implements OBUParserStrategy<OBPSequenceHeader> {

    /**
     * Parses a sequence header OBU and extracts the relevant data.
     *
     * @param buf     Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize Size of the input OBU buffer.
     * @return An {@link OBPSequenceHeader} object filled with the parsed data.
     * @throws OBUParseException If the sequence header cannot be parsed or the buffer is invalid.
     */
    @Override
    public OBPSequenceHeader parse(byte[] buf, int bufSize) throws OBUParseException {
        BitReader br = new BitReader(buf, bufSize);
        OBPSequenceHeader seq = new OBPSequenceHeader();

        seq.seqProfile = (byte) br.readBits(3);
        seq.stillPicture = br.readBits(1) != 0;
        seq.reducedStillPictureHeader = br.readBits(1) != 0;

        if (seq.reducedStillPictureHeader) {
            seq.timingInfoPresentFlag = false;
            seq.decoderModelInfoPresentFlag = false;
            seq.initialDisplayDelayPresentFlag = false;
            seq.operatingPointsCntMinus1 = 0;
            seq.operatingPointIdc[0] = 0;
            seq.seqLevelIdx[0] = 0;
            seq.seqTier[0] = 0;
            seq.decoderModelPresentForThisOp[0] = false;
            seq.initialDisplayDelayPresentForThisOp[0] = false;
        } else {
            seq.timingInfoPresentFlag = br.readBits(1) != 0;
            if (seq.timingInfoPresentFlag) {
                seq.timingInfo = new OBPSequenceHeader.TimingInfo();
                seq.timingInfo.numUnitsInDisplayTick = br.readBits(32);
                seq.timingInfo.timeScale = br.readBits(32);
                seq.timingInfo.equalPictureInterval = br.readBits(1) != 0;
                if (seq.timingInfo.equalPictureInterval) {
                    seq.timingInfo.numTicksPerPictureMinus1 = readUvlc(br);
                }
                seq.decoderModelInfoPresentFlag = br.readBits(1) != 0;
                if (seq.decoderModelInfoPresentFlag) {
                    seq.decoderModelInfo = new OBPSequenceHeader.DecoderModelInfo();
                    seq.decoderModelInfo.bufferDelayLengthMinus1 = (byte) br.readBits(5);
                    seq.decoderModelInfo.numUnitsInDecodingTick = br.readBits(32);
                    seq.decoderModelInfo.bufferRemovalTimeLengthMinus1 = (byte) br.readBits(5);
                    seq.decoderModelInfo.framePresentationTimeLengthMinus1 = (byte) br.readBits(5);
                }
            } else {
                seq.decoderModelInfoPresentFlag = false;
            }
            seq.initialDisplayDelayPresentFlag = br.readBits(1) != 0;
            seq.operatingPointsCntMinus1 = (byte) br.readBits(5);
            for (int i = 0; i <= seq.operatingPointsCntMinus1; i++) {
                seq.operatingPointIdc[i] = (byte) br.readBits(12);
                seq.seqLevelIdx[i] = (byte) br.readBits(5);
                if (seq.seqLevelIdx[i] > 7) {
                    seq.seqTier[i] = (byte) br.readBits(1);
                } else {
                    seq.seqTier[i] = 0;
                }
                if (seq.decoderModelInfoPresentFlag) {
                    seq.decoderModelPresentForThisOp[i] = br.readBits(1) != 0;
                    if (seq.decoderModelPresentForThisOp[i]) {
                        seq.operatingParametersInfo[i] = new OBPSequenceHeader.OperatingParametersInfo();
                        int n = seq.decoderModelInfo.bufferDelayLengthMinus1 + 1;
                        seq.operatingParametersInfo[i].decoderBufferDelay = br.readBits(n);
                        seq.operatingParametersInfo[i].encoderBufferDelay = br.readBits(n);
                        seq.operatingParametersInfo[i].lowDelayModeFlag = br.readBits(1) != 0;
                    }
                } else {
                    seq.decoderModelPresentForThisOp[i] = false;
                }
                if (seq.initialDisplayDelayPresentFlag) {
                    seq.initialDisplayDelayPresentForThisOp[i] = br.readBits(1) != 0;
                    if (seq.initialDisplayDelayPresentForThisOp[i]) {
                        seq.initialDisplayDelayMinus1[i] = (byte) br.readBits(4);
                    }
                }
            }
        }

        seq.frameWidthBitsMinus1 = (byte) br.readBits(4);
        seq.frameHeightBitsMinus1 = (byte) br.readBits(4);
        seq.maxFrameWidthMinus1 = (int) br.readBits(seq.frameWidthBitsMinus1 + 1);
        seq.maxFrameHeightMinus1 = (int) br.readBits(seq.frameHeightBitsMinus1 + 1);

        if (seq.reducedStillPictureHeader) {
            seq.frameIdNumbersPresentFlag = false;
        } else {
            seq.frameIdNumbersPresentFlag = br.readBits(1) != 0;
        }

        if (seq.frameIdNumbersPresentFlag) {
            seq.deltaFrameIdLengthMinus2 = (byte) br.readBits(4);
            seq.additionalFrameIdLengthMinus1 = (byte) br.readBits(3);
        }

        seq.use128x128Superblock = br.readBits(1) != 0;
        seq.enableFilterIntra = br.readBits(1) != 0;
        seq.enableIntraEdgeFilter = br.readBits(1) != 0;

        if (seq.reducedStillPictureHeader) {
            seq.enableInterintraCompound = false;
            seq.enableMaskedCompound = false;
            seq.enableWarpedMotion = false;
            seq.enableDualFilter = false;
            seq.enableOrderHint = false;
            seq.enableJntComp = false;
            seq.enableRefFrameMvs = false;
            seq.seqForceScreenContentTools = 2;
            seq.seqForceIntegerMv = 2;
            seq.OrderHintBits = 0;
        } else {
            seq.enableInterintraCompound = br.readBits(1) != 0;
            seq.enableMaskedCompound = br.readBits(1) != 0;
            seq.enableWarpedMotion = br.readBits(1) != 0;
            seq.enableDualFilter = br.readBits(1) != 0;
            seq.enableOrderHint = br.readBits(1) != 0;
            if (seq.enableOrderHint) {
                seq.enableJntComp = br.readBits(1) != 0;
                seq.enableRefFrameMvs = br.readBits(1) != 0;
            } else {
                seq.enableJntComp = false;
                seq.enableRefFrameMvs = false;
            }
            seq.seqChooseScreenContentTools = br.readBits(1) != 0;
            if (seq.seqChooseScreenContentTools) {
                seq.seqForceScreenContentTools = 2;
            } else {
                seq.seqForceScreenContentTools = (int) br.readBits(1);
            }
            if (seq.seqForceScreenContentTools > 0) {
                seq.seqChooseIntegerMv = br.readBits(1) != 0;
                if (seq.seqChooseIntegerMv) {
                    seq.seqForceIntegerMv = 2;
                } else {
                    seq.seqForceIntegerMv = (int) br.readBits(1);
                }
            } else {
                seq.seqForceIntegerMv = 2;
            }
            if (seq.enableOrderHint) {
                seq.orderHintBitsMinus1 = (byte) br.readBits(3);
                seq.OrderHintBits = (byte) (seq.orderHintBitsMinus1 + 1);
            } else {
                seq.OrderHintBits = 0;
            }
        }

        seq.enableSuperres = br.readBits(1) != 0;
        seq.enableCdef = br.readBits(1) != 0;
        seq.enableRestoration = br.readBits(1) != 0;

        seq.colorConfig = new OBPSequenceHeader.ColorConfig();
        seq.colorConfig.highBitdepth = br.readBits(1) != 0;
        if (seq.seqProfile == 2 && seq.colorConfig.highBitdepth) {
            seq.colorConfig.twelveBit = br.readBits(1) != 0;
            seq.colorConfig.BitDepth = seq.colorConfig.twelveBit ? (byte) 12 : (byte) 10;
        } else {
            seq.colorConfig.BitDepth = seq.colorConfig.highBitdepth ? (byte) 10 : (byte) 8;
        }
        if (seq.seqProfile == 1) {
            seq.colorConfig.monoChrome = false;
        } else {
            seq.colorConfig.monoChrome = br.readBits(1) != 0;
        }
        seq.colorConfig.NumPlanes = seq.colorConfig.monoChrome ? (byte) 1 : (byte) 3;
        seq.colorConfig.colorDescriptionPresentFlag = br.readBits(1) != 0;
        if (seq.colorConfig.colorDescriptionPresentFlag) {
            seq.colorConfig.colorPrimaries = OBPColorPrimaries.values()[br.readBits(8)];
            seq.colorConfig.transferCharacteristics = OBPTransferCharacteristics.values()[br.readBits(8)];
            seq.colorConfig.matrixCoefficients = OBPMatrixCoefficients.values()[br.readBits(8)];
        } else {
            seq.colorConfig.colorPrimaries = OBPColorPrimaries.CP_UNSPECIFIED;
            seq.colorConfig.transferCharacteristics = OBPTransferCharacteristics.TC_UNSPECIFIED;
            seq.colorConfig.matrixCoefficients = OBPMatrixCoefficients.MC_UNSPECIFIED;
        }
        if (seq.colorConfig.monoChrome) {
            seq.colorConfig.colorRange = br.readBits(1) != 0;
            seq.colorConfig.subsamplingX = true;
            seq.colorConfig.subsamplingY = true;
            seq.colorConfig.chromaSamplePosition = OBPChromaSamplePosition.CSP_UNKNOWN;
            seq.colorConfig.separateUvDeltaQ = false;
        } else if (seq.colorConfig.colorPrimaries == OBPColorPrimaries.CP_BT_709 && seq.colorConfig.transferCharacteristics == OBPTransferCharacteristics.TC_SRGB && seq.colorConfig.matrixCoefficients == OBPMatrixCoefficients.MC_IDENTITY) {
            seq.colorConfig.colorRange = true;
            seq.colorConfig.subsamplingX = false;
            seq.colorConfig.subsamplingY = false;
        } else {
            seq.colorConfig.colorRange = br.readBits(1) != 0;
            if (seq.seqProfile == 0) {
                seq.colorConfig.subsamplingX = true;
                seq.colorConfig.subsamplingY = true;
            } else if (seq.seqProfile == 1) {
                seq.colorConfig.subsamplingX = false;
                seq.colorConfig.subsamplingY = false;
            } else {
                if (seq.colorConfig.BitDepth == 12) {
                    seq.colorConfig.subsamplingX = br.readBits(1) != 0;
                    if (seq.colorConfig.subsamplingX) {
                        seq.colorConfig.subsamplingY = br.readBits(1) != 0;
                    } else {
                        seq.colorConfig.subsamplingY = false;
                    }
                } else {
                    seq.colorConfig.subsamplingX = true;
                    seq.colorConfig.subsamplingY = false;
                }
            }
            if (seq.colorConfig.subsamplingX && seq.colorConfig.subsamplingY) {
                seq.colorConfig.chromaSamplePosition = OBPChromaSamplePosition.values()[br.readBits(2)];
            }
        }
        seq.colorConfig.separateUvDeltaQ = br.readBits(1) != 0;

        seq.filmGrainParamsPresent = br.readBits(1) != 0;

        return seq;
    }

    private static long readUvlc(BitReader br) throws OBUParseException {
        int leadingZeros = 0;
        while (leadingZeros < 32 && br.readBits(1) == 0) {
            leadingZeros++;
        }
        if (leadingZeros == 32) {
            throw new OBUParseException("Invalid UVLC code");
        }
        return br.readBits(leadingZeros) + ((1L << leadingZeros) - 1);
    }
}
