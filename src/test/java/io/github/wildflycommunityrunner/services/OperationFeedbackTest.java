package io.github.wildflycommunityrunner.services;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class OperationFeedbackTest extends BasePlatformTestCase {
    public void testFailureActionsKeepOriginalTargetAndDoNotOfferUnsafeRetry() {
        var failure = OperationFeedback.describe("Deployment failed", "Request may still complete", "", "server-a");
        assertEquals("server-a", failure.serverId());
        assertEquals(List.of(OperationFeedback.Action.ACTIVITY, OperationFeedback.Action.SERVER_LOG,
                OperationFeedback.Action.SERVER_SETTINGS), failure.actions());
        assertTrue(failure.hint().contains("before retrying"));
        var build = OperationFeedback.describe("Build operation failed", "Invalid build JDK", "service-a", "server-b");
        assertEquals("service-a", build.serviceId());
        assertEquals(List.of(OperationFeedback.Action.ACTIVITY, OperationFeedback.Action.SERVICE_SETTINGS), build.actions());
    }
    public void testMissingContextOnlyOffersActivityAndSecretsAreRedacted() {
        var failure = OperationFeedback.describe("Start failed", "Bad -Dpassword=hunter2", null, null);
        assertFalse(failure.detail().contains("hunter2"));
        assertEquals(List.of(OperationFeedback.Action.ACTIVITY), failure.actions());
    }
    public void testDismissingOldFailureCannotRemoveNewFailure() {
        var feedback = getProject().getService(OperationFeedback.class);
        var first = feedback.report("Build failed", "first", "a", "");
        var second = feedback.report("Deployment failed", "second", "", "b");
        feedback.dismiss(first);
        assertSame(second, feedback.latest());
        feedback.dismiss(second);
        assertNull(feedback.latest());
    }
}
