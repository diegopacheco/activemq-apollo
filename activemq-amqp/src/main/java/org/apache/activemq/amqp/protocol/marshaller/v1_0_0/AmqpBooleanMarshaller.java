/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * his work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.amqp.protocol.marshaller.v1_0_0;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.Boolean;
import org.apache.activemq.amqp.protocol.marshaller.AmqpEncodingError;
import org.apache.activemq.amqp.protocol.marshaller.AmqpVersion;
import org.apache.activemq.amqp.protocol.marshaller.Encoded;
import org.apache.activemq.amqp.protocol.marshaller.Encoding;
import org.apache.activemq.amqp.protocol.marshaller.UnexpectedTypeException;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder.*;
import org.apache.activemq.amqp.protocol.types.AmqpBoolean;
import org.apache.activemq.util.buffer.Buffer;

public class AmqpBooleanMarshaller {

    private static final Encoder ENCODER = Encoder.SINGLETON;
    private static final Encoded<Boolean> NULL_ENCODED = new Encoder.NullEncoded<Boolean>();

    public static final byte TRUE_FORMAT_CODE = (byte) 0x41;
    public static final byte FALSE_FORMAT_CODE = (byte) 0x42;

    public static enum BOOLEAN_ENCODING implements Encoding{
        TRUE (TRUE_FORMAT_CODE), // the boolean value true
        FALSE (FALSE_FORMAT_CODE); // the boolean value false

        public final byte FORMAT_CODE;
        public final FormatSubCategory CATEGORY;

        BOOLEAN_ENCODING(byte formatCode) {
            this.FORMAT_CODE = formatCode;
            this.CATEGORY = FormatSubCategory.getCategory(formatCode);
        }

        public final byte getEncodingFormatCode() {
            return FORMAT_CODE;
        }

        public final AmqpVersion getEncodingVersion() {
            return AmqpMarshaller.VERSION;
        }

        public static BOOLEAN_ENCODING getEncoding(byte formatCode) throws UnexpectedTypeException {
            switch(formatCode) {
            case TRUE_FORMAT_CODE: {
                return TRUE;
            }
            case FALSE_FORMAT_CODE: {
                return FALSE;
            }
            default: {
                throw new UnexpectedTypeException("Unexpected format code for Boolean: " + formatCode);
            }
            }
        }

        static final AmqpBooleanEncoded createEncoded(EncodedBuffer buffer) throws AmqpEncodingError {
            switch(buffer.getEncodingFormatCode()) {
            case TRUE_FORMAT_CODE: {
                return new AmqpBooleanTrueEncoded(buffer);
            }
            case FALSE_FORMAT_CODE: {
                return new AmqpBooleanFalseEncoded(buffer);
            }
            default: {
                throw new UnexpectedTypeException("Unexpected format code for Boolean: " + buffer.getEncodingFormatCode());
            }
            }
        }
        static final AmqpBooleanEncoded createEncoded(byte formatCode, Boolean value) throws AmqpEncodingError {
            switch(formatCode) {
            case TRUE_FORMAT_CODE: {
                return new AmqpBooleanTrueEncoded(value);
            }
            case FALSE_FORMAT_CODE: {
                return new AmqpBooleanFalseEncoded(value);
            }
            default: {
                throw new UnexpectedTypeException("Unexpected format code for Boolean: " + formatCode);
            }
            }
        }
    }
    public static abstract class AmqpBooleanEncoded extends AbstractEncoded <Boolean> {
        public AmqpBooleanEncoded(EncodedBuffer encoded) {
            super(encoded);
        }

        public AmqpBooleanEncoded(byte formatCode, Boolean value) throws AmqpEncodingError {
            super(formatCode, value);
        }
    }

    /**
     * the boolean value true
     */
    private static class AmqpBooleanTrueEncoded extends AmqpBooleanEncoded {

        private final BOOLEAN_ENCODING encoding = BOOLEAN_ENCODING.TRUE;
        public AmqpBooleanTrueEncoded(EncodedBuffer encoded) {
            super(encoded);
        }

        public AmqpBooleanTrueEncoded(Boolean value) throws AmqpEncodingError {
            super(BOOLEAN_ENCODING.TRUE.FORMAT_CODE, value);
        }

        public final void encode(Boolean value, Buffer encoded, int offset) throws AmqpEncodingError {

        }

        public final void marshalData(DataOutput out) throws IOException {

        }

        public final Boolean decode(EncodedBuffer encoded) throws AmqpEncodingError {
            return ENCODER.valueOfBoolean(encoding);
        }

        public final Boolean unmarshalData(DataInput in) throws IOException {
            return ENCODER.valueOfBoolean(encoding);
        }
    }

    /**
     * the boolean value false
     */
    private static class AmqpBooleanFalseEncoded extends AmqpBooleanEncoded {

        private final BOOLEAN_ENCODING encoding = BOOLEAN_ENCODING.FALSE;
        public AmqpBooleanFalseEncoded(EncodedBuffer encoded) {
            super(encoded);
        }

        public AmqpBooleanFalseEncoded(Boolean value) throws AmqpEncodingError {
            super(BOOLEAN_ENCODING.FALSE.FORMAT_CODE, value);
        }

        public final void encode(Boolean value, Buffer encoded, int offset) throws AmqpEncodingError {

        }

        public final void marshalData(DataOutput out) throws IOException {

        }

        public final Boolean decode(EncodedBuffer encoded) throws AmqpEncodingError {
            return ENCODER.valueOfBoolean(encoding);
        }

        public final Boolean unmarshalData(DataInput in) throws IOException {
            return ENCODER.valueOfBoolean(encoding);
        }
    }


    private static final BOOLEAN_ENCODING chooseEncoding(AmqpBoolean val) throws AmqpEncodingError {
        return Encoder.chooseBooleanEncoding(val.getValue());
    }

    private static final BOOLEAN_ENCODING chooseEncoding(Boolean val) throws AmqpEncodingError {
        return Encoder.chooseBooleanEncoding(val);
    }

    static final Encoded<Boolean> encode(AmqpBoolean data) throws AmqpEncodingError {
        if(data == null) {
            return NULL_ENCODED;
        }
        return BOOLEAN_ENCODING.createEncoded(chooseEncoding(data).FORMAT_CODE, data.getValue());
    }

    static final Encoded<Boolean> createEncoded(Buffer source, int offset) throws AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(source, offset));
    }

    static final Encoded<Boolean> createEncoded(Boolean val) throws AmqpEncodingError {
        return BOOLEAN_ENCODING.createEncoded(chooseEncoding(val).FORMAT_CODE, val);
    }

    static final Encoded<Boolean> createEncoded(DataInput in) throws IOException, AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(in.readByte(), in));
    }

    static final Encoded<Boolean> createEncoded(EncodedBuffer buffer) throws AmqpEncodingError {
        if(buffer.getEncodingFormatCode() == AmqpNullMarshaller.FORMAT_CODE) {
            return NULL_ENCODED;
        }
        return BOOLEAN_ENCODING.createEncoded(buffer);
    }
}
