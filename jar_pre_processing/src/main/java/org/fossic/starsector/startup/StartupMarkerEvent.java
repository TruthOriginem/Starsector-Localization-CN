package org.fossic.starsector.startup;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("org.fossic.starsector.StartupMarker")
@Label("Starsector Startup Marker")
@Category({"Starsector", "Startup"})
@StackTrace(false)
final class StartupMarkerEvent extends Event {
    @Label("Marker")
    String marker;

    @Label("Elapsed Since CombatMain.main")
    @Timespan(Timespan.NANOSECONDS)
    long elapsedNanos;

    @Label("Thread")
    String thread;
}
