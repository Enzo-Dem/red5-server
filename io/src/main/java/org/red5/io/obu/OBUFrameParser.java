package org.red5.io.obu;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class OBUFrameParser implements OBUParserStrategy<OBPFrameHeader>{

    @Override
    public OBPFrameHeader parse(byte[] buf, int bufSize) throws OBUParseException {
        throw new OBUParseException(
                "FrameParser requires sequence header and state context. " +
                        "Use parseFrame() or parseFrameHeader() directly.");
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
     * @param SeenFrameHeader Tracking variable as per AV1 spec indicating if a frame header has been seen.
     * @throws OBUParseException If the frame header or tile group fails to parse.
     */
    public void parseFrame(byte[] buf, int bufSize, OBPSequenceHeader seq, OBPState state, int temporalId, int spatialId, OBPFrameHeader fh, OBPTileGroup tileGroup, AtomicBoolean SeenFrameHeader) throws OBUParseException {
        int startBitPos = 0, endBitPos, headerBytes;
        parseFrameHeader(buf, bufSize, seq, state, temporalId, spatialId, fh, SeenFrameHeader);
        endBitPos = state.frameHeaderEndPos;
        headerBytes = (endBitPos - startBitPos) / 8;
        parseTileGroup(buf, headerBytes, bufSize - headerBytes, fh, tileGroup, SeenFrameHeader);
    }

    /**
     * Parses a frame header OBU and fills out the fields in the provided {@link OBPFrameHeader} structure.
     *
     * @param buf             Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param bufSize         Size of the input OBU buffer.
     * @param seq             The sequence header associated with this frame.
     * @param state           An opaque state structure. Must be zeroed by the user on first use.
     * @param temporalId      A temporal ID previously obtained from the sequence header.
     * @param spatialId       A spatial ID previously obtained from the sequence header.
     * @param fh              The {@link OBPFrameHeader} object that will be filled with the parsed data.
     * @param seenFrameHeader Tracking variable as per AV1 spec indicating if a frame header was already seen.
     * @throws OBUParseException If the frame header cannot be parsed or the buffer is invalid.
     */
    private static void parseFrameHeader(byte[] buf, int bufSize, OBPSequenceHeader seq, OBPState state, int temporalId, int spatialId, OBPFrameHeader fh, AtomicBoolean seenFrameHeader) throws OBUParseException {
        BitReader br = new BitReader(buf, bufSize);
        if (seenFrameHeader.get()) {
            if (!state.prevFilled) {
                throw new OBUParseException("SeenFrameHeader is true, but no previous header exists in state");
            }
            copyFrameHeader(state.prev, fh);
            return;
        }
        seenFrameHeader.set(true);
        int idLen = 0;
        if (seq.frameIdNumbersPresentFlag) {
            idLen = seq.additionalFrameIdLengthMinus1 + seq.deltaFrameIdLengthMinus2 + 3;
        }
        byte allFrames = (byte) 255; // (1 << 8) - 1
        boolean frameIsIntra = false;
        if (seq.reducedStillPictureHeader) {
            fh.showExistingFrame = false;
            fh.frameType = OBPFrameType.KEYFRAME;
            frameIsIntra = true;
            fh.showFrame = true;
            fh.showableFrame = true;
        } else {
            fh.showExistingFrame = br.readBits(1) != 0;
            if (fh.showExistingFrame) {
                fh.frameToShowMapIdx = (byte) br.readBits(3);
                if (seq.decoderModelInfoPresentFlag && !seq.timingInfo.equalPictureInterval) {
                    int n = seq.decoderModelInfo.framePresentationTimeLengthMinus1 + 1;
                    fh.temporalPointInfo.framePresentationTime = br.readBits(n);
                }
                fh.refreshFrameFlags = 0;
                if (seq.frameIdNumbersPresentFlag) {
                    fh.displayFrameId = br.readBits(idLen);
                }
                fh.frameType = state.refFrameType[fh.frameToShowMapIdx];
                if (fh.frameType == OBPFrameType.KEYFRAME) {
                    fh.refreshFrameFlags = allFrames;
                }
                if (seq.filmGrainParamsPresent) {
                    copyFilmGrainParams(state.refGrainParams[fh.frameToShowMapIdx], fh.filmGrainParams);
                }
                return;
            }
            fh.frameType = OBPFrameType.values()[br.readBits(2)];
            frameIsIntra = (fh.frameType == OBPFrameType.INTRA_ONLY_FRAME || fh.frameType == OBPFrameType.KEYFRAME);
            fh.showFrame = br.readBits(1) != 0;
            if (fh.showFrame && seq.decoderModelInfoPresentFlag && !seq.timingInfo.equalPictureInterval) {
                int n = seq.decoderModelInfo.framePresentationTimeLengthMinus1 + 1;
                fh.temporalPointInfo.framePresentationTime = br.readBits(n);
            }
            if (fh.showFrame) {
                fh.showableFrame = (fh.frameType != OBPFrameType.KEYFRAME);
            } else {
                fh.showableFrame = br.readBits(1) != 0;
            }
            if (fh.frameType == OBPFrameType.SWITCH_FRAME || (fh.frameType == OBPFrameType.KEYFRAME && fh.showFrame)) {
                fh.errorResilientMode = true;
            } else {
                fh.errorResilientMode = br.readBits(1) != 0;
            }
        }
        if (fh.frameType == OBPFrameType.KEYFRAME && fh.showFrame) {
            for (int i = 0; i < 8; i++) {
                state.refValid[i] = 0;
                state.refOrderHint[i] = 0;
            }
            for (int i = 0; i < 7; i++) {
                state.orderHint[1 + i] = 0;
            }
        }
        fh.disableCdfUpdate = br.readBits(1) != 0;
        if (seq.seqForceScreenContentTools == 2) {
            fh.allowScreenContentTools = br.readBits(1) != 0;
        } else {
            fh.allowScreenContentTools = seq.seqForceScreenContentTools != 0;
        }
        if (fh.allowScreenContentTools) {
            if (seq.seqForceIntegerMv == 2) {
                fh.forceIntegerMv = br.readBits(1) != 0;
            } else {
                fh.forceIntegerMv = seq.seqForceIntegerMv != 0;
            }
        } else {
            fh.forceIntegerMv = false;
        }
        if (frameIsIntra) {
            fh.forceIntegerMv = true;
        }
        if (seq.frameIdNumbersPresentFlag) {
            fh.currentFrameId = br.readBits(idLen);
            byte diffLen = (byte) (seq.deltaFrameIdLengthMinus2 + 2);
            for (int i = 0; i < 8; i++) {
                if (fh.currentFrameId > (1 << diffLen)) {
                    if (state.refFrameId[i] > fh.currentFrameId || state.refFrameId[i] < (fh.currentFrameId - (1 << diffLen))) {
                        state.refValid[i] = 0;
                    }
                } else {
                    if (state.refFrameId[i] > fh.currentFrameId && state.refFrameId[i] < ((1 << idLen) + fh.currentFrameId - (1 << diffLen))) {
                        state.refValid[i] = 0;
                    }
                }
            }
        } else {
            fh.currentFrameId = 0;
        }
        if (fh.frameType == OBPFrameType.SWITCH_FRAME) {
            fh.frameSizeOverrideFlag = true;
        } else if (seq.reducedStillPictureHeader) {
            fh.frameSizeOverrideFlag = false;
        } else {
            fh.frameSizeOverrideFlag = br.readBits(1) != 0;
        }
        if (seq.OrderHintBits != 0) {
            fh.orderHint = (byte) br.readBits(seq.OrderHintBits);
        } else {
            fh.orderHint = 0;
        }
        byte orderHint = fh.orderHint;
        if (frameIsIntra || fh.errorResilientMode) {
            fh.primaryRefFrame = 7;
        } else {
            fh.primaryRefFrame = (byte) br.readBits(3);
        }
        if (seq.decoderModelInfoPresentFlag) {
            fh.bufferRemovalTimePresentFlag = br.readBits(1) != 0;
            if (fh.bufferRemovalTimePresentFlag) {
                for (int opNum = 0; opNum <= seq.operatingPointsCntMinus1; opNum++) {
                    if (seq.decoderModelPresentForThisOp[opNum]) {
                        int opPtIdc = seq.operatingPointIdc[opNum];
                        int inTemporalLayer = (opPtIdc >> temporalId) & 1;
                        int inSpatialLayer = (opPtIdc >> (spatialId + 8)) & 1;
                        if (opPtIdc == 0 || (inTemporalLayer != 0 && inSpatialLayer != 0)) {
                            int n = seq.decoderModelInfo.bufferRemovalTimeLengthMinus1 + 1;
                            fh.bufferRemovalTime[opNum] = br.readBits(n);
                        }
                    }
                }
            }
        }
        fh.allowHighPrecisionMv = false;
        fh.useRefFrameMvs = false;
        fh.allowIntrabc = false;
        if (fh.frameType == OBPFrameType.SWITCH_FRAME || (fh.frameType == OBPFrameType.KEYFRAME && fh.showFrame)) {
            fh.refreshFrameFlags = allFrames;
        } else {
            fh.refreshFrameFlags = (byte) br.readBits(8);
        }
        if (!frameIsIntra || fh.refreshFrameFlags != allFrames) {
            if (fh.errorResilientMode && seq.enableOrderHint) {
                for (int i = 0; i < 8; i++) {
                    fh.refOrderHint[i] = (byte) br.readBits(seq.OrderHintBits);
                    if (fh.refOrderHint[i] != state.refOrderHint[i]) {
                        state.refValid[i] = 0;
                    }
                }
            }
        }
        // Frame size
        if (frameIsIntra) {
            parseFrameSize(br, seq, fh);
            parseRenderSize(br, fh);
            if (fh.allowScreenContentTools && fh.upscaledWidth == fh.frameWidth) {
                fh.allowIntrabc = br.readBits(1) != 0;
            }
        } else {
            if (!seq.enableOrderHint) {
                fh.frameRefsShortSignaling = false;
            } else {
                fh.frameRefsShortSignaling = br.readBits(1) != 0;
                if (fh.frameRefsShortSignaling) {
                    fh.lastFrameIdx = (byte) br.readBits(3);
                    fh.goldFrameIdx = (byte) br.readBits(3);
                    setFrameRefs(fh, seq, state);
                }
            }
            for (int i = 0; i < 7; i++) {
                if (!fh.frameRefsShortSignaling) {
                    fh.refFrameIdx[i] = (byte) br.readBits(3);
                }
                if (seq.frameIdNumbersPresentFlag) {
                    int n = seq.deltaFrameIdLengthMinus2 + 2;
                    fh.deltaFrameIdMinus1[i] = (byte) br.readBits(n);
                    int DeltaFrameId = fh.deltaFrameIdMinus1[i] + 1;
                    int expectedFrameId = (fh.currentFrameId + (1 << idLen) - DeltaFrameId) % (1 << idLen);
                    if (state.refFrameId[fh.refFrameIdx[i]] != expectedFrameId) {
                        throw new OBUParseException("Reference frame id mismatch");
                    }
                }
            }
            if (fh.frameSizeOverrideFlag && !fh.errorResilientMode) {
                parseSuperresParams(br, seq, fh);
            } else {
                parseFrameSize(br, seq, fh);
                parseRenderSize(br, fh);
            }
            if (fh.forceIntegerMv) {
                fh.allowHighPrecisionMv = false;
            } else {
                fh.allowHighPrecisionMv = br.readBits(1) != 0;
            }
            parseInterpolationFilter(br, fh);
            fh.isMotionModeSwitchable = br.readBits(1) != 0;
            if (fh.errorResilientMode || !seq.enableRefFrameMvs) {
                fh.useRefFrameMvs = false;
            } else {
                fh.useRefFrameMvs = br.readBits(1) != 0;
            }
            for (int i = 0; i < 7; i++) {
                int refFrame = 1 + i;
                byte hint = state.refOrderHint[fh.refFrameIdx[i]];
                state.orderHint[refFrame] = hint;
                if (!seq.enableOrderHint) {
                    state.refFrameSignBias[refFrame] = 0;
                } else {
                    state.refFrameSignBias[refFrame] = getRelativeDist(hint, orderHint, seq) > 0 ? 1 : 0;
                }
            }
        }
        if (seq.reducedStillPictureHeader || fh.disableCdfUpdate) {
            fh.disableFrameEndUpdateCdf = true;
        } else {
            fh.disableFrameEndUpdateCdf = br.readBits(1) != 0;
        }
        if (fh.primaryRefFrame == 7) {
            setupPastIndependence(fh);
        } else {
            loadPrevious(fh, state);
        }
        // Tile info; after parsing frame size and other related parameters
        parseTileInfo(br, fh, seq);
        // Quantization params
        parseQuantizationParams(br, fh, seq);
        // Segmentation params
        parseSegmentationParams(br, fh, seq);
        // Delta Q params
        parseDeltaQParams(br, fh);
        // Delta LF params
        parseDeltaLfParams(br, fh, seq);
        boolean codedLossless = computeCodedLossless(fh, seq);
        fh.codedLossless = codedLossless;
        fh.allLossless = codedLossless && (fh.frameWidth == fh.upscaledWidth);
        // Loop filter params
        parseLoopFilterParams(br, fh, seq);
        // CDEF params
        parseCdefParams(br, fh, seq);
        // LR params
        parseLrParams(br, fh, seq);
        // Read TX mode
        if (codedLossless) {
            // TxMode implicitly set to ONLY_4X4
            fh.txMode = OBPTxMode.ONLY_4X4;
        } else {
            fh.txModeSelect = br.readBits(1) != 0;
            fh.txMode = fh.txModeSelect ? OBPTxMode.SELECT : OBPTxMode.LARGEST;
        }
        // Frame reference mode
        parseFrameReferenceMode(br, fh, frameIsIntra);
        // Skip mode params
        parseSkipModeParams(br, fh, seq, state, frameIsIntra);
        if (frameIsIntra || fh.errorResilientMode || !seq.enableWarpedMotion) {
            fh.allowWarpedMotion = false;
        } else {
            fh.allowWarpedMotion = br.readBits(1) != 0;
        }
        fh.reducedTxSet = br.readBits(1) != 0;
        // Global motion params
        parseGlobalMotionParams(br, fh, frameIsIntra);
        // Film grain params
        parseFilmGrainParams(br, fh, seq, state);
        br.byteAlignment();
        state.frameHeaderEndPos = br.getPosition();
        // Stash refs for future frame use
        for (int i = 0; i < 8; i++) {
            if ((fh.refreshFrameFlags & (1 << i)) != 0) {
                state.refOrderHint[i] = fh.orderHint;
                state.refFrameType[i] = fh.frameType;
                state.refUpscaledWidth[i] = fh.upscaledWidth;
                state.refFrameHeight[i] = fh.frameHeight;
                state.refRenderWidth[i] = fh.renderWidth;
                state.refRenderHeight[i] = fh.renderHeight;
                state.refFrameId[i] = fh.currentFrameId;

                // Save film grain parameters
                copyFilmGrainParams(fh.filmGrainParams, state.refGrainParams[i]);

                // Save global motion parameters into the state
                for (int j = 0; j < 8; j++) {
                    System.arraycopy(fh.globalMotionParams.gmParams[j], 0, state.savedGmParams[i][j], 0, 6);
                }

                // Save segmentation features and data
                for (int j = 0; j < 8; j++) {
                    System.arraycopy(fh.segmentationParams.featureEnabled[j], 0, state.savedFeatureEnabled[i][j], 0, 8);
                    System.arraycopy(fh.segmentationParams.featureData[j], 0, state.savedFeatureData[i][j], 0, 8);
                }

                // Save loop filter deltas (reference and mode) into the state
                System.arraycopy(fh.loopFilterParams.loopFilterRefDeltas, 0, state.savedLoopFilterRefDeltas[i], 0, 8);
                System.arraycopy(fh.loopFilterParams.loopFilterModeDeltas, 0, state.savedLoopFilterModeDeltas[i], 0, 2);
            }
        }
        // Handle show_existing_frame semantics
        if (fh.showExistingFrame && fh.frameType == OBPFrameType.KEYFRAME) {
            fh.orderHint = state.refOrderHint[fh.frameToShowMapIdx];
            for (int i = 0; i < 8; i++) {
                System.arraycopy(state.savedGmParams[fh.frameToShowMapIdx][i], 0, fh.globalMotionParams.gmParams[i], 0, 6);
            }
        }
        if (fh.showExistingFrame) {
            seenFrameHeader.set(false);
            state.prevFilled = false;
        } else {
            copyFrameHeader(fh, state.prev);
            state.prevFilled = true;
        }
    }

    /**
     * Parses a tile group OBU and fills out the fields in the provided {@link OBPTileGroup} structure.
     *
     * @param buf             Input OBU buffer. This is expected to *NOT* contain the OBU header.
     * @param offset          The offset into the buffer where the tile group starts.
     * @param size            Size of the input OBU buffer.
     * @param fh              A filled-in frame header OBU previously seen.
     * @param tileGroup       The {@link OBPTileGroup} structure to be filled with the parsed data.
     * @param SeenFrameHeader Tracking variable as per AV1 spec indicating if a frame header has been seen.
     * @throws OBUParseException If there are not enough bytes left to read the tile sizes.
     */
    private static void parseTileGroup(byte[] buf, int offset, int size, OBPFrameHeader fh, OBPTileGroup tileGroup, AtomicBoolean SeenFrameHeader) throws OBUParseException {
        BitReader br = new BitReader(buf, offset, size);
        tileGroup.numTiles = (short) (fh.tileInfo.tileCols * fh.tileInfo.tileRows);
        long startBitPos = br.getPosition();
        tileGroup.tileStartAndEndPresentFlag = false;
        if (tileGroup.numTiles > 1) {
            tileGroup.tileStartAndEndPresentFlag = br.readBits(1) != 0;
        }
        if (tileGroup.numTiles == 1 || !tileGroup.tileStartAndEndPresentFlag) {
            tileGroup.tgStart = 0;
            tileGroup.tgEnd = (short) (tileGroup.numTiles - 1);
        } else {
            int tileBits = tileLog2(1, fh.tileInfo.tileCols) + tileLog2(1, fh.tileInfo.tileRows);
            tileGroup.tgStart = (short) br.readBits(tileBits);
            tileGroup.tgEnd = (short) br.readBits(tileBits);
        }
        br.byteAlignment();
        long endBitPos = br.getPosition();
        long headerBytes = (endBitPos - startBitPos) / 8;
        long sz = size - headerBytes;
        long pos = headerBytes;

        for (int tileNum = tileGroup.tgStart; tileNum <= tileGroup.tgEnd; tileNum++) {
            boolean lastTile = (tileNum == tileGroup.tgEnd);
            if (lastTile) {
                tileGroup.tileSize[tileNum] = sz;
            } else {
                int TileSizeBytes = fh.tileInfo.tileSizeBytesMinus1 + 1;
                long tileSizeMinus1;
                if (sz < TileSizeBytes) {
                    throw new OBUParseException("Not enough bytes left to read tile size for tile " + tileNum);
                }
                tileSizeMinus1 = readLe(buf, (int) (offset + pos), TileSizeBytes);
                tileGroup.tileSize[tileNum] = tileSizeMinus1 + 1;
                if (sz < tileGroup.tileSize[tileNum]) {
                    throw new OBUParseException("Not enough bytes to contain TileSize for tile " + tileNum);
                }
                sz -= tileGroup.tileSize[tileNum] + TileSizeBytes;
                pos += tileGroup.tileSize[tileNum] + TileSizeBytes;
            }
        }
        if (tileGroup.tgEnd == tileGroup.numTiles - 1) {
            SeenFrameHeader.set(false);
        }
    }

    private static int tileLog2(int blkSize, int target) {
        int k = 0;
        for (; (blkSize << k) < target; k++)
            ;
        return k;
    }

    private static long readLe(byte[] buf, int offset, int n) {
        long t = 0;
        for (int i = 0; i < n; i++) {
            t |= ((long) (buf[offset + i] & 0xFF)) << (i * 8);
        }
        return t;
    }

    /*
     * This method performs a deep copy of a frame header.
     */
    private static void copyFrameHeader(OBPFrameHeader source, OBPFrameHeader dest) {
        // Implement a deep copy of all fields from source to dest
        // This is a simplified version, you'll need to copy all relevant fields
        dest.showExistingFrame = source.showExistingFrame;
        dest.frameType = source.frameType;
        dest.showFrame = source.showFrame;
        // TODO(paul) copy all other fields

        // For complex objects like filmGrainParams, use the copy method we defined earlier
        copyFilmGrainParams(source.filmGrainParams, dest.filmGrainParams);

        // For arrays, use System.arraycopy
        System.arraycopy(source.refFrameIdx, 0, dest.refFrameIdx, 0, source.refFrameIdx.length);
        // TODO(paul) copy other arrays

    }

    private static void copyFilmGrainParams(OBPFilmGrainParameters source, OBPFilmGrainParameters dest) {
        dest.applyGrain = source.applyGrain;
        dest.grainSeed = source.grainSeed;
        dest.updateGrain = source.updateGrain;
        dest.filmGrainParamsRefIdx = source.filmGrainParamsRefIdx;
        dest.numYPoints = source.numYPoints;
        System.arraycopy(source.pointYValue, 0, dest.pointYValue, 0, source.pointYValue.length);
        System.arraycopy(source.pointYScaling, 0, dest.pointYScaling, 0, source.pointYScaling.length);
        dest.chromaScalingFromLuma = source.chromaScalingFromLuma;
        dest.numCbPoints = source.numCbPoints;
        System.arraycopy(source.pointCbValue, 0, dest.pointCbValue, 0, source.pointCbValue.length);
        System.arraycopy(source.pointCbScaling, 0, dest.pointCbScaling, 0, source.pointCbScaling.length);
        dest.numCrPoints = source.numCrPoints;
        System.arraycopy(source.pointCrValue, 0, dest.pointCrValue, 0, source.pointCrValue.length);
        System.arraycopy(source.pointCrScaling, 0, dest.pointCrScaling, 0, source.pointCrScaling.length);
        dest.grainScalingMinus8 = source.grainScalingMinus8;
        dest.arCoeffLag = source.arCoeffLag;
        System.arraycopy(source.arCoeffsYPlus128, 0, dest.arCoeffsYPlus128, 0, source.arCoeffsYPlus128.length);
        System.arraycopy(source.arCoeffsCbPlus128, 0, dest.arCoeffsCbPlus128, 0, source.arCoeffsCbPlus128.length);
        System.arraycopy(source.arCoeffsCrPlus128, 0, dest.arCoeffsCrPlus128, 0, source.arCoeffsCrPlus128.length);
        dest.arCoeffShiftMinus6 = source.arCoeffShiftMinus6;
        dest.grainScaleShift = source.grainScaleShift;
        dest.cbMult = source.cbMult;
        dest.cbLumaMult = source.cbLumaMult;
        dest.cbOffset = source.cbOffset;
        dest.crMult = source.crMult;
        dest.crLumaMult = source.crLumaMult;
        dest.crOffset = source.crOffset;
        dest.overlapFlag = source.overlapFlag;
        dest.clipToRestrictedRange = source.clipToRestrictedRange;
    }

    /*
     * This method handles parsing the frame size, either from the bitstream (if frameSizeOverrideFlag is set) or using
     * the values from the sequence header.
     */
    private static void parseFrameSize(BitReader br, OBPSequenceHeader seq, OBPFrameHeader fh) throws OBUParseException {
        if (fh.frameSizeOverrideFlag) {
            int n = seq.frameWidthBitsMinus1 + 1;
            fh.frameWidthMinus1 = br.readBits(n);
            n = seq.frameHeightBitsMinus1 + 1;
            fh.frameHeightMinus1 = br.readBits(n);
            fh.frameWidth = fh.frameWidthMinus1 + 1;
            fh.frameHeight = fh.frameHeightMinus1 + 1;
        } else {
            fh.frameWidth = seq.maxFrameWidthMinus1 + 1;
            fh.frameHeight = seq.maxFrameHeightMinus1 + 1;
        }
        parseSuperresParams(br, seq, fh);
        fh.miCols = 2 * ((fh.frameWidth + 7) >> 3);
        fh.miRows = 2 * ((fh.frameHeight + 7) >> 3);
    }

    /*
     * This method parses the render size, which may be different from the frame size.
     */
    private static void parseRenderSize(BitReader br, OBPFrameHeader fh) throws OBUParseException {
        fh.renderAndFrameSizeDifferent = br.readBits(1) != 0;
        if (fh.renderAndFrameSizeDifferent) {
            fh.renderWidthMinus1 = br.readBits(16);
            fh.renderHeightMinus1 = br.readBits(16);
            fh.renderWidth = fh.renderWidthMinus1 + 1;
            fh.renderHeight = fh.renderHeightMinus1 + 1;
        } else {
            fh.renderWidth = fh.upscaledWidth;
            fh.renderHeight = fh.frameHeight;
        }
    }

    private static void setFrameRefs(OBPFrameHeader fh, OBPSequenceHeader seq, OBPState state) throws OBUParseException {
        int[] usedFrame = new int[8];
        long curFrameHint, lastOrderHint, goldOrderHint, latestOrderHint, earliestOrderHint;
        int ref;
        byte[] shiftedOrderHints = new byte[8];
        final int[] Ref_Frame_List = { 2, 3, 5, 6, 7 }; // LAST2_FRAME, LAST3_FRAME, BWDREF_FRAME, ALTREF2_FRAME, ALTREF_FRAME
        int[] refFrameIdx = new int[8];

        for (int i = 0; i < 7; i++) {
            refFrameIdx[i] = -1;
        }
        refFrameIdx[0] = fh.lastFrameIdx;
        refFrameIdx[3] = fh.goldFrameIdx;
        for (int i = 0; i < 8; i++) {
            usedFrame[i] = 0;
        }
        usedFrame[fh.lastFrameIdx] = 1;
        usedFrame[fh.goldFrameIdx] = 2;
        curFrameHint = 1L << (seq.OrderHintBits - 1);
        for (int i = 0; i < 8; i++) {
            shiftedOrderHints[i] = (byte) (curFrameHint + getRelativeDist(state.refOrderHint[i], fh.orderHint, seq));
        }
        lastOrderHint = shiftedOrderHints[fh.lastFrameIdx];
        goldOrderHint = shiftedOrderHints[fh.goldFrameIdx];
        if (lastOrderHint >= curFrameHint || goldOrderHint >= curFrameHint) {
            throw new OBUParseException("Invalid order hints");
        }

        // Find the latest backward reference
        ref = -1;
        latestOrderHint = 0;
        for (int i = 0; i < 8; i++) {
            long hint = shiftedOrderHints[i];
            if (usedFrame[i] == 0 && hint >= curFrameHint && (ref < 0 || hint >= latestOrderHint)) {
                ref = i;
                latestOrderHint = hint;
            }
        }
        if (ref >= 0) {
            refFrameIdx[6] = ref;
            usedFrame[ref] = 1;
        }

        // Find the earliest backward reference
        ref = -1;
        earliestOrderHint = 0;
        for (int i = 0; i < 8; i++) {
            long hint = shiftedOrderHints[i];
            if (usedFrame[i] == 0 && hint >= curFrameHint && (ref < 0 || hint < earliestOrderHint)) {
                ref = i;
                earliestOrderHint = hint;
            }
        }
        if (ref >= 0) {
            refFrameIdx[4] = ref;
            usedFrame[ref] = 1;
        }

        // Find the second earliest backward reference
        ref = -1;
        earliestOrderHint = 0;
        for (int i = 0; i < 8; i++) {
            long hint = shiftedOrderHints[i];
            if (usedFrame[i] == 0 && hint >= curFrameHint && (ref < 0 || hint < earliestOrderHint)) {
                ref = i;
                earliestOrderHint = hint;
            }
        }
        if (ref >= 0) {
            refFrameIdx[5] = ref;
            usedFrame[ref] = 1;
        }

        for (int i = 0; i < 5; i++) {
            int refFrame = Ref_Frame_List[i];
            if (refFrameIdx[refFrame - 1] < 0) {
                ref = -1;
                long latestOrderHintSubRef = 0;
                for (int j = 0; j < 8; j++) {
                    long hint = shiftedOrderHints[j];
                    if (usedFrame[j] == 0 && hint < curFrameHint && (ref < 0 || hint >= latestOrderHintSubRef)) {
                        ref = j;
                        latestOrderHintSubRef = hint;
                    }
                }
                if (ref >= 0) {
                    refFrameIdx[refFrame - 1] = ref;
                    usedFrame[ref] = 1;
                }
            }
        }

        ref = -1;
        for (int i = 0; i < 8; i++) {
            long hint = shiftedOrderHints[i];
            if (ref < 0 || hint < earliestOrderHint) {
                ref = i;
                earliestOrderHint = hint;
            }
        }
        for (int i = 0; i < 7; i++) {
            if (refFrameIdx[i] < 0) {
                refFrameIdx[i] = ref;
            }
        }
        for (int i = 0; i < 7; i++) {
            fh.refFrameIdx[i] = (byte) refFrameIdx[i];
        }
    }

    /*
     * This method parses the superresolution parameters if superresolution is enabled.
     */
    private static void parseSuperresParams(BitReader br, OBPSequenceHeader seq, OBPFrameHeader fh) throws OBUParseException {
        if (seq.enableSuperres) {
            fh.superresParams.useSuperres = br.readBits(1) != 0;
        } else {
            fh.superresParams.useSuperres = false;
        }
        if (fh.superresParams.useSuperres) {
            fh.superresParams.codedDenom = (byte) br.readBits(3);
            fh.superresParams.superresDenom = fh.superresParams.codedDenom + 9;
        } else {
            fh.superresParams.superresDenom = 8;
        }
        fh.upscaledWidth = fh.frameWidth;
        fh.frameWidth = (fh.upscaledWidth * 8 + (fh.superresParams.superresDenom / 2)) / fh.superresParams.superresDenom;
    }

    /*
     * This method parses the interpolation filter information from the bitstream.
     */
    private static void parseInterpolationFilter(BitReader br, OBPFrameHeader fh) throws OBUParseException {
        fh.interpolationFilter.isFilterSwitchable = br.readBits(1) != 0;
        if (fh.interpolationFilter.isFilterSwitchable) {
            fh.interpolationFilter.interpolationFilter = OBPInterpolationFilter.SWITCHABLE;
        } else {
            fh.interpolationFilter.interpolationFilter = OBPInterpolationFilter.values()[br.readBits(2)];
        }
    }

    private static int getRelativeDist(int a, int b, OBPSequenceHeader seq) {
        if (seq.enableOrderHint) {
            int diff = a - b;
            int m = 1 << (seq.OrderHintBits - 1);
            diff = (diff & (m - 1)) - (diff & m);
            return diff;
        }
        return 0;
    }

    private static void setupPastIndependence(OBPFrameHeader fh) {
        for (int i = 1; i < 7; i++) {
            fh.globalMotionParams.gmType[i] = 0;
            for (int j = 0; j < 6; j++) {
                fh.globalMotionParams.gmParams[i][j] = (j % 3 == 2) ? (1 << 16) : 0;
            }
        }
        fh.loopFilterParams.loopFilterDeltaEnabled = true;
        fh.loopFilterParams.loopFilterRefDeltas[0] = 1;
        fh.loopFilterParams.loopFilterRefDeltas[1] = 0;
        fh.loopFilterParams.loopFilterRefDeltas[2] = 0;
        fh.loopFilterParams.loopFilterRefDeltas[3] = 0;
        fh.loopFilterParams.loopFilterRefDeltas[4] = 0;
        fh.loopFilterParams.loopFilterRefDeltas[5] = -1;
        fh.loopFilterParams.loopFilterRefDeltas[6] = -1;
        fh.loopFilterParams.loopFilterRefDeltas[7] = -1;
        for (int i = 0; i < 2; i++) {
            fh.loopFilterParams.loopFilterModeDeltas[i] = 0;
        }
    }

    /*
     * This method loads the previous frame's parameters into the current frame header.
     */
    private static void loadPrevious(OBPFrameHeader fh, OBPState state) {
        int prevFrame = fh.refFrameIdx[fh.primaryRefFrame];
        // Load global motion parameters
        for (int i = 0; i < OBPConstants.REFS_PER_FRAME; i++) {
            for (int j = 0; j < 6; j++) {
                fh.globalMotionParams.prevGmParams[i][j] = state.savedGmParams[prevFrame][i][j];
            }
        }
        // Load loop filter parameters
        for (int i = 0; i < OBPConstants.TOTAL_REFS_PER_FRAME; i++) {
            fh.loopFilterParams.loopFilterRefDeltas[i] = state.savedLoopFilterRefDeltas[prevFrame][i];
        }
        for (int i = 0; i < 2; i++) {
            fh.loopFilterParams.loopFilterModeDeltas[i] = state.savedLoopFilterModeDeltas[prevFrame][i];
        }
        // Load segmentation parameters
        fh.segmentationParams.segmentationEnabled = state.savedSegmentationParams[prevFrame].segmentationEnabled;
        fh.segmentationParams.segmentationUpdateMap = state.savedSegmentationParams[prevFrame].segmentationUpdateMap;
        fh.segmentationParams.segmentationTemporalUpdate = state.savedSegmentationParams[prevFrame].segmentationTemporalUpdate;
        fh.segmentationParams.segmentationUpdateData = state.savedSegmentationParams[prevFrame].segmentationUpdateData;
        for (int i = 0; i < OBPConstants.MAX_SEGMENTS; i++) {
            for (int j = 0; j < OBPConstants.SEG_LVL_MAX; j++) {
                fh.segmentationParams.featureEnabled[i][j] = state.savedFeatureEnabled[prevFrame][i][j];
                fh.segmentationParams.featureData[i][j] = state.savedFeatureData[prevFrame][i][j];
            }
        }
    }


    private static void parseTileInfo(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        int sbCols = seq.use128x128Superblock ? ((fh.miCols + 31) >> 5) : ((fh.miCols + 15) >> 4);
        int sbRows = seq.use128x128Superblock ? ((fh.miRows + 31) >> 5) : ((fh.miRows + 15) >> 4);
        int sbShift = seq.use128x128Superblock ? 5 : 4;
        int sbSize = sbShift + 2;
        int maxTileWidthSb = 4096 >> sbSize;
        int maxTileAreaSb = (4096 * 2304) >> (2 * sbSize);
        int minLog2TileCols = tileLog2(maxTileWidthSb, sbCols);
        int maxLog2TileCols = tileLog2(1, Math.min(sbCols, 64));
        int maxLog2TileRows = tileLog2(1, Math.min(sbRows, 64));
        int minLog2Tiles = Math.max(minLog2TileCols, tileLog2(maxTileAreaSb, sbRows * sbCols));
        fh.tileInfo.uniformTileSpacingFlag = br.readBits(1) != 0;
        if (fh.tileInfo.uniformTileSpacingFlag) {
            fh.tileInfo.tileColsLog2 = minLog2TileCols;
            while (fh.tileInfo.tileColsLog2 < maxLog2TileCols) {
                int incrementTileColsLog2 = br.readBits(1);
                if (incrementTileColsLog2 == 1) {
                    fh.tileInfo.tileColsLog2++;
                } else {
                    break;
                }
            }
            int tileWidthSb = (sbCols + (1 << fh.tileInfo.tileColsLog2) - 1) >> fh.tileInfo.tileColsLog2;
            int i = 0;
            for (int startSb = 0; startSb < sbCols; startSb += tileWidthSb) {
                i++;
            }
            fh.tileInfo.tileCols = i;
            int minLog2TileRows = Math.max(minLog2Tiles - fh.tileInfo.tileColsLog2, 0);
            fh.tileInfo.tileRowsLog2 = minLog2TileRows;
            while (fh.tileInfo.tileRowsLog2 < maxLog2TileRows) {
                int incrementTileRowsLog2 = br.readBits(1);
                if (incrementTileRowsLog2 == 1) {
                    fh.tileInfo.tileRowsLog2++;
                } else {
                    break;
                }
            }
            int tileHeightSb = (sbRows + (1 << fh.tileInfo.tileRowsLog2) - 1) >> fh.tileInfo.tileRowsLog2;
            i = 0;
            for (int startSb = 0; startSb < sbRows; startSb += tileHeightSb) {
                i++;
            }
            fh.tileInfo.tileRows = i;
        } else {
            int widestTileSb = 0;
            int startSb = 0;
            int i;
            for (i = 0; startSb < sbCols; i++) {
                int maxWidth = Math.min(sbCols - startSb, maxTileWidthSb);
                int widthInSbs = (int) readNs(br, maxWidth) + 1;
                widestTileSb = Math.max(widestTileSb, widthInSbs);
                startSb += widthInSbs;
            }
            fh.tileInfo.tileCols = i;
            fh.tileInfo.tileColsLog2 = tileLog2(1, fh.tileInfo.tileCols);

            if (minLog2Tiles > 0) {
                maxTileAreaSb = (sbRows * sbCols) >> (minLog2Tiles + 1);
            } else {
                maxTileAreaSb = sbRows * sbCols;
            }
            int maxTileHeightSb = Math.max(maxTileAreaSb / widestTileSb, 1);

            startSb = 0;
            for (i = 0; startSb < sbRows; i++) {
                int maxHeight = Math.min(sbRows - startSb, maxTileHeightSb);
                int heightInSbs = (int) readNs(br, maxHeight) + 1;
                startSb += heightInSbs;
            }
            fh.tileInfo.tileRows = i;
            fh.tileInfo.tileRowsLog2 = tileLog2(1, fh.tileInfo.tileRows);
        }
        if (fh.tileInfo.tileColsLog2 > 0 || fh.tileInfo.tileRowsLog2 > 0) {
            fh.tileInfo.contextUpdateTileId = br.readBits(fh.tileInfo.tileColsLog2 + fh.tileInfo.tileRowsLog2);
            fh.tileInfo.tileSizeBytesMinus1 = br.readBits(2);
        } else {
            fh.tileInfo.contextUpdateTileId = 0;
        }
    }

    private static long readNs(BitReader br, long n) throws OBUParseException {
        if (n == 0) {
            return 0;
        }
        int w = floorLog2(n) + 1;
        long m = (1L << w) - n;
        long v = br.readBits(w - 1);
        if (v < m) {
            return v;
        }
        long extraBit = br.readBits(1);
        return (v << 1) - m + extraBit;
    }

    private static int floorLog2(long n) {
        return 63 - Long.numberOfLeadingZeros(n);
    }

    private static void parseQuantizationParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        fh.quantizationParams.baseQIdx = br.readBits(8);
        fh.quantizationParams.deltaQYDc = readDeltaQ(br);
        if (seq.colorConfig.NumPlanes > 1) {
            if (seq.colorConfig.separateUvDeltaQ) {
                fh.quantizationParams.diffUvDelta = br.readBits(1) != 0;
            } else {
                fh.quantizationParams.diffUvDelta = false;
            }
            fh.quantizationParams.deltaQUDc = readDeltaQ(br);
            fh.quantizationParams.deltaQUAc = readDeltaQ(br);
            if (fh.quantizationParams.diffUvDelta) {
                fh.quantizationParams.deltaQVDc = readDeltaQ(br);
                fh.quantizationParams.deltaQVAc = readDeltaQ(br);
            } else {
                fh.quantizationParams.deltaQVDc = fh.quantizationParams.deltaQUDc;
                fh.quantizationParams.deltaQVAc = fh.quantizationParams.deltaQUAc;
            }
        } else {
            fh.quantizationParams.deltaQUDc = 0;
            fh.quantizationParams.deltaQUAc = 0;
            fh.quantizationParams.deltaQVDc = 0;
            fh.quantizationParams.deltaQVAc = 0;
        }
        fh.quantizationParams.usingQmatrix = br.readBits(1) != 0;
        if (fh.quantizationParams.usingQmatrix) {
            fh.quantizationParams.qmY = br.readBits(4);
            fh.quantizationParams.qmU = br.readBits(4);
            if (!seq.colorConfig.separateUvDeltaQ) {
                fh.quantizationParams.qmV = fh.quantizationParams.qmU;
            } else {
                fh.quantizationParams.qmV = br.readBits(4);
            }
        }
    }

    /*
     * This method reads a delta Q value from the bitstream. It first reads a single bit to determine if the delta is
     * coded. If it is, it reads a signed value using 7 bits. If not, it returns 0.
     */
    private static int readDeltaQ(BitReader br) throws OBUParseException {
        int delta_coded = br.readBits(1);
        if (delta_coded != 0) {
            return br.readBits(7) - 1;
        } else {
            return 0;
        }
    }

    private static void parseSegmentationParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        fh.segmentationParams.segmentationEnabled = br.readBits(1) != 0;
        if (fh.segmentationParams.segmentationEnabled) {
            if (fh.primaryRefFrame == 7) {
                fh.segmentationParams.segmentationUpdateMap = true;
                fh.segmentationParams.segmentationTemporalUpdate = false;
                fh.segmentationParams.segmentationUpdateData = true;
            } else {
                fh.segmentationParams.segmentationUpdateMap = br.readBits(1) != 0;
                if (fh.segmentationParams.segmentationUpdateMap) {
                    fh.segmentationParams.segmentationTemporalUpdate = br.readBits(1) != 0;
                }
                fh.segmentationParams.segmentationUpdateData = br.readBits(1) != 0;
            }
            if (fh.segmentationParams.segmentationUpdateData) {
                for (int i = 0; i < 8; i++) {
                    for (int j = 0; j < 8; j++) {
                        int featureEnabled = br.readBits(1);
                        fh.segmentationParams.featureEnabled[i][j] = featureEnabled != 0;
                        if (featureEnabled != 0) {
                            int bitsToRead = OBPConstants.Segmentation_Feature_Bits[j];
                            int limit = OBPConstants.Segmentation_Feature_Max[j];
                            int featureValue;
                            if (OBPConstants.Segmentation_Feature_Signed[j] != 0) {
                                featureValue = br.readBits(1 + bitsToRead);
                                if ((featureValue & (1 << bitsToRead)) != 0) {
                                    featureValue = featureValue - (1 << (bitsToRead + 1));
                                }
                            } else {
                                featureValue = br.readBits(bitsToRead);
                            }
                            fh.segmentationParams.featureData[i][j] = (short) Math.max(-limit, Math.min(limit, featureValue));
                        } else {
                            fh.segmentationParams.featureData[i][j] = 0;
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    fh.segmentationParams.featureEnabled[i][j] = false;
                    fh.segmentationParams.featureData[i][j] = 0;
                }
            }
        }
    }

    private static void parseDeltaQParams(BitReader br, OBPFrameHeader fh) throws OBUParseException {
        fh.deltaQParams.deltaQRes = 0;
        fh.deltaQParams.deltaQPresent = false;
        if (fh.quantizationParams.baseQIdx > 0) {
            fh.deltaQParams.deltaQPresent = br.readBits(1) != 0;
        }
        if (fh.deltaQParams.deltaQPresent) {
            fh.deltaQParams.deltaQRes = (byte) br.readBits(2);
        }
    }

    private static void parseDeltaLfParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        fh.deltaLfParams.deltaLfPresent = false;
        fh.deltaLfParams.deltaLfRes = 0;
        fh.deltaLfParams.deltaLfMulti = false;
        if (fh.deltaQParams.deltaQPresent) {
            if (!fh.allowIntrabc) {
                fh.deltaLfParams.deltaLfPresent = br.readBits(1) != 0;
            }
            if (fh.deltaLfParams.deltaLfPresent) {
                fh.deltaLfParams.deltaLfRes = (byte) br.readBits(2);
                fh.deltaLfParams.deltaLfMulti = br.readBits(1) != 0;
            }
        }
    }

    /*
     * This method computes whether the frame is losslessly coded.
     */
    private static boolean computeCodedLossless(OBPFrameHeader fh, OBPSequenceHeader seq) {
        for (int segmentId = 0; segmentId < 8; segmentId++) {
            int qindex = getQIndex(true, segmentId, fh.quantizationParams.baseQIdx, fh, seq);
            if (qindex != 0 || fh.quantizationParams.deltaQYDc != 0 || fh.quantizationParams.deltaQUAc != 0 || fh.quantizationParams.deltaQUDc != 0 || fh.quantizationParams.deltaQVAc != 0 || fh.quantizationParams.deltaQVDc != 0) {
                return false;
            }
        }
        return true;
    }

    /*
     * This method computes the quantization index for a given segment.
     */
    private static int getQIndex(boolean ignoreDeltaQ, int segmentId, int currentQIndex, OBPFrameHeader fh, OBPSequenceHeader seq) {
        if (fh.segmentationParams.segmentationEnabled && fh.segmentationParams.featureEnabled[segmentId][0]) {
            int data = fh.segmentationParams.featureData[segmentId][0];
            int qindex = fh.quantizationParams.baseQIdx + data;
            if (!ignoreDeltaQ && fh.deltaQParams.deltaQPresent) {
                qindex = currentQIndex + data;
            }
            return Math.max(0, Math.min(255, qindex));
        }
        if (!ignoreDeltaQ && fh.deltaQParams.deltaQPresent) {
            return currentQIndex;
        }
        return fh.quantizationParams.baseQIdx;
    }

    private static void parseLoopFilterParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        if (fh.codedLossless || fh.allowIntrabc) {
            fh.loopFilterParams.loopFilterDeltaEnabled = true;
            fh.loopFilterParams.loopFilterRefDeltas[0] = 1;
            fh.loopFilterParams.loopFilterRefDeltas[1] = 0;
            fh.loopFilterParams.loopFilterRefDeltas[2] = 0;
            fh.loopFilterParams.loopFilterRefDeltas[3] = 0;
            fh.loopFilterParams.loopFilterRefDeltas[4] = 0;
            fh.loopFilterParams.loopFilterRefDeltas[5] = -1;
            fh.loopFilterParams.loopFilterRefDeltas[6] = -1;
            fh.loopFilterParams.loopFilterRefDeltas[7] = -1;
            for (int i = 0; i < 2; i++) {
                fh.loopFilterParams.loopFilterModeDeltas[i] = 0;
            }
            return;
        }

        fh.loopFilterParams.loopFilterLevel[0] = (byte) br.readBits(6);
        fh.loopFilterParams.loopFilterLevel[1] = (byte) br.readBits(6);
        if (seq.colorConfig.NumPlanes > 1) {
            if (fh.loopFilterParams.loopFilterLevel[0] != 0 || fh.loopFilterParams.loopFilterLevel[1] != 0) {
                fh.loopFilterParams.loopFilterLevel[2] = (byte) br.readBits(6);
                fh.loopFilterParams.loopFilterLevel[3] = (byte) br.readBits(6);
            }
        }
        fh.loopFilterParams.loopFilterSharpness = (byte) br.readBits(3);
        fh.loopFilterParams.loopFilterDeltaEnabled = br.readBits(1) != 0;
        if (fh.loopFilterParams.loopFilterDeltaEnabled) {
            fh.loopFilterParams.loopFilterDeltaUpdate = br.readBits(1) != 0;
            if (fh.loopFilterParams.loopFilterDeltaUpdate) {
                for (int i = 0; i < 8; i++) {
                    fh.loopFilterParams.updateRefDelta[i] = br.readBits(1) != 0;
                    if (fh.loopFilterParams.updateRefDelta[i]) {
                        fh.loopFilterParams.loopFilterRefDeltas[i] = (byte) br.readBits(7);
                        if ((fh.loopFilterParams.loopFilterRefDeltas[i] & 0x40) != 0) {
                            fh.loopFilterParams.loopFilterRefDeltas[i] -= 128;
                        }
                    }
                }
                for (int i = 0; i < 2; i++) {
                    fh.loopFilterParams.updateModeDelta[i] = br.readBits(1) != 0;
                    if (fh.loopFilterParams.updateModeDelta[i]) {
                        fh.loopFilterParams.loopFilterModeDeltas[i] = (byte) br.readBits(7);
                        if ((fh.loopFilterParams.loopFilterModeDeltas[i] & 0x40) != 0) {
                            fh.loopFilterParams.loopFilterModeDeltas[i] -= 128;
                        }
                    }
                }
            }
        }
    }

    private static void parseCdefParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        if (fh.codedLossless || fh.allowIntrabc || !seq.enableCdef) {
            fh.cdefParams.cdefBits = 0;
            fh.cdefParams.cdefYPriStrength[0] = 0;
            fh.cdefParams.cdefYSecStrength[0] = 0;
            fh.cdefParams.cdefUvPriStrength[0] = 0;
            fh.cdefParams.cdefUvSecStrength[0] = 0;
            return;
        }

        fh.cdefParams.cdefDampingMinus3 = (byte) br.readBits(2);
        fh.cdefParams.cdefBits = (byte) br.readBits(2);
        for (int i = 0; i < (1 << fh.cdefParams.cdefBits); i++) {
            fh.cdefParams.cdefYPriStrength[i] = (byte) br.readBits(4);
            fh.cdefParams.cdefYSecStrength[i] = (byte) br.readBits(2);
            if (fh.cdefParams.cdefYSecStrength[i] == 3) {
                fh.cdefParams.cdefYSecStrength[i] += 1;
            }
            if (seq.colorConfig.NumPlanes > 1) {
                fh.cdefParams.cdefUvPriStrength[i] = (byte) br.readBits(4);
                fh.cdefParams.cdefUvSecStrength[i] = (byte) br.readBits(2);
                if (fh.cdefParams.cdefUvSecStrength[i] == 3) {
                    fh.cdefParams.cdefUvSecStrength[i] += 1;
                }
            }
        }
    }

    private static void parseLrParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq) throws OBUParseException {
        if (fh.allLossless || fh.allowIntrabc || !seq.enableRestoration) {
            fh.lrParams.lrType[0] = 0;
            fh.lrParams.lrType[1] = 0;
            fh.lrParams.lrType[2] = 0;
            return;
        }
        boolean usesLr = false;
        boolean usesChromaLr = false;
        for (int i = 0; i < seq.colorConfig.NumPlanes; i++) {
            fh.lrParams.lrType[i] = (byte) br.readBits(2);
            if (fh.lrParams.lrType[i] != 0) {
                usesLr = true;
                if (i > 0) {
                    usesChromaLr = true;
                }
            }
        }
        if (usesLr) {
            if (seq.use128x128Superblock) {
                fh.lrParams.lrUnitShift = (byte) (br.readBits(1) + 1);
            } else {
                fh.lrParams.lrUnitShift = (byte) br.readBits(1);
                if (fh.lrParams.lrUnitShift != 0) {
                    fh.lrParams.lrUnitShift += br.readBits(1);
                }
            }
            // LoopRestorationSize is not directly used in parsing, so we don't set it here
            if (seq.colorConfig.subsamplingX && seq.colorConfig.subsamplingY && usesChromaLr) {
                fh.lrParams.lrUvShift = br.readBits(1) != 0;
            } else {
                fh.lrParams.lrUvShift = false;
            }
        }
    }

    /*
     * This method is quite straightforward. It sets the referenceSelectInter flag in the frame header based on whether
     * the frame is intra or not. If the frame is not intra, it reads a single bit from the bitstream to determine the
     * value of referenceSelectInter.
     */
    private static void parseFrameReferenceMode(BitReader br, OBPFrameHeader fh, boolean FrameIsIntra) throws OBUParseException {
        if (FrameIsIntra) {
            fh.referenceSelectInter = false;
        } else {
            fh.referenceSelectInter = br.readBits(1) != 0;
        }
    }

    private static void parseSkipModeParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq, OBPState state, boolean frameIsIntra) throws OBUParseException {
        boolean skipModeAllowed;
        if (frameIsIntra || !fh.referenceSelectInter || !seq.enableOrderHint) {
            skipModeAllowed = false;
        } else {
            int forwardIdx = -1;
            int backwardIdx = -1;
            int forwardHint = 0;
            int backwardHint = 0;
            for (int i = 0; i < OBPConstants.REFS_PER_FRAME; i++) {
                int refHint = state.refOrderHint[fh.refFrameIdx[i]];
                if (getRelativeDist(refHint, fh.orderHint, seq) < 0) {
                    if (forwardIdx < 0 || getRelativeDist(refHint, forwardHint, seq) > 0) {
                        forwardIdx = i;
                        forwardHint = refHint;
                    }
                } else if (getRelativeDist(refHint, fh.orderHint, seq) > 0) {
                    if (backwardIdx < 0 || getRelativeDist(refHint, backwardHint, seq) < 0) {
                        backwardIdx = i;
                        backwardHint = refHint;
                    }
                }
            }
            if (forwardIdx < 0) {
                skipModeAllowed = false;
            } else if (backwardIdx >= 0) {
                skipModeAllowed = true;
                // SkipModeFrame not used in parsing, so we don't set it here
            } else {
                int secondForwardIdx = -1;
                int secondForwardHint = 0;
                for (int i = 0; i < OBPConstants.REFS_PER_FRAME; i++) {
                    int refHint = state.refOrderHint[fh.refFrameIdx[i]];
                    if (getRelativeDist(refHint, forwardHint, seq) < 0) {
                        if (secondForwardIdx < 0 || getRelativeDist(refHint, secondForwardHint, seq) > 0) {
                            secondForwardIdx = i;
                            secondForwardHint = refHint;
                        }
                    }
                }
                skipModeAllowed = secondForwardIdx >= 0;
                // SkipModeFrame not used in parsing, so we don't set it here
            }
        }
        if (skipModeAllowed) {
            fh.skipModePresent = br.readBits(1) != 0;
        } else {
            fh.skipModePresent = false;
        }
    }

    private static void parseGlobalMotionParams(BitReader br, OBPFrameHeader fh, boolean frameIsIntra) throws OBUParseException {
        for (int ref = 1; ref < 7; ref++) {
            fh.globalMotionParams.gmType[ref] = 0;
            for (int i = 0; i < 6; i++) {
                fh.globalMotionParams.gmParams[ref][i] = (i % 3 == 2) ? (1 << 16) : 0;
            }
        }
        if (!frameIsIntra) {
            for (int ref = 1; ref <= 7; ref++) {
                boolean isGlobal = br.readBits(1) != 0;
                if (isGlobal) {
                    boolean isRotZoom = br.readBits(1) != 0;
                    if (isRotZoom) {
                        fh.globalMotionParams.gmType[ref] = 2;
                    } else {
                        boolean isTranslation = br.readBits(1) != 0;
                        fh.globalMotionParams.gmType[ref] = (byte) (isTranslation ? 1 : 3);
                    }
                }
                if (fh.globalMotionParams.gmType[ref] >= 2) {
                    readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 2);
                    readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 3);
                    if (fh.globalMotionParams.gmType[ref] == 3) {
                        readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 4);
                        readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 5);
                    } else {
                        fh.globalMotionParams.gmParams[ref][4] = -fh.globalMotionParams.gmParams[ref][3];
                        fh.globalMotionParams.gmParams[ref][5] = fh.globalMotionParams.gmParams[ref][2];
                    }
                }
                if (fh.globalMotionParams.gmType[ref] >= 1) {
                    readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 0);
                    readGlobalParam(br, fh, fh.globalMotionParams.gmType[ref], ref, 1);
                }
            }
        }
    }

    private static void readGlobalParam(BitReader br, OBPFrameHeader fh, int type, int ref, int idx) throws OBUParseException {
        int absBits = 12;
        int precBits = 15;
        if (idx < 2) {
            if (type == 1) { // TRANSLATION
                absBits = 9 - (fh.allowHighPrecisionMv ? 0 : 1);
                precBits = 3 - (fh.allowHighPrecisionMv ? 0 : 1);
            } else {
                absBits = 12;
                precBits = 6;
            }
        }
        int precDiff = 16 - precBits;
        int round = (idx % 3) == 2 ? (1 << 16) : 0;
        int sub = (idx % 3) == 2 ? (1 << precBits) : 0;
        int mx = (1 << absBits);
        int r = (fh.globalMotionParams.prevGmParams[ref][idx] >> precDiff) - sub;
        int val = decodeSignedSubexpWithRef(br, -mx, mx + 1, r);
        if (val < 0) {
            val = -val;
            fh.globalMotionParams.gmParams[ref][idx] = -(val << precDiff) + round;
        } else {
            fh.globalMotionParams.gmParams[ref][idx] = (val << precDiff) + round;
        }
    }

    private static int decodeSignedSubexpWithRef(BitReader br, int low, int high, int r) throws OBUParseException {
        int x = decodeUnsignedSubexpWithRef(br, high - low, r - low);
        return x + low;
    }

    private static int decodeUnsignedSubexpWithRef(BitReader br, int mx, int r) throws OBUParseException {
        int v;
        if ((r << 1) <= mx) {
            v = decodeSubexp(br, mx);
            if (v < r) {
                return v;
            } else {
                return mx - 1 - v + r;
            }
        } else {
            v = decodeSubexp(br, mx);
            if (v < (mx - r)) {
                return r + v;
            } else {
                return v - (mx - r);
            }
        }
    }

    private static int decodeSubexp(BitReader br, int numSyms) throws OBUParseException {
        int i = 0;
        int mk = 0;
        int k = 3;
        while (true) {
            int b2 = i != 0 ? k + i - 1 : k;
            int a = 1 << b2;
            if (numSyms <= mk + 3 * a) {
                return (int) readNs(br, numSyms - mk) + mk;
            } else {
                boolean subexpMoreBits = br.readBits(1) != 0;
                if (subexpMoreBits) {
                    i++;
                    mk += a;
                } else {
                    return (int) br.readBits(b2) + mk;
                }
            }
        }
    }

    private static void parseFilmGrainParams(BitReader br, OBPFrameHeader fh, OBPSequenceHeader seq, OBPState state) throws OBUParseException {
        if (!seq.filmGrainParamsPresent || (!fh.showFrame && !fh.showableFrame)) {
            resetGrainParams(fh.filmGrainParams);
            return;
        }

        fh.filmGrainParams.applyGrain = br.readBits(1) != 0;

        if (!fh.filmGrainParams.applyGrain) {
            resetGrainParams(fh.filmGrainParams);
            return;
        }

        fh.filmGrainParams.grainSeed = (short) br.readBits(16);

        if (fh.frameType == OBPFrameType.INTERFRAME) {
            fh.filmGrainParams.updateGrain = br.readBits(1) != 0;
        } else {
            fh.filmGrainParams.updateGrain = true;
        }

        if (!fh.filmGrainParams.updateGrain) {
            fh.filmGrainParams.filmGrainParamsRefIdx = (byte) br.readBits(3);
            short tempGrainSeed = fh.filmGrainParams.grainSeed;
            fh.filmGrainParams = state.refGrainParams[fh.filmGrainParams.filmGrainParamsRefIdx];
            fh.filmGrainParams.grainSeed = tempGrainSeed;
            return;
        }

        fh.filmGrainParams.numYPoints = (byte) br.readBits(4);
        for (int i = 0; i < fh.filmGrainParams.numYPoints; i++) {
            fh.filmGrainParams.pointYValue[i] = (byte) br.readBits(8);
            fh.filmGrainParams.pointYScaling[i] = (byte) br.readBits(8);
        }

        if (seq.colorConfig.monoChrome) {
            fh.filmGrainParams.chromaScalingFromLuma = false;
        } else {
            fh.filmGrainParams.chromaScalingFromLuma = br.readBits(1) != 0;
        }

        if (seq.colorConfig.monoChrome || fh.filmGrainParams.chromaScalingFromLuma || (seq.colorConfig.subsamplingX && seq.colorConfig.subsamplingY && fh.filmGrainParams.numYPoints == 0)) {
            fh.filmGrainParams.numCbPoints = 0;
            fh.filmGrainParams.numCrPoints = 0;
        } else {
            fh.filmGrainParams.numCbPoints = (byte) br.readBits(4);
            for (int i = 0; i < fh.filmGrainParams.numCbPoints; i++) {
                fh.filmGrainParams.pointCbValue[i] = (byte) br.readBits(8);
                fh.filmGrainParams.pointCbScaling[i] = (byte) br.readBits(8);
            }
            fh.filmGrainParams.numCrPoints = (byte) br.readBits(4);
            for (int i = 0; i < fh.filmGrainParams.numCrPoints; i++) {
                fh.filmGrainParams.pointCrValue[i] = (byte) br.readBits(8);
                fh.filmGrainParams.pointCrScaling[i] = (byte) br.readBits(8);
            }
        }

        fh.filmGrainParams.grainScalingMinus8 = (byte) br.readBits(2);
        fh.filmGrainParams.arCoeffLag = (byte) br.readBits(2);

        int numPosLuma = 2 * fh.filmGrainParams.arCoeffLag * (fh.filmGrainParams.arCoeffLag + 1);
        int numPosChroma = numPosLuma;
        if (fh.filmGrainParams.numYPoints > 0) {
            numPosChroma = numPosLuma + 1;
            for (int i = 0; i < numPosLuma; i++) {
                fh.filmGrainParams.arCoeffsYPlus128[i] = (byte) br.readBits(8);
            }
        }
        if (fh.filmGrainParams.chromaScalingFromLuma || fh.filmGrainParams.numCbPoints > 0) {
            for (int i = 0; i < numPosChroma; i++) {
                fh.filmGrainParams.arCoeffsCbPlus128[i] = (byte) br.readBits(8);
            }
        }
        if (fh.filmGrainParams.chromaScalingFromLuma || fh.filmGrainParams.numCrPoints > 0) {
            for (int i = 0; i < numPosChroma; i++) {
                fh.filmGrainParams.arCoeffsCrPlus128[i] = (byte) br.readBits(8);
            }
        }

        fh.filmGrainParams.arCoeffShiftMinus6 = (byte) br.readBits(2);
        fh.filmGrainParams.grainScaleShift = (byte) br.readBits(2);

        if (fh.filmGrainParams.numCbPoints > 0) {
            fh.filmGrainParams.cbMult = (byte) br.readBits(8);
            fh.filmGrainParams.cbLumaMult = (byte) br.readBits(8);
            fh.filmGrainParams.cbOffset = (short) br.readBits(9);
        }

        if (fh.filmGrainParams.numCrPoints > 0) {
            fh.filmGrainParams.crMult = (byte) br.readBits(8);
            fh.filmGrainParams.crLumaMult = (byte) br.readBits(8);
            fh.filmGrainParams.crOffset = (short) br.readBits(9);
        }

        fh.filmGrainParams.overlapFlag = br.readBits(1) != 0;
        fh.filmGrainParams.clipToRestrictedRange = br.readBits(1) != 0;
    }

    private static void resetGrainParams(OBPFilmGrainParameters params) {
        params.applyGrain = false;
        params.grainSeed = 0;
        params.updateGrain = false;
        params.filmGrainParamsRefIdx = 0;
        params.numYPoints = 0;
        Arrays.fill(params.pointYValue, (byte) 0);
        Arrays.fill(params.pointYScaling, (byte) 0);
        params.chromaScalingFromLuma = false;
        params.numCbPoints = 0;
        Arrays.fill(params.pointCbValue, (byte) 0);
        Arrays.fill(params.pointCbScaling, (byte) 0);
        params.numCrPoints = 0;
        Arrays.fill(params.pointCrValue, (byte) 0);
        Arrays.fill(params.pointCrScaling, (byte) 0);
        params.grainScalingMinus8 = 0;
        params.arCoeffLag = 0;
        Arrays.fill(params.arCoeffsYPlus128, (byte) 0);
        Arrays.fill(params.arCoeffsCbPlus128, (byte) 0);
        Arrays.fill(params.arCoeffsCrPlus128, (byte) 0);
        params.arCoeffShiftMinus6 = 0;
        params.grainScaleShift = 0;
        params.cbMult = 0;
        params.cbLumaMult = 0;
        params.cbOffset = 0;
        params.crMult = 0;
        params.crLumaMult = 0;
        params.crOffset = 0;
        params.overlapFlag = false;
        params.clipToRestrictedRange = false;
    }

}
