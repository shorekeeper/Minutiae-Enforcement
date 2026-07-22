package org.synergyst.minutiae.web.hive;

/**
 * A navigable child reference of a Hive key, analogous to a registry subkey.
 *
 * <p>{@code name} is the path segment appended to the parent path and is stable
 * and machine-oriented (a UUID, an identifier, an ordinal). {@code label} is the
 * text shown in the tree; when null, the name is shown. This separation lets a
 * branch address a subject by UUID while displaying the player's name.
 * {@code hasChildren} advises the tree whether an expansion affordance should be
 * drawn without eagerly resolving the child.
 *
 * @param name        path segment
 * @param label       display label, or null to display the name
 * @param hasChildren whether the child has its own children
 */
public record HiveChild(String name, String label, boolean hasChildren) {

    /** Returns the display text: the label when present, otherwise the name. */
    public String display() {
        return label != null ? label : name;
    }
}