package org.red5.io.obu;

public interface OBUParserStrategy<T> {

    T parse(byte[] buf, int bufSize) throws OBUParseException;
}