/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.dtstack.flinkx.sqlservercdc;

import com.dtstack.flinkx.util.StringUtil;

import java.util.Arrays;

/**
 * Date: 2019/12/03
 * Company: www.dtstack.com
 * <p>
 * this class is copied from (https://github.com/debezium/debezium).
 * but there are some different from the origin.
 *
 * @author tudou
 */
public class Lsn implements Comparable<Lsn> {

    private static final String NULL_STRING = "NULL";

    public static final Lsn NULL = new Lsn(null);

    private final byte[] binary;
    private int[] unsignedBinary;

    private String string;

    private Lsn(byte[] binary) {
        this.binary = binary;
    }

    public byte[] getBinary() {
        return binary;
    }

    public boolean isAvailable() {
        return binary != null;
    }

    private int[] getUnsignedBinary() {
        if (unsignedBinary != null || binary == null) {
            return unsignedBinary;
        }

        unsignedBinary = new int[binary.length];
        for (int i = 0; i < binary.length; i++) {
            unsignedBinary[i] = Byte.toUnsignedInt(binary[i]);
        }
        return unsignedBinary;
    }

    @Override
    public String toString() {
        if (string != null) {
            return string;
        }
        final StringBuilder sb = new StringBuilder();
        if (binary == null) {
            return NULL_STRING;
        }

        final int[] unsigned = getUnsignedBinary();
        if (null == unsigned) {
            return NULL_STRING;
        }

        for (int i = 0; i < unsigned.length; i++) {
            final String byteStr = Integer.toHexString(unsigned[i]);
            if (byteStr.length() == 1) {
                sb.append('0');
            }
            sb.append(byteStr);
            if (i == 3 || i == 7) {
                sb.append(':');
            }
        }
        string = sb.toString();
        return string;
    }

    public static Lsn valueOf(String lsnString) {
        return (lsnString == null || NULL_STRING.equals(lsnString)) ? NULL : new Lsn(StringUtil.hexStringToByteArray(lsnString.replace(":", "")));
    }

    public static Lsn valueOf(byte[] lsnBinary) {
        return (lsnBinary == null) ? NULL : new Lsn(lsnBinary);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(binary);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Lsn other = (Lsn) obj;
        if (!Arrays.equals(binary, other.binary)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(Lsn o) {
        if (this == o) {
            return 0;
        }

        if (!this.isAvailable()) {
            if (!o.isAvailable()) {
                return 0;
            }
            return -1;
        }

        if (!o.isAvailable()) {
            return 1;
        }

        final int[] thisU = getUnsignedBinary();
        final int[] thatU = o.getUnsignedBinary();
        if(null != thisU && null != thatU){
            for (int i = 0; i < thisU.length; i++) {
                final int diff = thisU[i] - thatU[i];
                if (diff != 0) {
                    return diff;
                }
            }
        }

        return 0;
    }

    public boolean isBetween(Lsn from, Lsn to) {
        return this.compareTo(from) >= 0 && this.compareTo(to) < 0;
    }
}
