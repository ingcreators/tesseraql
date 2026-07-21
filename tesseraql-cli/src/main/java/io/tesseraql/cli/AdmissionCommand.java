package io.tesseraql.cli;

import io.tesseraql.yaml.governance.AdmissionProfile;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The marketplace admission profile (roadmap Phase 47): the machine-checkable bar an app must
 * clear before it is shared — lint clean, deny-by-default policies defined, declarative-only,
 * governance-approved, egress bounded, CSP intact. Exit 1 on any failure.
 */
@Command(name = "admission", description = "Run the marketplace admission profile over an app tree.")
public final class AdmissionCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the app home.")
    Path app;

    @Override
    public Integer call() {
        AdmissionProfile.Report report = AdmissionProfile.check(app);
        for (AdmissionProfile.Finding note : report.notes()) {
            System.out.println("NOTE [" + note.subject() + "] " + note.reason());
        }
        for (AdmissionProfile.Finding failure : report.failures()) {
            System.err.println(failure.code() + " " + failure.subject() + ": "
                    + failure.reason());
        }
        if (report.admitted()) {
            System.out.println("Admission profile passed: " + app);
            return 0;
        }
        System.err.println("Admission profile FAILED: " + report.failures().size()
                + " finding(s)");
        return 1;
    }
}
