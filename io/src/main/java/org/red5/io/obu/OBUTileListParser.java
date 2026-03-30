package org.red5.io.obu;

import java.util.Arrays;

public class OBUTileListParser implements OBUParserStrategy<OBPTileList>{

    @Override
    public OBPTileList parse(byte[] buf, int bufSize) throws OBUParseException {
        return parseTileList(buf, bufSize);
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
    public OBPTileList parseTileList(byte[] buf, long bufSize) throws OBUParseException {
        OBPTileList tileList = new OBPTileList();
        int pos = 0;
        if (bufSize < 4) {
            throw new OBUParseException("Tile list OBU must be at least 4 bytes");
        }
        tileList.outputFrameWidthInTilesMinus1 = buf[0];
        tileList.outputFrameHeightInTilesMinus1 = buf[1];
        tileList.tileCountMinus1 = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF));
        pos += 4;
        tileList.tileListEntry = new OBPTileList.TileListEntry[tileList.tileCountMinus1 + 1];
        for (int i = 0; i <= tileList.tileCountMinus1; i++) {
            if (pos + 5 > bufSize) {
                throw new OBUParseException("Tile list OBU malformed: Not enough bytes for next tile_list_entry()");
            }
            OBPTileList.TileListEntry entry = new OBPTileList.TileListEntry();
            entry.anchorFrameIdx = buf[pos];
            entry.anchorTileRow = buf[pos + 1];
            entry.anchorTileCol = buf[pos + 2];
            entry.tileDataSizeMinus1 = (short) (((buf[pos + 3] & 0xFF) << 8) | (buf[pos + 4] & 0xFF));
            pos += 5;
            int N = 8 * (entry.tileDataSizeMinus1 + 1);
            if (pos + N > bufSize) {
                throw new OBUParseException("Tile list OBU malformed: Not enough bytes for next tile_list_entry()'s data");
            }
            entry.codedTileData = Arrays.copyOfRange(buf, pos, (pos + N));
            entry.codedTileDataSize = N;
            pos += N;
            tileList.tileListEntry[i] = entry;
        }
        return tileList;
    }

}
