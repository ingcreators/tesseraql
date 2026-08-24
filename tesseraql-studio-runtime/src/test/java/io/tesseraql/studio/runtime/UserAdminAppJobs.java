package io.tesseraql.studio.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A copied example app's daily job must not keep its real schedule under a test runtime: a run
 * crossing 02:00:00 UTC otherwise really fires {@code user.dailyMaintenance} — deactivating the
 * seeded PENDING users, enqueuing a NOTIFICATION outbox event, and recording an execution
 * mid-test (observed 2026-08-24). Tests that need the job run it explicitly; what the live
 * schedule would prove, it proves nowhere. The cron is parked on a far-future year (Quartz's
 * optional seventh field) rather than stripped so the job still reads as scheduled — the ops
 * console renders its cron and the jobs catalog keeps its trigger. Loud on drift, because a
 * replace that silently stopped matching would re-arm the flake.
 */
final class UserAdminAppJobs {

    /** The example's real schedule, and the never-firing one every copied app runs under. */
    static final String REAL_CRON = "0 0 2 * * ?";
    static final String PARKED_CRON = "0 0 2 1 1 ? 2099";

    private UserAdminAppJobs() {
    }

    /** Call on every copy of {@code examples/user-admin-app} before a runtime boots from it. */
    static void parkDailyMaintenanceSchedule(Path appHome) throws IOException {
        Path job = appHome.resolve("batch/user/daily-maintenance/job.yml");
        String yaml = Files.readString(job);
        String schedule = "cron: \"" + REAL_CRON + "\"";
        if (!yaml.contains(schedule)) {
            throw new IllegalStateException("The example's daily-maintenance schedule moved;"
                    + " update UserAdminAppJobs so the job cannot fire mid-test");
        }
        Files.writeString(job, yaml.replace(schedule, "cron: \"" + PARKED_CRON + "\""));
    }
}
