package runtime;

import common.PathCoverage;

import java.util.Optional;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;


import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;


public class TestExecutor
{
    private String testClass;
    private TestExecutionSummary summary;
    private Optional<PathCoverage> pathCoverage;
    private Class clazz;

    public TestExecutor(String testClass) throws ClassNotFoundException
    {
        clazz = Class.forName(testClass);
    }

    public TestExecutor(Class clazz)
    {
        this.clazz = clazz;
    }

    public void runTests()
    {
        System.out.println("[TestExecutor] Running tests for " + clazz.getName());
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(
                selectClass(clazz)
            )
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        TestExecutorListener testExecutorListener = new TestExecutorListener();

        try (LauncherSession session = LauncherFactory.openSession()) {
            Launcher launcher = session.getLauncher();

            launcher.registerTestExecutionListeners(listener);
            launcher.registerTestExecutionListeners(testExecutorListener);

            launcher.execute(request);
        } 

        summary = listener.getSummary();
        pathCoverage = testExecutorListener.getPathCoverage();
    }

    public PathCoverage getPathCoverage() throws PathCoverageNotFoundException
    {
        if (pathCoverage.isPresent())
        {
            return pathCoverage.get();
        }
        throw new PathCoverageNotFoundException("Path coverage not found");
    }

    public long getFoundTestCount()
    {
        return summary.getTestsFoundCount();
    }
}
