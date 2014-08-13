/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.rounding;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;

import java.io.IOException;

/**
 * A strategy for rounding long values.
 */
public abstract class Rounding implements Streamable {

    public abstract byte id();

    /**
     * Given a value, compute a key that uniquely identifies the rounded value although it is not necessarily equal to the rounding value itself.
     */
    public abstract long roundKey(long value);

    /**
     * Compute the rounded value given the key that identifies it.
     */
    public abstract long valueForKey(long key);

    /**
     * Rounds the given value, equivalent to calling <code>roundValue(roundKey(value))</code>.
     *
     * @param value The value to round.
     * @return      The rounded value.
     */
    public final long round(long value) {
        return valueForKey(roundKey(value));
    }

    /**
     * Given the rounded value (which was potentially generated by {@link #round(long)}, returns the next rounding value. For example, with
     * interval based rounding, if the interval is 3, {@code nextRoundValue(6) = 9 }.
     *
     * @param value The current rounding value
     * @return      The next rounding value;
     */
    public abstract long nextRoundingValue(long value);

    /**
     * Rounding strategy which is based on an interval
     *
     * {@code rounded = value - (value % interval) }
     */
    public static class Interval extends Rounding {

        final static byte ID = 0;

        private long interval;

        public Interval() { // for serialization
        }

        /**
         * Creates a new interval rounding.
         *
         * @param interval The interval
         */
        public Interval(long interval) {
            this.interval = interval;
        }

        @Override
        public byte id() {
            return ID;
        }

        public static long roundKey(long value, long interval) {
            if (value < 0) {
                return (value - interval + 1) / interval;
            } else {
                return value / interval;
            }
        }

        public static long roundValue(long key, long interval) {
            return key * interval;
        }

        @Override
        public long roundKey(long value) {
            return roundKey(value, interval);
        }

        @Override
        public long valueForKey(long key) {
            return key * interval;
        }

        @Override
        public long nextRoundingValue(long value) {
            assert value == round(value);
            return value + interval;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            interval = in.readVLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(interval);
        }
    }

    public static class FactorRounding extends Rounding {

        final static byte ID = 7;

        private Rounding rounding;

        private float factor;

        FactorRounding() { // for serialization
        }

        FactorRounding(Rounding rounding, float factor) {
            this.rounding = rounding;
            this.factor = factor;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long roundKey(long utcMillis) {
            return rounding.roundKey((long) (factor * utcMillis));
        }

        @Override
        public long valueForKey(long key) {
            return rounding.valueForKey(key);
        }

        @Override
        public long nextRoundingValue(long value) {
            return rounding.nextRoundingValue(value);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            rounding = (TimeZoneRounding) Rounding.Streams.read(in);
            factor = in.readFloat();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            Rounding.Streams.write(rounding, out);
            out.writeFloat(factor);
        }
    }
    
    public static class PrePostRounding extends Rounding {

        final static byte ID = 8;

        private Rounding rounding;

        private long preOffset;
        private long postOffset;

        PrePostRounding() { // for serialization
        }

        public PrePostRounding(Rounding intervalRounding, long preOffset, long postOffset) {
            this.rounding = intervalRounding;
            this.preOffset = preOffset;
            this.postOffset = postOffset;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long roundKey(long value) {
            return rounding.roundKey(value + preOffset);
        }

        @Override
        public long valueForKey(long key) {
            return postOffset + rounding.valueForKey(key);
        }

        @Override
        public long nextRoundingValue(long value) {
            return postOffset + rounding.nextRoundingValue(value - postOffset);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            rounding = Rounding.Streams.read(in);
            preOffset = in.readVLong();
            postOffset = in.readVLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            Rounding.Streams.write(rounding, out);
            out.writeVLong(preOffset);
            out.writeVLong(postOffset);
        }
    }

    public static class Streams {

        public static void write(Rounding rounding, StreamOutput out) throws IOException {
            out.writeByte(rounding.id());
            rounding.writeTo(out);
        }

        public static Rounding read(StreamInput in) throws IOException {
            Rounding rounding = null;
            byte id = in.readByte();
            switch (id) {
                case Interval.ID: rounding = new Interval(); break;
                case TimeZoneRounding.TimeTimeZoneRoundingFloor.ID: rounding = new TimeZoneRounding.TimeTimeZoneRoundingFloor(); break;
                case TimeZoneRounding.UTCTimeZoneRoundingFloor.ID: rounding = new TimeZoneRounding.UTCTimeZoneRoundingFloor(); break;
                case TimeZoneRounding.DayTimeZoneRoundingFloor.ID: rounding = new TimeZoneRounding.DayTimeZoneRoundingFloor(); break;
                case TimeZoneRounding.UTCIntervalTimeZoneRounding.ID: rounding = new TimeZoneRounding.UTCIntervalTimeZoneRounding(); break;
                case TimeZoneRounding.TimeIntervalTimeZoneRounding.ID: rounding = new TimeZoneRounding.TimeIntervalTimeZoneRounding(); break;
                case TimeZoneRounding.DayIntervalTimeZoneRounding.ID: rounding = new TimeZoneRounding.DayIntervalTimeZoneRounding(); break;
                case TimeZoneRounding.FactorRounding.ID: rounding = new FactorRounding(); break;
                case PrePostRounding.ID: rounding = new PrePostRounding(); break;
                default: throw new ElasticsearchException("unknown rounding id [" + id + "]");
            }
            rounding.readFrom(in);
            return rounding;
        }

    }

}
