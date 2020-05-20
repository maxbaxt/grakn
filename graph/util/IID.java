/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package hypergraph.graph.util;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;

import static hypergraph.common.collection.ByteArrays.doubleToBytes;
import static hypergraph.common.collection.ByteArrays.join;
import static hypergraph.common.collection.ByteArrays.longToBytes;
import static hypergraph.graph.util.Schema.STRING_ENCODING;
import static hypergraph.graph.util.Schema.STRING_MAX_LENGTH;
import static hypergraph.graph.util.Schema.TIME_ZONE_ID;
import static java.util.Arrays.copyOfRange;

public class IID {

    protected final byte[] bytes;

    IID(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] bytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return Arrays.toString(bytes);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        IID that = (IID) object;
        return Arrays.equals(this.bytes, that.bytes);
    }

    @Override
    public final int hashCode() {
        return Arrays.hashCode(bytes);
    }

    public static class Prefix extends IID {

        public static final int LENGTH = 1;

        Prefix(byte[] bytes) {
            super(bytes);
            assert bytes.length == LENGTH;
        }

        public static Prefix of(byte[] bytes) {
            return new Prefix(bytes);
        }
    }

    public static class Infix extends IID {

        public static final int LENGTH = 1;

        Infix(byte[] bytes) {
            super(bytes);
            assert bytes.length == LENGTH;
        }

        public static Infix of(byte[] bytes) {
            return new Infix(bytes);
        }
    }

    public static abstract class Index extends IID {

        Index(byte[] bytes) {
            super(bytes);
        }

        public static class Type extends Index {

            Type(byte[] bytes) {
                super(bytes);
            }

            /**
             * Returns the index address of given {@code TypeVertex}
             *
             * @param label of the {@code TypeVertex}
             * @param scope of the {@code TypeVertex}, which could be null
             * @return a byte array representing the index address of a {@code TypeVertex}
             */
            public static Index of(String label, @Nullable String scope) {
                return new Index.Type(join(Schema.Index.TYPE.prefix().bytes(), Schema.Vertex.Type.scopedLabel(label, scope).getBytes()));
            }
        }

    }

    public static abstract class Vertex extends IID {

        Vertex(byte[] bytes) {
            super(bytes);
        }

        public static class Type extends IID.Vertex {

            public static final int LENGTH = Prefix.LENGTH + 2;

            Type(byte[] bytes) {
                super(bytes);
                assert bytes.length == LENGTH;
            }

            public static IID.Vertex.Type of(byte[] bytes) {
                return new IID.Vertex.Type(bytes);
            }

            /**
             * Generate an IID for a {@code TypeVertex} for a given {@code Schema}
             *
             * @param keyGenerator to generate the IID for a {@code TypeVertex}
             * @param schema       of the {@code TypeVertex} in which the IID will be used for
             * @return a byte array representing a new IID for a {@code TypeVertex}
             */
            public static Type generate(KeyGenerator keyGenerator, Schema.Vertex.Type schema) {
                return of(join(schema.prefix().bytes(), keyGenerator.forType(Prefix.of(schema.prefix().bytes()))));
            }

            public Schema.Vertex.Type schema() {
                return Schema.Vertex.Type.of(bytes[0]);
            }
        }

        public static class Thing extends IID.Vertex {

            public static final int PREFIX_TYPE_LENGTH = Prefix.LENGTH + Type.LENGTH;
            public static final int LENGTH = PREFIX_TYPE_LENGTH + 8;

            public Thing(byte[] bytes) {
                super(bytes);
            }

            public IID.Vertex.Type type() {
                return IID.Vertex.Type.of(copyOfRange(bytes, Prefix.LENGTH, Type.LENGTH));
            }

            public Schema.Vertex.Thing schema() {
                return Schema.Vertex.Thing.of(bytes[0]);
            }
        }

        public static abstract class Attribute extends IID.Vertex.Thing {

            Attribute(Schema.ValueType valueType, Type typeIID, byte[] valueBytes) {
                super(join(
                        Schema.Vertex.Thing.ATTRIBUTE.prefix().bytes(),
                        typeIID.bytes(),
                        valueType.bytes(),
                        valueBytes
                ));
            }

            public static class Boolean extends Attribute {

                public Boolean(IID.Vertex.Type typeIID, boolean value) {
                    super(Schema.ValueType.BOOLEAN, typeIID, new byte[]{(byte) (value ? 1 : 0)});
                }
            }

            public static class Long extends Attribute {

                public Long(IID.Vertex.Type typeIID, long value) {
                    super(Schema.ValueType.LONG, typeIID, longToBytes(value));
                }
            }

            public static class Double extends Attribute {

                public Double(IID.Vertex.Type typeIID, double value) {
                    super(Schema.ValueType.DOUBLE, typeIID, doubleToBytes(value));
                }
            }

            public static class String extends Attribute {

                public String(IID.Vertex.Type typeIID, java.lang.String value) {
                    super(Schema.ValueType.STRING, typeIID, serialise(value));
                }

                private static byte[] serialise(java.lang.String value) {
                    byte[] bytes = value.getBytes(STRING_ENCODING);
                    assert bytes.length <= STRING_MAX_LENGTH;

                    return join(new byte[]{(byte) bytes.length}, bytes);
                }

                private static java.lang.String deserialise(byte[] bytes) {
                    byte[] x = Arrays.copyOfRange(bytes, 1, 1 + bytes[0]);
                    return new java.lang.String(x, STRING_ENCODING);
                }
            }

            public static class DateTime extends Attribute {

                public DateTime(IID.Vertex.Type typeIID, java.time.LocalDateTime value) {
                    super(Schema.ValueType.DATETIME, typeIID, serialise(value));
                }

                private static byte[] serialise(java.time.LocalDateTime value) {
                    return longToBytes(value.atZone(TIME_ZONE_ID).toInstant().toEpochMilli());
                }

                private static java.time.LocalDateTime deserialise(byte[] bytes) {
                    return LocalDateTime.ofInstant(Instant.ofEpochMilli(ByteBuffer.wrap(bytes).getLong()), TIME_ZONE_ID);
                }
            }
        }
    }

    public static abstract class Edge<EDGE_SCHEMA extends Schema.Edge, VERTEX_IID extends IID.Vertex> extends IID {

        Edge(byte[] bytes) {
            super(bytes);
        }

        public abstract boolean isOutwards();

        public abstract EDGE_SCHEMA schema();

        public abstract VERTEX_IID start();

        public abstract VERTEX_IID end();

        public static class Type extends IID.Edge<Schema.Edge.Type, IID.Vertex.Type> {

            Type(byte[] bytes) {
                super(bytes);
            }

            public static IID.Edge.Type of(byte[] bytes) {
                return new IID.Edge.Type(bytes);
            }

            public static IID.Edge.Type of(IID.Vertex.Type start, Schema.Infix infix, IID.Vertex.Type end) {
                return new IID.Edge.Type(join(start.bytes, infix.bytes(), end.bytes));
            }

            @Override
            public boolean isOutwards() {
                return Schema.Edge.isOut(bytes[Vertex.Type.LENGTH]);
            }

            @Override

            public Schema.Edge.Type schema() {
                return Schema.Edge.Type.of(bytes[Vertex.Type.LENGTH]);
            }

            @Override
            public Vertex.Type start() {
                return Vertex.Type.of(copyOfRange(bytes, 0, Vertex.Type.LENGTH));
            }

            @Override
            public Vertex.Type end() {
                return Vertex.Type.of(copyOfRange(bytes, Vertex.Type.LENGTH + Infix.LENGTH, bytes.length));
            }

        }

        public static class Thing extends IID.Edge<Schema.Edge.Thing, IID.Vertex.Thing> {

            Thing(byte[] bytes) {
                super(bytes);
            }

            public static IID.Edge.Thing of(byte[] bytes) {
                return new IID.Edge.Thing(bytes);
            }

            public static Thing of(Vertex.Thing start, Schema.Infix infix, Vertex.Thing end) {
                return new IID.Edge.Thing(join(start.bytes(), infix.bytes(), end.bytes()));
            }

            @Override
            public boolean isOutwards() {
                return false; // TODO
            }

            @Override
            public Schema.Edge.Thing schema() {
                return null; // TODO
            }

            @Override
            public IID.Vertex.Thing start() {
                return null; // TODO
            }

            @Override
            public IID.Vertex.Thing end() {
                return null; // TODO
            }
        }
    }
}
