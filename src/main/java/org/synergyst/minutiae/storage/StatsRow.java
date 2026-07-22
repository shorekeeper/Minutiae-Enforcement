package org.synergyst.minutiae.storage;

/**
 * Aggregate system counters for the panel system view.
 *
 * @param totalSanctions  total recorded sanctions
 * @param activeSanctions currently active sanctions
 * @param pendingAppeals  appeals awaiting review
 * @param totalRules      cached rule count
 */
public record StatsRow(int totalSanctions, int activeSanctions, int pendingAppeals, int totalRules) {
}