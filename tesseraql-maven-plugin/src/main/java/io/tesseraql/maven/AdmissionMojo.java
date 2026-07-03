package io.tesseraql.maven;

import io.tesseraql.yaml.governance.AdmissionProfile;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * The marketplace admission profile as a build gate (roadmap Phase 47): fails the build when
 * the app would not clear the bar a shared app must pass.
 */
@Mojo(name = "admission", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class AdmissionMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", defaultValue = "${project.basedir}")
    private File appHome;

    @Override
    public void execute() throws MojoFailureException {
        AdmissionProfile.Report report = AdmissionProfile.check(appHome.toPath());
        for (AdmissionProfile.Finding failure : report.failures()) {
            getLog().error(failure.code() + " " + failure.subject() + ": "
                    + failure.reason());
        }
        if (!report.admitted()) {
            throw new MojoFailureException("Admission profile failed: "
                    + report.failures().size() + " finding(s)");
        }
        getLog().info("Admission profile passed");
    }
}
