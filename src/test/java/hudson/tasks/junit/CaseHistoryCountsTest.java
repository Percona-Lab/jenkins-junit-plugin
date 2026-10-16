package hudson.tasks.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CaseHistoryCountsTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        CaseHistoryCounts.clearCache();
    }

    /**
     * Random histories with two disjoint families of suites (like one job running two storage engines), builds
     * without results, cases that appear and disappear, and skipped cases. Every build, package and class must
     * get the same counts as summing {@link CaseResult#getCondition()} over its cases.
     */
    @Test
    void matchesPerCaseConditionOnRandomHistories() throws Exception {
        for (long seed = 1; seed <= 6; seed++) {
            Random random = new Random(seed);
            List<String> reports = new ArrayList<>();
            for (int b = 0; b < 14; b++) {
                reports.add(random.nextInt(6) == 0 ? null : randomReport(random));
            }
            FreeStyleProject p = j.createFreeStyleProject("history-" + seed);
            p.getBuildersList().add(new ReportWriter(reports));
            JUnitResultArchiver archiver = new JUnitResultArchiver("report.xml");
            archiver.setAllowEmptyResults(true);
            p.getPublishersList().add(archiver);
            int compared = 0;
            for (int b = 0; b < reports.size(); b++) {
                FreeStyleBuild build = p.scheduleBuild2(0).get();
                TestResultAction action = build.getAction(TestResultAction.class);
                if (reports.get(b) == null) {
                    continue;
                }
                assertNotNull(action, "seed " + seed + " build " + build.number);
                TestResult root = action.getResult();
                compared += compare(root, "seed " + seed + " build " + build.number);
                for (PackageResult pkg : root.getChildren()) {
                    compared += compare(pkg, "seed " + seed + " package " + pkg.getName());
                    for (ClassResult cls : pkg.getChildren()) {
                        compared += compare(cls, "seed " + seed + " class " + cls.getName());
                    }
                }
            }
            assertTrue(compared > 0);
        }
    }

    @Test
    void cachesCountsOfCompletedBuilds() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        List<String> reports = List.of(
                "<testsuite name='s'><testcase classname='A' name='x'><failure/></testcase>"
                        + "<testcase classname='A' name='y'/></testsuite>",
                "<testsuite name='s'><testcase classname='A' name='x'/>"
                        + "<testcase classname='A' name='y'><failure/></testcase></testsuite>");
        p.getBuildersList().add(new ReportWriter(reports));
        p.getPublishersList().add(new JUnitResultArchiver("report.xml"));
        p.scheduleBuild2(0).get();
        FreeStyleBuild second = p.scheduleBuild2(0).get();
        TestResult root = second.getAction(TestResultAction.class).getResult();

        assertEquals(1, root.getRegressionCount());
        assertEquals(1, root.getFixedCount());
        assertEquals(List.of(second.getExternalizableId() + "/" + root.getId()), CaseHistoryCounts.cachedKeys());
    }

    private static int compare(hudson.tasks.test.TestResult scope, String what) {
        long regressions = 0;
        long fixed = 0;
        for (CaseResult c : cases(scope)) {
            CaseResult.Status condition = c.getCondition();
            if (condition == CaseResult.Status.REGRESSION) {
                regressions++;
            } else if (condition == CaseResult.Status.FIXED) {
                fixed++;
            }
        }
        CaseHistoryCounts.Counts counts = CaseHistoryCounts.of(scope);
        assertNotNull(counts, what);
        assertEquals(regressions, counts.regressions(), what + " regressions");
        assertEquals(fixed, counts.fixed(), what + " fixed");
        assertEquals(regressions, scope.getRegressionCount(), what + " getRegressionCount");
        assertEquals(fixed, scope.getFixedCount(), what + " getFixedCount");
        return 1;
    }

    private static List<CaseResult> cases(hudson.tasks.test.TestResult scope) {
        List<CaseResult> out = new ArrayList<>();
        if (scope instanceof CaseResult c) {
            out.add(c);
        } else if (scope instanceof hudson.tasks.test.TabulatedResult t) {
            for (hudson.tasks.test.TestResult child : t.getChildren()) {
                out.addAll(cases(child));
            }
        }
        return out;
    }

    private static String randomReport(Random random) {
        String family = random.nextBoolean() ? "wt" : "im";
        StringBuilder xml = new StringBuilder("<testsuites>");
        for (int s = 0; s < 4; s++) {
            String suite = "suite" + s + "-" + family;
            xml.append("<testsuite name='").append(suite).append("'>");
            for (int c = 0; c < 12; c++) {
                if (random.nextInt(7) == 0) {
                    continue;
                }
                xml.append("<testcase classname='pkg")
                        .append(s % 2)
                        .append(".Cls")
                        .append(c % 3)
                        .append("' name='t")
                        .append(c)
                        .append("'>");
                int status = random.nextInt(10);
                if (status < 2) {
                    xml.append("<failure message='boom'/>");
                } else if (status == 2) {
                    xml.append("<skipped/>");
                }
                xml.append("</testcase>");
            }
            xml.append("</testsuite>");
        }
        return xml.append("</testsuites>").toString();
    }

    /** Writes the report for the build number, or deletes it to record a build without results. */
    private static final class ReportWriter extends TestBuilder {
        private final List<String> reports;

        ReportWriter(List<String> reports) {
            this.reports = reports;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws java.io.IOException, InterruptedException {
            FilePath report = build.getWorkspace().child("report.xml");
            String xml = reports.get(build.number - 1);
            if (xml == null) {
                report.delete();
            } else {
                report.write(xml, "UTF-8");
                // the archiver skips reports older than the build
                report.touch(System.currentTimeMillis() + 1000);
            }
            return true;
        }
    }
}
