package org.speckitty.missionstatus.domain.readmodel.reducer;

import java.util.Comparator;
import org.speckitty.missionstatus.domain.model.StatusEvent;

/**
 * The order in which the fold applies events, unless the approved-departure lift moves a row (then the reducer uses the oracle's lifted key).
 * It is a constructor argument of the reducer so a test can inject a wrong one.
 */
public interface EventOrdering extends Comparator<StatusEvent> {}
