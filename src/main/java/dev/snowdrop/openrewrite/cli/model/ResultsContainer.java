package dev.snowdrop.openrewrite.cli.model;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Result;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ResultsContainer {
    private List<Result> generated = new ArrayList<>();
    private List<Result> deleted = new ArrayList<>();
    private List<Result> moved = new ArrayList<>();
    private List<Result> refactoredInPlace = new ArrayList<>();

    public ResultsContainer(Collection<Result> results) {
        for (Result result : results) {
            if (result.getBefore() == null && result.getAfter() == null) {
                continue;
            }
            if (result.getBefore() == null && result.getAfter() != null) {
                generated.add(result);
            } else if (result.getBefore() != null && result.getAfter() == null) {
                deleted.add(result);
            } else if (result.getBefore() != null && result.getAfter() != null &&
                !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                moved.add(result);
            } else {
                if (!result.diff(Paths.get("")).isEmpty()) {
                    refactoredInPlace.add(result);
                }
            }
        }
    }

    public @Nullable RuntimeException getFirstException() {
        // Simplified version - in the full implementation this would check for recipe errors
        return null;
    }

    public boolean isNotEmpty() {
        return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
    }

    public List<Result> getGenerated() {
        return generated;
    }

    public void setGenerated(List<Result> generated) {
        this.generated = generated;
    }

    public List<Result> getDeleted() {
        return deleted;
    }

    public void setDeleted(List<Result> deleted) {
        this.deleted = deleted;
    }

    public List<Result> getMoved() {
        return moved;
    }

    public void setMoved(List<Result> moved) {
        this.moved = moved;
    }

    public List<Result> getRefactoredInPlace() {
        return refactoredInPlace;
    }

    public void setRefactoredInPlace(List<Result> refactoredInPlace) {
        this.refactoredInPlace = refactoredInPlace;
    }
}
