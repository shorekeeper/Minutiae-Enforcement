package org.synergyst.minutiae.boot;

/**
 * Enumeration of ordered boot stages.
 *
 * <p>Each constant carries a stable ordinal-derived index and a short label
 * used in diagnostic output. The sequence defines the canonical order in which
 * subsystems are brought online.
 */
public enum BootStage {

    CONFIG("config"),
    ASYNC("async"),
    MESSAGES("messages"),
    STORAGE("storage"),
    RULES("rules"),
    ANNOTATIONS("annotations"),
    LANG("lang"),
    LAYOUTS("layouts"),
    FINGERPRINT("fingerprint"),
    BEHAVIOUR("behaviour"),
    MAINTENANCE("maintenance"),
    NOTIFY("notify"),
    NETWORK("network"),
    ENFORCE("enforce"),
    DISPATCH("dispatch"),
    WEB("web"),
    COMMANDS("commands"),
    READY("ready");

    private final String label;

    BootStage(final String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}