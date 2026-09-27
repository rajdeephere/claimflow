package com.claimflow.claim.claim;

import com.claimflow.common.error.PreconditionFailedException;

/**
 * The claim's JPA @Version doubles as its HTTP entity tag: GET returns {@code ETag: "3"}, and a client
 * that sends {@code If-Match: "3"} on a change is saying "only if nobody changed it since I looked".
 */
final class ETags {

    private ETags() {
    }

    static String of(Claim claim) {
        return "\"" + claim.getVersion() + "\"";
    }

    /** @return the expected version, or null when the client sent no If-Match (or "*": any version) */
    static Long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String tag = ifMatch.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        tag = tag.replace("\"", "");
        try {
            return Long.parseLong(tag);
        } catch (NumberFormatException e) {
            // A tag we never issued can't match the current version.
            throw new PreconditionFailedException("If-Match " + ifMatch + " does not match any version of this claim");
        }
    }
}
