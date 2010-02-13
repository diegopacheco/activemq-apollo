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
import java.io.IOException;
import org.apache.activemq.amqp.protocol.marshaller.AmqpEncodingError;
import org.apache.activemq.amqp.protocol.marshaller.Encoded;
import org.apache.activemq.amqp.protocol.marshaller.UnexpectedTypeException;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder.*;
import org.apache.activemq.amqp.protocol.types.AmqpAttach;
import org.apache.activemq.amqp.protocol.types.AmqpBoolean;
import org.apache.activemq.amqp.protocol.types.AmqpOptions;
import org.apache.activemq.amqp.protocol.types.AmqpSequenceNo;
import org.apache.activemq.amqp.protocol.types.AmqpSessionName;
import org.apache.activemq.amqp.protocol.types.AmqpSymbol;
import org.apache.activemq.amqp.protocol.types.AmqpType;
import org.apache.activemq.amqp.protocol.types.AmqpUbyte;
import org.apache.activemq.amqp.protocol.types.AmqpUint;
import org.apache.activemq.amqp.protocol.types.AmqpUlong;
import org.apache.activemq.amqp.protocol.types.IAmqpList;
import org.apache.activemq.util.buffer.Buffer;

public class AmqpAttachMarshaller implements DescribedTypeMarshaller<AmqpAttach>{

    static final AmqpAttachMarshaller SINGLETON = new AmqpAttachMarshaller();
    private static final Encoded<IAmqpList<AmqpType<?, ?>>> NULL_ENCODED = new Encoder.NullEncoded<IAmqpList<AmqpType<?, ?>>>();

    public static final String SYMBOLIC_ID = "amqp:attach:list";
    //Format code: 0x00000001:0x00000202:
    public static final long CATEGORY = 1;
    public static final long DESCRIPTOR_ID = 514;
    public static final long NUMERIC_ID = CATEGORY << 32 | DESCRIPTOR_ID; //(4294967810L)
    //Hard coded descriptor:
    public static final EncodedBuffer DESCRIPTOR = FormatCategory.createBuffer(new Buffer(new byte [] {
        (byte) 0x80,                                         // ulong descriptor encoding)
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,  // CATEGORY CODE
        (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x02   // DESCRIPTOR ID CODE
    }), 0);

    private static final ListDecoder<AmqpType<?, ?>> DECODER = new ListDecoder<AmqpType<?, ?>>() {
        public final IAmqpList<AmqpType<?, ?>> unmarshalType(int dataCount, int dataSize, DataInput in) throws AmqpEncodingError, IOException {
            if (dataCount > 7) {
                throw new AmqpEncodingError("Too many fields for " + SYMBOLIC_ID + ": " + dataCount);
            }
            IAmqpList<AmqpType<?, ?>> rc = new IAmqpList.ArrayBackedList<AmqpType<?, ?>>(new AmqpType<?, ?>[7]);
            //options:
            if(dataCount > 0) {
                rc.set(0, AmqpOptions.AmqpOptionsBuffer.create(AmqpMapMarshaller.createEncoded(in)));
                dataCount--;
            }

            //name:
            if(dataCount > 0) {
                rc.set(1, AmqpSessionName.AmqpSessionNameBuffer.create(AmqpBinaryMarshaller.createEncoded(in)));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": name");
            }

            //opening:
            if(dataCount > 0) {
                rc.set(2, AmqpBoolean.AmqpBooleanBuffer.create(AmqpBooleanMarshaller.createEncoded(in)));
                dataCount--;
            }

            //received:
            if(dataCount > 0) {
                rc.set(3, AmqpSequenceNo.AmqpSequenceNoBuffer.create(AmqpUintMarshaller.createEncoded(in)));
                dataCount--;
            }

            //timeout:
            if(dataCount > 0) {
                rc.set(4, AmqpUint.AmqpUintBuffer.create(AmqpUintMarshaller.createEncoded(in)));
                dataCount--;
            }

            //txn-mode:
            if(dataCount > 0) {
                rc.set(5, AmqpUbyte.AmqpUbyteBuffer.create(AmqpUbyteMarshaller.createEncoded(in)));
                dataCount--;
            }

            //txn-support:
            if(dataCount > 0) {
                rc.set(6, AmqpUbyte.AmqpUbyteBuffer.create(AmqpUbyteMarshaller.createEncoded(in)));
                dataCount--;
            }
            return rc;
        }

        public IAmqpList<AmqpType<?, ?>> decode(EncodedBuffer[] constituents) {
            if (constituents.length > 7) {
                throw new AmqpEncodingError("Too many fields for " + SYMBOLIC_ID + ":" + constituents.length);
            }
            int dataCount = constituents.length;
            IAmqpList<AmqpType<?, ?>> rc = new IAmqpList.ArrayBackedList<AmqpType<?, ?>>(new AmqpType<?, ?>[7]);
            //options:
            if(dataCount > 0) {
                rc.set(0, AmqpOptions.AmqpOptionsBuffer.create(AmqpMapMarshaller.createEncoded(constituents[0])));
                dataCount--;
            }

            //name:
            if(dataCount > 0) {
                rc.set(1, AmqpSessionName.AmqpSessionNameBuffer.create(AmqpBinaryMarshaller.createEncoded(constituents[1])));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": name");
            }

            //opening:
            if(dataCount > 0) {
                rc.set(2, AmqpBoolean.AmqpBooleanBuffer.create(AmqpBooleanMarshaller.createEncoded(constituents[2])));
                dataCount--;
            }

            //received:
            if(dataCount > 0) {
                rc.set(3, AmqpSequenceNo.AmqpSequenceNoBuffer.create(AmqpUintMarshaller.createEncoded(constituents[3])));
                dataCount--;
            }

            //timeout:
            if(dataCount > 0) {
                rc.set(4, AmqpUint.AmqpUintBuffer.create(AmqpUintMarshaller.createEncoded(constituents[4])));
                dataCount--;
            }

            //txn-mode:
            if(dataCount > 0) {
                rc.set(5, AmqpUbyte.AmqpUbyteBuffer.create(AmqpUbyteMarshaller.createEncoded(constituents[5])));
                dataCount--;
            }

            //txn-support:
            if(dataCount > 0) {
                rc.set(6, AmqpUbyte.AmqpUbyteBuffer.create(AmqpUbyteMarshaller.createEncoded(constituents[6])));
                dataCount--;
            }
            return rc;
        }
    };

    public static class AmqpAttachEncoded extends DescribedEncoded<IAmqpList<AmqpType<?, ?>>> {

        public AmqpAttachEncoded(DescribedBuffer buffer) {
            super(buffer);
        }

        public AmqpAttachEncoded(AmqpAttach value) {
            super(AmqpListMarshaller.encode(value));
        }

        protected final String getSymbolicId() {
            return SYMBOLIC_ID;
        }

        protected final long getNumericId() {
            return NUMERIC_ID;
        }

        protected final Encoded<IAmqpList<AmqpType<?, ?>>> decodeDescribed(EncodedBuffer encoded) throws AmqpEncodingError {
            return AmqpListMarshaller.createEncoded(encoded, DECODER);
        }

        protected final Encoded<IAmqpList<AmqpType<?, ?>>> unmarshalDescribed(DataInput in) throws IOException {
            return AmqpListMarshaller.createEncoded(in, DECODER);
        }

        protected final EncodedBuffer getDescriptor() {
            return DESCRIPTOR;
        }
    }

    public static final Encoded<IAmqpList<AmqpType<?, ?>>> encode(AmqpAttach value) throws AmqpEncodingError {
        return new AmqpAttachEncoded(value);
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(Buffer source, int offset) throws AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(source, offset));
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(DataInput in) throws IOException, AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(in.readByte(), in));
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(EncodedBuffer buffer) throws AmqpEncodingError {
        byte fc = buffer.getEncodingFormatCode();
        if (fc == Encoder.NULL_FORMAT_CODE) {
            return NULL_ENCODED;
        }

        DescribedBuffer db = buffer.asDescribed();
        AmqpType<?, ?> descriptor = AmqpMarshaller.SINGLETON.decodeType(db.getDescriptorBuffer());
        if(!(descriptor instanceof AmqpUlong && ((AmqpUlong)descriptor).getValue().longValue() == NUMERIC_ID ||
               descriptor instanceof AmqpSymbol && ((AmqpSymbol)descriptor).getValue().equals(SYMBOLIC_ID))) {
            throw new UnexpectedTypeException("descriptor mismatch: " + descriptor);
        }
        return new AmqpAttachEncoded(db);
    }

    public final AmqpAttach.AmqpAttachBuffer decodeDescribedType(AmqpType<?, ?> descriptor, DescribedBuffer encoded) throws AmqpEncodingError {
        return AmqpAttach.AmqpAttachBuffer.create(new AmqpAttachEncoded(encoded));
    }
}
