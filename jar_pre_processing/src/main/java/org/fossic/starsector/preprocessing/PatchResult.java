package org.fossic.starsector.preprocessing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PatchResult {
    private final String patchId;
    private final String classPath;
    private final int expected;
    private final int applied;
    private final int verified;
    private final List<String> details;

    public PatchResult(String patchId, String classPath, int expected, int applied, int verified, List<String> details) {
        this.patchId = patchId;
        this.classPath = classPath;
        this.expected = expected;
        this.applied = applied;
        this.verified = verified;
        this.details = Collections.unmodifiableList(new ArrayList<>(details));
    }

    public static PatchResult of(String patchId, String classPath, int expected, int applied, int verified, String detail) {
        return new PatchResult(patchId, classPath, expected, applied, verified, List.of(detail));
    }

    public String patchId() {
        return patchId;
    }

    public String classPath() {
        return classPath;
    }

    public int expected() {
        return expected;
    }

    public int applied() {
        return applied;
    }

    public int verified() {
        return verified;
    }

    public List<String> details() {
        return details;
    }

    public void requireSuccess() {
        if (applied != expected || verified != expected) {
            throw new PatchException(
                    patchId + " failed for " + classPath + ": expected=" + expected
                            + ", applied=" + applied + ", verified=" + verified + ", details=" + details);
        }
    }

    public String toJson(int indent) {
        String pad = " ".repeat(indent);
        String child = " ".repeat(indent + 2);
        return pad + "{\n"
                + child + "\"patchId\": " + JsonUtil.quote(patchId) + ",\n"
                + child + "\"classPath\": " + JsonUtil.quote(classPath) + ",\n"
                + child + "\"status\": " + JsonUtil.quote("ok") + ",\n"
                + child + "\"expected\": " + expected + ",\n"
                + child + "\"applied\": " + applied + ",\n"
                + child + "\"verified\": " + verified + ",\n"
                + child + "\"details\": " + JsonUtil.stringArray(details) + "\n"
                + pad + "}";
    }
}
