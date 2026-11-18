package com.likwiid.ragregulation.ingest;

public final class Vectors {

    private Vectors() {
    }

    /** pgvector text form, for example "[0.1,0.2]". */
    public static String literal(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
