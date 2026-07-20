package com.company.loopengine.publishing;

import com.company.loopengine.publishing.PublishRepair.ArtifactGate;
import com.company.loopengine.publishing.PublishRepair.AttemptView;
import com.company.loopengine.publishing.PublishRepair.BranchGate;
import com.company.loopengine.publishing.PublishRepair.MergeRequestGate;
import com.company.loopengine.publishing.PublishRepair.PublishStepRecord;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Hard gate: defect may enter ready-for-test only when every publication prerequisite holds.
 */
public final class ReadyForTestPolicy {
    private static final Set<String> REQUIRED_BEFORE_FINALIZE = Set.of(
        "ARTIFACT_VERIFIED",
        "PATCH_PREPARED",
        "BRANCH_PUSHED",
        "MR_CREATED");

    public boolean allows(
            ArtifactGate artifact,
            BranchGate branch,
            MergeRequestGate mergeRequest,
            AttemptView attempt,
            List<PublishStepRecord> publishSteps) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(mergeRequest, "mergeRequest");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(publishSteps, "publishSteps");
        return artifact.verified()
            && artifact.patchBytes() > 0
            && artifact.mandatoryValidationsPassed()
            && branch.commitSha() != null
            && mergeRequest.iid() != null
            && attempt.hasTerminalSuccessEvent()
            && allCompletedBefore(publishSteps, "STATE_FINALIZED");
    }

    boolean allCompletedBefore(List<PublishStepRecord> publishSteps, String beforeStep) {
        Objects.requireNonNull(beforeStep, "beforeStep");
        if (!"STATE_FINALIZED".equals(beforeStep)) {
            throw new IllegalArgumentException("unsupported before-step: " + beforeStep);
        }
        for (String required : REQUIRED_BEFORE_FINALIZE) {
            boolean completed = publishSteps.stream()
                .anyMatch(s -> required.equals(s.step())
                    && PublishRepair.STEP_COMPLETED.equals(s.state()));
            if (!completed) {
                return false;
            }
        }
        return true;
    }
}
