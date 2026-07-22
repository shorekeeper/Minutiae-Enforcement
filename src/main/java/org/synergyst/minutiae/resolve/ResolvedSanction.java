package org.synergyst.minutiae.resolve;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.Map;

/**
 * A fully resolved, execution-ready sanction description.
 *
 * <p>When {@code amendId} is non-zero the description targets an existing
 * sanction for modification rather than the issuance of a new one; in that case
 * {@code measure} is null and only {@code rule}, {@code reason}, {@code duration}
 * and {@code counts} are meaningful. When {@code weaveParent} is non-zero the new
 * sanction is joined under the named parent case. When {@code probationFor} is
 * non-null the new sanction opens a probation window of that length.
 *
 * <p>Procedural windows: {@code suspendFor} records the sanction as a
 * suspended sentence, inactive until a further sanction under the same rule
 * within the window activates it; {@code reviewFor} schedules a mandatory
 * staff review; {@code expungeFor} schedules removal from docket visibility;
 * {@code note} attaches an internal case note never shown to the subject.
 *
 * @param target             target player name
 * @param layoutKey          originating layout key, or null
 * @param measure            resolved measure, or null for an amend
 * @param rule               primary rule identifier, or null
 * @param counts             additional cited rule identifiers, never null
 * @param reason             resolved reason, or null
 * @param duration           base duration prior to escalation
 * @param durationOverridden whether an explicit {@code duration=} override applied
 * @param escalation         escalation ladder from the originating layout
 * @param stayFor            stay window, or null
 * @param backdateFor        backdate offset, or null
 * @param attributedStaff    staff attribution override, or null
 * @param link               external reference, or null
 * @param mask               implication-expanded behavioural annotation mask
 * @param explicit           effective explicit behavioural annotations by name
 * @param dryRun             whether the dry-run directive was present
 * @param provisional        whether the sanction is a provisional holding action
 * @param appealable         whether the sanction may be appealed
 * @param decayFor           precedent-decay window, or null for never
 * @param warnFirst          warning quota before the measure applies, or zero
 * @param now                whether the warn-first gate is bypassed
 * @param escalate           whether precedent escalation is engaged
 * @param tariffFor          minimum-duration floor, or null
 * @param notifyChannels     notification channel names, or null when none
 * @param amendId            sanction to amend, or zero for issuance
 * @param weaveParent        joinder parent sanction identifier, or zero
 * @param probationFor       probation-window length, or null
 * @param suspendFor         suspended-sentence window, or null
 * @param reviewFor          mandatory-review offset, or null
 * @param expungeFor         docket-expungement offset, or null
 * @param note               internal case note, or null
 */
public record ResolvedSanction(String target,
                               String layoutKey,
                               Measure measure,
                               String rule,
                               String[] counts,
                               String reason,
                               DurationSpec duration,
                               boolean durationOverridden,
                               DurationSpec[] escalation,
                               DurationSpec stayFor,
                               DurationSpec backdateFor,
                               String attributedStaff,
                               String link,
                               long mask,
                               Map<String, RawAnnotation> explicit,
                               boolean dryRun,
                               boolean provisional,
                               boolean appealable,
                               DurationSpec decayFor,
                               int warnFirst,
                               boolean now,
                               boolean escalate,
                               DurationSpec tariffFor,
                               String[] notifyChannels,
                               long amendId,
                               long weaveParent,
                               DurationSpec probationFor,
                               DurationSpec suspendFor,
                               DurationSpec reviewFor,
                               DurationSpec expungeFor,
                               String note) {
}