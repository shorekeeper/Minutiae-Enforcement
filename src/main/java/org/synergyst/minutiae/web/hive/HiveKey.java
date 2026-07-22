package org.synergyst.minutiae.web.hive;

import java.util.List;

/**
 * A resolved Hive key: a node in the navigable tree carrying its child
 * references and its values, analogous to a registry key.
 *
 * @param path     the key's canonical path
 * @param name     the key's display name
 * @param children navigable child references, in display order
 * @param values   the key's values, in display order
 */
public record HiveKey(HivePath path, String name, List<HiveChild> children, List<HiveValue> values) {
}