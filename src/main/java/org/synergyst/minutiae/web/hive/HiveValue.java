package org.synergyst.minutiae.web.hive;

/**
 * A single named value within a Hive key, analogous to a registry value.
 *
 * <p>{@code display} is the human-rendered form shown in the panel;
 * {@code raw} is the underlying machine value; {@code link} is the target Hive
 * path when {@link #type()} is {@link ValueType#LINK}, and null otherwise.
 *
 * @param name    value name
 * @param type    value type
 * @param display human-rendered form
 * @param raw     underlying machine value
 * @param link    target path for a link value, or null
 */
public record HiveValue(String name, ValueType type, String display, String raw, String link) {
}