package dev.snowdrop.openrewrite.cli.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class Config {
    private Path appPath;
    private List<String> additionalJarPaths;
    private List<String> activeRecipes;
    private String yamlRecipes;
    private Boolean exportDatatables;
    private int sizeThresholdMb;
    private Set<String> exclusions;
    private Set<String> plainTextMasks;
    private Set<String> recipeOptions;
    private boolean logMessages;

    public Path getAppPath() {
        return appPath;
    }

    public void setAppPath(Path appPath) {
        this.appPath = appPath;
    }

    public List<String> getAdditionalJarPaths() {
        return additionalJarPaths;
    }

    public void setAdditionalJarPaths(List<String> additionalJarPaths) {
        this.additionalJarPaths = additionalJarPaths;
    }

    public List<String> getActiveRecipes() {
        return activeRecipes;
    }

    public void setActiveRecipes(List<String> activeRecipes) {
        this.activeRecipes = activeRecipes;
    }

    public String getYamlRecipes() {
        return yamlRecipes;
    }

    public void setYamlRecipes(String yamlRecipes) {
        this.yamlRecipes = yamlRecipes;
    }

    public Boolean canExportDatatables() {
        return exportDatatables;
    }

    public void setExportDatatables(Boolean exportDatatables) {
        this.exportDatatables = exportDatatables;
    }

    public int getSizeThresholdMb() {
        return sizeThresholdMb;
    }

    public void setSizeThresholdMb(int sizeThresholdMb) {
        this.sizeThresholdMb = sizeThresholdMb;
    }

    public Set<String> getExclusions() {
        return exclusions;
    }

    public void setExclusions(Set<String> exclusions) {
        this.exclusions = exclusions;
    }

    public Set<String> getPlainTextMasks() {
        return plainTextMasks;
    }

    public void setPlainTextMasks(Set<String> plainTextMasks) {
        this.plainTextMasks = plainTextMasks;
    }

    public Set<String> getRecipeOptions() {
        return recipeOptions;
    }

    public void setRecipeOptions(Set<String> recipeOptions) {
        this.recipeOptions = recipeOptions;
    }

    public Boolean getExportDatatables() {
        return exportDatatables;
    }

    public boolean isLogMessages() {
        return logMessages;
    }

    public void setLogMessages(boolean logMessages) {
        this.logMessages = logMessages;
    }
}
