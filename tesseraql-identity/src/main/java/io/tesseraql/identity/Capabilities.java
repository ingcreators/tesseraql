package io.tesseraql.identity;

/**
 * Realm management capabilities (design ch. 10.3.1, 10.7.3). Each area is {@code readWrite},
 * {@code readOnly}, or {@code none}; write contracts are rejected unless the area is readWrite, and
 * the admin UI shows or hides actions accordingly.
 */
public record Capabilities(String userManagement, String groupManagement, String roleManagement) {

    public static final String READ_WRITE = "readWrite";
    public static final String READ_ONLY = "readOnly";
    public static final String NONE = "none";

    public boolean userWriteAllowed() {
        return READ_WRITE.equals(userManagement);
    }

    public static Capabilities readWrite() {
        return new Capabilities(READ_WRITE, READ_WRITE, READ_WRITE);
    }

    public static Capabilities readOnly() {
        return new Capabilities(READ_ONLY, READ_ONLY, READ_ONLY);
    }
}
