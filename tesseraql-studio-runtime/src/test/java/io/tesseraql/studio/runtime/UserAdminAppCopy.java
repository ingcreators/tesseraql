package io.tesseraql.studio.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What every copy of {@code examples/user-admin-app} needs before a test runtime boots from it.
 *
 * <p>The daily job must not keep its real schedule: a run crossing 02:00:00 UTC otherwise
 * really fires {@code user.dailyMaintenance} — deactivating the seeded PENDING users, enqueuing
 * a NOTIFICATION outbox event, and recording an execution mid-test (observed 2026-08-24). Tests
 * that need the job run it explicitly; what the live schedule would prove, it proves nowhere.
 * The cron is parked on a far-future year (Quartz's optional seventh field) rather than
 * stripped so the job still reads as scheduled — the ops console renders its cron and the jobs
 * catalog keeps its trigger.
 *
 * <p>The module declaration must go: the example declares the pdf module
 * (docs/codec-discovery.md decision 6), which {@code tesseraql dev} resolves into
 * {@code work/modules} and a copy does not carry, and a host refuses an application whose
 * declared modules are not on disk ({@code TQL-APP-4216}) — the right answer for an operator's
 * install root, and a fixture's classpath carries the codec already. Both edits are loud on
 * drift, because a replace that silently stopped matching would re-arm the flake or the refusal.
 */
final class UserAdminAppCopy {

    /** The example's real schedule, and the never-firing one every copied app runs under. */
    static final String REAL_CRON = "0 0 2 * * ?";
    static final String PARKED_CRON = "0 0 2 1 1 ? 2099";

    private UserAdminAppCopy() {
    }

    /** The example's module declaration, dropped from every copy (a copy resolves nothing). */
    static final String MODULES = "  modules:\n    - io.tesseraql:tesseraql-pdf\n";

    /** Call on every copy of {@code examples/user-admin-app} before a runtime boots from it. */
    static void prepare(Path appHome) throws IOException {
        Path job = appHome.resolve("batch/user/daily-maintenance/job.yml");
        String yaml = Files.readString(job);
        String schedule = "cron: \"" + REAL_CRON + "\"";
        if (!yaml.contains(schedule)) {
            throw new IllegalStateException("The example's daily-maintenance schedule moved;"
                    + " update UserAdminAppCopy so the job cannot fire mid-test");
        }
        Files.writeString(job, yaml.replace(schedule, "cron: \"" + PARKED_CRON + "\""));
        Path config = appHome.resolve("config/tesseraql.yml");
        String declared = Files.readString(config);
        if (!declared.contains(MODULES)) {
            throw new IllegalStateException("The example's module declaration moved; update"
                    + " UserAdminAppCopy so a host fixture does not refuse the copy");
        }
        Files.writeString(config, declared.replace(MODULES, ""));
    }
}
