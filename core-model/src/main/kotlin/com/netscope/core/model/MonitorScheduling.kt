package com.netscope.core.model

/**
 * How a host-monitoring run should be scheduled.
 *
 * Android does not offer one mechanism that covers every interval, so the choice is
 * made explicitly rather than by passing a user-chosen interval straight to
 * WorkManager and hoping.
 */
sealed interface MonitorSchedule {

    /**
     * Deferred periodic work.
     *
     * WorkManager's minimum period is 15 minutes and execution is **inexact**: the
     * system batches work and may delay it under Doze or battery restrictions. The UI
     * must present the interval as "about every N minutes", never as a guarantee.
     */
    data class Periodic(
        val intervalMinutes: Long,
        val flexMinutes: Long,
    ) : MonitorSchedule {
        val caveat: String
            get() = "WorkManager runs deferred work no more often than every " +
                "${MonitorSchedulingPolicy.WORK_MANAGER_MIN_INTERVAL_MINUTES} minutes and does " +
                "not run it at an exact time. Doze and battery restrictions can delay it further."
    }

    /**
     * A continuous run the user starts while the app is open, kept alive by a
     * foreground service with a persistent notification.
     *
     * This is the only way to poll faster than WorkManager's floor, and it is honest
     * about the cost: the notification is visible for as long as monitoring runs.
     */
    data class ForegroundSession(
        val intervalSeconds: Long,
    ) : MonitorSchedule {
        val caveat: String
            get() = "Intervals under ${MonitorSchedulingPolicy.WORK_MANAGER_MIN_INTERVAL_MINUTES} " +
                "minutes need a foreground service with a persistent notification, which you " +
                "start from inside the app and can stop at any time."
    }

    /** The request cannot be honoured, with the reason to show the user. */
    data class Rejected(val reason: String) : MonitorSchedule
}

/**
 * Chooses the scheduling mechanism for a monitoring interval.
 *
 * The rules encode platform behaviour rather than preference:
 *
 *  - 15 minutes or longer is deferred periodic work.
 *  - Below that, only a user-started foreground session can poll reliably.
 *  - A foreground service may not be started from the background on Android 12 and
 *    newer, so a monitoring session must begin while the app is visible.
 */
object MonitorSchedulingPolicy {

    /** WorkManager refuses a shorter period; this is a platform floor, not a choice. */
    const val WORK_MANAGER_MIN_INTERVAL_MINUTES = 15L

    /** Flex window given to periodic work so the system can batch it efficiently. */
    const val WORK_MANAGER_FLEX_MINUTES = 5L

    /** Polling faster than this would be wasteful and thermally significant. */
    const val MIN_FOREGROUND_INTERVAL_SECONDS = 5L

    /** Android 12 and newer block starting a foreground service from the background. */
    const val FOREGROUND_SERVICE_BACKGROUND_START_BLOCKED_FROM = 31

    /**
     * Picks a schedule.
     *
     * [appIsVisible] matters because a sub-15-minute interval needs a foreground
     * service, and on Android 12+ that can only be started while the app is in the
     * foreground. Returning [MonitorSchedule.Rejected] here is correct behaviour: the
     * alternative is a start that throws at runtime.
     */
    fun scheduleFor(
        requestedIntervalSeconds: Long,
        sdkInt: Int,
        appIsVisible: Boolean,
    ): MonitorSchedule {
        if (requestedIntervalSeconds < MIN_FOREGROUND_INTERVAL_SECONDS) {
            return MonitorSchedule.Rejected(
                "The shortest supported interval is $MIN_FOREGROUND_INTERVAL_SECONDS seconds. " +
                    "Probing faster than that produces no extra information and drains the battery.",
            )
        }

        val workManagerFloorSeconds = WORK_MANAGER_MIN_INTERVAL_MINUTES * 60
        if (requestedIntervalSeconds >= workManagerFloorSeconds) {
            return MonitorSchedule.Periodic(
                intervalMinutes = requestedIntervalSeconds / 60,
                flexMinutes = WORK_MANAGER_FLEX_MINUTES,
            )
        }

        if (!appIsVisible && sdkInt >= FOREGROUND_SERVICE_BACKGROUND_START_BLOCKED_FROM) {
            return MonitorSchedule.Rejected(
                "An interval under $WORK_MANAGER_MIN_INTERVAL_MINUTES minutes needs a foreground " +
                    "service, and Android 12 and newer do not allow one to be started from the " +
                    "background. Open NetScope and start monitoring from the host monitor screen, " +
                    "or choose an interval of $WORK_MANAGER_MIN_INTERVAL_MINUTES minutes or more.",
            )
        }

        return MonitorSchedule.ForegroundSession(intervalSeconds = requestedIntervalSeconds)
    }

    /** Wording for the interval picker, so the UI never implies precision it lacks. */
    fun describeInterval(requestedIntervalSeconds: Long): String {
        val workManagerFloorSeconds = WORK_MANAGER_MIN_INTERVAL_MINUTES * 60
        return if (requestedIntervalSeconds >= workManagerFloorSeconds) {
            "About every ${requestedIntervalSeconds / 60} minutes, in the background. Android " +
                "decides the exact moment."
        } else {
            "Every ${requestedIntervalSeconds} seconds while a monitoring session is running, " +
                "with a persistent notification."
        }
    }
}
