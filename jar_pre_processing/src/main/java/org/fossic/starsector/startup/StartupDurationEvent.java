package org.fossic.starsector.startup;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("org.fossic.starsector.StartupDuration")
@Label("Starsector Startup Duration")
@Category({"Starsector", "Startup"})
@StackTrace(false)
final class StartupDurationEvent extends Event {
    @Label("Phase")
    String phase;

    @Label("Phase Start Since CombatMain.main")
    @Timespan(Timespan.NANOSECONDS)
    long startElapsedNanos;

    @Label("Duration")
    @Timespan(Timespan.NANOSECONDS)
    long durationNanos;

    @Label("Thread")
    String thread;
}
