package hudson.tasks.junit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import hudson.tasks.test.TabulatedResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Counts the regressed and fixed cases under a result in one pass over the earlier builds.
 *
 * <p>{@link CaseResult#getCondition()} looks up the previous result of a single case, walking back up to
 * {@link CaseResult#PREVIOUS_TEST_RESULT_BACKTRACK_BUILDS_MAX} builds and loading each earlier result again
 * whenever it was garbage collected in between. Doing that for every case of a build with hundreds of
 * thousands of cases does not finish. This class walks the same earlier builds once, matching all the
 * still unmatched cases against each build before moving to the next one, so each earlier result is loaded
 * at most once per count and only one of them is held at a time. The counts are the same as summing
 * {@link CaseResult#getCondition()} over the cases.
 *
 * <p>Counts of completed builds are cached, and concurrent requests for the same result share one count.
 */
@Restricted(NoExternalUse.class)
public final class CaseHistoryCounts {

    static int CACHE_SIZE = SystemProperties.getInteger(CaseHistoryCounts.class.getName() + ".CACHE_SIZE", 1000);

    private static final Map<String, Counts> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Counts> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static final ConcurrentHashMap<String, CompletableFuture<Counts>> IN_FLIGHT = new ConcurrentHashMap<>();

    public record Counts(long regressions, long fixed) {}

    private CaseHistoryCounts() {}

    /**
     * Counts the regressed and fixed cases under {@code scope}.
     *
     * @return the counts, or null when {@code scope} is not a file-backed JUnit result, in which case the caller
     *     falls back to {@link CaseResult#getCondition()}
     */
    @CheckForNull
    public static Counts of(hudson.tasks.test.TestResult scope) {
        List<CaseResult> cases = new ArrayList<>();
        if (!collectCases(scope, cases)) {
            return null;
        }
        TestResult root = rootOf(scope, cases);
        if (root == null || root.supportsPreviousCaseResultViaStorage()) {
            return null;
        }
        Run<?, ?> run = scope.getRun();
        if (run == null || run.isBuilding()) {
            return count(root, cases);
        }
        String key = run.getExternalizableId() + "/" + scope.getId();
        synchronized (CACHE) {
            Counts cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        CompletableFuture<Counts> mine = new CompletableFuture<>();
        CompletableFuture<Counts> running = IN_FLIGHT.putIfAbsent(key, mine);
        if (running != null) {
            return join(running);
        }
        try {
            Counts counts = count(root, cases);
            synchronized (CACHE) {
                CACHE.put(key, counts);
            }
            mine.complete(counts);
            return counts;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(key, mine);
        }
    }

    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static Counts join(CompletableFuture<Counts> running) {
        try {
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the test counts", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Adds the cases under {@code result} to {@code cases}.
     *
     * @return false when {@code result} contains results that are not JUnit cases
     */
    private static boolean collectCases(hudson.tasks.test.TestResult result, List<CaseResult> cases) {
        if (result instanceof CaseResult caseResult) {
            cases.add(caseResult);
            return true;
        }
        if (result instanceof TestResult testResult) {
            for (SuiteResult suite : testResult.getSuites()) {
                cases.addAll(suite.getCases());
            }
            return true;
        }
        if (result instanceof TabulatedResult tabulatedResult) {
            for (hudson.tasks.test.TestResult child : tabulatedResult.getChildren()) {
                if (!collectCases(child, cases)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @CheckForNull
    private static TestResult rootOf(hudson.tasks.test.TestResult scope, List<CaseResult> cases) {
        if (scope instanceof TestResult testResult) {
            return testResult;
        }
        for (CaseResult c : cases) {
            SuiteResult suite = c.getSuiteResult();
            if (suite != null && suite.getParent() != null) {
                return suite.getParent();
            }
        }
        return null;
    }

    /** Same walk as {@link CaseResult#getPreviousResult()}, done once for all the cases. */
    private static Counts count(TestResult root, List<CaseResult> cases) {
        List<Pending> pending = new ArrayList<>();
        for (CaseResult c : cases) {
            // getCondition() has no status for a skipped case or one without a suite
            if (c.isSkipped() || c.getSuiteResult() == null) {
                continue;
            }
            pending.add(new Pending(c.getSuiteResult().getName(), c.getTransformedFullDisplayName(), c.isPassed()));
        }
        long regressions = 0;
        long fixed = 0;
        hudson.tasks.test.TestResult previous = root;
        int n = 0;
        while (!pending.isEmpty() && n < CaseResult.PREVIOUS_TEST_RESULT_BACKTRACK_BUILDS_MAX) {
            previous = previous.getPreviousResult();
            if (previous == null) {
                break;
            }
            if (previous instanceof TestResult earlier) {
                List<Pending> unmatched = new ArrayList<>();
                for (Pending p : pending) {
                    CaseResult before = earlier.getCase(p.suite, p.name);
                    if (before == null) {
                        unmatched.add(p);
                    } else if (before.isPassed()) {
                        if (!p.passed) {
                            regressions++;
                        }
                    } else if (p.passed) {
                        fixed++;
                    }
                }
                pending = unmatched;
            }
            ++n;
        }
        return new Counts(regressions, fixed);
    }

    private record Pending(String suite, String name, boolean passed) {}

    static Collection<String> cachedKeys() {
        synchronized (CACHE) {
            return new ArrayList<>(CACHE.keySet());
        }
    }
}
