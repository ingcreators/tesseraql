package io.tesseraql.scim;

/**
 * A SCIM provisioning failure carrying the HTTP status and SCIM detail type (design ch. 10.15,
 * RFC 7644 §3.12), rendered into a {@link ScimError} response.
 */
public final class ScimException extends RuntimeException {

    private final int status;
    private final String scimType;

    public ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public int status() {
        return status;
    }

    public String scimType() {
        return scimType;
    }

    public ScimError toError() {
        return new ScimError(java.util.List.of(ScimError.SCHEMA), getMessage(),
                String.valueOf(status), scimType);
    }
}
